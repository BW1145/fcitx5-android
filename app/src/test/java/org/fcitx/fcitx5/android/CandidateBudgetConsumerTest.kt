/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android

import android.os.Looper
import android.view.View
import androidx.core.view.allViews
import kotlinx.coroutines.*
import org.fcitx.fcitx5.android.core.*
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.InputView
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcaster
import org.fcitx.fcitx5.android.input.candidates.horizontal.HorizontalCandidateComponent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import java.time.Duration

/** Real production component/adapter consumers; fake lazy engine avoids native initialization. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=FcitxApplication::class,shadows=[AndroidAtomicFileShadow::class])
class CandidateBudgetConsumerTest {
    private fun await(check:()->Boolean) {
        val until=System.nanoTime()+8_000_000_000L
        while(!check() && System.nanoTime()<until) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16));Thread.sleep(2)
        }
        assertTrue("Real consumer completes requested progress",check())
    }
    private class Source:FcitxConnection {
        var calls=0;var active=0;var revision=7L;var pause:CompletableDeferred<Unit>?=null
        private var cursor=0
        val state get()=CandidateScanState(revision,4,cursor.toLong(),(cursor-10000).coerceIn(0,40),cursor>=10040,true)
        private val fallback=Proxy.newProxyInstance(FcitxAPI::class.java.classLoader,arrayOf(FcitxAPI::class.java)) { _,method,_->
            error("Unexpected API call: ${method.name}")
        } as FcitxAPI
        private val api=object:FcitxAPI by fallback {
            override val statusAreaActionsCached=emptyArray<Action>()
            override val inputMethodEntryCached=InputMethodEntry("rime")
            override val clientPreeditCached=FormattedText.Empty
            override val inputPanelCached=FcitxEvent.InputPanelEvent.Data()
            override suspend fun getCandidateScanState()=state
            override suspend fun getCandidateBatch(offset:Int,limit:Int,expectedRevision:Long,expectedRuleRevision:Long):CandidateBatch {
                calls++;active++
                try {
                    pause?.await()
                    if(expectedRevision>=0 && expectedRevision!=revision)return CandidateBatch(emptyArray(),state.copy(stale=true))
                    cursor=(cursor+32).coerceAtMost(10040)
                    val end=minOf(state.available,offset+limit)
                    val words=(offset until end).map { CandidateWord("","word-$it","").also { it.revision=revision } }.toTypedArray()
                    return CandidateBatch(words,state)
                } finally {active--}
            }
        }
        override val lifecycleScope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        override fun <T> runImmediately(block:suspend FcitxAPI.()->T):T=runBlocking {api.block()}
        override suspend fun <T> runOnReady(block:suspend FcitxAPI.()->T):T=api.block()
        override fun runIfReady(block:suspend FcitxAPI.()->Unit) { lifecycleScope.launch {api.block()} }
    }
    private inline fun fixture(block:(InputView,HorizontalCandidateComponent,InputBroadcaster,Source)->Unit) {
        val service=Robolectric.buildService(FcitxInputMethodService::class.java).get()
        val source=Source();val view=InputView(service,source,ThemeManager.DefaultTheme)
        val horizontal=InputView::class.java.getDeclaredField("horizontalCandidate").apply {isAccessible=true}.get(view) as HorizontalCandidateComponent
        val broadcaster=InputView::class.java.getDeclaredField("broadcaster").apply {isAccessible=true}.get(view) as InputBroadcaster
        try {block(view,horizontal,broadcaster,source)} finally {
            InputView::class.java.getDeclaredMethod("onDetachedFromWindow").apply {isAccessible=true}.invoke(view)
            source.lifecycleScope.cancel();shadowOf(Looper.getMainLooper()).idle()
        }
    }
    private fun demand(horizontal:HorizontalCandidateComponent) {
        HorizontalCandidateComponent::class.java.getDeclaredMethod("loadMore").apply {isAccessible=true}.invoke(horizontal)
    }
    private fun publish(broadcaster:InputBroadcaster,source:Source) {
        broadcaster.onPreeditEmptyStateUpdate(false)
        broadcaster.onCandidateUpdate(FcitxEvent.CandidateListEvent.Data(-1,emptyArray(),source.state))
    }
    @Test fun emptyPendingHasRealExpandControlAndOneDemandCrossesHiddenPrefix()=fixture { view,horizontal,broadcaster,source ->
        publish(broadcaster,source);shadowOf(Looper.getMainLooper()).idle()
        assertTrue(horizontal.hasMore);assertEquals(0,source.calls)
        val expand=view.allViews.first {it.contentDescription?.toString()==view.context.getString(R.string.expand_candidates_list)}
        assertEquals(View.VISIBLE,expand.visibility)
        demand(horizontal);await {horizontal.adapter.itemCount>0}
        assertEquals(313,source.calls);assertEquals(16,horizontal.adapter.itemCount)
        assertTrue("Short/partial materialization remains continuable",horizontal.hasMore)
        demand(horizontal);await {horizontal.adapter.itemCount==32}
        demand(horizontal);await {horizontal.adapter.itemCount==40}
        assertFalse(horizontal.hasMore)
    }
    @Test fun newGenerationCancelsActualHorizontalLoadAndDiscardsOldRows()=fixture { _,horizontal,broadcaster,source ->
        source.pause=CompletableDeferred();publish(broadcaster,source);demand(horizontal)
        await {source.active==1}
        source.revision=8;publish(broadcaster,source)
        await {source.active==0}
        source.pause!!.complete(Unit);shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0,horizontal.adapter.itemCount);assertEquals(8L,horizontal.scanState!!.revision)
    }
    @Test fun closingActualComponentCancelsPendingEngineRequest()=fixture { _,horizontal,broadcaster,source ->
        source.pause=CompletableDeferred();publish(broadcaster,source);demand(horizontal)
        await {source.active==1};horizontal.close();await {source.active==0}
        source.pause!!.complete(Unit);shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0,horizontal.adapter.itemCount)
    }
    @Test fun realExpandButtonStartsEmptyDemandAndHideCancelsIt()=fixture { view,_,broadcaster,source ->
        source.pause=CompletableDeferred();publish(broadcaster,source);shadowOf(Looper.getMainLooper()).idle()
        val expand=view.allViews.first {it.contentDescription?.toString()==view.context.getString(R.string.expand_candidates_list)}
        assertEquals(View.VISIBLE,expand.visibility);assertTrue(expand.performClick())
        await {source.active==1}
        val hide=view.allViews.first {it.contentDescription?.toString()==view.context.getString(R.string.hide_candidates_list)}
        assertTrue(hide.performClick());await {source.active==0}
        source.pause!!.complete(Unit);shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1,source.calls)
    }

}

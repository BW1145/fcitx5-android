/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android

import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.core.view.allViews
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.core.Action
import org.fcitx.fcitx5.android.core.FcitxAPI
import org.fcitx.fcitx5.android.core.FormattedText
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.data.SavedContentStore
import org.fcitx.fcitx5.android.data.KeyMacros
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.InputView
import org.fcitx.fcitx5.android.input.keyboard.BaseKeyboard
import org.fcitx.fcitx5.android.input.keyboard.AlphabetKey
import org.fcitx.fcitx5.android.input.keyboard.TextKeyView
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyActionListener
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcaster
import org.fcitx.fcitx5.android.input.saved.SavedContentWindow
import org.fcitx.fcitx5.android.input.status.StatusAreaWindow
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mechdancer.dependency.DynamicScope
import org.mechdancer.dependency.manager.wrapToUniqueComponent
import org.mechdancer.dependency.plusAssign
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPopupMenu
import java.time.Duration
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=FcitxApplication::class,shadows=[AndroidAtomicFileShadow::class])
class InputWorkloadTest {
    private fun await(check:()->Boolean) {
        val until=System.nanoTime()+5_000_000_000L
        while(!check() && System.nanoTime()<until) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
            Thread.sleep(5)
        }
        assertTrue("The asynchronous view update completes",check())
    }

    @Test fun serviceKeepsCommandsInOrderWithoutQueuingCandidateRendering() {
        val service=Robolectric.buildService(FcitxInputMethodService::class.java).get()
        @Suppress("UNCHECKED_CAST")
        val listener=FcitxInputMethodService::class.java.getDeclaredField("inputEventListener").apply { isAccessible=true }
            .get(service) as (FcitxEvent<*>)->Unit
        @Suppress("UNCHECKED_CAST")
        val queue=FcitxInputMethodService::class.java.getDeclaredField("inputEvents").apply { isAccessible=true }
            .get(service) as Channel<Any>
        val expected=mutableListOf<FcitxEvent<*>>()
        thread {
            repeat(100) { index ->
                listOf(
                    FcitxEvent.CommitStringEvent(FcitxEvent.CommitStringEvent.Data("word$index",-1)),
                    FcitxEvent.ClientPreeditEvent(FormattedText.Empty),
                    FcitxEvent.DeleteSurroundingEvent(FcitxEvent.DeleteSurroundingEvent.Data(1,0)),
                    FcitxEvent.IMChangeEvent(InputMethodEntry("ime$index")),
                    FcitxEvent.SwitchInputMethodEvent(FcitxEvent.SwitchInputMethodEvent.Data(
                        FcitxEvent.SwitchInputMethodEvent.Reason.Trigger,"ime${index-1}"))
                ).forEach { expected+=it;listener(it) }
                repeat(100) { listener(FcitxEvent.CandidateListEvent(FcitxEvent.CandidateListEvent.Data(0))) }
                listener(FcitxEvent.InputPanelEvent(FcitxEvent.InputPanelEvent.Data()))
            }
        }.join()
        val delivered=buildList {
            while(true) {
                val dispatch=queue.tryReceive().getOrNull() ?: break
                add(dispatch.javaClass.getDeclaredField("event").apply { isAccessible=true }.get(dispatch))
            }
        }
        assertEquals("Text and input-method switches survive the UI burst in order",expected,delivered)
        queue.cancel()
    }

    private class Window:InputWindow.SimpleInputWindow<Window>() {
        var created=0;var attached=0;var detached=0
        override fun onCreateView():View=TextView(context).also { created++ }
        override fun onAttached() { attached++ }
        override fun onDetached() { detached++ }
    }

    private fun windowScope(service:FcitxInputMethodService,fcitx:FcitxConnection?=null):Pair<DynamicScope,InputWindowManager> {
        val scope=DynamicScope()
        val broadcaster=InputBroadcaster()
        val manager=InputWindowManager()
        scope+=ContextThemeWrapper(service,R.style.Theme_InputViewTheme).wrapToUniqueComponent()
        scope+=service.wrapToUniqueComponent()
        if(fcitx!=null) scope+=fcitx.wrapToUniqueComponent()
        val theme:Theme=ThemeManager.DefaultTheme
        scope+=theme.wrapToUniqueComponent()
        scope+=broadcaster
        scope+=manager
        broadcaster.onScopeSetupFinished(scope)
        return scope to manager
    }

    private class MenuSource:FcitxConnection {
        var queries=0
        var active=0
        private val unused=Proxy.newProxyInstance(FcitxAPI::class.java.classLoader,arrayOf(FcitxAPI::class.java)) { _,method,_->
            error("Unexpected menu API call: ${method.name}")
        } as FcitxAPI
        private val api=object:FcitxAPI by unused {
            override val statusAreaActionsCached=emptyArray<Action>()
            override val inputMethodEntryCached=InputMethodEntry("rime")
            override val clientPreeditCached=FormattedText.Empty
            override val inputPanelCached=FcitxEvent.InputPanelEvent.Data()
            override suspend fun statusArea():Array<Action> {
                queries++;active++
                try { return CompletableDeferred<Array<Action>>().await() }
                finally { active-- }
            }
        }
        override val lifecycleScope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        override fun <T> runImmediately(block:suspend FcitxAPI.()->T):T=runBlocking { api.block() }
        override suspend fun <T> runOnReady(block:suspend FcitxAPI.()->T):T=api.block()
        override fun runIfReady(block:suspend FcitxAPI.()->Unit) { error("Not used for menu rendering") }
    }

    @Test fun menuReopensWithImmediateEntriesAndCancelsEveryDetachedRequest() {
        val service=Robolectric.buildService(FcitxInputMethodService::class.java).get()
        val source=MenuSource()
        val (scope,manager)=windowScope(service,source)
        val menu=StatusAreaWindow()
        val keyboard=Window()
        try {
            repeat(100) {
                manager.attachWindow(menu)
                assertTrue("Menu entries do not wait for an engine query",menu.view.adapter!!.itemCount>0)
                repeat(5) { manager.attachWindow(menu) }
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(1,source.active)
                manager.attachWindow(keyboard)
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals("Closing a menu releases its pending request",0,source.active)
            }
            assertEquals("Rapid repeat taps do not restart the same menu",100,source.queries)
        } finally { menu.onDetached();source.lifecycleScope.cancel();scope.clear() }
    }

    @Test fun realKeyboardMenuButtonAndReturnButtonStayUsableAcrossRepeatedClicks() {
        val service=Robolectric.buildService(FcitxInputMethodService::class.java).get()
        val source=MenuSource()
        val keyboard=InputView(service,source,ThemeManager.DefaultTheme)
        val bar=keyboard.allViews.filterIsInstance<android.widget.ViewAnimator>().first()
        val more=keyboard.allViews.first { it.contentDescription?.toString()==service.getString(R.string.status_area) }
        try {
            repeat(30) {
                more.performClick()
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals("The menu toolbar is visible after clicking its real button",2,bar.displayedChild)
                val back=keyboard.allViews.first { it.contentDescription?.toString()==service.getString(R.string.back_to_keyboard) }
                val menu=keyboard.findViewById<android.widget.FrameLayout>(R.id.input_window).getChildAt(0) as RecyclerView
                assertTrue(menu.adapter!!.itemCount>0)
                repeat(3) { more.performClick() }
                assertSame(menu,keyboard.findViewById<android.widget.FrameLayout>(R.id.input_window).getChildAt(0))
                back.performClick()
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals("Returning restores the keyboard toolbar",0,bar.displayedChild)
                assertEquals(0,source.active)
            }
            assertEquals(30,source.queries)
            more.performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1,source.active)
        } finally {
            InputView::class.java.getDeclaredMethod("onDetachedFromWindow").apply { isAccessible=true }.invoke(keyboard)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("Destroying a keyboard closes the visible menu and its request",0,source.active)
            source.lifecycleScope.cancel()
        }
    }

    @Test fun attachingTheVisibleWindowPreservesItsViewAndLifecycle() {
        val service=Robolectric.buildService(FcitxInputMethodService::class.java).get()
        val (scope,manager)=windowScope(service)
        try {
            val first=Window()
            manager.attachWindow(first)
            val view=manager.view.getChildAt(0)
            repeat(100) { manager.attachWindow(first) }
            assertEquals(1,first.created)
            assertEquals(1,first.attached)
            assertEquals(0,first.detached)
            assertSame(view,manager.view.getChildAt(0))
            val second=Window()
            manager.attachWindow(second)
            assertEquals(1,first.detached)
            assertEquals(1,second.attached)
            assertTrue(manager.isAttached(second))
        } finally { scope.clear() }
    }

    private fun withEntries(entries:List<SavedContentStore.Entry>,block:()->Unit) {
        val app=RuntimeEnvironment.getApplication()
        val file=(app.getExternalFilesDir(null) ?: app.filesDir).resolve("data/saved-content.json")
        val previous=file.takeIf { it.exists() }?.readBytes()
        file.parentFile!!.mkdirs()
        file.writeText(Json.encodeToString(entries),Charsets.UTF_8)
        try { block() } finally {
            if(previous==null) file.delete() else file.writeBytes(previous)
        }
    }

    @Test fun savedContentUsesVisibleRowsAndKeepsMoveDeleteAndReopenWorking() {
        val entries=List(250) { SavedContentStore.Entry("entry-$it","title-$it","value-$it") }
        withEntries(entries) {
            val service=Robolectric.buildService(FcitxInputMethodService::class.java).get()
            val (scope,manager)=windowScope(service)
            val saved=SavedContentWindow()
            try {
                manager.attachWindow(saved)
                val root=manager.view.getChildAt(0) as android.view.ViewGroup
                val list=root.getChildAt(0) as RecyclerView
                await { list.adapter!!.itemCount==250 }
                fun layout() {
                    root.measure(View.MeasureSpec.makeMeasureSpec(500,View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(600,View.MeasureSpec.EXACTLY))
                    root.layout(0,0,500,600)
                }
                layout()
                assertTrue("Only visible saved entries create views",list.childCount in 1..30)
                list.findViewHolderForAdapterPosition(0)!!.itemView.performLongClick()
                val move=ShadowPopupMenu.getLatestPopupMenu().menu.getItem(2)
                move.javaClass.getMethod("invoke").invoke(move)
                await { (list.adapter as androidx.recyclerview.widget.ListAdapter<*,*>).currentList.first()==entries[1] }
                layout()
                list.findViewHolderForAdapterPosition(0)!!.itemView.performLongClick()
                val delete=ShadowPopupMenu.getLatestPopupMenu().menu.getItem(3)
                delete.javaClass.getMethod("invoke").invoke(delete)
                await { list.adapter!!.itemCount==249 }
                assertFalse(SavedContentStore.load().any { it.id==entries[1].id })
                manager.attachWindow(Window())
                manager.attachWindow(saved)
                await { list.adapter!!.itemCount==249 }
                list.scrollToPosition(248);layout()
                assertEquals("title-249\nvalue-249",(list.findViewHolderForAdapterPosition(248)!!.itemView as TextView).text.toString())
            } finally { saved.onDetached();scope.clear() }
        }
    }

    @Test fun concurrentSavedContentWritesPreserveEveryEntry() {
        withEntries(emptyList()) {
            val start=CountDownLatch(1)
            val workers=List(8) { index -> thread {
                start.await()
                repeat(10) { item -> SavedContentStore.put("$index-$item","title","value") }
            } }
            start.countDown();workers.forEach { it.join() }
            assertEquals(80,SavedContentStore.load().size)
        }
    }

    @Test fun keyboardsApplyOneMacroSnapshotAndRecreationPicksUpEdits() {
        val previous=KeyMacros.load()
        val context=ContextThemeWrapper(RuntimeEnvironment.getApplication(),R.style.Theme_InputViewTheme)
        fun keyboard()=object:BaseKeyboard(context,ThemeManager.DefaultTheme,
            listOf(listOf(AlphabetKey("A","@"),AlphabetKey("B","!")))) {}
        fun press(view:BaseKeyboard,key:String):String {
            var action:KeyAction?=null
            view.keyActionListener=KeyActionListener { value,_-> action=value }
            view.allViews.filterIsInstance<TextKeyView>().first { it.mainText.text.toString()==key }.performClick()
            return (action as KeyAction.MacroAction).steps.single().text
        }
        try {
            KeyMacros.save(listOf(KeyMacros.Binding("a","tap",listOf(KeyMacros.Step("text","first"))),
                KeyMacros.Binding("b","tap",listOf(KeyMacros.Step("text","second")))))
            val original=keyboard()
            assertEquals("first",press(original,"A"))
            assertEquals("second",press(original,"B"))
            KeyMacros.set(KeyMacros.Binding("a","tap",listOf(KeyMacros.Step("text","edited"))))
            assertEquals("first",press(original,"A"))
            val rebuilt=keyboard()
            assertEquals("edited",press(rebuilt,"A"))
            assertEquals("second",press(rebuilt,"B"))
        } finally { KeyMacros.save(previous) }
    }
}

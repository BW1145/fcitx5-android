/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android

import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.fcitx.fcitx5.android.core.FcitxAPI
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.core.FormattedText
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.BaseInputView
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.preedit.PreeditUi
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowChoreographer
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=FcitxApplication::class)
class InputUiEventsTest {
    private class NativeEvents:FcitxConnection {
        val listeners=CopyOnWriteArrayList<(FcitxEvent<*>)->Unit>()
        val flow=MutableSharedFlow<FcitxEvent<*>>(extraBufferCapacity=15,onBufferOverflow=BufferOverflow.DROP_OLDEST)
        var panel=FcitxEvent.InputPanelEvent.Data()
        private val api=Proxy.newProxyInstance(FcitxAPI::class.java.classLoader,arrayOf(FcitxAPI::class.java)) { _,method,args ->
            when(method.name) {
                "getEventFlow" -> flow
                "getInputPanelCached" -> panel
                "addInputEventListener" -> {
                    @Suppress("UNCHECKED_CAST")
                    listeners.add(args!![0] as (FcitxEvent<*>)->Unit)
                    Unit
                }
                "removeInputEventListener" -> { listeners.remove(args!![0]);Unit }
                else -> error("Unexpected API call: ${method.name}")
            }
        } as FcitxAPI
        override val lifecycleScope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        override fun <T> runImmediately(block:suspend FcitxAPI.()->T):T=runBlocking { api.block() }
        override suspend fun <T> runOnReady(block:suspend FcitxAPI.()->T):T=api.block()
        override fun runIfReady(block:suspend FcitxAPI.()->Unit) { error("Not used by UI event delivery") }
        fun emit(event:FcitxEvent<*>) {
            if(event is FcitxEvent.InputPanelEvent) panel=event.data
            listeners.forEach { it(event) }
            flow.tryEmit(event)
        }
        fun preedit(text:String)=FcitxEvent.InputPanelEvent(FcitxEvent.InputPanelEvent.Data(
            FormattedText(arrayOf(text),intArrayOf(0),text.length),FormattedText.Empty,FormattedText.Empty,emptyArray()))
    }

    private class Keyboard(service:FcitxInputMethodService,val source:NativeEvents):
        BaseInputView(service,source,ThemeManager.DefaultTheme) {
        val preedit=PreeditUi(service,ThemeManager.DefaultTheme)
        val handled=mutableListOf<FcitxEvent<*>>()
        override fun onStartHandleFcitxEvent() { preedit.update(source.panel) }
        override fun handleFcitxEvent(it:FcitxEvent<*>) {
            handled+=it
            if(it is FcitxEvent.InputPanelEvent) preedit.update(it.data)
        }
    }

    @Test fun committedPinyinClearsAfterTheMainThreadWasBusyAndNextCompositionStillShows() {
        val service=Robolectric.buildService(FcitxInputMethodService::class.java).get()
        val source=NativeEvents()
        val keyboard=Keyboard(service,source)
        val main=shadowOf(Looper.getMainLooper())
        try {
            keyboard.handleEvents=true
            main.idleFor(Duration.ofMillis(32))
            thread { source.emit(source.preedit("ren sheng ji shi nian")) }.join()
            main.idleFor(Duration.ofMillis(32))
            assertTrue(keyboard.preedit.visible)
            keyboard.handled.clear()
            // A host editor may occupy Main while native selection has already
            // committed the word, cleared preedit and updated other UI state.
            thread {
                source.emit(FcitxEvent.CommitStringEvent(FcitxEvent.CommitStringEvent.Data("人生几十年",-1)))
                source.emit(FcitxEvent.InputPanelEvent(FcitxEvent.InputPanelEvent.Data()))
                repeat(64) { source.emit(FcitxEvent.CandidateListEvent(FcitxEvent.CandidateListEvent.Data(0))) }
            }.join()
            main.idleFor(Duration.ofMillis(32))
            assertFalse("Committed pinyin must disappear even after a burst exceeds the shared-flow buffer",keyboard.preedit.visible)
            assertEquals("The burst renders only the latest preedit and candidate states",2,keyboard.handled.size)
            keyboard.handleEvents=false
            main.idleFor(Duration.ofMillis(32))
            assertTrue("Hidden views release their native listener",source.listeners.isEmpty())
            thread { source.emit(source.preedit("ni hao")) }.join()
            main.idleFor(Duration.ofMillis(32))
            assertFalse("Hidden views do not process new events",keyboard.preedit.visible)
            keyboard.handleEvents=true
            main.idleFor(Duration.ofMillis(32))
            assertTrue("Reopening restores the current composition",keyboard.preedit.visible)
        } finally {
            keyboard.handleEvents=false
            source.lifecycleScope.cancel()
            main.idleFor(Duration.ofMillis(32))
        }
    }

    @Test fun thousandsOfUpdatesRenderOnlyTheLatestStateOfEachUiType() {
        val service=Robolectric.buildService(FcitxInputMethodService::class.java).get()
        val source=NativeEvents()
        val keyboard=Keyboard(service,source)
        val main=shadowOf(Looper.getMainLooper())
        try {
            keyboard.handleEvents=true
            main.idleFor(Duration.ofMillis(32))
            thread {
                repeat(5_000) { i ->
                    source.emit(source.preedit("ni $i"))
                    source.emit(FcitxEvent.CandidateListEvent(FcitxEvent.CandidateListEvent.Data(i)))
                    source.emit(FcitxEvent.ClientPreeditEvent(FormattedText(arrayOf("ni $i"),intArrayOf(0),0)))
                }
                val im=InputMethodEntry("rime")
                source.emit(FcitxEvent.IMChangeEvent(im))
                source.emit(FcitxEvent.StatusAreaEvent(FcitxEvent.StatusAreaEvent.Data(emptyArray(),im)))
                source.emit(FcitxEvent.PagedCandidateEvent(FcitxEvent.PagedCandidateEvent.Data.Empty))
                source.emit(FcitxEvent.CommitStringEvent(FcitxEvent.CommitStringEvent.Data("你好",-1)))
                source.emit(FcitxEvent.DeleteSurroundingEvent(FcitxEvent.DeleteSurroundingEvent.Data(1,0)))
            }.join()
            main.idleFor(Duration.ofMillis(32))
            assertEquals("UI work stays bounded to six state types",6,keyboard.handled.size)
            assertEquals("ni 4999",keyboard.handled.filterIsInstance<FcitxEvent.InputPanelEvent>().single().data.preedit.toString())
            assertEquals(4999,keyboard.handled.filterIsInstance<FcitxEvent.CandidateListEvent>().single().data.total)
            assertEquals("ni 4999",keyboard.handled.filterIsInstance<FcitxEvent.ClientPreeditEvent>().single().data.toString())
            assertTrue(keyboard.handled.none { it is FcitxEvent.CommitStringEvent || it is FcitxEvent.DeleteSurroundingEvent })
        } finally {
            keyboard.handleEvents=false
            source.lifecycleScope.cancel()
            main.idleFor(Duration.ofMillis(32))
        }
    }

    @Test fun pendingFrameUsesTheNewestValueAndIsCancelledWhenTheViewStops() {
        val service=Robolectric.buildService(FcitxInputMethodService::class.java).get()
        val source=NativeEvents()
        val keyboard=Keyboard(service,source)
        val main=shadowOf(Looper.getMainLooper())
        val previous=ShadowChoreographer.getFrameDelay()
        val wasPaused=ShadowChoreographer.isPaused()
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        try {
            keyboard.handleEvents=true
            main.idle()
            thread { source.emit(source.preedit("ni")) }.join()
            main.idle()
            assertTrue("The state waits for the next screen frame",keyboard.handled.isEmpty())
            thread { source.emit(source.preedit("ni hao")) }.join()
            main.idleFor(Duration.ofMillis(32))
            assertEquals(1,keyboard.handled.size)
            assertEquals("ni hao",(keyboard.handled.single() as FcitxEvent.InputPanelEvent).data.preedit.toString())

            thread { source.emit(FcitxEvent.InputPanelEvent(FcitxEvent.InputPanelEvent.Data())) }.join()
            main.idle()
            keyboard.handleEvents=false
            main.idleFor(Duration.ofMillis(32))
            assertEquals("Stopping discards the scheduled refresh",1,keyboard.handled.size)
            assertTrue(source.listeners.isEmpty())
            keyboard.handleEvents=true
            main.idleFor(Duration.ofMillis(32))
            assertFalse("Reopening reads the current empty composition",keyboard.preedit.visible)
        } finally {
            keyboard.handleEvents=false
            source.lifecycleScope.cancel()
            main.idleFor(Duration.ofMillis(32))
            ShadowChoreographer.setFrameDelay(previous)
            ShadowChoreographer.setPaused(wasPaused)
        }
    }
}

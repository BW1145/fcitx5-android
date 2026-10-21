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
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
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
        override fun onStartHandleFcitxEvent() { preedit.update(source.panel) }
        override fun handleFcitxEvent(it:FcitxEvent<*>) {
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
            main.idle()
            thread { source.emit(source.preedit("ren sheng ji shi nian")) }.join()
            main.idle()
            assertTrue(keyboard.preedit.visible)
            // A host editor may occupy Main while native selection has already
            // committed the word, cleared preedit and updated other UI state.
            thread {
                source.emit(FcitxEvent.CommitStringEvent(FcitxEvent.CommitStringEvent.Data("人生几十年",-1)))
                source.emit(FcitxEvent.InputPanelEvent(FcitxEvent.InputPanelEvent.Data()))
                repeat(64) { source.emit(FcitxEvent.CandidateListEvent(FcitxEvent.CandidateListEvent.Data(0))) }
            }.join()
            main.idle()
            assertFalse("Committed pinyin must disappear even after a burst exceeds the shared-flow buffer",keyboard.preedit.visible)
            keyboard.handleEvents=false
            main.idle()
            assertTrue("Hidden views release their native listener",source.listeners.isEmpty())
            thread { source.emit(source.preedit("ni hao")) }.join()
            main.idle()
            assertFalse("Hidden views do not process new events",keyboard.preedit.visible)
            keyboard.handleEvents=true
            main.idle()
            assertTrue("Reopening restores the current composition",keyboard.preedit.visible)
        } finally {
            keyboard.handleEvents=false
            source.lifecycleScope.cancel()
            main.idle()
        }
    }
}

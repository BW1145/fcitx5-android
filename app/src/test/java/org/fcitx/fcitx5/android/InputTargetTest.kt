/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android

import android.app.Activity
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.fcitx.fcitx5.android.core.*
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.data.KeyMacros
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.BaseInputView
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.editor.PanelEditorSession
import org.fcitx.fcitx5.android.input.keyboard.CommonKeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyActionListener
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mechdancer.dependency.DynamicScope
import org.mechdancer.dependency.manager.wrapToUniqueComponent
import org.mechdancer.dependency.plusAssign
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPopupMenu
import org.robolectric.util.ReflectionHelpers
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=FcitxApplication::class)
class InputTargetTest {
    private class Engine:FcitxConnection {
        val events=mutableListOf<FcitxEvent<*>>()
        var target=0L
        var preedit=0
        var backspaces=0
        var resets=0
        var revision=1L
        var requests=0
        var actions=0
        var query=CompletableDeferred<Array<CandidateAction>>()
        private val unused=Proxy.newProxyInstance(FcitxAPI::class.java.classLoader,arrayOf(FcitxAPI::class.java)) { _,m,_->
            error("Unexpected API call: ${m.name}")
        } as FcitxAPI
        val api=object:FcitxAPI by unused {
            override val inputMethodEntryCached=InputMethodEntry("rime","Rime","","","","zh_CN","rime",false)
            // These display caches deliberately lag behind engine state.
            override val clientPreeditCached=FormattedText.Empty
            override val inputPanelCached=FcitxEvent.InputPanelEvent.Data()
            override val candidateRevisionCached get()=revision
            override fun addInputEventListener(listener:(FcitxEvent<*>)->Unit) {}
            override fun removeInputEventListener(listener:(FcitxEvent<*>)->Unit) {}
            override suspend fun hasPreedit()=preedit>0
            override suspend fun enqueueInputBarrier(enqueue:()->Unit) { enqueue() }
            override suspend fun setInputTarget(target:Long) { reset();this@Engine.target=target }
            override suspend fun setCapFlags(flags:CapabilityFlags) {}
            override suspend fun focus(focus:Boolean) {}
            override suspend fun focusOutIn() {}
            override suspend fun reset() { resets++;preedit=0 }
            override suspend fun sendKey(sym:KeySym,states:KeyStates,code:Int,up:Boolean,timestamp:Int) {
                if(up) return
                if(sym.sym==FcitxKeyMapping.FcitxKey_BackSpace) {
                    backspaces++
                    if(preedit>0) preedit-- else emit(FcitxEvent.DeleteSurroundingEvent(FcitxEvent.DeleteSurroundingEvent.Data(1,0)))
                }
            }
            override suspend fun getCandidateActions(idx:Int,candidate:CandidateWord):Array<CandidateAction> {
                requests++
                try { return query.await() } finally { requests-- }
            }
            override suspend fun triggerCandidateAction(idx:Int,actionIdx:Int,candidate:CandidateWord) {
                if(candidate.revision==revision) actions++
            }
        }
        fun emit(event:FcitxEvent<*>) { event.inputTarget=target;events+=event }
        override val lifecycleScope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        override fun <T> runImmediately(block:suspend FcitxAPI.()->T):T=runBlocking { api.block() }
        override suspend fun <T> runOnReady(block:suspend FcitxAPI.()->T):T=api.block()
        override fun runIfReady(block:suspend FcitxAPI.()->Unit) { lifecycleScope.launch { api.block() } }
    }
    private class Harness:AutoCloseable {
        val service=Robolectric.buildService(FcitxInputMethodService::class.java).get()
        val engine=Engine()
        private val method=FcitxInputMethodService::class.java.getDeclaredMethod("handleFcitxEvent",FcitxEvent::class.java).apply { isAccessible=true }
        @Suppress("UNCHECKED_CAST")
        private val jobs=ReflectionHelpers.getField<Channel<Job>>(service,"jobs")
        private val events=ReflectionHelpers.getField<Channel<Any>>(service,"inputEvents")
        private val scope=DynamicScope()
        val keys=CommonKeyActionListener()
        init {
            ReflectionHelpers.setField(service,"fcitx",engine)
            ReflectionHelpers.setField(service,"decorView",TextView(service))
            scope+=ContextThemeWrapper(service,R.style.Theme_InputViewTheme).wrapToUniqueComponent()
            scope+=service.wrapToUniqueComponent();scope+=engine.wrapToUniqueComponent()
            scope+=InputWindowManager();scope+=keys
        }
        fun host(text:String):EditText {
            val view=EditText(service).apply { setText(text);setSelection(text.length) }
            val info=EditorInfo().apply { inputType=1;initialSelStart=text.length;initialSelEnd=text.length }
            ReflectionHelpers.setField(service,"mStartedInputConnection",view.onCreateInputConnection(info))
            ReflectionHelpers.setField(service,"mInputStarted",true)
            ReflectionHelpers.setField(service,"mInputEditorInfo",info)
            service.onStartInput(info,false)
            return view
        }
        fun action(action:KeyAction) { keys.listener.onKeyAction(action,KeyActionListener.Source.Keyboard) }
        fun deliver(event:FcitxEvent<*>) { method.invoke(service,event) }
        fun drain() {
            var active:Job?=null
            repeat(50) {
                // Match the service's jobs.consumeEach { it.join() }: later
                // commands cannot overtake a suspended editor/barrier operation.
                if(active?.isCompleted!=false) {
                    active=jobs.tryReceive().getOrNull()
                    active?.start()
                }
                engine.events.toList().also { engine.events.clear() }.forEach(::deliver)
                while(true) {
                    val event=events.tryReceive().getOrNull() ?: break
                    val completion=ReflectionHelpers.getField<CompletableDeferred<Unit>>(event,"completion")
                    completion.complete(Unit)
                }
                shadowOf(Looper.getMainLooper()).idle()
                if(active==null && engine.events.isEmpty()) return
            }
            error("Ordered input operations did not finish")
        }
        override fun close() { service.lifecycleScope.cancel();engine.lifecycleScope.cancel();scope.clear() }
    }

    @Test fun deletionUsesEngineStateBeforeTheNextDisplayFrame()=Harness().use { h ->
        val host=h.host("prefix");h.drain()
        h.engine.preedit=2
        h.action(KeyAction.BeginBackspaceAction)
        repeat(5) { h.action(KeyAction.SymAction(KeySym(FcitxKeyMapping.FcitxKey_BackSpace))) }
        h.drain()
        assertEquals(2,h.engine.backspaces)
        assertEquals("prefix",host.text.toString())
        h.action(KeyAction.MacroAction(listOf(KeyMacros.Step("clear"))));h.drain()
        assertEquals("A composition-only gesture keeps committed text", "prefix",host.text.toString())
        h.action(KeyAction.EndBackspaceAction);h.action(KeyAction.BeginBackspaceAction)
        h.action(KeyAction.SymAction(KeySym(FcitxKeyMapping.FcitxKey_BackSpace)));h.drain()
        assertEquals("A new press without composition deletes normally","prefi",host.text.toString())
    }

    @Test fun lateTextAndDeletionKeepTheirSourceEditorAcrossHostAndPanelSwitches()=Harness().use { h ->
        val first=h.host("first");h.drain()
        h.engine.emit(FcitxEvent.CommitStringEvent(FcitxEvent.CommitStringEvent.Data("old",-1)))
        h.engine.emit(FcitxEvent.DeleteSurroundingEvent(FcitxEvent.DeleteSurroundingEvent.Data(1,0)))
        val second=h.host("second")
        h.drain()
        assertEquals("first",first.text.toString());assertEquals("second",second.text.toString())
        val panel=PanelEditorSession(h.service,ThemeManager.DefaultTheme)
        h.engine.emit(FcitxEvent.CommitStringEvent(FcitxEvent.CommitStringEvent.Data("late-host",-1)))
        panel.open(PanelEditorSession.Mode.SavedContent);h.drain()
        val editor=panel.root.findViewById<EditText>(android.R.id.edit) ?: panel.root.let { root ->
            (root.getChildAt(0) as android.view.ViewGroup).getChildAt(0) as EditText
        }
        assertEquals("",editor.text.toString())
        h.engine.emit(FcitxEvent.CommitStringEvent(FcitxEvent.CommitStringEvent.Data("panel",-1)));h.drain()
        assertEquals("panel",editor.text.toString());assertEquals("second",second.text.toString())
        h.engine.preedit=2
        panel.handleAction(KeyAction.BeginBackspaceAction)
        repeat(5) { panel.handleAction(KeyAction.SymAction(KeySym(FcitxKeyMapping.FcitxKey_BackSpace))) }
        panel.handleAction(KeyAction.MacroAction(listOf(KeyMacros.Step("clear"))));h.drain()
        assertEquals("A composition gesture in the panel keeps its draft","panel",editor.text.toString())
        h.engine.emit(FcitxEvent.CommitStringEvent(FcitxEvent.CommitStringEvent.Data("late-panel",-1)))
        panel.close();h.drain()
        assertEquals("second",second.text.toString())
        h.engine.emit(FcitxEvent.CommitStringEvent(FcitxEvent.CommitStringEvent.Data("current",-1)));h.drain()
        assertEquals("secondcurrent",second.text.toString())
    }

    @Test fun candidateCommitReplacesCompositionBeforeClearingPreedit()=Harness().use { h ->
        val host=h.host("prefix");h.drain()
        h.engine.emit(FcitxEvent.ClientPreeditEvent(FormattedText(arrayOf("nihao"),intArrayOf(0),5)))
        h.drain()
        assertEquals("prefixnihao",host.text.toString())
        // The service must process the commit before the following empty preedit,
        // even when the UI has not drawn another frame yet.
        h.engine.emit(FcitxEvent.CommitStringEvent(FcitxEvent.CommitStringEvent.Data("你好",-1)))
        h.engine.emit(FcitxEvent.ClientPreeditEvent(FormattedText.Empty))
        h.drain()
        assertEquals("prefix你好",host.text.toString())
        assertEquals(-1,android.view.inputmethod.BaseInputConnection.getComposingSpanStart(host.text))
        h.engine.emit(FcitxEvent.ClientPreeditEvent(FormattedText(arrayOf("ma"),intArrayOf(0),2)))
        h.drain()
        assertEquals("prefix你好ma",host.text.toString())
    }

    @Test fun hidingKeyboardKeepsCallbacksWorkingInTheSameEditor()=Harness().use { h ->
        val host=h.host("draft");h.drain()
        h.engine.emit(FcitxEvent.CommitStringEvent(FcitxEvent.CommitStringEvent.Data("stale",-1)))
        h.service.onFinishInputView(false)
        h.drain()
        assertEquals("Callbacks queued before hiding are discarded","draft",host.text.toString())
        assertEquals("Native and service targets stay synchronized",h.service.inputActionToken,h.engine.target)
        // A hidden keyboard can continue serving hardware input in the same editor,
        // without Android starting a new input session.
        h.engine.emit(FcitxEvent.CommitStringEvent(FcitxEvent.CommitStringEvent.Data("current",-1)))
        h.drain()
        assertEquals("draftcurrent",host.text.toString())
    }

    @Test fun delayedCandidateMenuCannotOpenForAChangedListOrClosedKeyboard()=Harness().use { h ->
        val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
        val keyboard=object:BaseInputView(h.service,h.engine,ThemeManager.DefaultTheme) {
            override fun onStartHandleFcitxEvent() {}
            override fun handleFcitxEvent(it:FcitxEvent<*>) {}
        }
        val anchor=View(activity);activity.setContentView(anchor)
        val candidate=CandidateWord("","word","").apply { revision=1 }
        try {
            keyboard.handleEvents=true
            keyboard.showCandidateActionMenu(0,candidate,anchor)
            assertEquals(1,h.engine.requests)
            h.engine.revision=2
            h.engine.query.complete(arrayOf(CandidateAction(-1001,"forget",false,"",false,false)))
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(ShadowPopupMenu.getLatestPopupMenu())
            h.engine.query=CompletableDeferred()
            keyboard.showCandidateActionMenu(0,candidate,anchor)
            assertEquals(1,h.engine.requests)
            keyboard.handleEvents=false
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("Closing cancels the pending action query",0,h.engine.requests)
            assertNull(ShadowPopupMenu.getLatestPopupMenu())
            keyboard.handleEvents=true
            val current=candidate.copy().apply { revision=2 }
            h.engine.query=CompletableDeferred(arrayOf(CandidateAction(-1001,"forget",false,"",false,false)))
            keyboard.showCandidateActionMenu(0,current,anchor)
            shadowOf(Looper.getMainLooper()).idle()
            val popup=ShadowPopupMenu.getLatestPopupMenu()
            assertNotNull("A current candidate still opens its actions",popup)
            h.engine.revision=3
            val item=popup.menu.getItem(1)
            item.javaClass.getMethod("invoke").invoke(item)
            h.drain()
            assertEquals("An action cannot apply after its candidate list changes",0,h.engine.actions)
        } finally { keyboard.handleEvents=false;activity.finish() }
    }

    @Test fun rebindingAnUnchangedWordKeepsTheNewCandidateIdentity()=Harness().use { h ->
        val ui=org.fcitx.fcitx5.android.input.candidates.CandidateItemUi(
            ContextThemeWrapper(h.service,R.style.Theme_InputViewTheme),ThemeManager.DefaultTheme)
        val holder=org.fcitx.fcitx5.android.input.candidates.CandidateViewHolder(ui)
        val old=CandidateWord("","word","").apply { revision=1 }
        val current=CandidateWord("","word","").apply { revision=2 }
        holder.update(0,old);holder.update(0,current)
        assertSame(current,holder.candidate)
        assertEquals(2,holder.candidate.revision)
    }
}

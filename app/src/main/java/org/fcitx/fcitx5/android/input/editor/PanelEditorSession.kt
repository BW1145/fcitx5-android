/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.editor

import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.FcitxAPI
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.data.KeyMacros
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import splitties.dimensions.dp

/** Routes engine events to an IME-owned editor for search or AI. */
class PanelEditorSession(private val service: FcitxInputMethodService, private val theme: Theme) {
    private enum class State { Closed, Opening, Open, Closing }
    @Volatile private var state = State.Closed
    @Volatile private var session = 0L
    val active get() = state == State.Opening || state == State.Open
    val selection get() = editor.selection
    private val editor = PanelEditor(service)
    val root = LinearLayout(service).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(theme.keyBackgroundColor)
        visibility = View.GONE
    }
    private val results = org.fcitx.fcitx5.android.input.clipboard.ContentSearchResults(service, service.lifecycleScope, theme) { close(it) }
    enum class Mode { Clipboard, SavedContent, Ai }
    private var mode = Mode.Clipboard
    private val aiMode get() = mode == Mode.Ai
    private val ai by lazy { org.fcitx.fcitx5.android.input.ai.AiPanel(service,theme,editor.view,
        { sendAi() }, { text,replace -> close(text,replace) }) }
    private var job: Job? = null
    private var backspaceStartedWithPreedit = false
    private var swipeHadPreedit = false
    private var selecting = false

    init {
        editor.view.setTextColor(theme.keyTextColor)
        root.addView(LinearLayout(service).apply {
            addView(editor.view, LinearLayout.LayoutParams(0, -2, 1f))
            addView(Button(service).apply {
                setText(android.R.string.cancel)
                setOnClickListener { close() }
            })
        })
        root.addView(results.root,
            LinearLayout.LayoutParams(-1, service.dp(180)))
        editor.view.doAfterTextChanged {
            if (state == State.Open && aiMode) {
                job?.cancel()
                job=service.lifecycleScope.launch { delay(200);if(active && aiMode) ai.rememberDraft() }
            } else if (active && !aiMode) search()
        }
    }

    fun open(requested: Mode) {
        val assistant = requested == Mode.Ai
        if(active) close()
        session++
        state = State.Opening
        mode = requested
        backspaceStartedWithPreedit = false
        selecting = false
        swipeHadPreedit = false
        editor.clear()
        root.visibility = View.VISIBLE
        results.root.visibility = if(assistant) View.GONE else View.VISIBLE
        if(assistant) {
            if(ai.root.parent==null) root.addView(ai.root)
            ai.root.visibility=View.VISIBLE
        } else {
            if(root.childCount>2) root.getChildAt(2).visibility=View.GONE
            editor.view.setHint(R.string.content_search)
        }
        service.beginPanelEditing(this, session)
        if(assistant) ai.open() else search()
    }

    internal fun ready(token: Long) {
        if (session == token && state == State.Opening) state = State.Open
    }

    fun close(result: String? = null, replaceDraft: Boolean = false) {
        if (!active) return
        if(aiMode) ai.close()
        state = State.Closing
        root.visibility = View.GONE
        job?.cancel()
        results.close()
        editor.finishComposing()
        service.endPanelEditing(this, session, result, replaceDraft)
    }

    internal fun closed(token: Long): Boolean {
        if (session != token || state != State.Closing) return false
        state = State.Closed
        return true
    }

    private fun accepts(token: Long) = session == token && active

    private fun enqueue(block: suspend FcitxAPI.(Long) -> Unit) {
        if (!active) return
        val token = session
        service.postFcitxJob {
            if (accepts(token)) block(token)
        }
    }

    private suspend fun edit(token: Long, block: PanelEditor.() -> Unit) {
        withContext(Dispatchers.Main) { if (accepts(token)) editor.block() }
    }

    private suspend fun FcitxAPI.commitAndReset(token: Long) {
        service.awaitInputEvents(this)
        if (inputMethodEntryCached.languageCode.startsWith("zh")) {
            if (clientPreeditCached.isNotEmpty() || inputPanelCached.preedit.isNotEmpty()) select(0)
        } else {
            edit(token) { finishComposing() }
        }
        reset()
        service.awaitInputEvents(this)
    }

    private fun sendAi() {
        enqueue { token ->
            commitAndReset(token)
            withContext(Dispatchers.Main) { if(accepts(token)) ai.send() }
        }
    }

    fun commitLiteral(text: String, cursor: Int = -1) {
        enqueue { token ->
            commitAndReset(token)
            edit(token) { commit(text, cursor) }
        }
    }

    fun cancelSelection() { enqueue { token ->
        service.awaitInputEvents(this)
        edit(token) { cancelSelection() }
    } }

    fun deleteSelection() { enqueue { token ->
        service.awaitInputEvents(this)
        edit(token) { deleteSelection() }
    } }

    fun combinationKey(keyCode: Int, alt: Boolean, ctrl: Boolean, shift: Boolean) {
        var meta = 0
        if (alt) meta = meta or KeyEvent.META_ALT_ON
        if (ctrl) meta = meta or KeyEvent.META_CTRL_ON
        if (shift) meta = meta or KeyEvent.META_SHIFT_ON
        val event = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, keyCode, 0, meta)
        enqueue { token ->
            commitAndReset(token)
            edit(token) { key(keyCode, event.unicodeChar, meta) }
        }
    }

    private suspend fun FcitxAPI.macro(token: Long, steps: List<KeyMacros.Step>) {
        for (step in steps) {
            if (!accepts(token)) return
            if(step.action=="privacy") {
                withContext(Dispatchers.Main) { service.togglePrivacyMode() }
                return
            }
            if (step.action == "clear") {
                reset()
                service.awaitInputEvents(this)
                edit(token) { clear() }
                selecting = false
            } else {
                commitAndReset(token)
                edit(token) {
                    when (step.action) {
                        "text" -> commit(step.text)
                        "selectAll" -> contextAction(android.R.id.selectAll)
                        "copy" -> contextAction(android.R.id.copy)
                        "paste" -> contextAction(android.R.id.paste)
                        "cut" -> contextAction(android.R.id.cut)
                        "undo" -> contextAction(android.R.id.undo)
                        "redo" -> contextAction(android.R.id.redo)
                        "key" -> {
                            val code = KeyEvent.keyCodeFromString("KEYCODE_" + step.text.uppercase().removePrefix("KEYCODE_"))
                            val meta = (if (step.ctrl) KeyEvent.META_CTRL_ON else 0) or
                                (if (step.alt) KeyEvent.META_ALT_ON else 0) or
                                (if (step.shift) KeyEvent.META_SHIFT_ON else 0)
                            val event = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, code, 0, meta)
                            key(code, event.unicodeChar, meta)
                        }
                    }
                }
            }
        }
    }

    fun handleAction(action: KeyAction): Boolean {
        when (action) {
            is KeyAction.CommitAction -> commitLiteral(action.text)
            is KeyAction.MacroAction -> enqueue { token -> macro(token, action.steps) }
            is KeyAction.BeginBackspaceAction -> enqueue {
                backspaceStartedWithPreedit = clientPreeditCached.isNotEmpty() || inputPanelCached.preedit.isNotEmpty()
                selecting = false
                swipeHadPreedit = backspaceStartedWithPreedit
            }
            is KeyAction.EndBackspaceAction -> enqueue { backspaceStartedWithPreedit = false }
            is KeyAction.MoveSelectionAction -> enqueue { token ->
                service.awaitInputEvents(this)
                if (!swipeHadPreedit) {
                    edit(token) { moveSelection(action.start, action.end) }
                    selecting = true
                }
            }
            is KeyAction.DeleteSelectionAction -> enqueue { token ->
                service.awaitInputEvents(this)
                if (selecting) edit(token) { deleteSelection() }
                else if (swipeHadPreedit && action.totalCnt < 0) reset()
                selecting = false
            }
            is KeyAction.FcitxKeyAction -> enqueue {
                sendKey(action.act, action.states.states, action.code)
                if (inputMethodEntryCached.uniqueName == "anthy") sendKey(action.act, action.states.states, action.code, up = true)
            }
            is KeyAction.SymAction -> {
                enqueue {
                    val preeditOnly = backspaceStartedWithPreedit && action.sym.sym == FcitxKeyMapping.FcitxKey_BackSpace
                    if (!preeditOnly || clientPreeditCached.isNotEmpty() || inputPanelCached.preedit.isNotEmpty()) {
                        sendKey(action.sym, action.states)
                        if (inputMethodEntryCached.uniqueName == "anthy") sendKey(action.sym, action.states, up = true)
                    }
                }
            }
            is KeyAction.QuickPhraseAction -> enqueue { token -> commitAndReset(token); triggerQuickPhrase() }
            is KeyAction.UnicodeAction -> enqueue { token -> commitAndReset(token); triggerUnicode() }
            else -> return false
        }
        return true
    }

    private fun search() {
        results.search(editor.view.text.toString(), mode == Mode.SavedContent)
    }

    fun handle(event: FcitxEvent<*>): Boolean {
        val editing = event is FcitxEvent.CommitStringEvent || event is FcitxEvent.ClientPreeditEvent ||
            event is FcitxEvent.DeleteSurroundingEvent || event is FcitxEvent.KeyEvent
        if (!editing) return false
        // Keep consuming while closing. A previously queued key/candidate must never
        // reach the original app merely because the search view has become invisible.
        if (state == State.Open) editor.handle(event)
        return true
    }
}

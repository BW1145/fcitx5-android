/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.clipboard

import android.content.Context
import android.text.Editable
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
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
import org.fcitx.fcitx5.android.core.FormattedText
import org.fcitx.fcitx5.android.data.KeyMacros
import org.fcitx.fcitx5.android.data.SavedContentStore
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.cursor.CursorRange
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import splitties.dimensions.dp
import kotlin.math.max
import kotlin.math.min

/** An editor owned by the IME; none of its operations use the app's InputConnection. */
internal class ContentSearchEditor(context: Context) {
    val view = EditText(context).apply {
        setSingleLine()
        setHint(R.string.content_search)
        showSoftInputOnFocus = false
        isFocusable = false
        setSelection(0)
    }
    private val connection = object : BaseInputConnection(view, true) {
        override fun getEditable(): Editable = view.text
    }
    val selection get() = CursorRange().apply {
        update(view.selectionStart.coerceAtLeast(0), view.selectionEnd.coerceAtLeast(0))
    }

    fun clear() {
        view.setText("")
        view.setSelection(0)
    }

    fun commit(text: String, cursor: Int = -1) {
        val start = BaseInputConnection.getComposingSpanStart(view.text)
            .takeIf { it >= 0 } ?: selection.start
        connection.commitText(text, 1)
        if (cursor >= 0) view.setSelection((start + cursor).coerceIn(0, view.length()))
    }

    fun updatePreedit(text: FormattedText) {
        // An empty reset with no composing span must not replace a local selection.
        if (text.isEmpty() && BaseInputConnection.getComposingSpanStart(view.text) < 0) return
        val start = BaseInputConnection.getComposingSpanStart(view.text)
            .takeIf { it >= 0 } ?: selection.start
        connection.setComposingText(text.toString(), 1)
        if (text.cursor >= 0) view.setSelection((start + text.cursor).coerceIn(0, view.length()))
    }

    fun finishComposing() { connection.finishComposingText() }

    // Convert code-point lengths to UTF-16 lengths and use the API 3 method. This also
    // works on API 23, where deleteSurroundingTextInCodePoints does not exist.
    fun deleteSurrounding(before: Int, after: Int) {
        val text = view.text.toString()
        val range = selection
        val composingStart = BaseInputConnection.getComposingSpanStart(view.text)
        val composingEnd = BaseInputConnection.getComposingSpanEnd(view.text)
        val start = if (composingStart >= 0) min(range.start, composingStart) else range.start
        val end = if (composingEnd >= 0) max(range.end, composingEnd) else range.end
        val left = Character.offsetByCodePoints(text, start,
            -before.coerceIn(0, text.codePointCount(0, start)))
        val right = Character.offsetByCodePoints(text, end,
            after.coerceIn(0, text.codePointCount(end, text.length)))
        connection.deleteSurroundingText(start - left, right - end)
    }

    fun moveSelection(startOffset: Int, endOffset: Int = 0) {
        val range = selection
        fun move(position: Int, amount: Int): Int = Character.offsetByCodePoints(
            view.text, position, amount.coerceIn(-Character.codePointCount(view.text, 0, position),
                Character.codePointCount(view.text, position, view.length())))
        val start = move(range.start, startOffset)
        val end = move(range.end, endOffset)
        if (start <= end) view.setSelection(start, end)
    }

    fun deleteSelection() {
        if (selection.isNotEmpty()) commit("")
    }

    fun cancelSelection() { view.setSelection(selection.end) }

    fun contextAction(action: Int) { view.onTextContextMenuItem(action) }

    fun key(keyCode: Int, unicode: Int = 0, metaState: Int = 0) {
        val ctrl = metaState and KeyEvent.META_CTRL_ON != 0
        val shift = metaState and KeyEvent.META_SHIFT_ON != 0
        if (ctrl) {
            val action = when (keyCode) {
                KeyEvent.KEYCODE_A -> android.R.id.selectAll
                KeyEvent.KEYCODE_C -> android.R.id.copy
                KeyEvent.KEYCODE_X -> android.R.id.cut
                KeyEvent.KEYCODE_V -> android.R.id.paste
                KeyEvent.KEYCODE_Z -> if (shift) android.R.id.redo else android.R.id.undo
                KeyEvent.KEYCODE_Y -> android.R.id.redo
                else -> null
            }
            action?.let(::contextAction)
            return
        }
        when (keyCode) {
            KeyEvent.KEYCODE_DEL -> if (selection.isNotEmpty()) deleteSelection() else deleteSurrounding(1, 0)
            KeyEvent.KEYCODE_FORWARD_DEL -> if (selection.isNotEmpty()) deleteSelection() else deleteSurrounding(0, 1)
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> finishComposing()
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_MOVE_END -> {
                val range = selection
                val current = view.selectionEnd.coerceAtLeast(0)
                val target = when (keyCode) {
                    KeyEvent.KEYCODE_MOVE_HOME -> 0
                    KeyEvent.KEYCODE_MOVE_END -> view.length()
                    KeyEvent.KEYCODE_DPAD_LEFT -> if (!shift && range.isNotEmpty()) range.start
                        else Character.offsetByCodePoints(view.text, current, if (current > 0) -1 else 0)
                    else -> if (!shift && range.isNotEmpty()) range.end
                        else Character.offsetByCodePoints(view.text, current, if (current < view.length()) 1 else 0)
                }
                view.setSelection(if (shift) view.selectionStart.coerceAtLeast(0) else target, target)
            }
            else -> if (unicode > 0 && !Character.isISOControl(unicode)) commit(Character.toString(unicode))
        }
    }

    fun handle(event: FcitxEvent<*>): Boolean {
        when (event) {
            is FcitxEvent.CommitStringEvent -> commit(event.data.text, event.data.cursor)
            is FcitxEvent.ClientPreeditEvent -> updatePreedit(event.data)
            is FcitxEvent.DeleteSurroundingEvent -> deleteSurrounding(event.data.before, event.data.after)
            is FcitxEvent.KeyEvent -> if (!event.data.up) {
                key(event.data.sym.keyCode, event.data.unicode, event.data.states.metaState)
            }
            else -> return false
        }
        return true
    }
}

/** Search shares the keyboard engine, with a separate editor and an explicit session boundary. */
class ContentSearchPanel(private val service: FcitxInputMethodService, private val theme: Theme) {
    private enum class State { Closed, Opening, Open, Closing }
    @Volatile private var state = State.Closed
    @Volatile private var session = 0L
    val active get() = state == State.Opening || state == State.Open
    val selection get() = editor.selection
    private val editor = ContentSearchEditor(service)
    val root = LinearLayout(service).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(theme.keyBackgroundColor)
        visibility = View.GONE
    }
    private val results = LinearLayout(service).apply { orientation = LinearLayout.VERTICAL }
    private var saved = false
    private var aiMode = false
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
        root.addView(ScrollView(service).apply { addView(results) },
            LinearLayout.LayoutParams(-1, service.dp(180)))
        editor.view.doAfterTextChanged {
            if (state == State.Open && aiMode) {
                job?.cancel()
                job=service.lifecycleScope.launch { delay(200);if(active && aiMode) ai.rememberDraft() }
            } else if (active && !aiMode) search()
        }
    }

    fun open(savedContent: Boolean, assistant: Boolean = false) {
        if(active) close()
        session++
        state = State.Opening
        saved = savedContent
        aiMode = assistant
        backspaceStartedWithPreedit = false
        selecting = false
        swipeHadPreedit = false
        editor.clear()
        root.visibility = View.VISIBLE
        results.parent.let { (it as? View)?.visibility = if(assistant) View.GONE else View.VISIBLE }
        if(assistant) {
            if(ai.root.parent==null) root.addView(ai.root)
            ai.root.visibility=View.VISIBLE
        } else {
            if(root.childCount>2) root.getChildAt(2).visibility=View.GONE
            editor.view.setHint(R.string.content_search)
        }
        service.beginContentSearch(this, session)
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
        editor.finishComposing()
        service.endContentSearch(this, session, result, replaceDraft)
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

    private suspend fun edit(token: Long, block: ContentSearchEditor.() -> Unit) {
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
        job?.cancel()
        val token = session
        val text = editor.view.text.toString()
        job = service.lifecycleScope.launch {
            delay(120)
            val items = if (saved) {
                SavedContentStore.load().filter { it.title.contains(text, true) || it.text.contains(text, true) }
                    .map { it.title to it.text }
            } else ClipboardManager.search(text).map {
                (if (it.sensitive && AppPrefs.getInstance().clipboard.clipboardMaskSensitive.getValue()) "••••" else it.text) to it.text
            }
            if (!accepts(token)) return@launch
            results.removeAllViews()
            if (items.isEmpty()) results.addView(TextView(service).apply {
                setText(R.string.search_empty)
                setTextColor(theme.keyTextColor)
            })
            items.forEach { (label, value) ->
                results.addView(TextView(service).apply {
                    this.text = label.ifBlank { value }
                    textSize = 16f
                    setTextColor(theme.keyTextColor)
                    setPadding(service.dp(12), service.dp(12), service.dp(12), service.dp(12))
                    setOnClickListener { if (accepts(token)) close(value) }
                })
            }
        }
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

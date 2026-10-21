/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.editor

import android.content.Context
import android.text.Editable
import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection
import android.widget.EditText
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.core.FormattedText
import org.fcitx.fcitx5.android.input.cursor.CursorRange
import kotlin.math.max
import kotlin.math.min

/** An editor owned by the IME; none of its operations use the app's InputConnection. */
internal class PanelEditor(context: Context) {
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

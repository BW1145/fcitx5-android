/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.preedit

import android.content.Context
import android.annotation.SuppressLint
import android.graphics.Paint
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.RectShape
import android.text.Spanned
import android.text.SpannedString
import android.text.style.DynamicDrawableSpan
import android.view.View
import android.view.MotionEvent
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.core.text.buildSpannedString
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.data.theme.Theme
import splitties.dimensions.dp
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.textView
import splitties.views.dsl.core.verticalLayout

open class PreeditUi(
    override val ctx: Context,
    private val theme: Theme,
    private val setupTextView: (TextView.() -> Unit)? = null
) : Ui {

    class CursorSpan(ctx: Context, @ColorInt color: Int, metrics: Paint.FontMetricsInt) :
        DynamicDrawableSpan() {
        private val drawable = ShapeDrawable(RectShape()).apply {
            paint.color = color
            setBounds(0, metrics.ascent, ctx.dp(1), metrics.bottom)
        }

        override fun getDrawable() = drawable
    }

    private val cursorSpan by lazy {
        CursorSpan(ctx, theme.keyTextColor, upView.paint.fontMetricsInt)
    }

    private fun createTextView() = textView {
        setTextColor(theme.keyTextColor)
        textSize = 16f
        setupTextView?.invoke(this)
    }

    private val upView = createTextView()

    private val downView = createTextView()

    var onCursorRequested: ((Int) -> Unit)? = null
    private var preedit = FcitxEvent.InputPanelEvent.Data()
    private var prefixLength = 0
    private var cursorMarker = -1

    @SuppressLint("ClickableViewAccessibility")
    private fun enableCursorTouch() {
        upView.setOnTouchListener { view, event ->
            if (onCursorRequested == null || preedit.preedit.isEmpty()) return@setOnTouchListener false
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                val displayed = upView.getOffsetForPosition(event.x, event.y)
                val offset = displayed - if (cursorMarker >= 0 && displayed > cursorMarker) 1 else 0
                if (offset >= prefixLength) {
                    val position = (offset - prefixLength).coerceIn(0, preedit.preedit.length)
                    onCursorRequested?.invoke(preedit.preedit.codePointCountUntil(position))
                }
                view.performClick()
            }
            true
        }
    }

    init {
        enableCursorTouch()
    }

    var visible = false
        private set

    override val root: View = verticalLayout {
        add(upView, lParams())
        add(downView, lParams())
    }

    private fun updateTextView(view: TextView, str: CharSequence, visible: Boolean) {
        view.text = str
        view.visibility = if (visible) View.VISIBLE else View.GONE
    }

    fun update(inputPanel: FcitxEvent.InputPanelEvent.Data) {
        preedit = inputPanel
        prefixLength = inputPanel.auxUp.length
        val activeBkg = theme.genericActiveBackgroundColor
        val upString: SpannedString
        val upCursor: Int
        if (inputPanel.auxUp.isEmpty()) {
            upString = inputPanel.preedit.toSpannedString(activeBkg)
            upCursor = inputPanel.preedit.cursor
        } else {
            upString = buildSpannedString {
                append(inputPanel.auxUp.toSpannedString(activeBkg))
                append(inputPanel.preedit.toSpannedString(activeBkg))
            }
            upCursor = inputPanel.preedit.cursor.let {
                if (it < 0) it
                else inputPanel.auxUp.length + it
            }
        }
        val downString = inputPanel.auxDown.toSpannedString(activeBkg)
        val hasUp = upString.isNotEmpty()
        val hasDown = downString.isNotEmpty()
        visible = hasUp || hasDown
        if (!visible) {
            updateTextView(upView, "", false)
            updateTextView(downView, "", false)
            return
        }
        cursorMarker = if (upCursor >= 0 && (upCursor < upString.length || onCursorRequested != null)) upCursor else -1
        val upStringWithCursor = if (cursorMarker < 0) {
            upString
        } else buildSpannedString {
            if (upCursor > 0) append(upString, 0, upCursor)
            append('|')
            setSpan(cursorSpan, upCursor, upCursor + 1, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
            append(upString, upCursor, upString.length)
        }
        updateTextView(upView, upStringWithCursor, hasUp)
        updateTextView(downView, downString, hasDown)
    }
}

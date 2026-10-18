/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout

class PersonalInputTestActivity : Activity() {
    companion object {
        const val EXTRA_TEST_EDITOR_ENTRY = "org.fcitx.fcitx5.android.test.EDITOR_ENTRY"
        const val EXTRA_SHOW_SOFT_INPUT = "org.fcitx.fcitx5.android.test.SHOW_SOFT_INPUT"
    }

    lateinit var editor: EditText
        private set
    lateinit var otherEditor: EditText
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val testEditorEntry = intent.getBooleanExtra(EXTRA_TEST_EDITOR_ENTRY, false)
        val showSoftInput = intent.getBooleanExtra(EXTRA_SHOW_SOFT_INPUT, true)
        if (!showSoftInput) {
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        editor = EditText(this).apply {
            id = View.generateViewId()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            hint = "Input test"
            showSoftInputOnFocus = showSoftInput
            if (testEditorEntry) {
                setText("测试AB")
                setSelection(length())
            }
        }
        otherEditor = EditText(this).apply {
            id = View.generateViewId()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            hint = "Focus test"
            showSoftInputOnFocus = showSoftInput
            setText("切换CD")
            setSelection(length())
        }
        setContentView(if (testEditorEntry) LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(editor)
            addView(otherEditor)
        } else editor)
        editor.requestFocus()
        if (showSoftInput) {
            editor.post {
                getSystemService(InputMethodManager::class.java).showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }
}


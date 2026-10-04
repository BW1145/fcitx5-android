/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.SavedContentStore
import splitties.dimensions.dp

class SavedContentEditActivity : Activity() {
    private lateinit var name: EditText
    private lateinit var content: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.attributes.gravity = Gravity.TOP
        val id = intent.getStringExtra("id")
        val entry = SavedContentStore.load().find { it.id == id }
        name = EditText(this).apply {
            hint = getString(R.string.saved_content_title_hint)
            setSingleLine()
            setText(savedInstanceState?.getString("title") ?: entry?.title.orEmpty())
        }
        content = EditText(this).apply {
            hint = getString(R.string.saved_content_text_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            gravity = Gravity.TOP or Gravity.START
            minLines = 4
            maxLines = 8
            setText(savedInstanceState?.getString("text") ?: entry?.text.orEmpty())
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
            addView(name)
            addView(content)
            addView(LinearLayout(this@SavedContentEditActivity).apply {
                gravity = Gravity.END
                addView(Button(this@SavedContentEditActivity).apply {
                    setText(android.R.string.cancel)
                    setOnClickListener { finish() }
                })
                addView(Button(this@SavedContentEditActivity).apply {
                    setText(R.string.save)
                    setOnClickListener {
                        val text = content.text.toString()
                        if (text.isBlank()) {
                            content.error = getString(R.string.saved_content_text_hint)
                        } else {
                            SavedContentStore.put(id, name.text.toString(), text)
                            finish()
                        }
                    }
                })
            })
        }
        setContentView(layout)
        content.requestFocus()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("title", name.text.toString())
        outState.putString("text", content.text.toString())
        super.onSaveInstanceState(outState)
    }
}

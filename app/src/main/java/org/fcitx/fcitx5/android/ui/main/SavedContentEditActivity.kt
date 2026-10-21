/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main

import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.SavedContentStore
import splitties.dimensions.dp

class SavedContentEditActivity : ComponentActivity() {
    private lateinit var name: EditText
    private lateinit var content: EditText
    private var loaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.attributes.gravity = Gravity.TOP
        val id = intent.getStringExtra("id")
        loaded = id == null || savedInstanceState?.getBoolean("loaded", true) == true
        name = EditText(this).apply {
            hint = getString(R.string.saved_content_title_hint)
            setSingleLine()
            setText(savedInstanceState?.getString("title").orEmpty())
        }
        content = EditText(this).apply {
            hint = getString(R.string.saved_content_text_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            gravity = Gravity.TOP or Gravity.START
            minLines = 4
            maxLines = 8
            setText(savedInstanceState?.getString("text").orEmpty())
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
                            val title = name.text.toString()
                            isEnabled = false
                            name.isEnabled = false
                            content.isEnabled = false
                            lifecycleScope.launch {
                                withContext(Dispatchers.IO) { SavedContentStore.put(id, title, text) }
                                finish()
                            }
                        }
                    }
                })
            })
        }
        setContentView(layout)
        content.requestFocus()
        if (!loaded) {
            // Wait for the initial value before accepting edits; loading must not
            // replace text that the user has already started typing.
            content.isEnabled = false
            name.isEnabled = false
            lifecycleScope.launch {
                val entry = withContext(Dispatchers.IO) { SavedContentStore.load().find { it.id == id } }
                name.setText(entry?.title.orEmpty())
                content.setText(entry?.text.orEmpty())
                loaded = true
                name.isEnabled = true
                content.isEnabled = true
                content.requestFocus()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("title", name.text.toString())
        outState.putString("text", content.text.toString())
        outState.putBoolean("loaded", loaded)
        super.onSaveInstanceState(outState)
    }
}

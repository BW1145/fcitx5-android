/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.saved

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.SavedContentStore
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.fcitx.fcitx5.android.ui.main.SavedContentEditActivity
import org.mechdancer.dependency.manager.must
import splitties.dimensions.dp

class SavedContentWindow : InputWindow.ExtendedInputWindow<SavedContentWindow>() {
    private val service by manager.inputMethodService()
    private val theme by manager.theme()
    private val windowManager: InputWindowManager by manager.must()
    override val title get() = context.getString(R.string.saved_content)
    private var menu: PopupMenu? = null
    private val list by lazy {
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(8))
        }
    }
    private val scroll by lazy { ScrollView(context).apply { addView(list) } }

    private fun edit(id: String? = null) {
        context.startActivity(Intent(context, SavedContentEditActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            id?.let { putExtra("id", it) }
        })
    }

    private fun render() {
        list.removeAllViews()
        val entries = SavedContentStore.load()
        if (entries.isEmpty()) list.addView(TextView(context).apply {
            setText(R.string.saved_content_empty)
            setTextColor(theme.keyTextColor)
            setPadding(dp(8), dp(16), dp(8), dp(16))
        })
        entries.forEachIndexed { index, entry ->
            list.addView(TextView(context).apply {
                text = if (entry.title.isBlank()) entry.text else "${entry.title}\n${entry.text}"
                textSize = 16f
                setTextColor(theme.keyTextColor)
                setPadding(dp(12), dp(12), dp(12), dp(12))
                background = GradientDrawable().apply {
                    setColor(theme.keyBackgroundColor)
                    cornerRadius = context.dp(8f)
                }
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
                setOnClickListener {
                    service.postFcitxJob {
                        reset()
                        service.lifecycleScope.launch {
                            service.commitText(entry.text)
                            windowManager.attachWindow(KeyboardWindow)
                        }
                    }
                }
                setOnLongClickListener { anchor ->
                    menu?.dismiss()
                    menu = PopupMenu(context, anchor).apply {
                        menu.add(R.string.edit).setOnMenuItemClickListener { edit(entry.id); true }
                        menu.add(R.string.saved_content_move_up).apply {
                            isEnabled = index > 0
                            setOnMenuItemClickListener { SavedContentStore.move(entry.id, -1); render(); true }
                        }
                        menu.add(R.string.saved_content_move_down).apply {
                            isEnabled = index < entries.lastIndex
                            setOnMenuItemClickListener { SavedContentStore.move(entry.id, 1); render(); true }
                        }
                        menu.add(R.string.delete).setOnMenuItemClickListener {
                            SavedContentStore.delete(entry.id)
                            render()
                            true
                        }
                        show()
                    }
                    true
                }
            })
        }
    }

    override fun onCreateView(): View = scroll
    override fun onCreateBarExtension(): View = LinearLayout(context).apply {
        addView(Button(context).apply { setText(R.string.content_search);setOnClickListener { windowManager.attachWindow(KeyboardWindow);service.openContentSearch(true) } })
        addView(Button(context).apply { setText(R.string.saved_content_add);setOnClickListener { edit() } })
    }
    override fun onAttached() = render()
    override fun onDetached() { menu?.dismiss(); menu = null }
}

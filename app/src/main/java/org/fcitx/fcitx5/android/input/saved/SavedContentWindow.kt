/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.saved

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private var loadJob: Job? = null
    private var attached = false
    private class EntryHolder(val text: TextView) : RecyclerView.ViewHolder(text)
    private val adapter by lazy {
        object : ListAdapter<SavedContentStore.Entry, EntryHolder>(object : DiffUtil.ItemCallback<SavedContentStore.Entry>() {
            override fun areItemsTheSame(old: SavedContentStore.Entry, new: SavedContentStore.Entry) = old.id == new.id
            override fun areContentsTheSame(old: SavedContentStore.Entry, new: SavedContentStore.Entry) = old == new
        }) {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = EntryHolder(TextView(context).apply {
                textSize = 16f
                setTextColor(theme.keyTextColor)
                setPadding(dp(12), dp(12), dp(12), dp(12))
                background = GradientDrawable().apply {
                    setColor(theme.keyBackgroundColor)
                    cornerRadius = context.dp(8f)
                }
                org.fcitx.fcitx5.android.data.theme.ArtStyle.of(theme)?.let {
                    background = org.fcitx.fcitx5.android.data.theme.ArtDrawable(theme, it,
                        org.fcitx.fcitx5.android.data.theme.ArtDrawable.Surface.Key,
                        theme.keyBackgroundColor, resources.displayMetrics.density)
                }
                layoutParams = RecyclerView.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
            })

            override fun onBindViewHolder(holder: EntryHolder, position: Int) {
                val entry = getItem(position)
                holder.text.text = if (entry.title.isBlank()) entry.text else "${entry.title}\n${entry.text}"
                holder.text.setOnClickListener {
                    val token = service.inputActionToken
                    service.postInputJob(token) {
                        reset()
                        service.awaitInputEvents(this)
                        service.onInputTarget(token) {
                            service.commitText(entry.text)
                            windowManager.attachWindow(KeyboardWindow)
                        }
                    }
                }
                holder.text.setOnLongClickListener { anchor ->
                    val index = holder.bindingAdapterPosition
                    if (index == RecyclerView.NO_POSITION) return@setOnLongClickListener false
                    menu?.dismiss()
                    menu = PopupMenu(context, anchor).apply {
                        menu.add(R.string.edit).setOnMenuItemClickListener { edit(entry.id); true }
                        menu.add(R.string.saved_content_move_up).apply {
                            isEnabled = index > 0
                            setOnMenuItemClickListener { update { SavedContentStore.move(entry.id, -1) }; true }
                        }
                        menu.add(R.string.saved_content_move_down).apply {
                            isEnabled = index < currentList.lastIndex
                            setOnMenuItemClickListener { update { SavedContentStore.move(entry.id, 1) }; true }
                        }
                        menu.add(R.string.delete).setOnMenuItemClickListener {
                            update { SavedContentStore.delete(entry.id) }
                            true
                        }
                        show()
                    }
                    true
                }
            }
        }
    }
    private val list by lazy {
        RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@SavedContentWindow.adapter
            itemAnimator = null
            setPadding(dp(12), dp(4), dp(12), dp(8))
        }
    }
    private val empty by lazy { TextView(context).apply {
        setText(R.string.saved_content_empty)
        setTextColor(theme.keyTextColor)
        setPadding(dp(20), dp(16), dp(20), dp(16))
        visibility = View.GONE
    } }

    private fun edit(id: String? = null) {
        context.startActivity(Intent(context, SavedContentEditActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            id?.let { putExtra("id", it) }
        })
    }

    private fun render() {
        loadJob?.cancel()
        loadJob = service.lifecycleScope.launch {
            val entries = withContext(Dispatchers.IO) { SavedContentStore.load() }
            if (!attached) return@launch
            adapter.submitList(entries)
            empty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun update(action: () -> Unit) {
        service.lifecycleScope.launch {
            withContext(Dispatchers.IO) { action() }
            if (attached) render()
        }
    }

    private val root by lazy { android.widget.FrameLayout(context).apply {
        addView(list, android.widget.FrameLayout.LayoutParams(-1, -1))
        addView(empty, android.widget.FrameLayout.LayoutParams(-1, -2))
    } }
    override fun onCreateView(): View = root
    override fun onCreateBarExtension(): View = LinearLayout(context).apply {
        addView(Button(context).apply { setText(R.string.content_search);setOnClickListener { windowManager.attachWindow(KeyboardWindow);service.openContentSearch(true) } })
        addView(Button(context).apply { setText(R.string.saved_content_add);setOnClickListener { edit() } })
    }
    override fun onAttached() { attached = true; render() }
    override fun onDetached() { attached = false; loadJob?.cancel(); menu?.dismiss(); menu = null }
}

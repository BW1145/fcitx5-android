/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.clipboard

import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.SavedContentStore
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import splitties.dimensions.dp

/** Clipboard/saved-content queries are independent of editor routing and AI. */
internal class ContentSearchResults(
    private val service:FcitxInputMethodService,
    private val theme:Theme,
    private val choose:(String)->Unit
) {
    val root=LinearLayout(service).apply { orientation=LinearLayout.VERTICAL }
    private var job:Job?=null
    private var generation=0

    fun search(text:String,saved:Boolean) {
        close()
        val token=generation
        job=service.lifecycleScope.launch {
            delay(120)
            val items=withContext(Dispatchers.IO) {
                if(saved) SavedContentStore.load()
                    .filter { it.title.contains(text,true) || it.text.contains(text,true) }
                    .map { it.title to it.text }
                else ClipboardManager.search(text).map {
                    (if(it.sensitive && AppPrefs.getInstance().clipboard.clipboardMaskSensitive.getValue()) "••••" else it.text) to it.text
                }
            }
            if(token!=generation) return@launch
            root.removeAllViews()
            if(items.isEmpty()) root.addView(TextView(service).apply {
                setText(R.string.search_empty);setTextColor(theme.keyTextColor)
            })
            items.forEach { (label,value) -> root.addView(TextView(service).apply {
                this.text=label.ifBlank { value };textSize=16f;setTextColor(theme.keyTextColor)
                setPadding(service.dp(12),service.dp(12),service.dp(12),service.dp(12))
                setOnClickListener { if(token==generation) choose(value) }
            }) }
        }
    }

    fun close() { generation++;job?.cancel();job=null }
}

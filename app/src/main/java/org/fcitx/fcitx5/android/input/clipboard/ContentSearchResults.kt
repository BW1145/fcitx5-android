/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.clipboard

import android.content.Context
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.*
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.SavedContentStore
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.Theme
import splitties.dimensions.dp

/** Clipboard/saved-content queries are independent of editor routing and AI. */
internal class ContentSearchResults(
    private val context:Context,
    private val scope:CoroutineScope,
    private val theme:Theme,
    private val choose:(String)->Unit
) {
    val root=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL }
    private var job:Job?=null
    private var generation=0
    private var query:Pair<String,Boolean>?=null

    fun search(text:String,saved:Boolean) {
        if(query==(text to saved)) return
        close()
        query=text to saved
        // Old rows belong to the previous query and must not remain tappable
        // while its replacement is waiting for the background search.
        root.removeAllViews()
        val token=generation
        job=scope.launch {
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
            if(items.isEmpty()) root.addView(TextView(context).apply {
                setText(R.string.search_empty);setTextColor(theme.keyTextColor)
            })
            items.forEach { (label,value) -> root.addView(TextView(context).apply {
                this.text=label.ifBlank { value };textSize=16f;setTextColor(theme.keyTextColor)
                setPadding(context.dp(12),context.dp(12),context.dp(12),context.dp(12))
                setOnClickListener { if(token==generation) choose(value) }
            }) }
        }
    }

    fun close() { generation++;job?.cancel();job=null;query=null }
}

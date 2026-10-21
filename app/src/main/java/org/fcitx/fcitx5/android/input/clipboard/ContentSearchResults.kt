/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.clipboard

import android.content.Context
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
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
    private data class Row(val label:String,val value:String?,val session:Int)
    private class Holder(val text:TextView):RecyclerView.ViewHolder(text)
    private val adapter=object:ListAdapter<Row,Holder>(object:DiffUtil.ItemCallback<Row>() {
        override fun areItemsTheSame(old:Row,new:Row)=old.session==new.session && old.value==new.value
        override fun areContentsTheSame(old:Row,new:Row)=old==new
    }) {
        override fun onCreateViewHolder(parent:ViewGroup,viewType:Int)=Holder(TextView(context).apply {
            textSize=16f;setTextColor(theme.keyTextColor)
            setPadding(context.dp(12),context.dp(12),context.dp(12),context.dp(12))
            layoutParams=RecyclerView.LayoutParams(-1,-2)
        })
        override fun onBindViewHolder(holder:Holder,position:Int) {
            val row=getItem(position)
            holder.text.text=row.label.ifBlank { row.value.orEmpty() }
            // Only closing the panel ends an action's editor session.
            holder.text.setOnClickListener {
                if(row.session==session) row.value?.let(choose)
            }
        }
    }
    val root=RecyclerView(context).apply {
        layoutManager=LinearLayoutManager(context)
        adapter=this@ContentSearchResults.adapter
        itemAnimator=null
    }
    private var job:Job?=null
    private var generation=0
    private var session=0
    private var query:Pair<String,Boolean>?=null
    private var displayed:List<Pair<String,String>>?=null

    fun search(text:String,saved:Boolean) {
        if(query==(text to saved)) return
        job?.cancel()
        generation++
        query=text to saved
        val token=generation
        val panelSession=session
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
            // Keep matching views attached across intermediate preedit changes,
            // including a refresh that finishes between touch down and touch up.
            if(items==displayed) return@launch
            displayed=items
            adapter.submitList(if(items.isEmpty()) listOf(Row(context.getString(R.string.search_empty),null,panelSession))
                else items.map { (label,value)->Row(label,value,panelSession) })
        }
    }

    fun close() {
        session++;generation++;job?.cancel();job=null;query=null;displayed=null
        adapter.submitList(emptyList())
    }
}

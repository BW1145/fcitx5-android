/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.clipboard

import android.text.Editable
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.widget.*
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.data.SavedContentStore
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import splitties.dimensions.dp

/** Search uses the current keyboard; engine output is routed into this local editor. */
class ContentSearchPanel(private val service: FcitxInputMethodService, private val theme: Theme) {
    val root=LinearLayout(service).apply { orientation=LinearLayout.VERTICAL;setBackgroundColor(theme.keyBackgroundColor);visibility=View.GONE }
    private val query=EditText(service).apply { setSingleLine();setTextColor(theme.keyTextColor);setHint(R.string.content_search);showSoftInputOnFocus=false;isFocusable=false }
    private val results=LinearLayout(service).apply { orientation=LinearLayout.VERTICAL }
    private val connection=object:BaseInputConnection(query,true) { override fun getEditable():Editable=query.text }
    private var saved=false
    private var job:Job?=null
    val active get()=root.visibility==View.VISIBLE
    init {
        root.addView(LinearLayout(service).apply {
            addView(query,LinearLayout.LayoutParams(0,-2,1f))
            addView(Button(service).apply { setText(android.R.string.cancel);setOnClickListener { close() } })
        })
        root.addView(ScrollView(service).apply { addView(results) },LinearLayout.LayoutParams(-1,service.dp(180)))
        query.doAfterTextChanged { search() }
    }
    fun open(savedContent:Boolean) {
        service.postFcitxJob { reset() }
        saved=savedContent;root.visibility=View.VISIBLE;query.setText("");search()
    }
    fun close() { root.visibility=View.GONE;job?.cancel();connection.finishComposingText();service.postFcitxJob { reset() } }
    fun commit(text:String,cursor:Int=-1) {
        val start=BaseInputConnection.getComposingSpanStart(query.text).takeIf { it>=0 } ?: query.selectionStart.coerceAtLeast(0)
        connection.commitText(text,1)
        if(cursor>=0) query.setSelection((start+cursor).coerceIn(0,query.length()))
    }
    fun clear() { query.setText("") }
    private fun search() {
        job?.cancel()
        val text=query.text.toString()
        job=service.lifecycleScope.launch {
            delay(120)
            val items=if(saved) SavedContentStore.load().filter { it.title.contains(text,true)||it.text.contains(text,true) }.map { it.title to it.text }
                else ClipboardManager.search(text).map { (if(it.sensitive&&AppPrefs.getInstance().clipboard.clipboardMaskSensitive.getValue()) "••••" else it.text) to it.text }
            results.removeAllViews()
            if(items.isEmpty()) results.addView(TextView(service).apply { setText(R.string.search_empty);setTextColor(theme.keyTextColor) })
            items.forEach { (label,value) -> results.addView(TextView(service).apply {
                this.text=label.ifBlank { value };textSize=16f;setTextColor(theme.keyTextColor);setPadding(service.dp(12),service.dp(12),service.dp(12),service.dp(12))
                setOnClickListener { close(); service.commitText(value) }
            }) }
        }
    }
    fun handle(event:FcitxEvent<*>):Boolean {
        if(!active) return false
        when(event) {
            is FcitxEvent.CommitStringEvent -> commit(event.data.text,event.data.cursor)
            is FcitxEvent.ClientPreeditEvent -> connection.setComposingText(event.data.toString(),1)
            is FcitxEvent.DeleteSurroundingEvent -> connection.deleteSurroundingTextInCodePoints(event.data.before,event.data.after)
            is FcitxEvent.KeyEvent -> {
                if(event.data.up) return true
                when(event.data.sym.sym) {
                    FcitxKeyMapping.FcitxKey_BackSpace -> if(query.selectionStart>0) connection.deleteSurroundingTextInCodePoints(1,0)
                    FcitxKeyMapping.FcitxKey_Delete -> connection.deleteSurroundingTextInCodePoints(0,1)
                    FcitxKeyMapping.FcitxKey_Left -> query.setSelection((query.selectionStart-1).coerceAtLeast(0))
                    FcitxKeyMapping.FcitxKey_Right -> query.setSelection((query.selectionStart+1).coerceAtMost(query.length()))
                    FcitxKeyMapping.FcitxKey_Return -> connection.finishComposingText()
                    else -> if(event.data.unicode>0) commit(Character.toString(event.data.unicode))
                }
            }
            else -> return false
        }
        return true
    }
}

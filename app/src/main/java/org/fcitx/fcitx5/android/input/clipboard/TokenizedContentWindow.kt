/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.clipboard

import android.icu.text.BreakIterator
import android.view.View
import android.widget.*
import com.google.android.flexbox.FlexboxLayout
import com.google.android.flexbox.FlexWrap
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.mechdancer.dependency.manager.must
import java.util.Locale

class TokenizedContentWindow(private val text:String):InputWindow.ExtendedInputWindow<TokenizedContentWindow>() {
    private val service by manager.inputMethodService()
    private val theme by manager.theme()
    private val wm:InputWindowManager by manager.must()
    override val title get()=context.getString(R.string.split_content)
    private val selected=mutableSetOf<Int>()
    private val tokens by lazy {
        val iterator=BreakIterator.getWordInstance(Locale.CHINESE);iterator.setText(text)
        buildList { var start=iterator.first();var end=iterator.next();while(end!=BreakIterator.DONE) { if(text.substring(start,end).isNotBlank()) add(start until end);start=end;end=iterator.next() } }
    }
    override fun onCreateView():View=ScrollView(context).apply {
        addView(FlexboxLayout(context).apply {
            flexWrap=FlexWrap.WRAP
            tokens.forEachIndexed { i,range -> addView(CheckBox(context).apply {
                this.text=this@TokenizedContentWindow.text.substring(range);setTextColor(theme.keyTextColor)
                setOnCheckedChangeListener { _,checked -> if(checked) selected.add(i) else selected.remove(i) }
            }) }
        })
    }
    fun selectionText():String=buildString {
        var previous:Int?=null
        selected.sorted().forEach { i ->
            previous?.let { p ->
                val gap=text.substring(tokens[p].last+1,tokens[i].first)
                if(i==p+1) append(gap) else if(gap.contains('\n')) append('\n') else if(gap.any { it.isWhitespace() }) append(' ')
            }
            append(text.substring(tokens[i]));previous=i
        }
    }
    override fun onCreateBarExtension():View=Button(context).apply {
        setText(R.string.insert_selected)
        setOnClickListener { val value=selectionText();if(value.isNotEmpty()) { service.commitText(value);wm.attachWindow(KeyboardWindow) } }
    }
    override fun onAttached() {}
    override fun onDetached() {}
}

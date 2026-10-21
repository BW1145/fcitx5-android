/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main

import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** Shared readable, inset-aware chrome for the personal tools. */
open class ExperienceActivity : AppCompatActivity() {
    fun dp(value:Int)=(value*resources.displayMetrics.density).toInt()
    fun column()=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(8),dp(16),dp(16)) }
    fun button(label:String,block:()->Unit)=Button(this).apply { text=label;setOnClickListener { block() } }
    fun label(value:String)=TextView(this).apply { text=value;textSize=16f;setPadding(0,dp(10),0,dp(6));setTextIsSelectable(true) }
    fun field(hint:String,value:String="",multiline:Boolean=false)=EditText(this).apply {
        this.hint=hint;setText(value);setSingleLine(!multiline);if(multiline) minLines=2
    }
    override fun setContentView(view:View?) {
        val shell=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            addView(LinearLayout(this@ExperienceActivity).apply {
                addView(button("‹ 返回") { finish() })
                addView(label(title.toString()),LinearLayout.LayoutParams(0,-2,1f))
            })
            addView(view,LinearLayout.LayoutParams(-1,0,1f))
        }
        ViewCompat.setOnApplyWindowInsetsListener(shell) { v,insets ->
            val bars=insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left,bars.top,bars.right,bars.bottom);insets
        }
        super.setContentView(shell)
    }
}

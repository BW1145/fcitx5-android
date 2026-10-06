/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main

import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.ToolbarLayout
import org.fcitx.fcitx5.android.input.status.ToolbarActions

class ToolbarActivity : AppCompatActivity() {
    private lateinit var rows: LinearLayout
    private val main = ToolbarLayout.main().toMutableList()
    override fun onCreate(state: Bundle?) {
        super.onCreate(state);title=getString(R.string.toolbar_layout)
        rows=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(24,24,24,24) }
        setContentView(ScrollView(this).apply { addView(rows) });render()
    }
    private fun render() {
        rows.removeAllViews()
        rows.addView(TextView(this).apply { setText(R.string.toolbar_help) })
        (main + ToolbarLayout.available.filterNot { it in main }).forEach { type ->
            val row=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
            row.addView(CheckBox(this).apply {
                text=ToolbarActions.entry(this@ToolbarActivity,type).label;isChecked=type in main
                setOnCheckedChangeListener { _,checked -> if(checked) main.add(type) else main.remove(type);ToolbarLayout.save(main);render() }
            },LinearLayout.LayoutParams(0,-2,1f))
            if(type in main) listOf(-1 to "↑",1 to "↓").forEach { (delta,label) ->
                row.addView(Button(this).apply { text=label;setOnClickListener {
                    val i=main.indexOf(type);if(i+delta in main.indices) { main.removeAt(i);main.add(i+delta,type);ToolbarLayout.save(main);render() }
                } })
            }
            rows.addView(row)
        }
        rows.addView(Button(this).apply { setText(R.string.macro_restore);setOnClickListener { main.clear();main.addAll(ToolbarLayout.defaults);ToolbarLayout.save(main);render() } })
    }
}

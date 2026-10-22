/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.fcitx.fcitx5.android.data.WordLibrary

internal sealed interface WordLibraryRow {
    val id: String
    val keys: List<String>
    data class Learned(val group: WordLibraryPresentation.WordGroup) : WordLibraryRow {
        override val id = group.id
        override val keys = group.records.map(WordLibraryPresentation::wordKey).distinct()
    }
    data class Adjusted(val rule: WordLibrary.Rule) : WordLibraryRow {
        override val id = "rule:" + WordLibraryPresentation.ruleKey(rule)
        override val keys = listOf(WordLibraryPresentation.ruleKey(rule))
    }
}
internal fun wordLibraryReading(reading: String) = when {
    reading == "japanese" -> "日语候选"
    reading.startsWith("predict:") -> "联想：${reading.removePrefix("predict:")}"
    reading.isBlank() -> "未记录读音"
    else -> reading
}


/** Compact recycled rows. Selection and mutations stay owned by the screen. */
internal class WordLibraryAdapter(
    private val context: Context,
    private val selection: WordLibraryPresentation.Selection,
    private val canInteract: () -> Boolean,
    private val onClick: (WordLibraryRow) -> Unit,
    private val onLongClick: (WordLibraryRow) -> Unit
) : ListAdapter<WordLibraryRow, WordLibraryAdapter.WordHolder>(object : DiffUtil.ItemCallback<WordLibraryRow>() {
    override fun areItemsTheSame(old: WordLibraryRow, new: WordLibraryRow) = old.id == new.id
    override fun areContentsTheSame(old: WordLibraryRow, new: WordLibraryRow) = old == new
}) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = WordHolder()
    override fun onBindViewHolder(holder: WordHolder, position: Int) = holder.bind(getItem(position))

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    private fun horizontal() = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun mutedText(size: Float) = TextView(context).apply {
        textSize = size
        val value = TypedValue()
        context.theme.resolveAttribute(android.R.attr.textColorSecondary, value, true)
        if (value.resourceId != 0) setTextColor(resources.getColorStateList(value.resourceId, context.theme)) else setTextColor(value.data)
    }

    inner class WordHolder : RecyclerView.ViewHolder(horizontal()) {
        private val row = itemView as LinearLayout
        private val checkbox = CheckBox(context).apply { isClickable = false; isFocusable = false; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        private val title = TextView(context).apply { textSize = 18f; setTypeface(typeface, Typeface.BOLD); maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        private val reading = mutedText(14f).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        private val detail = mutedText(12f).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        private val arrow = mutedText(22f).apply { text = "›"; gravity = Gravity.CENTER; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        init {
            row.layoutParams = RecyclerView.LayoutParams(-1, -2)
            row.minimumHeight = dp(72)
            row.setPadding(dp(16), dp(10), dp(12), dp(10))
            val value = TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
            row.setBackgroundResource(value.resourceId)
            row.addView(checkbox, LinearLayout.LayoutParams(dp(42), dp(48)))
            row.addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(title)
                addView(reading, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })
                addView(detail, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(arrow, LinearLayout.LayoutParams(dp(24), -1))
        }
        fun bind(item: WordLibraryRow) {
            val selected = selection.count(item.keys)
            checkbox.visibility = if (selection.active) View.VISIBLE else View.GONE
            checkbox.isChecked = selected > 0
            checkbox.jumpDrawablesToCurrentState()
            arrow.visibility = if (selection.active) View.GONE else View.VISIBLE
            row.isActivated = selected > 0
            val accent = TypedValue().also { context.theme.resolveAttribute(androidx.appcompat.R.attr.colorAccent, it, true) }.data
            row.foreground = if (selected > 0) GradientDrawable().apply { setColor((accent and 0x00ffffff) or 0x14000000) } else null
            when (item) {
                is WordLibraryRow.Learned -> {
                    val group = item.group
                    title.text = group.text
                    reading.text = group.readings.joinToString("  ·  ") { wordLibraryReading(it) }
                    detail.text = if (selected in 1 until item.keys.size) "已选 $selected / ${item.keys.size} 条记录" else
                        if (group.recordCount > 1) "${group.readings.size} 个读音 · ${group.recordCount} 条记录 · ${group.records.map { it.dictionary }.distinct().size} 个词典" else ""
                }
                is WordLibraryRow.Adjusted -> {
                    title.text = item.rule.text
                    reading.text = wordLibraryReading(item.rule.reading)
                    detail.text = "${if (item.rule.mode == "hide") "已忘记" else "已降低优先级"} · ${item.rule.engine}"
                }
            }
            detail.visibility = if (detail.text.isBlank()) View.GONE else View.VISIBLE
            row.contentDescription = listOf(title.text, reading.text, detail.text).filter { it.isNotBlank() }.joinToString("，") + if (selection.active) if (selected > 0) "，已选择" else "，未选择" else "，点按管理，长按多选"
            row.setOnClickListener { if (canInteract()) onClick(item) }
            row.setOnLongClickListener { if (canInteract()) { onLongClick(item); true } else false }
        }
    }
}

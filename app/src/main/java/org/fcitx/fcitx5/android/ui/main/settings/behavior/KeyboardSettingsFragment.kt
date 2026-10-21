/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceFragment

class KeyboardSettingsFragment : ManagedPreferenceFragment(AppPrefs.getInstance().keyboard) {
    override fun onPreferenceUiCreated(screen: androidx.preference.PreferenceScreen) {
        val original=(0 until screen.preferenceCount).map { screen.getPreference(it) }
        screen.removeAll()
        val groups=linkedMapOf<String,androidx.preference.PreferenceCategory>()
        fun group(title:String)=groups.getOrPut(title) {
            androidx.preference.PreferenceCategory(requireContext()).apply { this.title=title;screen.addPreference(this) }
        }
        original.forEach { pref ->
            val key=pref.key.orEmpty()
            val section=when {
                "vibration" in key || "haptic" in key || "sound" in key -> "按键声音与触感"
                "candidate" in key -> "候选词排版"
                "height" in key || "padding" in key || key=="letter_size" || "expand_keypress" in key -> "键盘尺寸与位置"
                "space" in key || "long_press" in key || "swipe" in key || "lang_switch" in key -> "长按、滑动与语言切换"
                else -> "键盘与工具栏行为"
            }
            val explanation=when {
                key.startsWith("keyboard_bottom_padding") -> "增加下方空白，让键盘往上移。"
                key=="expanded_candidate_style" -> "按词长排列：长词占用更多宽度，适合查找整句。"
                key=="space_long_press_behavior" -> "默认长按空格切换隐私模式；左右滑动空格移动光标。"
                else -> null
            }
            if(explanation!=null) {
                @Suppress("UNCHECKED_CAST")
                val provider=pref.summaryProvider as? androidx.preference.Preference.SummaryProvider<androidx.preference.Preference>
                val originalSummary=pref.summary
                pref.summaryProvider=androidx.preference.Preference.SummaryProvider<androidx.preference.Preference> {
                    listOfNotNull(provider?.provideSummary(it) ?: originalSummary,explanation).joinToString("\n")
                }
            }
            group(section).addPreference(pref)
        }
        group("按键声音与触感").addPreference(androidx.preference.Preference(requireContext()).apply {
            title="触感预设与试打";summary="轻柔、清脆、明显，或自定义短按与长按参数。"
            setOnPreferenceClickListener { startActivity(android.content.Intent(requireContext(),org.fcitx.fcitx5.android.ui.main.HapticsActivity::class.java));true }
        })
        listOf(org.fcitx.fcitx5.android.R.string.key_macros to org.fcitx.fcitx5.android.ui.main.KeyMacroActivity::class.java,
            org.fcitx.fcitx5.android.R.string.toolbar_layout to org.fcitx.fcitx5.android.ui.main.ToolbarActivity::class.java).forEach { (label,activity) ->
            screen.addPreference(androidx.preference.Preference(requireContext()).apply { setTitle(label);setOnPreferenceClickListener { startActivity(android.content.Intent(requireContext(),activity));true } })
        }
    }
}

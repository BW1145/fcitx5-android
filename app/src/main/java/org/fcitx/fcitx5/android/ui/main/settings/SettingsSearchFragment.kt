/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.settings

import android.os.Bundle
import androidx.preference.*
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fcitx.fcitx5.android.utils.navigateWithAnim
import org.fcitx.fcitx5.android.ui.main.settings.behavior.AdvancedSettingsFragment

object SettingFocus { var key: String? = null }

class SettingsSearchFragment : PaddingPreferenceFragment() {
    private data class Entry(val title: String,val summary: String,val key: String,val route: SettingsRoute?,val section: String,val activity:Class<*>?=null)
    private val entries = mutableListOf<Entry>()
    private lateinit var results: PreferenceCategory
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val ctx = requireContext()
        val p = AppPrefs.getInstance()
        listOf(Triple(p.keyboard,SettingsRoute.VirtualKeyboard,"按键与候选"),
            Triple(p.candidates,SettingsRoute.CandidatesWindow,"浮动候选窗"),
            Triple(p.clipboard,SettingsRoute.Clipboard,"剪贴板"),
            Triple(p.symbols,SettingsRoute.Symbol,"表情与符号"),
            Triple(p.advanced,SettingsRoute.Advanced,"高级与备份"),
            Triple(ThemeManager.prefs,SettingsRoute.Theme,"主题外观")).forEach { (provider,route,section) ->
            val screen = preferenceManager.createPreferenceScreen(ctx)
            provider.createUi(screen)
            fun collect(group: PreferenceGroup) {
                for (i in 0 until group.preferenceCount) {
                    val pref = group.getPreference(i)
                    if (pref is PreferenceGroup) collect(pref)
                    else pref.key?.let { entries += Entry(pref.title?.toString().orEmpty(),
                        pref.summary?.toString().orEmpty(),it,route,section) }
                }
            }
            collect(screen)
        }
        entries+=Entry("按键触感","轻柔、清脆、明显预设，自定义震动强度、时长与试打","",null,"外观与触感",org.fcitx.fcitx5.android.ui.main.HapticsActivity::class.java)
        entries+=Entry("AI 助手","API 接口、模型、固定提示词、聊天记录、常用指令","",null,"内容与助手",org.fcitx.fcitx5.android.ui.main.AiSettingsActivity::class.java)
        entries+=Entry("我的词库与历史备份","搜索词条、添加人名、忘记、降低优先级、词频快照恢复与合并","",null,"个人词库",org.fcitx.fcitx5.android.ui.main.WordLibraryActivity::class.java)
        entries+=Entry("语音识别测试","检查系统语音服务、中文识别与麦克风权限","",null,"内容与助手",org.fcitx.fcitx5.android.ui.main.SpeechTestActivity::class.java)
        entries+=Entry("按键与宏","自定义长按滑动动作、隐私模式","",null,"按键与操作",org.fcitx.fcitx5.android.ui.main.KeyMacroActivity::class.java)
        entries+=Entry("工具栏布局","调整工具栏按钮顺序、主栏与更多菜单","",null,"按键与操作",org.fcitx.fcitx5.android.ui.main.ToolbarActivity::class.java)
        preferenceScreen = preferenceManager.createPreferenceScreen(ctx).apply {
            addPreference(EditTextPreference(ctx).apply {
                key="settings_search_query"
                title="搜索设置"; summary="输入名称或用途，例如：震动、键盘往上挪、候选太挤"
                isPersistent=false; dialogTitle="搜索设置"
                setOnPreferenceChangeListener { _, value -> show(value.toString());true }
            })
            results=PreferenceCategory(ctx).apply { title="设置位置" }
            addPreference(results)
        }
        AdvancedSettingsFragment.userDataEntries.forEach { (key, title) ->
            entries += Entry(ctx.getString(title), "用户数据、配置与备份迁移", key, SettingsRoute.Advanced, "高级与备份")
        }
        show("")
    }
    private fun aliases(key: String) = when {
        "vibration" in key || "haptic" in key -> "震动 振动 触感 马达 力度 强度"
        "bottom_padding" in key -> "键盘往上挪 抬高 底部 空白 位置"
        "height" in key -> "高度 键盘太矮 太高 放大"
        "candidate" in key -> "候选太挤 候选词 排版 长词 横排 词长"
        "letter_size" == key -> "字母 字号 大小 放大"
        "space" in key -> "空格 光标 移动 长按"
        "toolbar" in key -> "工具栏 按钮 菜单 收起 展开"
        else -> ""
    }
    private fun show(query: String) {
        results.removeAll()
        val q=query.trim()
        entries.map { it to when {
            q.isEmpty() -> 1
            it.title.contains(q,true) -> 3
            (it.summary+aliases(it.key)+it.section).contains(q,true) -> 2
            else -> 0
        } }.filter { it.second>0 }.sortedByDescending { it.second }.forEach { (e,_) ->
            results.addPreference(Preference(requireContext()).apply {
                title=e.title; summary="${e.section} · ${e.summary}";isIconSpaceReserved=false
                setOnPreferenceClickListener {
                    val route=e.route
                    if(route!=null) { SettingFocus.key=e.key;navigateWithAnim(route) }
                    else startActivity(android.content.Intent(requireContext(),e.activity))
                    true
                }
            })
        }
        if(results.preferenceCount==0) results.addPreference(Preference(requireContext()).apply {
            title="没有找到，试试更短的关键词";isSelectable=false
        })
    }
}

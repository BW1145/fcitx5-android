/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.view.MenuProvider
import androidx.lifecycle.Lifecycle
import androidx.preference.PreferenceCategory
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.utils.Const
import org.fcitx.fcitx5.android.utils.addCategory
import org.fcitx.fcitx5.android.utils.addPreference
import org.fcitx.fcitx5.android.utils.item
import org.fcitx.fcitx5.android.utils.navigateWithAnim

class MainFragment : PaddingPreferenceFragment() {

    override fun onViewCreated(
        view: View, savedInstanceState: Bundle?
    ) {
        super.onViewCreated(view, savedInstanceState)
        // AboutMenuProvider is tied to viewLifecycleOwner, so the about menu items
        // are automatically shown when this Fragment is visible and removed when it's not
        requireActivity().addMenuProvider(
            AboutMenuProvider(), viewLifecycleOwner, Lifecycle.State.STARTED
        )
    }

    private inner class AboutMenuProvider : MenuProvider {
        override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
            menu.item(R.string.faq) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Const.faqUrl)))
            }
            menu.item(R.string.developer) {
                navigateWithAnim(SettingsRoute.Developer)
            }
            menu.item(R.string.about) {
                navigateWithAnim(SettingsRoute.About)
            }
        }

        override fun onMenuItemSelected(menuItem: MenuItem): Boolean = false
    }

    private fun PreferenceCategory.addDestinationPreference(
        @StringRes title: Int,
        @DrawableRes icon: Int,
        route: SettingsRoute
    ) {
        addPreference(title, icon = icon) {
            navigateWithAnim(route)
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val ctx = requireContext()
        preferenceScreen = preferenceManager.createPreferenceScreen(ctx).apply {
            fun destination(group: androidx.preference.PreferenceGroup, name: String, detail: String,
                            route: SettingsRoute? = null, activity: Class<*>? = null) {
                group.addPreference(androidx.preference.Preference(ctx).apply {
                    title=name;summary=detail;isIconSpaceReserved=false
                    setOnPreferenceClickListener {
                        if(route!=null) navigateWithAnim(route)
                        else startActivity(Intent(ctx,activity))
                        true
                    }
                })
            }
            destination(this,"搜索设置","按名称或用途查找，直接跳到对应位置",SettingsRoute.Search)
            fun category(name:String, block:(androidx.preference.PreferenceCategory)->Unit) {
                val group=androidx.preference.PreferenceCategory(ctx).apply { title=name }
                addPreference(group);block(group)
            }
            category("输入与个人词库") { g ->
                destination(g,"输入法与语言","雾凇拼音、日语、英文和方案选项",SettingsRoute.InputMethodList)
                destination(g,"我的词库与历史备份","查词、添加词、管理候选偏好、合并或恢复学习数据",activity=WordLibraryActivity::class.java)
                destination(g,"按键与候选","键盘高度、空格手势、候选词排版及工具栏",SettingsRoute.VirtualKeyboard)
                destination(g,"浮动候选窗","外接键盘时显示的独立候选窗口",SettingsRoute.CandidatesWindow)
            }
            category("外观与操作") { g ->
                destination(g,"键盘主题","四套艺术风格、日夜主题和自定义外观",SettingsRoute.Theme)
                destination(g,"按键触感","震动预设、自定义时长与强度、即时试打",activity=HapticsActivity::class.java)
                destination(g,"按键与宏","设置长按、滑动、快捷动作与隐私模式",activity=KeyMacroActivity::class.java)
                destination(g,"工具栏布局","安排常用按钮和更多菜单",activity=ToolbarActivity::class.java)
                destination(g,"表情与符号","肤色、符号和表情显示",SettingsRoute.Symbol)
            }
            category("内容与助手") { g ->
                destination(g,"剪贴板","自动记录、保留数量、敏感内容显示",SettingsRoute.Clipboard)
                destination(g,"AI 助手","接口、模型、固定提示词、会话记录与常用指令",activity=AiSettingsActivity::class.java)
                destination(g,"语音识别测试","检查系统服务，尝试识别一句中文",activity=SpeechTestActivity::class.java)
            }
            category("备份与扩展") { g ->
                destination(g,"数据备份与高级选项","导入导出全部用户数据、兼容设置",SettingsRoute.Advanced)
                destination(g,"全局输入选项","输入行为与快捷键",SettingsRoute.GlobalConfig)
                destination(g,"附加组件","输入引擎与扩展组件的原始选项",SettingsRoute.AddonList)
                destination(g,"外置插件","管理独立安装的插件",SettingsRoute.Plugin)
            }
        }
    }
}

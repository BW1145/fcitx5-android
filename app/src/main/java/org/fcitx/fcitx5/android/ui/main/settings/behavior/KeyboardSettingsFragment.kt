/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceFragment

class KeyboardSettingsFragment : ManagedPreferenceFragment(AppPrefs.getInstance().keyboard) {
    override fun onPreferenceUiCreated(screen: androidx.preference.PreferenceScreen) {
        listOf(org.fcitx.fcitx5.android.R.string.key_macros to org.fcitx.fcitx5.android.ui.main.KeyMacroActivity::class.java,
            org.fcitx.fcitx5.android.R.string.toolbar_layout to org.fcitx.fcitx5.android.ui.main.ToolbarActivity::class.java).forEach { (label,activity) ->
            screen.addPreference(androidx.preference.Preference(requireContext()).apply { setTitle(label);setOnPreferenceClickListener { startActivity(android.content.Intent(requireContext(),activity));true } })
        }
    }
}

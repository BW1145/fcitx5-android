/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.core

import android.content.Context

/** Seed the personal input configuration before Fcitx reads its profile. */
internal object PersonalDefaults {
    suspend fun enableBundledJapanese(context: Context, api: FcitxAPI) {
        val root = context.getExternalFilesDir(null) ?: context.filesDir
        val marker = root.resolve("config/personal-anthy-enabled")
        if (api.availableIme().none { it.uniqueName == "anthy" }) return
        if (!marker.exists()) {
            val enabled = api.enabledIme().map { it.uniqueName }
            if ("anthy" !in enabled) api.setEnabledIme((enabled + "anthy").toTypedArray())
            marker.parentFile?.mkdirs()
            marker.writeText("1\n", Charsets.UTF_8)
        }
        val candidatesMarker = root.resolve("config/personal-anthy-candidates-v1")
        if (!candidatesMarker.exists()) {
            val config = api.getAddonConfig("anthy")["cfg"]
            config["General"]["PredictOnInput"].value = "True"
            config["General"]["NTriggersToShowCandWin"].value = "1"
            api.setAddonConfig("anthy", config)
            candidatesMarker.writeText("1\n", Charsets.UTF_8)
        }
    }

    fun install(context: Context) {
        val root = context.getExternalFilesDir(null) ?: context.filesDir
        val experienceMarker=root.resolve("config/personal-experience-v1")
        if(!experienceMarker.exists()) {
            val prefs=org.fcitx.fcitx5.android.data.prefs.AppPrefs.getInstance().keyboard
            prefs.expandedCandidateStyle.setValue(org.fcitx.fcitx5.android.input.candidates.expanded.ExpandedCandidateStyle.Flexbox)
            if(prefs.spaceKeyLongPressBehavior.getValue()==org.fcitx.fcitx5.android.input.keyboard.SpaceLongPressBehavior.None &&
                org.fcitx.fcitx5.android.data.KeyMacros.load().none { it.key=="space" && it.trigger=="hold" })
                prefs.spaceKeyLongPressBehavior.setValue(org.fcitx.fcitx5.android.input.keyboard.SpaceLongPressBehavior.Privacy)
            val toolbar=org.fcitx.fcitx5.android.data.ToolbarLayout
            val ai=org.fcitx.fcitx5.android.input.status.StatusAreaEntry.Android.Type.AiAssistant
            if(toolbar.main()==toolbar.defaults-ai) toolbar.save(toolbar.defaults)
            experienceMarker.parentFile?.mkdirs();experienceMarker.writeText("1\n",Charsets.UTF_8)
        }
        for (path in listOf(
            "config/profile", "config/config", "data/rime/default.custom.yaml",
            "data/rime/rime_ice.custom.yaml", "data/rime/personal_ice.yaml",
            "data/rime/zh-hans-t-essay-bgw-compact.gram", "data/rime/predict.db"
        )) {
            val file = root.resolve(path)
            if (file.exists()) continue
            file.parentFile?.mkdirs()
            context.assets.open("personal-defaults/$path").use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
        }
    }
}

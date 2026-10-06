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
        for (path in listOf(
            "config/profile", "config/config", "data/rime/default.custom.yaml",
            "data/rime/rime_ice.custom.yaml"
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

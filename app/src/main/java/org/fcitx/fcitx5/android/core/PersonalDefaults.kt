/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.core

import android.content.Context

/** Seed the personal input configuration before Fcitx reads its profile. */
internal object PersonalDefaults {
    fun install(context: Context) {
        val root = context.getExternalFilesDir(null) ?: context.filesDir
        for (path in listOf("config/profile", "config/config", "data/rime/default.custom.yaml")) {
            val file = root.resolve(path)
            if (file.exists()) continue
            file.parentFile?.mkdirs()
            context.assets.open("personal-defaults/$path").use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
        }
    }
}

/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data

import android.util.AtomicFile
import kotlinx.serialization.json.Json
import org.fcitx.fcitx5.android.utils.appContext
import java.io.File

object ExperienceData {
    val root: File get() = (appContext.getExternalFilesDir(null) ?: appContext.filesDir).resolve("data")
    val json = Json { prettyPrint=true;ignoreUnknownKeys=true }
    fun write(file: File,text: String) {
        file.parentFile?.mkdirs()
        val atomic=AtomicFile(file);val stream=atomic.startWrite()
        try { stream.write(text.toByteArray(Charsets.UTF_8));atomic.finishWrite(stream) }
        catch(e:Exception) { atomic.failWrite(stream);throw e }
    }
    fun read(file: File): String? = if(file.exists() || File(file.path+".bak").exists())
        AtomicFile(file).openRead().bufferedReader(Charsets.UTF_8).use { it.readText() } else null
}

object PrivacyMode {
    @Volatile var enabled=false
        private set
    fun toggle(): Boolean { enabled=!enabled;return enabled }
}

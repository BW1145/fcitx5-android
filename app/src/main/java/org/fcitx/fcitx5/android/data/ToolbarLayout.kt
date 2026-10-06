/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.fcitx.fcitx5.android.input.status.StatusAreaEntry.Android.Type
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.data.prefs.AppPrefs

object ToolbarLayout {
    val defaults = listOf(Type.Undo, Type.Redo, Type.SavedContent, Type.Clipboard)
    val available = listOf(Type.Undo,Type.Redo,Type.SavedContent,Type.Clipboard,Type.TextEditing,Type.ThemeList,Type.InputMethod,Type.ReloadConfig,Type.Keyboard)
    private val file get() = (appContext.getExternalFilesDir(null) ?: appContext.filesDir).resolve("data/toolbar.json")
    fun main(): List<Type> = if(file.exists() || file.resolveSibling(file.name+".bak").exists()) android.util.AtomicFile(file).openRead().bufferedReader(Charsets.UTF_8).use { Json.decodeFromString<List<String>>(it.readText()).mapNotNull { name -> available.find { it.name==name } }.distinct() } else defaults
    fun menu() = available.filterNot { it in main() } + listOf(Type.KeyMacros,Type.ToolbarLayout)
    fun save(items: List<Type>) {
        file.parentFile?.mkdirs()
        val atomic = android.util.AtomicFile(file); val stream=atomic.startWrite()
        try { stream.write(Json.encodeToString(items.map { it.name }).toByteArray(Charsets.UTF_8)); atomic.finishWrite(stream) }
        catch(e:Exception) { atomic.failWrite(stream);throw e }
        AppPrefs.getInstance().internal.customUiRevision.let { it.setValue(it.getValue()+1) }
    }
}

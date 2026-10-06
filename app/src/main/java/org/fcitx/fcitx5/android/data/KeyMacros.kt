/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data

import android.util.AtomicFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.fcitx.fcitx5.android.utils.appContext

object KeyMacros {
    @Serializable data class Step(val action: String, val text: String = "", val ctrl: Boolean = false, val alt: Boolean = false, val shift: Boolean = false)
    @Serializable data class Binding(val key: String, val trigger: String, val steps: List<Step>)
    val keys = (('a'..'z') + ('0'..'9')).map { it.toString() } + listOf("backspace", "space", "return", "caps", "language", "symbols", ",", ".")
    val triggers = listOf("tap", "hold", "up", "down", "left", "right")
    val actions = listOf("text", "key", "selectAll", "copy", "paste", "cut", "undo", "redo", "clear")
    private val json = Json { prettyPrint = true }
    private val file get() = (appContext.getExternalFilesDir(null) ?: appContext.filesDir).resolve("data/key-macros.json")
    private val defaultBindings = listOf(Binding("backspace", "up", listOf(Step("clear"))))
    fun load(): List<Binding> = if (file.exists() || file.resolveSibling(file.name+".bak").exists()) android.util.AtomicFile(file).openRead().bufferedReader(Charsets.UTF_8).use { json.decodeFromString(it.readText()) } else defaultBindings
    fun save(bindings: List<Binding>) {
        file.parentFile?.mkdirs()
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try { stream.write(json.encodeToString(bindings).toByteArray(Charsets.UTF_8)); atomic.finishWrite(stream); org.fcitx.fcitx5.android.data.prefs.AppPrefs.getInstance().internal.customUiRevision.let { it.setValue(it.getValue()+1) } }
        catch (e: Exception) { atomic.failWrite(stream); throw e }
    }
    fun set(binding: Binding) = save(load().filterNot { it.key == binding.key && it.trigger == binding.trigger } + binding)
    fun remove(key: String, trigger: String) = save(load().filterNot { it.key == key && it.trigger == trigger })
    fun restore() = save(defaultBindings)
}

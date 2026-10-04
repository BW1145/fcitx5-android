/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data

import android.util.AtomicFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.fcitx.fcitx5.android.utils.appContext
import java.util.UUID

object SavedContentStore {
    @Serializable
    data class Entry(val id: String, val title: String, val text: String)

    private val file get() = (appContext.getExternalFilesDir(null) ?: appContext.filesDir)
        .resolve("data/saved-content.json")
    private val json = Json { prettyPrint = true }

    fun load(): List<Entry> {
        val atomic = AtomicFile(file)
        if (!file.exists() && !file.resolveSibling(file.name + ".bak").exists()) return emptyList()
        return atomic.openRead().bufferedReader(Charsets.UTF_8).use { json.decodeFromString(it.readText()) }
    }

    private fun save(entries: List<Entry>) {
        file.parentFile?.mkdirs()
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            stream.write(json.encodeToString(entries).toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (e: Exception) {
            atomic.failWrite(stream)
            throw e
        }
    }

    fun put(id: String?, title: String, text: String): Entry {
        val entries = load().toMutableList()
        val entry = Entry(id ?: UUID.randomUUID().toString(), title, text)
        val index = entries.indexOfFirst { it.id == entry.id }
        if (index < 0) entries.add(entry) else entries[index] = entry
        save(entries)
        return entry
    }

    fun delete(id: String) = save(load().filterNot { it.id == id })

    fun move(id: String, delta: Int) {
        val entries = load().toMutableList()
        val index = entries.indexOfFirst { it.id == id }
        if (index < 0 || index + delta !in entries.indices) return
        val entry = entries.removeAt(index)
        entries.add(index + delta, entry)
        save(entries)
    }
}

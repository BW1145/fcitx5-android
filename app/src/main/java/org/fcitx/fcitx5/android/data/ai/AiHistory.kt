/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.ai

import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import org.fcitx.fcitx5.android.data.ExperienceData
import java.io.File
import android.os.FileObserver
import kotlin.coroutines.coroutineContext

/** JSON conversations remain the source of truth; this index can always be rebuilt. */
internal class AiHistory(private val directory: File, private val indexFile: File) : AutoCloseable {
    @Serializable
    data class Entry(val summary: AiStore.Summary, val modified: Long, val size: Long)

    private var index: MutableMap<String, Entry>? = null
    private var dirty=false
    private var checked=false
    private var ordered:List<AiStore.Summary>?=null
    private val changed=mutableSetOf<String>()
    private val deleted = mutableSetOf<String>()
    @Suppress("DEPRECATION")
    private val observer=object:FileObserver(directory.absolutePath,CLOSE_WRITE or MOVED_TO or MOVED_FROM or DELETE or CREATE) {
        override fun onEvent(event:Int,path:String?) {
            if(path?.endsWith(".json")==true) synchronized(this@AiHistory) { changed+=path.removeSuffix(".json") }
        }
    }

    init { directory.mkdirs();observer.startWatching() }
    override fun close() { observer.stopWatching() }

    @Synchronized fun invalidate() {
        index = null
        dirty=false
        checked=false;ordered=null;changed.clear()
        deleted.clear()
        indexFile.delete()
    }

    @Synchronized fun get(id: String): AiStore.Conversation? =
        ExperienceData.read(directory.resolve("$id.json"))?.let {
            ExperienceData.json.decodeFromString<AiStore.Conversation>(it)
        }

    private fun entry(file: File, value: AiStore.Conversation) = Entry(
        AiStore.Summary(value.id, value.title, value.updated, value.messages.size),
        file.lastModified(), file.length()
    )

    @Synchronized fun summaries(): List<AiStore.Summary> {
        val entries = index ?: run {
            val saved = try {
                ExperienceData.read(indexFile)?.let {
                    ExperienceData.json.decodeFromString<List<Entry>>(it)
                }.orEmpty()
            } catch(_:SerializationException) { dirty=true;emptyList() }
            saved.associateByTo(mutableMapOf()) { it.summary.id }.also { index = it }
        }
        val initialScan=!checked
        val files=if(initialScan) {
            val initial=directory.listFiles()?.filter { it.extension=="json" }.orEmpty()
            val present=initial.mapTo(mutableSetOf()) { it.nameWithoutExtension }
            if(entries.keys.retainAll(present)) dirty=true
            checked=true
            initial
        } else changed.map { directory.resolve("$it.json") }
        changed.clear()
        files.forEach { file ->
            val previous = entries[file.nameWithoutExtension]
            if(!file.exists()) {
                if(entries.remove(file.nameWithoutExtension)!=null) dirty=true
            } else if (!initialScan || previous == null || previous.modified != file.lastModified() || previous.size != file.length()) {
                get(file.nameWithoutExtension)?.let { entries[file.nameWithoutExtension] = entry(file, it);dirty=true }
            }
        }
        if(dirty || !indexFile.exists()) {
            ExperienceData.write(indexFile, ExperienceData.json.encodeToString(entries.values.toList()))
            ordered=null
            dirty=false
        }
        return ordered ?: entries.values.map { it.summary }.sortedByDescending { it.updated }.also { ordered=it }
    }

    suspend fun page(query: String, offset: Int, limit: Int): List<AiStore.Summary> {
        val result = mutableListOf<AiStore.Summary>()
        var skipped = 0
        for (summary in summaries()) {
            coroutineContext.ensureActive()
            val matches = query.isBlank() || summary.title.contains(query, true) ||
                get(summary.id)?.messages?.any { it.text.contains(query, true) } == true
            if (matches && skipped++ >= offset) result += summary
            if (result.size == limit) break
        }
        return result
    }

    @Synchronized fun save(value: AiStore.Conversation, requireExisting: Boolean = false) {
        val file = directory.resolve("${value.id}.json")
        if (value.id in deleted || (requireExisting && !file.exists())) return
        ExperienceData.write(file, ExperienceData.json.encodeToString(value))
        index?.set(value.id, entry(file, value))
        dirty=true
        ordered=null
    }

    @Synchronized fun delete(id: String) {
        directory.resolve("$id.json").delete()
        directory.resolve("$id.json.bak").delete()
        index?.remove(id)
        dirty=true
        ordered=null
        deleted += id
    }

    fun all(): List<AiStore.Conversation> = summaries().mapNotNull { get(it.id) }
}

/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.utils.extract
import java.io.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.zip.*

object WordLibrary {
    private val changes=MutableSharedFlow<Unit>(extraBufferCapacity=1,onBufferOverflow=BufferOverflow.DROP_OLDEST)
    val ruleChanges=changes.asSharedFlow()
    fun notifyRulesChanged() { changes.tryEmit(Unit) }
    private val access=Mutex()
    data class Word(val dictionary:String,val text:String,val reading:String,val weight:String)
    data class Rule(val engine:String,val reading:String,val text:String,val mode:String) {
        val line get()=listOf(engine,reading,text).joinToString("\t") { hex(it) }+"\t"+mode
    }
    @Serializable data class Snapshot(val id:String,val name:String,val note:String,val time:Long,val count:Int)
    private val exchange get()=ExperienceData.root.resolve("rime/personal-exchange")
    private val history get()=ExperienceData.root.resolve("input-history")
    private val rulesFile get()=ExperienceData.root.resolve("candidate-preferences.tsv")
    private fun hex(text:String)=text.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun unhex(text:String):String {
        require(text.length % 2 == 0 && text.all { it in "0123456789abcdefABCDEF" }) { "候选规则编码无效" }
        val bytes=text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    }
    internal fun parseRules(file:File):List<Rule> = file.takeIf { it.exists() }?.readLines(Charsets.UTF_8)?.filter { it.isNotEmpty() }?.map {
        val parts=it.removeSuffix("\r").split('\t')
        require(parts.size==4 && parts[3] in listOf("hide","lower")) { "候选规则格式无效" }
        Rule(unhex(parts[0]),unhex(parts[1]),unhex(parts[2]),parts[3])
    }.orEmpty()
    fun rules():List<Rule> = parseRules(rulesFile)
    private fun writeRules(items:List<Rule>) {
        ExperienceData.write(rulesFile,items.joinToString("\n") { it.line }.let { if(it.isEmpty()) it else "$it\n" })
    }
    suspend fun updateRules(connection:FcitxConnection,update:(List<Rule>)->List<Rule>) {
        connection.runOnReady { updateCandidatePreferences { writeRules(update(rules())) } }
    }
    suspend fun removeRule(connection:FcitxConnection,rule:Rule) = updateRules(connection) { current ->
        current.filterNot { it.engine==rule.engine && it.reading==rule.reading && it.text==rule.text }
    }
    suspend fun setRule(connection:FcitxConnection,word:Word,mode:String) {
        require(mode in listOf("hide","lower"))
        val reading=word.reading.replace(" ","").replace("'","").lowercase()
        val engines=connection.runOnReady { preferenceSchemas(word.dictionary) }
        require(engines.isNotEmpty()) { "没有找到使用此词典的拼音方案" }
        val added=engines.map { Rule("rime:$it",reading,word.text,mode) }
        updateRules(connection) { current ->
            current.filterNot { old -> added.any { it.engine==old.engine && it.reading==old.reading && it.text==old.text } }+added
        }
    }
    private suspend fun operation(connection:FcitxConnection,name:String) {
        exchange.mkdirs();exchange.resolve("status.txt").delete()
        connection.runOnReady { setAddonSubConfig("rime","personal/$name") }
        val status=exchange.resolve("status.txt").takeIf { it.exists() }?.readText(Charsets.UTF_8)
        check(status=="ok") { status ?: "词库服务未返回结果" }
    }
    private fun wordsIn(dir:File):List<Word> = dir.listFiles()?.filter { it.extension=="txt" }?.flatMap { file ->
        file.readLines(Charsets.UTF_8).filterNot { it.startsWith("#") || it.isBlank() }.mapNotNull {
            val fields=it.split('\t')
            if(fields.size<2) null else Word(file.nameWithoutExtension,fields[0],fields[1],fields.getOrElse(2) { "0" })
        }
    }.orEmpty()
    suspend fun words(connection:FcitxConnection):List<Word> = withContext(Dispatchers.IO) {
        access.withLock { operation(connection,"export");wordsIn(exchange.resolve("exports")) }
    }
    suspend fun add(connection:FcitxConnection,dictionary:String,text:String,reading:String,weight:Int=1) = withContext(Dispatchers.IO) {
        access.withLock {
        require(text.isNotBlank() && '\t' !in text && '\n' !in text)
        require(reading.matches(Regex("[a-z]+(?:[ '\\-][a-z]+)*"))) { "拼音请用空格分隔，例如 shua wo men wan" }
        exchange.mkdirs();ExperienceData.write(exchange.resolve("dictionary.txt"),dictionary)
        ExperienceData.write(exchange.resolve("import.txt"),"$text\t${reading.replace("'"," ").replace("-"," ")}\t$weight\n")
        operation(connection,"import")
        val code=reading.replace(" ","").replace("'","").replace("-","")
        val engines=connection.runOnReady { preferenceSchemas(dictionary) }.map { "rime:$it" } + "rime"
        updateRules(connection) { rules -> rules.filterNot { it.engine in engines && it.reading==code && it.text==text } }
        }
    }
    suspend fun forget(connection:FcitxConnection,word:Word) {
        add(connection,word.dictionary,word.text,word.reading,-1)
        setRule(connection,word,"hide")
    }
    fun snapshots():List<Snapshot> = history.listFiles()?.filter { it.isDirectory }?.mapNotNull {
        ExperienceData.read(it.resolve("metadata.json"))?.let { text -> ExperienceData.json.decodeFromString<Snapshot>(text) }
    }?.sortedByDescending { it.time }.orEmpty()
    suspend fun snapshot(connection:FcitxConnection,name:String,note:String=""):Snapshot = withContext(Dispatchers.IO) {
        access.withLock { snapshotUnlocked(connection,name,note) }
    }
    private suspend fun snapshotUnlocked(connection:FcitxConnection,name:String,note:String=""):Snapshot {
        val time=System.currentTimeMillis();val id="$time-${(1000..9999).random()}"
        operation(connection,"backup/$id")
        val snapshot=Snapshot(id,name.ifBlank { "输入习惯备份" },note,time,wordsIn(history.resolve("$id/words")).size)
        ExperienceData.write(history.resolve("$id/metadata.json"),ExperienceData.json.encodeToString(snapshot));return snapshot
    }
    fun diff(snapshot:Snapshot):Pair<List<String>,List<String>> {
        fun set(s:Snapshot)=wordsIn(history.resolve("${s.id}/words")).map { "${it.text}  ${it.reading}" }.toSet()
        val before=snapshots().firstOrNull { it.time<snapshot.time }?.let(::set).orEmpty()
        val after=set(snapshot);return (after-before).sorted() to (before-after).sorted()
    }
    suspend fun restore(connection:FcitxConnection,snapshot:Snapshot,merge:Boolean) = withContext(Dispatchers.IO) {
        access.withLock {
        parseRules(history.resolve("${snapshot.id}/candidate-preferences.tsv"))
        val rollback=snapshotUnlocked(connection,"${if(merge) "合并" else "恢复"}前自动保存")
        try {
            operation(connection,"${if(merge) "merge" else "restore"}/${snapshot.id}")
        } catch(e:Exception) {
            // A native operation may have changed data before cancellation reaches
            // its caller. Finish recovery even if the owning screen is closing.
            try {
                withContext(NonCancellable) { operation(connection,"restore/${rollback.id}") }
            } catch(recovery:Exception) {
                // A recovered coroutine exception may be unwrapped on rethrow,
                // losing newly added suppressed errors. Keep both in a fresh
                // exception, while preserving cancellation as cancellation.
                val failure=if(e is CancellationException) CancellationException(e.message).apply { initCause(e) }
                    else IllegalStateException(e.message,e)
                throw failure.apply { addSuppressed(recovery) }
            }
            throw e
        }
        finally { notifyRulesChanged() }
        }
    }
    fun delete(snapshot:Snapshot) { history.resolve(snapshot.id).deleteRecursively() }
    fun export(snapshot:Snapshot,output:OutputStream) {
        val folder=history.resolve(snapshot.id)
        ZipOutputStream(output).use { zip -> folder.walkTopDown().filter { it.isFile }.forEach {
            zip.putNextEntry(ZipEntry(it.relativeTo(folder).invariantSeparatorsPath));it.inputStream().use { input -> input.copyTo(zip) };zip.closeEntry()
        } }
    }
    fun import(input:InputStream):Snapshot {
        val id="${System.currentTimeMillis()}-${(1000..9999).random()}";val folder=history.resolve(id)
        try {
            folder.mkdirs();ZipInputStream(input).use { it.extract(folder) }
            require(folder.resolve("db").isDirectory && folder.resolve("words").isDirectory) { "文件不是输入习惯备份" }
            parseRules(folder.resolve("candidate-preferences.tsv"))
            val meta=ExperienceData.json.decodeFromString<Snapshot>(folder.resolve("metadata.json").readText(Charsets.UTF_8)).copy(id=id)
            ExperienceData.write(folder.resolve("metadata.json"),ExperienceData.json.encodeToString(meta));return meta
        } catch(e:Exception) { folder.deleteRecursively();throw e }
    }
}

/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.fcitx.fcitx5.android.data.ExperienceData
import org.fcitx.fcitx5.android.data.PrivacyMode
import org.fcitx.fcitx5.android.data.UserDataManager
import org.fcitx.fcitx5.android.data.ai.AiHistory
import org.fcitx.fcitx5.android.data.ai.AiStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry
import android.util.AtomicFile
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.RealObject
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.system.measureNanoTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=FcitxApplication::class,shadows=[AndroidAtomicFileShadow::class])
class AiHistoryTest {
    private fun fixture():Pair<File,AiHistory> {
        val root=File(RuntimeEnvironment.getApplication().cacheDir,"history-test-${System.nanoTime()}").apply { mkdirs() }
        return root to AiHistory(root.resolve("conversations"),root.resolve("index.json"))
    }

    @Test fun legacyJsonSupportsPagingBodySearchAndExternalRestore()=runBlocking {
        val (root,history)=fixture()
        try {
            val values=(0 until 23).map { i -> AiStore.Conversation(id="old-$i",title="对话 $i",updated=i.toLong(),
                messages=listOf(AiStore.Message("user",if(i==0) "正文中的检索词" else "内容"))) }
            values.forEach { value ->
                // Write the existing on-disk format without going through the index.
                ExperienceData.write(root.resolve("conversations/${value.id}.json"),ExperienceData.json.encodeToString(value))
            }
            val before=root.resolve("conversations/old-0.json").readBytes()
            assertEquals(20,history.page("",0,20).size)
            assertEquals(listOf("old-2","old-1","old-0"),history.page("",20,20).map { it.id })
            assertEquals(listOf("old-0"),history.page("检索词",0,20).map { it.id })
            assertEquals(values.first(),history.get("old-0"))
            assertArrayEquals(before,root.resolve("conversations/old-0.json").readBytes())
            val restored=values.first().copy(title="从旧备份恢复",updated=100)
            ExperienceData.write(root.resolve("conversations/old-0.json"),ExperienceData.json.encodeToString(restored))
            history.invalidate() // The same callback used by the user-data importer.
            assertEquals(restored.title,history.summaries().first().title)
            root.resolve("index.json").delete()
            assertEquals(23,AiHistory(root.resolve("conversations"),root.resolve("index.json")).summaries().size)
        } finally { history.close();root.deleteRecursively() }
    }

    @Test fun queuedDraftReplyDeletionAndBackupKeepTheirOrder()=runBlocking {
        val value=AiStore.Conversation(title="存储回归",messages=listOf(AiStore.Message("user","问题")))
        try {
            AiStore.write { AiStore.save(value) }
            AiStore.write { AiStore.saveDraft(value.copy(draft="最近的草稿")) }
            AiStore.read { AiStore.complete(value,"回复",null) }
            // A draft captured before the reply must not erase the completed reply.
            AiStore.write { AiStore.saveDraft(value.copy(draft="导出前最后输入")) }
            val output=ByteArrayOutputStream()
            UserDataManager.export(output).getOrThrow()
            val saved=AiStore.read { AiStore.get(value.id) }!!
            val beforeImport=AiStore.version
            assertEquals("回复",saved.messages.last().text)
            assertEquals("导出前最后输入",saved.draft)
            var zipped:AiStore.Conversation?=null
            ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
                while(true) {
                    val entry=zip.nextEntry ?: break
                    if(entry.name.endsWith("${value.id}.json")) zipped=ExperienceData.json.decodeFromString(zip.readBytes().toString(Charsets.UTF_8))
                }
            }
            assertEquals(saved,zipped)
            AiStore.read { AiStore.delete(saved) }
            assertNull(AiStore.read { AiStore.complete(value,"迟到的回复",null) })
            AiStore.write { AiStore.saveDraft(value.copy(draft="迟到的草稿")) }
            assertNull(AiStore.read { AiStore.get(value.id) })
            // Import the legacy AI payload independently of the Windows host's open SQLite files.
            val legacy=ByteArrayOutputStream()
            ZipOutputStream(legacy).use { dest ->
                ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { src ->
                    while(true) {
                        val entry=src.nextEntry ?: break
                        val name=entry.name.replace('\\','/')
                        if(name=="metadata.json" || name.startsWith("external/data/ai/")) {
                            dest.putNextEntry(ZipEntry(name));src.copyTo(dest);dest.closeEntry()
                        }
                    }
                }
            }
            UserDataManager.import(ByteArrayInputStream(legacy.toByteArray())).getOrThrow()
            assertEquals(saved,AiStore.read { AiStore.get(value.id) })
            assertNull(AiStore.read { AiStore.complete(value,"导入前的迟到回复",null,beforeImport) })
            AiStore.write { AiStore.saveDraft(value.copy(draft="导入前的迟到草稿"),beforeImport) }
            assertEquals(saved,AiStore.read { AiStore.get(value.id) })
        } finally { AiStore.read { AiStore.delete(value) } }
    }

    @Test fun storageUsesBackgroundThreadAndPrivacyLeavesNoHistory()=runBlocking {
        assertNotEquals(Thread.currentThread(),AiStore.read { Thread.currentThread() })
        val previous=PrivacyMode.enabled
        try {
            if(!previous) PrivacyMode.toggle()
            val value=AiStore.read { AiStore.current().copy(draft="私密草稿") }
            AiStore.write { AiStore.saveDraft(value) }
            assertEquals("私密草稿",AiStore.read { AiStore.current().draft })
            assertNull(AiStore.read { AiStore.get(value.id) })
            PrivacyMode.toggle();AiStore.discardTemporary()
            assertNull(AiStore.read { AiStore.complete(value,"迟到的私密回复",null) })
            PrivacyMode.toggle()
            val next=AiStore.read { AiStore.current() }
            AiStore.write { AiStore.saveDraft(value) }
            assertEquals(next,AiStore.read { AiStore.current() })
        } finally { AiStore.discardTemporary();if(PrivacyMode.enabled!=previous) PrivacyMode.toggle() }
    }

    @Test fun compareLegacyFullScanWithIndexedOpenAndHistoryPages()=runBlocking {
        val (root,history)=fixture()
        try {
            val records=(0 until 500).map { i -> AiStore.Conversation(id="record-$i",title="会话 $i",updated=i.toLong(),
                messages=(0 until 20).map { AiStore.Message(if(it%2==0) "user" else "assistant","样本内容".repeat(24)) }) }
            records.forEach { history.save(it) }
            fun legacy():AiStore.Conversation = root.resolve("conversations").listFiles()!!.map {
                ExperienceData.json.decodeFromString<AiStore.Conversation>(it.readText(Charsets.UTF_8))
            }.sortedByDescending { it.updated }.first()
            val cold=measureNanoTime { assertEquals(20,history.page("",0,20).size) }
            val legacyTimes=mutableListOf<Long>();val openTimes=mutableListOf<Long>();val pageTimes=mutableListOf<Long>()
            var heap=0L
            repeat(23) { run ->
                val before=measureNanoTime { assertEquals("record-499",legacy().id) }
                val after=measureNanoTime { assertEquals("record-499",history.get("record-499")!!.id) }
                val page=measureNanoTime { assertEquals(20,history.page("",0,20).size) }
                if(run>=3) { legacyTimes+=before;openTimes+=after;pageTimes+=page }
                val runtime=Runtime.getRuntime();heap=maxOf(heap,runtime.totalMemory()-runtime.freeMemory())
            }
            fun metrics(values:List<Long>):String {
                val sorted=values.sorted()
                return "p50_ms=${sorted[sorted.size/2]/1e6}, p95_ms=${sorted[(sorted.size*95/100).coerceAtMost(sorted.lastIndex)]/1e6}"
            }
            val report="500 conversations x 20 messages; local JVM storage comparison\n"+
                "legacy full scan: ${metrics(legacyTimes)}\nindexed direct open: ${metrics(openTimes)}\n"+
                "history first 20: ${metrics(pageTimes)}\ncold index_ms=${cold/1e6}\nobserved_heap_bytes=$heap\n"
            File("build/reports/maintenance").apply { mkdirs() }.resolve("ai-history.txt").writeText(report,Charsets.UTF_8)
            println(report)
        } finally { history.close();root.deleteRecursively() }
    }
}

/** File.renameTo replaces on Android/Linux; Windows needs REPLACE_EXISTING. */
@Implements(AtomicFile::class)
class AndroidAtomicFileShadow {
    @RealObject private lateinit var file:AtomicFile
    @Implementation fun finishWrite(stream:FileOutputStream?) {
        stream ?: return
        stream.fd.sync();stream.close()
        Files.move(File(file.baseFile.path+".new").toPath(),file.baseFile.toPath(),StandardCopyOption.REPLACE_EXISTING)
    }
}

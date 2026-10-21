/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android

import kotlinx.coroutines.*
import org.fcitx.fcitx5.android.core.FcitxAPI
import org.fcitx.fcitx5.android.core.RawConfig
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.data.WordLibrary
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=FcitxApplication::class,shadows=[AndroidAtomicFileShadow::class])
class WordLibraryRulesTest {
    private val root get()=RuntimeEnvironment.getApplication().let { it.getExternalFilesDir(null) ?: it.filesDir }.resolve("data")

    private class RestoreEngine(private val root:File):FcitxConnection,AutoCloseable {
        private val dispatcher=Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val operations=mutableListOf<String>()
        var beforeRestore:suspend (String)->String={"ok"}
        private val unused=Proxy.newProxyInstance(FcitxAPI::class.java.classLoader,arrayOf(FcitxAPI::class.java)) { _,m,_-> error(m.name) } as FcitxAPI
        private val api=object:FcitxAPI by unused {
            override suspend fun setAddonSubConfig(addon:String,path:String,config:RawConfig)=withContext(dispatcher) {
                val operation=path.removePrefix("personal/")
                operations+=operation
                val id=operation.substringAfter('/')
                val folder=root.resolve("input-history/$id")
                val rules=root.resolve("candidate-preferences.tsv")
                val status=if(operation.startsWith("backup/")) {
                    folder.resolve("db").mkdirs();folder.resolve("words").mkdirs()
                    if(rules.exists()) rules.copyTo(folder.resolve("candidate-preferences.tsv"),overwrite=true)
                    "ok"
                } else {
                    val result=beforeRestore(id)
                    if(result=="ok") {
                        val saved=folder.resolve("candidate-preferences.tsv")
                        if(saved.exists()) saved.copyTo(rules,overwrite=true) else rules.delete()
                    }
                    result
                }
                root.resolve("rime/personal-exchange/status.txt").writeText(status)
            }
        }
        override val lifecycleScope=CoroutineScope(SupervisorJob()+dispatcher)
        override fun <T> runImmediately(block:suspend FcitxAPI.()->T):T=runBlocking { api.block() }
        override suspend fun <T> runOnReady(block:suspend FcitxAPI.()->T):T=api.block()
        override fun runIfReady(block:suspend FcitxAPI.()->Unit) { error("unused") }
        override fun close() {
            lifecycleScope.cancel();dispatcher.close()
            operations.filter { it.startsWith("backup/") }.forEach { root.resolve("input-history/${it.substringAfter('/')}").deleteRecursively() }
        }
    }

    @Test fun ruleReadModifyWriteIncludesAnInterleavedNativeChoice()=runBlocking {
        val file=root.resolve("candidate-preferences.tsv")
        val previous=file.takeIf { it.exists() }?.readBytes()
        val dispatcher=Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        var nativePending=true
        val native=WordLibrary.Rule("anthy","かな","仮名","hide")
        val lower=WordLibrary.Rule("rime:rime_ice","nihao","你好","lower")
        val unused=Proxy.newProxyInstance(FcitxAPI::class.java.classLoader,arrayOf(FcitxAPI::class.java)) { _,m,_-> error(m.name) } as FcitxAPI
        val api=object:FcitxAPI by unused {
            override suspend fun updateCandidatePreferences(update:()->Unit) = withContext(dispatcher) {
                // A native action already waiting in the queue runs before this mutation.
                if(nativePending) { file.writeText(native.line+"\n",Charsets.UTF_8);nativePending=false }
                update()
            }
        }
        val connection=object:FcitxConnection {
            override val lifecycleScope=CoroutineScope(SupervisorJob()+dispatcher)
            override fun <T> runImmediately(block:suspend FcitxAPI.()->T):T=runBlocking { api.block() }
            override suspend fun <T> runOnReady(block:suspend FcitxAPI.()->T):T=api.block()
            override fun runIfReady(block:suspend FcitxAPI.()->Unit) { error("unused") }
        }
        try {
            file.parentFile!!.mkdirs();file.writeText("",Charsets.UTF_8)
            WordLibrary.updateRules(connection) { it+lower }
            assertEquals(setOf(native,lower),WordLibrary.rules().toSet())
            WordLibrary.removeRule(connection,lower)
            assertEquals(listOf(native),WordLibrary.rules())
        } finally {
            dispatcher.close();connection.lifecycleScope.cancel()
            if(previous==null) file.delete() else file.writeBytes(previous)
        }
    }

    private fun archive(rule:String):ByteArray {
        val output=ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            mapOf("db/" to "","words/" to "","metadata.json" to
                """{"id":"1","name":"snapshot","note":"","time":1,"count":0}""",
                "candidate-preferences.tsv" to rule).forEach { (name,text) ->
                zip.putNextEntry(ZipEntry(name));zip.write(text.toByteArray(Charsets.UTF_8));zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
    @Test fun invalidSnapshotPreferencesAreRejectedBeforeImport() {
        val before=WordLibrary.snapshots().map { it.id }.toSet()
        for(line in listOf("zz\t00\t00\thide\n","61\t62\t63\tother\n","f\t62\t63\tlower\n","ff\t62\t63\thide\n",
            "eda080\t62\t63\thide\n","c080\t62\t63\thide\n","61\t62\t63\thide\t\n","61\t62\t63\n")) {
            assertTrue(runCatching { WordLibrary.import(ByteArrayInputStream(archive(line))) }.isFailure)
        }
        assertEquals(before,WordLibrary.snapshots().map { it.id }.toSet())
    }
    @Test fun legacyAndScopedRulesRoundTripInTheExistingSnapshotFormat() {
        val rules=listOf(WordLibrary.Rule("rime","nihao","你好","lower"),WordLibrary.Rule("anthy","japanese","仮名","hide"),
            WordLibrary.Rule("rime:rime_ice","nihao","你好","hide"))
        val snapshot=WordLibrary.import(ByteArrayInputStream(archive(rules.joinToString("\n") { it.line })))
        try { assertEquals(rules,WordLibrary.parseRules(root.resolve("input-history/${snapshot.id}/candidate-preferences.tsv"))) }
        finally { WordLibrary.delete(snapshot) }
    }

    @Test fun cancelledRestoreFinishesRollbackBeforeReturning()=runBlocking {
        val file=root.resolve("candidate-preferences.tsv")
        val previous=file.takeIf { it.exists() }?.readBytes()
        val original=WordLibrary.Rule("anthy","かな","仮名","hide").line+"\n"
        val snapshot=WordLibrary.import(ByteArrayInputStream(archive("")))
        try {
            file.writeText(original)
            RestoreEngine(root).use { engine ->
                val changed=CompletableDeferred<Unit>()
                engine.beforeRestore={ id ->
                    if(id==snapshot.id) {
                        file.writeText("") // Native work changed state before the caller was cancelled.
                        changed.complete(Unit)
                        awaitCancellation()
                    }
                    "ok"
                }
                val job=launch { WordLibrary.restore(engine,snapshot,false) }
                withTimeout(5_000) { changed.await() }
                job.cancelAndJoin()
                assertTrue(job.isCancelled)
                assertEquals(original,file.readText())
                assertEquals(3,engine.operations.size)
                assertEquals("restore/${engine.operations.first().substringAfter('/')}",engine.operations.last())
            }
        } finally {
            WordLibrary.delete(snapshot)
            if(previous==null) file.delete() else file.writeBytes(previous)
        }
    }

    @Test fun nativeStatusFailuresRollBackPartiallyChangedRestoreAndMerge()=runBlocking {
        val file=root.resolve("candidate-preferences.tsv")
        val previous=file.takeIf { it.exists() }?.readBytes()
        val original=WordLibrary.Rule("rime:rime_ice","nihao","你好","lower").line+"\n"
        val snapshot=WordLibrary.import(ByteArrayInputStream(archive("")))
        try {
            for(merge in listOf(false,true)) {
                file.writeText(original)
                RestoreEngine(root).use { engine ->
                    engine.beforeRestore={ id ->
                        if(id==snapshot.id) { file.writeText("");"native operation failed" } else "ok"
                    }
                    val error=runCatching { WordLibrary.restore(engine,snapshot,merge) }.exceptionOrNull()
                    assertEquals("native operation failed",error?.message)
                    assertEquals(original,file.readText())
                    assertEquals(3,engine.operations.size)
                    assertEquals("${if(merge) "merge" else "restore"}/${snapshot.id}",engine.operations[1])
                }
            }
        } finally {
            WordLibrary.delete(snapshot)
            if(previous==null) file.delete() else file.writeBytes(previous)
        }
    }

    @Test fun restoreReportsTheOriginalErrorWhenRollbackAlsoFails()=runBlocking {
        val snapshot=WordLibrary.import(ByteArrayInputStream(archive("")))
        try {
            for(failure in listOf(IllegalStateException("restore failed"),CancellationException("restore cancelled"))) {
                val recovery=IllegalStateException("rollback failed")
                RestoreEngine(root).use { engine ->
                    engine.beforeRestore={ id -> throw if(id==snapshot.id) failure else recovery }
                    val thrown=runCatching { WordLibrary.restore(engine,snapshot,false) }.exceptionOrNull()
                    assertEquals(failure.javaClass,thrown?.javaClass)
                    assertEquals(failure.message,thrown?.message)
                    // Coroutine stack-trace recovery may return a copy; both
                    // the original cause and rollback diagnostics must survive.
                    val causes=generateSequence(thrown) { it.cause }.toList()
                    assertTrue(causes.drop(1).any { it.javaClass==failure.javaClass && it.message==failure.message })
                    val suppressed=causes.flatMap { it.suppressed.toList() }
                    assertTrue(suppressed.any { it.javaClass==recovery.javaClass && it.message==recovery.message })
                    assertEquals(3,engine.operations.size)
                }
            }
        } finally { WordLibrary.delete(snapshot) }
    }

    @Test fun malformedRestoreRulesAreRejectedBeforeCreatingRollbackOrCallingNative()=runBlocking {
        val snapshot=WordLibrary.import(ByteArrayInputStream(archive("")))
        root.resolve("input-history/${snapshot.id}/candidate-preferences.tsv").writeText("61\t62\t63\tbad\n")
        try {
            RestoreEngine(root).use { engine ->
                assertTrue(runCatching { WordLibrary.restore(engine,snapshot,false) }.isFailure)
                assertTrue(engine.operations.isEmpty())
            }
        } finally { WordLibrary.delete(snapshot) }
    }
}

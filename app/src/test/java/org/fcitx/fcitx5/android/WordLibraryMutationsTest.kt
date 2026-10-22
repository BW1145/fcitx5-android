/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android

import kotlinx.coroutines.*
import org.fcitx.fcitx5.android.core.FcitxAPI
import org.fcitx.fcitx5.android.core.RawConfig
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.data.ExperienceData
import org.fcitx.fcitx5.android.data.WordLibrary
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.lang.reflect.Proxy
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = FcitxApplication::class, shadows = [AndroidAtomicFileShadow::class])
class WordLibraryMutationsTest {
    private val root get() = ExperienceData.root
    private val rulesFile get() = root.resolve("candidate-preferences.tsv")
    private val exchange get() = root.resolve("rime/personal-exchange")
    private val saved = mutableMapOf<String, ByteArray?>()

    @Before fun saveExistingFiles() {
        listOf("candidate-preferences.tsv", "rime/personal-exchange/dictionary.txt",
            "rime/personal-exchange/import.txt", "rime/personal-exchange/status.txt").forEach { name ->
            saved[name] = root.resolve(name).takeIf { it.isFile }?.readBytes()
        }
        rulesFile.parentFile!!.mkdirs()
        rulesFile.writeText("")
    }

    @After fun restoreExistingFiles() {
        saved.forEach { (name, bytes) ->
            val file = root.resolve(name)
            if (bytes == null) file.delete() else { file.parentFile!!.mkdirs(); file.writeBytes(bytes) }
        }
    }

    private inner class Engine : FcitxConnection, AutoCloseable {
        private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val imports = mutableListOf<Pair<String, String>>()
        val schemaLookups = mutableListOf<String>()
        var updates = 0
        var schemas: suspend (String) -> Array<String> = { arrayOf("rime_ice", "double_pinyin") }
        var beforeUpdate: suspend () -> Unit = {}
        var importStatus: (String) -> String = { "ok" }
        private val unused = Proxy.newProxyInstance(FcitxAPI::class.java.classLoader,
            arrayOf(FcitxAPI::class.java)) { _, method, _ -> error(method.name) } as FcitxAPI
        private val api = object : FcitxAPI by unused {
            override suspend fun preferenceSchemas(dictionary: String): Array<String> = withContext(dispatcher) {
                schemaLookups += dictionary
                schemas(dictionary)
            }
            override suspend fun updateCandidatePreferences(update: () -> Unit) = withContext(dispatcher) {
                updates++
                beforeUpdate()
                update()
            }
            override suspend fun setAddonSubConfig(addon: String, path: String, config: RawConfig) = withContext(dispatcher) {
                assertEquals("rime", addon)
                assertEquals("personal/import", path)
                val dictionary = exchange.resolve("dictionary.txt").readText()
                imports += dictionary to exchange.resolve("import.txt").readText()
                exchange.resolve("status.txt").writeText(importStatus(dictionary))
            }
        }
        override val lifecycleScope = CoroutineScope(SupervisorJob() + dispatcher)
        override fun <T> runImmediately(block: suspend FcitxAPI.() -> T): T = runBlocking { api.block() }
        override suspend fun <T> runOnReady(block: suspend FcitxAPI.() -> T): T = api.block()
        override fun runIfReady(block: suspend FcitxAPI.() -> Unit) { error("unused") }
        override fun close() { lifecycleScope.cancel(); dispatcher.close() }
    }

    private fun writeRules(rules: List<WordLibrary.Rule>) {
        rulesFile.writeText(rules.joinToString("\n", postfix = if (rules.isEmpty()) "" else "\n") { it.line })
    }
    private fun word(dictionary: String, text: String = "你好", reading: String = "ni hao") =
        WordLibrary.Word(dictionary, text, reading, "c=13 d=0.4 t=900")

    @Test fun lowerPreservesLearningAndOnlyReplacesTheMatchedScopedRules() = runBlocking {
        val kept = listOf(WordLibrary.Rule("anthy", "japanese", "你好", "hide"),
            WordLibrary.Rule("rime:another", "nihao", "你好", "hide"),
            WordLibrary.Rule("rime:rime_ice", "different", "你好", "hide"))
        writeRules(kept + WordLibrary.Rule("rime:rime_ice", "nihao", "你好", "hide"))
        Engine().use { engine ->
            val result = WordLibrary.applyWordActions(engine, listOf(word("rime_ice")), WordLibrary.WordAction.LOWER)
            assertEquals(listOf(word("rime_ice")), result.succeeded)
            assertTrue(result.failed.isEmpty())
            assertTrue("Lowering never deletes or imports learning records", engine.imports.isEmpty())
            assertEquals((kept + listOf("rime_ice", "double_pinyin").map {
                WordLibrary.Rule("rime:$it", "nihao", "你好", "lower") }).toSet(), WordLibrary.rules().toSet())
        }
    }

    @Test fun forgetUsesTheOriginalDictionaryAndNegativeLearningBeforeHidingCandidate() = runBlocking {
        val unrelated = WordLibrary.Rule("rime:another", "nihao", "你好", "lower")
        writeRules(listOf(unrelated, WordLibrary.Rule("rime", "nihao", "你好", "lower")))
        Engine().use { engine ->
            val selected = word("custom_dictionary", reading = "ni'hao")
            val result = WordLibrary.applyWordActions(engine, listOf(selected), WordLibrary.WordAction.FORGET)
            assertEquals(listOf(selected), result.succeeded)
            assertTrue(result.failed.isEmpty())
            assertEquals(listOf("custom_dictionary" to "你好\tni hao\t-1\n"), engine.imports)
            assertEquals((listOf(unrelated) + listOf("rime_ice", "double_pinyin").map {
                WordLibrary.Rule("rime:$it", "nihao", "你好", "hide") }).toSet(), WordLibrary.rules().toSet())
        }
    }

    @Test fun batchContinuesAfterNativeFailureAndDeduplicatesOnlyTheExactStoredRecord() = runBlocking {
        Engine().use { engine ->
            engine.importStatus = { if (it == "broken") "dictionary unavailable" else "ok" }
            val first = word("first")
            val failed = word("broken")
            val third = word("third", reading = "ni'hao")
            val result = WordLibrary.applyWordActions(engine,
                listOf(first, first.copy(weight = "changed"), failed, third), WordLibrary.WordAction.FORGET)
            assertEquals(listOf(first, third), result.succeeded)
            assertEquals(listOf(WordLibrary.BatchFailure(failed, "dictionary unavailable")), result.failed)
            assertEquals(listOf("first", "broken", "third"), engine.imports.map { it.first })
            assertTrue(WordLibrary.rules().all { it.mode == "hide" })
        }
    }

    @Test fun emptyBatchDoesNotContactEngineAndLowerFailureDoesNotStopLaterWords() = runBlocking {
        Engine().use { engine ->
            assertEquals(WordLibrary.BatchResult<WordLibrary.Word>(emptyList(), emptyList()),
                WordLibrary.applyWordActions(engine, emptyList(), WordLibrary.WordAction.LOWER))
            assertEquals(WordLibrary.BatchResult<WordLibrary.Rule>(emptyList(), emptyList()),
                WordLibrary.restoreRules(engine, emptyList()))
            assertTrue(engine.schemaLookups.isEmpty()); assertEquals(0, engine.updates)
            engine.schemas = { if (it == "missing") emptyArray() else arrayOf(it) }
            val first = word("first")
            val missing = word("missing")
            val last = word("last")
            val result = WordLibrary.applyWordActions(engine, listOf(first, missing, last), WordLibrary.WordAction.LOWER)
            assertEquals(listOf(first, last), result.succeeded)
            assertEquals(listOf(missing), result.failed.map { it.item })
            assertTrue(result.failed.single().message.contains("拼音方案"))
            assertTrue(engine.imports.isEmpty())
        }
    }

    @Test fun loweringManyRecordsCachesSchemaLookupsAndMergesRulesInOneEngineWrite() = runBlocking {
        val unrelated = WordLibrary.Rule("anthy", "japanese", "仮名", "hide")
        val native = WordLibrary.Rule("rime:other", "predict:你好", "世界", "lower")
        writeRules(listOf(unrelated))
        Engine().use { engine ->
            engine.beforeUpdate = { writeRules(WordLibrary.rules() + native) }
            val selected = (0 until 120).map { word("shared", "词条$it", "ci tiao") }
            val result = WordLibrary.applyWordActions(engine, selected, WordLibrary.WordAction.LOWER)
            assertEquals(selected, result.succeeded)
            assertTrue(result.failed.isEmpty())
            assertEquals(listOf("shared"), engine.schemaLookups)
            assertEquals(1, engine.updates)
            assertEquals(242, WordLibrary.rules().size)
            assertTrue(WordLibrary.rules().containsAll(listOf(unrelated, native)))
        }
    }

    @Test fun sharedRuleWriteFailureMarksAllPendingWordsFailedWithoutFalseSuccess() = runBlocking {
        val existing = WordLibrary.Rule("anthy", "japanese", "仮名", "hide")
        writeRules(listOf(existing))
        Engine().use { engine ->
            engine.schemas = { if (it == "missing") emptyArray() else arrayOf(it) }
            engine.beforeUpdate = { error("write unavailable") }
            val selected = listOf(word("first"), word("missing"), word("last"))
            val result = WordLibrary.applyWordActions(engine, selected, WordLibrary.WordAction.LOWER)
            assertTrue(result.succeeded.isEmpty())
            assertEquals(selected.toSet(), result.failed.map { it.item }.toSet())
            assertEquals(setOf("write unavailable"), result.failed.filter { it.item.dictionary != "missing" }.map { it.message }.toSet())
            assertEquals(listOf(existing), WordLibrary.rules())
            assertEquals(1, engine.updates)
        }
    }

    @Test fun cancellationRemainsCancellationAndNeverStartsLaterSelectedWords() = runBlocking {
        Engine().use { engine ->
            val secondStarted = CompletableDeferred<Unit>()
            engine.schemas = { dictionary ->
                if (dictionary == "second") { secondStarted.complete(Unit); awaitCancellation() }
                arrayOf(dictionary)
            }
            val job = launch { WordLibrary.applyWordActions(engine,
                listOf(word("first"), word("second"), word("never")), WordLibrary.WordAction.LOWER) }
            withTimeout(5_000) { secondStarted.await() }
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
            assertEquals(listOf("first", "second"), engine.schemaLookups)
            assertTrue("The cancelled staged batch never reaches the shared rule write", WordLibrary.rules().isEmpty())
            assertEquals(0, engine.updates)
        }
    }

    @Test fun cancelledForgetKeepsCompletedWorkAndDoesNotStartTheRemainingRecords() = runBlocking {
        Engine().use { engine ->
            val secondStarted = CompletableDeferred<Unit>()
            engine.schemas = { dictionary ->
                if (dictionary == "second") { secondStarted.complete(Unit); awaitCancellation() }
                arrayOf(dictionary)
            }
            val job = launch { WordLibrary.applyWordActions(engine,
                listOf(word("first"), word("second"), word("never")), WordLibrary.WordAction.FORGET) }
            withTimeout(5_000) { secondStarted.await() }
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
            assertEquals(listOf("first", "second"), engine.imports.map { it.first })
            assertEquals(listOf(WordLibrary.Rule("rime:first", "nihao", "你好", "hide")), WordLibrary.rules())
        }
    }

    @Test fun restoringRuleOnlyItemsUsesOneQueuedUpdateAndPreservesInterleavedChoices() = runBlocking {
        val hidden = WordLibrary.Rule("anthy", "japanese", "仮名", "hide")
        val lowered = WordLibrary.Rule("rime:rime_ice", "predict:你好", "世界", "lower")
        val untouched = WordLibrary.Rule("rime:other", "predict:你好", "世界", "hide")
        val native = WordLibrary.Rule("anthy", "japanese", "別の候補", "lower")
        writeRules(listOf(hidden, lowered, untouched))
        Engine().use { engine ->
            engine.beforeUpdate = { writeRules(WordLibrary.rules() + native) }
            val result = WordLibrary.restoreRules(engine, listOf(hidden, hidden.copy(mode = "lower"), lowered))
            assertEquals(listOf(hidden, lowered), result.succeeded)
            assertTrue(result.failed.isEmpty())
            assertEquals(1, engine.updates)
            assertTrue(engine.imports.isEmpty()); assertTrue(engine.schemaLookups.isEmpty())
            assertEquals(setOf(untouched, native), WordLibrary.rules().toSet())
        }
    }

    @Test fun ruleRestoreFailureReportsEverySelectedIdentityAndLeavesTheFileIntact() = runBlocking {
        val rules = listOf(WordLibrary.Rule("anthy", "japanese", "仮名", "hide"),
            WordLibrary.Rule("rime:rime_ice", "nihao", "你好", "lower"))
        writeRules(rules)
        Engine().use { engine ->
            engine.beforeUpdate = { error("engine unavailable") }
            val result = WordLibrary.restoreRules(engine, rules)
            assertTrue(result.succeeded.isEmpty())
            assertEquals(rules.map { WordLibrary.BatchFailure(it, "engine unavailable") }, result.failed)
            assertEquals(rules, WordLibrary.rules())
            assertEquals(1, engine.updates)
        }
    }

    @Test fun restoredRuleBatchPropagatesCancellationWithoutReportingFalseFailures() = runBlocking {
        val rule = WordLibrary.Rule("anthy", "japanese", "仮名", "hide")
        writeRules(listOf(rule))
        Engine().use { engine ->
            engine.beforeUpdate = { throw CancellationException("screen closed") }
            val failure = runCatching { WordLibrary.restoreRules(engine, listOf(rule)) }.exceptionOrNull()
            assertTrue(failure is CancellationException)
            assertEquals(listOf(rule), WordLibrary.rules())
        }
    }

    @Test fun existingZipFormatRoundTripsLearningBytesMetadataAndRuleOnlyCandidates() {
        val rules = listOf(WordLibrary.Rule("anthy", "japanese", "仮名", "hide"),
            WordLibrary.Rule("rime:rime_ice", "predict:你好", "世界", "lower"))
        val contents = linkedMapOf(
            "db/" to byteArrayOf(), "words/" to byteArrayOf(),
            "db/native.userdb" to byteArrayOf(0, 1, -1, 10),
            "words/rime_ice.txt" to "你好\tni hao\tc=9 d=0.2 t=800\n".toByteArray(),
            "metadata.json" to """{"id":"old","name":"学习顺手","note":"保留备注","time":123,"count":1}""".toByteArray(),
            "candidate-preferences.tsv" to rules.joinToString("\n", postfix = "\n") { it.line }.toByteArray()
        )
        val source = ByteArrayOutputStream()
        ZipOutputStream(source).use { zip -> contents.forEach { (path, bytes) ->
            zip.putNextEntry(ZipEntry(path)); zip.write(bytes); zip.closeEntry()
        } }
        val first = WordLibrary.import(ByteArrayInputStream(source.toByteArray()))
        var second: WordLibrary.Snapshot? = null
        try {
            val output = ByteArrayOutputStream()
            WordLibrary.export(first, output)
            val exported = mutableMapOf<String, ByteArray>()
            ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
                while (true) { val entry = zip.nextEntry ?: break; exported[entry.name] = zip.readBytes() }
            }
            for (path in listOf("db/native.userdb", "words/rime_ice.txt", "candidate-preferences.tsv"))
                assertArrayEquals(path, contents.getValue(path), exported[path])
            second = WordLibrary.import(ByteArrayInputStream(output.toByteArray()))
            assertNotEquals(first.id, second.id)
            assertEquals(first.copy(id = second.id), second)
            assertEquals("学习顺手", second.name); assertEquals("保留备注", second.note)
            assertEquals(123L, second.time); assertEquals(1, second.count)
            assertEquals(rules, WordLibrary.parseRules(root.resolve("input-history/${second.id}/candidate-preferences.tsv")))
        } finally { WordLibrary.delete(first); second?.let(WordLibrary::delete) }
    }
}

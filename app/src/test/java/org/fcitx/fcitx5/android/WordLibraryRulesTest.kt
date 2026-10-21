/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android

import kotlinx.coroutines.*
import org.fcitx.fcitx5.android.core.FcitxAPI
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
import java.lang.reflect.Proxy
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=FcitxApplication::class,shadows=[AndroidAtomicFileShadow::class])
class WordLibraryRulesTest {
    private val root get()=RuntimeEnvironment.getApplication().let { it.getExternalFilesDir(null) ?: it.filesDir }.resolve("data")

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
        for(line in listOf("zz\t00\t00\thide\n","61\t62\t63\tother\n","f\t62\t63\tlower\n","ff\t62\t63\thide\n")) {
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
}

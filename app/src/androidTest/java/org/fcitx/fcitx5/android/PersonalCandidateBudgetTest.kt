/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import org.fcitx.fcitx5.android.core.*
import org.fcitx.fcitx5.android.data.ExperienceData
import org.fcitx.fcitx5.android.data.WordLibrary
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

/** Actual Rime/JNI batch contract. 10k synthetic scan proof is a separate native test. */
class PersonalCandidateBudgetTest {
    @Test fun realRimeBoundedPrefixContinuationRevisionsAndSelection(): Unit = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val engine=Fcitx(context)
        val ready=async(start=CoroutineStart.UNDISPATCHED) { engine.eventFlow.filterIsInstance<FcitxEvent.ReadyEvent>().first() }
        val file=ExperienceData.root.resolve("candidate-preferences.tsv")
        val saved=file.takeIf {it.exists()}?.readBytes()
        val report=JSONObject().put("scope","API35 real Rime; initial key vs explicit full-range wall time; not phone touch-to-pixel")
        val batches=JSONArray();report.put("batches",batches)
        fun hash(words:List<String>)=MessageDigest.getInstance("SHA-256").digest(words.joinToString("\u0000").toByteArray())
            .joinToString("") {"%02x".format(it.toInt() and 255)}
        suspend fun rules(words:Set<String>)=engine.updateCandidatePreferences {
            ExperienceData.write(file,words.joinToString("\n",postfix=if(words.isEmpty()) "" else "\n") {
                WordLibrary.Rule("rime:rime_ice","shi",it,"hide").line
            })
        }
        suspend fun type(input:String) {engine.reset();input.forEach {engine.sendKey(it)}}
        engine.start()
        try {
            withTimeout(180_000) {ready.await()}
            engine.activate(android.os.Process.myUid(),context.packageName);engine.focus();engine.activateIme("rime")
            rules(emptySet())
            withTimeout(180_000) {
                while(true) {type("nihao");if(engine.getCandidates(0,16).any {it.text=="你好"})break;delay(250)}
            }
            type("shi")
            val referenceCandidates=engine.getCandidates(0,128)
            val referenceState=engine.getCandidateScanState()
            assertTrue("Unfiltered candidate revisions remain actionable",referenceCandidates.all {it.revision==referenceState.revision})
            val original=referenceCandidates.map {it.text}
            assertTrue("Real Rime shi fixture has at least 96 candidates",original.size>=96)
            val hidden=original.take(64).toSet()
            val expected=original.filterNot {it in hidden}.take(16)
            assertEquals("Reference window includes sixteen surviving choices",16,expected.size)
            report.put("referenceCount",original.size).put("hiddenRuleCount",hidden.size).put("expectedHash",hash(expected))
            rules(hidden)
            engine.reset();engine.sendKey('s');engine.sendKey('h')
            val keyStart=SystemClock.elapsedRealtimeNanos();engine.sendKey('i')
            report.put("initialKeyReturnNs",SystemClock.elapsedRealtimeNanos()-keyStart)
            val first=engine.getCandidateScanState()
            assertTrue(first.budgeted);assertTrue(first.pending);assertEquals(0,first.available)
            assertTrue("Initial automatic scan is bounded",first.cursor in 1L..32L)
            report.put("initialRawCursor",first.cursor)
            val collected=ArrayList<CandidateWord>();var state=first
            val fullStart=SystemClock.elapsedRealtimeNanos()
            withTimeout(30_000) {
                while(collected.size<16 && state.pending) {
                    val before=state.cursor;val start=SystemClock.elapsedRealtimeNanos()
                    val batch=engine.getCandidateBatch(collected.size,16-collected.size,first.revision,first.ruleRevision)
                    val elapsed=SystemClock.elapsedRealtimeNanos()-start
                    assertTrue(first.sameGeneration(batch.state));assertTrue(batch.state.cursor-before in 0L..32L)
                    assertTrue("Batch words carry the snapshot revision",batch.candidates.all {it.revision==batch.state.revision})
                    batches.put(JSONObject().put("beforeCursor",before).put("afterCursor",batch.state.cursor)
                        .put("returned",batch.candidates.size).put("durationNs",elapsed).put("exhausted",batch.state.exhausted))
                    collected.addAll(batch.candidates);state=batch.state
                    if(collected.size<16 && state.pending)delay(1)
                }
            }
            report.put("explicitRangeWallNs",SystemClock.elapsedRealtimeNanos()-fullStart)
            assertEquals(expected,collected.map {it.text});report.put("actualHash",hash(collected.map {it.text}))
            // New input invalidates the exact earlier generation without scanning it.
            engine.sendKey('a');val changed=engine.getCandidateScanState()
            val stale=engine.getCandidateBatch(0,16,first.revision,first.ruleRevision)
            assertTrue(stale.state.stale);assertTrue(stale.candidates.isEmpty())
            assertEquals(changed.cursor,engine.getCandidateScanState().cursor)
            // Rule restoration invalidates old requests and restores the original visible order.
            type("shi");val beforeRestore=engine.getCandidateScanState();rules(emptySet())
            val restoredState=engine.getCandidateScanState()
            assertNotEquals(beforeRestore.ruleRevision,restoredState.ruleRevision)
            assertTrue(engine.getCandidateBatch(0,16,beforeRestore.revision,beforeRestore.ruleRevision).state.stale)
            assertEquals(original.take(16),engine.getCandidates(0,16).map {it.text})
            report.put("staleInputRejected",true).put("staleRulesRejected",true).put("hiddenRestored",true)
            // Focus guard changes immediately, before any queued native target update.
            rules(hidden);type("shi");val beforeGuard=engine.getCandidateScanState()
            withContext(InputTargetGuard {false}) {engine.sendKey(' ')}
            assertEquals(beforeGuard.cursor,engine.getCandidateScanState().cursor)
            assertEquals(beforeGuard.revision,engine.getCandidateScanState().revision)
            report.put("focusGuardStoppedSelection",true)
            // Explicit Space waits for the first correct surviving raw candidate; measure total wall time.
            val committed=async(start=CoroutineStart.UNDISPATCHED) {
                withTimeout(30_000) {engine.eventFlow.filterIsInstance<FcitxEvent.CommitStringEvent>().first().data.text}
            }
            val selectionStart=SystemClock.elapsedRealtimeNanos();engine.sendKey(' ')
            report.put("explicitSpaceWallNs",SystemClock.elapsedRealtimeNanos()-selectionStart)
            assertEquals(expected.first(),committed.await())
            report.put("selectionCorrect",true).put("passed",true)
        } finally {
            val cleanup=runCatching {
                engine.updateCandidatePreferences {if(saved==null)file.delete() else file.writeBytes(saved)}
                engine.reset();engine.stop()
            }
            cleanup.exceptionOrNull()?.let {report.put("cleanupError",it.javaClass.name)}
            context.filesDir.resolve("candidate-budget.json").writeText(report.toString())
            if(report.optBoolean("passed") && cleanup.isFailure)throw cleanup.exceptionOrNull()!!
        }
    }
}

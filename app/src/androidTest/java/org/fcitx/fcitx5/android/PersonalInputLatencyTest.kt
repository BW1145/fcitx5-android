/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import org.fcitx.fcitx5.android.core.Fcitx
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.data.ExperienceData
import org.fcitx.fcitx5.android.data.WordLibrary
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest
import java.util.Collections

/** Same source is compiled against both commits. Only synthetic fixtures; no typed text in reports.
 * sendReturnNs includes dispatcher/JNI; candidatesReturnNs additionally includes getCandidates.
 * mainQueueNs is a Handler queue probe, NOT key-to-screen latency or an OPPO device measurement.
 */
class PersonalInputLatencyTest {
    @Test fun syntheticRimeRoundTripsAndMainQueue() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val engine = Fcitx(context)
        val ready = async(start = CoroutineStart.UNDISPATCHED) {
            engine.eventFlow.filterIsInstance<FcitxEvent.ReadyEvent>().first()
        }
        val ruleFile = ExperienceData.root.resolve("candidate-preferences.tsv")
        val savedRules = ruleFile.takeIf { it.exists() }?.readBytes()
        val samples = JSONArray()
        val mainSamples = Collections.synchronizedList(mutableListOf<Long>())
        val handler = Handler(Looper.getMainLooper())
        val report = JSONObject().put("schema", 1).put("clock", "elapsedRealtimeNanos")
            .put("scope", "synthetic engine calls and independent main queue; excludes touch/editor/draw")
        engine.start()
        try {
            withTimeout(180_000) { ready.await() }
            engine.activate(android.os.Process.myUid(), context.packageName)
            engine.focus(); engine.activateIme("rime")
            suspend fun rules(mode: String?, word: String = "") {
                engine.updateCandidatePreferences {
                    ExperienceData.write(ruleFile, if (mode == null) "" else
                        WordLibrary.Rule("rime:rime_ice", "nihao", word, mode).line + "\n")
                }
            }
            suspend fun type(input: String) {
                engine.reset(); input.forEach { engine.sendKey(it) }
            }
            rules(null)
            // Deployment and cache warmup are deliberately outside measured samples.
            withTimeout(180_000) {
                while (true) {
                    type("nihao")
                    if (engine.getCandidates(0, 16).any { it.text == "你好" }) break
                    delay(250)
                }
            }
            val original = engine.getCandidates(0, 16).map { it.text }
            assertTrue(original.size > 1)
            val victim = original.first()
            val corpus = listOf("nihao", "nihaoshijie", "zhongguo", "jintiantianqi", "woaixuexi")
            for (mode in listOf("none", "hide", "lower")) {
                rules(mode.takeUnless { it == "none" }, victim)
                type("nihao")
                val actual = engine.getCandidates(0, 16).map { it.text }
                when (mode) {
                    "none" -> assertEquals(original, actual)
                    "hide" -> assertFalse(actual.contains(victim))
                    "lower" -> assertTrue(actual.indexOf(victim) > 0 || !actual.contains(victim))
                }
                repeat(3) { for (input in corpus) { type(input); engine.getCandidates(0, 16) } }
                val probe = launch(Dispatchers.Default) {
                    while (isActive) {
                        val posted = SystemClock.elapsedRealtimeNanos()
                        val done = CompletableDeferred<Unit>()
                        handler.post {
                            mainSamples.add(SystemClock.elapsedRealtimeNanos() - posted)
                            done.complete(Unit)
                        }
                        done.await(); delay(16)
                    }
                }
                try {
                    repeat(12) { iteration ->
                        for ((fixture, input) in corpus.withIndex()) {
                            engine.reset()
                            for ((key, letter) in input.withIndex()) {
                                val start = SystemClock.elapsedRealtimeNanos()
                                engine.sendKey(letter)
                                val sent = SystemClock.elapsedRealtimeNanos()
                                val candidates = engine.getCandidates(0, 16).map { it.text }
                                val finished = SystemClock.elapsedRealtimeNanos()
                                // Hash order/content to compare outputs without exporting candidate text.
                                val digest = MessageDigest.getInstance("SHA-256")
                                    .digest(candidates.joinToString("\u0000").toByteArray(Charsets.UTF_8))
                                    .joinToString("") { "%02x".format(it.toInt() and 255) }
                                samples.put(JSONObject().put("mode", mode).put("iteration", iteration)
                                    .put("fixture", fixture).put("key", key).put("startNs", start)
                                    .put("sendReturnNs", sent - start).put("candidatesReturnNs", finished - start)
                                    .put("candidateCount", candidates.size).put("candidateHash", digest))
                            }
                        }
                    }
                } finally { probe.cancelAndJoin() }
            }
            assertTrue(samples.length() > 1000)
            assertTrue(mainSamples.isNotEmpty())
            rules(null)
            // First long press with no pre-existing rules must still resolve the lazy scope.
            type("nihao")
            val candidate = engine.getCandidates(0, 1).first()
            assertTrue(engine.getCandidateActions(0, candidate).any { it.id == -1001 })
            engine.triggerCandidateAction(0, -1001, candidate)
            assertTrue(WordLibrary.rules().any { it.text == candidate.text && it.mode == "hide" })
            rules(null)
            report.put("samples", samples).put("mainQueueNs", JSONArray(mainSamples.toList()))
            context.filesDir.resolve("input-latency.json").writeText(report.toString())
        } finally {
            engine.updateCandidatePreferences {
                if (savedRules == null) ruleFile.delete() else ruleFile.writeBytes(savedRules)
            }
            engine.reset(); engine.stop()
        }
    }
}

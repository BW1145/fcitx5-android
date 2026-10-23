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
import org.fcitx.fcitx5.android.debug.CandidateAttribution
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

/** Explicit profiling build only: identical APK/data and ABBA custom on vs full bypass. */
class PersonalCandidateAttributionTest {
    @Test fun sameBuildCustomLayerAttribution(): Unit=runBlocking {
        org.junit.Assume.assumeTrue("Explicit profiling-only invocation required",
            InstrumentationRegistry.getArguments().getString("candidateAttribution")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val progressFile=context.filesDir.resolve("candidate-attribution-progress.txt")
        fun progress(stage:String) {progressFile.appendText("${SystemClock.elapsedRealtime()} $stage\n")}
        progressFile.writeText("");progress("create engine")
        val engine=Fcitx(context)
        val ready=async(start=CoroutineStart.UNDISPATCHED) {engine.eventFlow.filterIsInstance<FcitxEvent.ReadyEvent>().first()}
        val file=ExperienceData.root.resolve("candidate-preferences.tsv")
        val saved=file.takeIf {it.exists()}?.readBytes()
        val names=listOf("process_key","get_context","begin_preferences","rules_load","scope_resolve",
            "classify_lookup","raw_scan_fetch","output_materialize","initial_update","batch_request",
            "candidate_jni","rime_fetch_miss","rime_fetch_hit","scan_items","output_items","input_panel")
        val rows=JSONArray()
        val report=JSONObject().put("schema",1).put("metrics",JSONArray(names)).put("samples",rows)
            .put("scope","same profiling debug APK; Rime shi; native times inclusive and nested, do not sum; adapter miss is not necessarily new librime generation; excludes UI draw")
            .put("order",JSONArray(listOf("bypass","enabled","enabled","bypass")))
        fun hash(words:List<String>)=MessageDigest.getInstance("SHA-256").digest(words.joinToString("\u0000").toByteArray())
            .joinToString("") {"%02x".format(it.toInt() and 255)}
        suspend fun configure(mode:Int) {engine.enqueueInputBarrier {CandidateAttribution.configure(mode)}}
        suspend fun drain():LongArray {
            var data=longArrayOf();engine.enqueueInputBarrier {data=CandidateAttribution.drain()}
            assertEquals(33,data.size);assertEquals(1L,data[0]);assertTrue(data.drop(1).all {it>=0})
            return data
        }
        suspend fun rules(words:Set<String>)=engine.updateCandidatePreferences {
            ExperienceData.write(file,words.joinToString("\n",postfix=if(words.isEmpty()) "" else "\n") {
                WordLibrary.Rule("rime:rime_ice","shi",it,"hide").line
            })
        }
        suspend fun type(input:String) {engine.reset();input.forEach {engine.sendKey(it)}}
        progress("start engine");engine.start()
        try {
            withTimeout(180_000) {ready.await()};progress("engine ready")
            progress("activate rime")
            engine.activate(android.os.Process.myUid(),context.packageName);engine.focus();engine.activateIme("rime")
            progress("rime activated configure off before");configure(0);progress("configure off after")
            progress("clear test rules");rules(emptySet())
            withTimeout(180_000) {while(true) {type("nihao");if(engine.getCandidates(0,16).any {it.text=="你好"})break;delay(250)}}
            progress("fixture ready reference range");type("shi");val original=engine.getCandidates(0,128).map {it.text}
            assertTrue(original.size>=96)
            val hidden=original.take(64).toSet()
            val filtered=original.filterNot {it in hidden}.take(16);assertEquals(16,filtered.size)
            report.put("fixture","shi").put("referenceCount",original.size).put("hiddenCount",hidden.size)
                .put("originalPrefixHash",hash(original.take(16))).put("filteredPrefixHash",hash(filtered))
            for(scenario in listOf("none","hide64")) {
                progress("scenario $scenario")
                configure(0);rules(if(scenario=="none") emptySet() else hidden)
                val fileHash=hash(listOf(file.readText()))
                for((block,mode) in listOf(2,1,1,2).withIndex()) {
                    progress("block $block mode $mode configure")
                    configure(mode)
                    repeat(3) {type("shi");engine.getCandidates(0,16)}
                    repeat(20) {iteration ->
                        progress("sample $scenario $block $iteration prepare")
                        engine.reset();engine.sendKey('s');engine.sendKey('h');drain()
                        val initialStart=SystemClock.elapsedRealtimeNanos();engine.sendKey('i')
                        val initialWall=SystemClock.elapsedRealtimeNanos()-initialStart
                        val initialStats=drain()
                        val rangeStart=SystemClock.elapsedRealtimeNanos();val candidates=engine.getCandidates(0,16)
                        val rangeWall=SystemClock.elapsedRealtimeNanos()-rangeStart
                        val rangeStats=drain()
                        val expected=if(scenario=="hide64" && mode==1)filtered else original.take(16)
                        assertEquals(expected,candidates.map {it.text})
                        rows.put(JSONObject().put("scenario",scenario).put("block",block).put("iteration",iteration)
                            .put("condition",if(mode==2)"bypass" else "enabled").put("ruleFileHash",fileHash)
                            .put("candidateHash",hash(candidates.map {it.text})).put("candidateCount",candidates.size)
                            .put("initialWallNs",initialWall).put("explicitRangeWallNs",rangeWall)
                            .put("initialNative",JSONArray(initialStats.toList())).put("rangeNative",JSONArray(rangeStats.toList())))
                        // File I/O stays outside both measured phases; retain completed samples on native crash.
                        context.filesDir.resolve("candidate-attribution.json").writeText(report.toString())
                        progress("sample $scenario $block $iteration complete")
                    }
                    assertEquals("Rule file unchanged between conditions",fileHash,hash(listOf(file.readText())))
                }
            }
            assertEquals(160,rows.length());report.put("passed",true)
        } finally {
            val cleanup=runCatching {configure(0);engine.updateCandidatePreferences {if(saved==null)file.delete() else file.writeBytes(saved)};engine.reset();engine.stop()}
            cleanup.exceptionOrNull()?.let {report.put("cleanupError",it.javaClass.name)}
            context.filesDir.resolve("candidate-attribution.json").writeText(report.toString())
            if(report.optBoolean("passed") && cleanup.isFailure)throw cleanup.exceptionOrNull()!!
        }
    }
}

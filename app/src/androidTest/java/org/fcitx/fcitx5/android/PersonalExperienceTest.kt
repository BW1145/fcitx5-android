/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.data.*
import org.fcitx.fcitx5.android.data.ai.AiStore
import org.fcitx.fcitx5.android.data.theme.ArtThemes
import org.fcitx.fcitx5.android.input.keyboard.TextKeyboard
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

class PersonalExperienceTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    @Test fun artThemesRenderDifferentCompleteKeyboards() {
        instrumentation.runOnMainSync {
            val hashes=mutableSetOf<Int>()
            ArtThemes.all.forEachIndexed { index,theme ->
                val keyboard=TextKeyboard(context,theme)
                keyboard.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(650,View.MeasureSpec.EXACTLY))
                keyboard.layout(0,0,1080,650)
                val bitmap=Bitmap.createBitmap(1080,650,Bitmap.Config.ARGB_8888)
                val canvas=Canvas(bitmap)
                theme.backgroundDrawable().apply { setBounds(0,0,1080,650);draw(canvas) }
                keyboard.draw(canvas)
                val pixels=IntArray(1080*650);bitmap.getPixels(pixels,0,1080,0,0,1080,650)
                hashes+=pixels.contentHashCode()
                val output=context.getExternalFilesDir(null)!!.resolve("theme-$index.png")
                output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
                bitmap.recycle()
            }
            assertEquals("All four styles have distinct rendered geometry and materials",4,hashes.size)
        }
    }
    private fun withWordFixture(block:suspend (org.fcitx.fcitx5.android.daemon.FcitxConnection)->Unit)=runBlocking {
        val connection=FcitxDaemon.connect("experience-test")
        val previous=WordLibrary.snapshots().map { it.id }.toSet()
        var baseline:WordLibrary.Snapshot?=null
        try {
            connection.runOnReady {
                activate(android.os.Process.myUid(),context.packageName);focus();activateIme("rime");reset()
                "nihao".forEach { sendKey(it) };delay(200);select(0);reset()
            }
            baseline=WordLibrary.snapshot(connection,"测试前状态")
            WordLibrary.add(connection,"rime_ice","验收词甲","yan shou ci jia",1000)
            block(connection)
        } finally {
            connection.runOnReady { reset();activateIme("rime") }
            baseline?.let { WordLibrary.restore(connection,it,false) }
            WordLibrary.snapshots().filterNot { it.id in previous }.forEach { WordLibrary.delete(it) }
            FcitxDaemon.disconnect("experience-test")
        }
    }

    @Test fun wordSnapshotsRestoreMergeAndRoundTrip()=withWordFixture { connection ->
            assertTrue(WordLibrary.words(connection).any { it.text=="验收词甲" })
            val first=WordLibrary.snapshot(connection,"词条甲")
            WordLibrary.add(connection,"rime_ice","验收词乙","yan shou ci yi",1000)
            WordLibrary.setRule(connection,WordLibrary.Word("rime_ice","验收词甲","yan shou ci jia","1000"),"hide")
            val second=WordLibrary.snapshot(connection,"词条乙")
            WordLibrary.restore(connection,first,false)
            assertFalse(WordLibrary.words(connection).any { it.text=="验收词乙" })
            connection.runOnReady {
                reset();"yanshoucijia".forEach { sendKey(it) };delay(200)
                assertTrue("Restore immediately restores the candidate",getCandidates(0,64).any { it.text=="验收词甲" })
            }
            WordLibrary.restore(connection,second,true)
            assertTrue(WordLibrary.words(connection).any { it.text=="验收词乙" })
            connection.runOnReady {
                reset();"yanshoucijia".forEach { sendKey(it) };delay(200)
                assertFalse("Merge immediately applies restored candidate preferences",getCandidates(0,64).any { it.text=="验收词甲" })
            }
            val zipped=ByteArrayOutputStream().also { WordLibrary.export(first,it) }.toByteArray()
            val imported=WordLibrary.import(ByteArrayInputStream(zipped))
            assertEquals(first.count,imported.count)
    }

    @Test fun batchWordManagementPreservesUnselectedRules()=withWordFixture { connection ->
        WordLibrary.add(connection,"rime_ice","验收词乙","yan shou ci yi",1000)
        val words=WordLibrary.words(connection)
        val first=words.first { it.text=="验收词甲" }
        val second=words.first { it.text=="验收词乙" }
        val lowered=WordLibrary.applyWordActions(connection,listOf(first,second,first),WordLibrary.WordAction.LOWER)
        assertTrue(lowered.failed.isEmpty())
        assertEquals(2,lowered.succeeded.size)
        assertTrue(WordLibrary.rules().any { it.text==first.text && it.mode=="lower" })
        assertTrue(WordLibrary.rules().any { it.text==second.text && it.mode=="lower" })
        val forgotten=WordLibrary.applyWordActions(connection,listOf(first),WordLibrary.WordAction.FORGET)
        assertTrue(forgotten.failed.isEmpty())
        connection.runOnReady {
            reset();"yanshoucijia".forEach { sendKey(it) };delay(200)
            assertFalse(getCandidates(0,64).any { it.text==first.text })
        }
        val selectedRules=WordLibrary.rules().filter { it.text==first.text }
        assertTrue(selectedRules.isNotEmpty())
        assertTrue(selectedRules.all { it.mode=="hide" })
        val restored=WordLibrary.restoreRules(connection,selectedRules)
        assertTrue(restored.failed.isEmpty())
        assertFalse(WordLibrary.rules().any { it.text==first.text })
        assertTrue(WordLibrary.rules().any { it.text==second.text && it.mode=="lower" })
    }

    @Test fun chineseCandidateActionsPreserveSelection()=withWordFixture { connection ->
        connection.runOnReady {
                reset();"yanshoucijia".forEach { sendKey(it) };delay(200)
                val original=getCandidates(0,64)
                val index=original.indexOfFirst { it.text=="验收词甲" };assertTrue(index>=0)
                assertTrue(getCandidateActions(index).any { it.id == -1002 })
                val pending=inputPanelCached.preedit.toString()
                WordLibrary.setRule(connection,WordLibrary.Word("rime_ice","验收词甲","yan shou ci jia","1000"),"lower")
                refreshCandidatePreferences()
                val lowered=getCandidates(0,64)
                assertEquals("Editing preferences does not reset the active composition",pending,inputPanelCached.preedit.toString())
                assertTrue(lowered.indexOfFirst { it.text=="验收词甲" }>0)
                val expected=lowered.first().text
                data class Selection(val commits:List<String>,val preedit:String,val clientPreedit:String)
                suspend fun selectFirst(space:Boolean):Selection {
                    val commits=java.util.concurrent.CopyOnWriteArrayList<String>()
                    val updatedPreedit=CompletableDeferred<String>()
                    val listener:(FcitxEvent<*>)->Unit = { event ->
                        if(event is FcitxEvent.CommitStringEvent) commits+=event.data.text
                        if(event is FcitxEvent.InputPanelEvent) updatedPreedit.complete(event.data.preedit.toString())
                    }
                    addInputEventListener(listener)
                    try {
                        if(space) { sendKey(' ');sendKey(' ',up=true) }
                        else assertTrue(select(0))
                        // Candidate selection queues the panel update in the native event loop.
                        // Its JNI return only confirms selection, not delivery of that update.
                        val preedit=withTimeout(5000) { updatedPreedit.await() }
                        return Selection(commits.toList(),preedit,clientPreeditCached.toString())
                    } finally { removeInputEventListener(listener) }
                }
                val clicked=selectFirst(false)
                assertTrue("Expected selection of '$expected', received $clicked",
                    clicked.commits.joinToString("").startsWith(expected) || clicked.preedit.startsWith(expected) || clicked.clientPreedit.startsWith(expected))
                reset();"yanshoucijia".forEach { sendKey(it) };delay(200)
                assertEquals(expected,getCandidates(0,1).first().text)
                assertEquals("Space and a tap select the same displayed word, including partial composition",clicked,selectFirst(true))
                reset();"nihao".forEach { sendKey(it) };delay(200)
                val complete=getCandidates(0,1).first().text
                val finished=selectFirst(true)
                assertEquals("A full-length choice commits immediately",listOf(complete),finished.commits)
                reset();"yanshoucijia".forEach { sendKey(it) };delay(200)
                val victim=getCandidates(0,64).indexOfFirst { it.text=="验收词甲" };assertTrue(victim>=0)
                triggerCandidateAction(victim,-1001);delay(200)
                assertFalse(getCandidates(0,64).any { it.text=="验收词甲" })
                reset()
        }
    }

    @Test fun japanesePriorityPreservesConversion()=withWordFixture { connection ->
        connection.runOnReady {
                activateIme("anthy")
                "nihongo".forEach { sendKey(it);sendKey(it,up=true) };delay(200)
                assertTrue("Japanese candidates appear while typing",getCandidates(0,64).isNotEmpty())
                // Learned predictions may contain only one word. Conversion supplies
                // the dictionary alternatives needed to verify priority changes.
                sendKey(' ');sendKey(' ',up=true);delay(200)
                val japanese=getCandidates(0,64)
                assertTrue("Japanese conversion offers alternatives: $japanese",japanese.size>1)
                val word=japanese.first().text
                triggerCandidateAction(0,-1002);delay(200)
                assertNotEquals(word,getCandidates(0,64).first().text)
                val before=clientPreeditCached.toString()+inputPanelCached.preedit.toString()
                sendKey(' ');sendKey(' ',up=true);delay(200)
                assertTrue("Japanese space keeps its conversion candidates visible",getCandidates(0,64).isNotEmpty())
                assertNotEquals(word,getCandidates(0,64).first().text)
                val after=clientPreeditCached.toString()+inputPanelCached.preedit.toString()
                assertNotEquals("Space advances an existing conversion",before,after)
                assertTrue(WordLibrary.rules().any { it.engine=="anthy" && it.reading=="にほんご" && it.text==word })
        }
    }

    @Test fun candidateActionsRejectAnOldListAndKeepTheCurrentComposition()=withWordFixture { connection ->
        connection.runOnReady {
            reset();"nihao".forEach { sendKey(it) };delay(200)
            val old=getCandidates(0,1).first()
            assertTrue(getCandidateActions(0,old).isNotEmpty())
            sendKey('m');delay(200)
            val current=getCandidates(0,1).first()
            val pending=inputPanelCached.preedit.toString()
            assertNotEquals(old.revision,current.revision)
            assertTrue(getCandidateActions(0,old).isEmpty())
            assertFalse(select(0,old))
            val rules=WordLibrary.rules()
            triggerCandidateAction(0,-1001,old)
            assertEquals(rules,WordLibrary.rules())
            assertEquals(pending,inputPanelCached.preedit.toString())
            assertTrue(getCandidateActions(0,current).isNotEmpty())
        }
    }

    @Test fun snapshotMergePreservesDynamicWeightsTicksAndDeletionRecords()=withWordFixture { connection ->
        val source=WordLibrary.snapshot(connection,"完整学习记录")
        val root=(context.getExternalFilesDir(null) ?: context.filesDir).resolve("data/input-history")
        val learning=root.resolve("${source.id}/learning/rime_ice.userdb.txt")
        learning.parentFile!!.mkdirs()
        learning.writeText("#@/db_name\trime_ice\n#@/db_type\tuserdb\n#@/tick\t2000000000\n"+
            "he bing yan zheng \t合并验证\tc=7 d=5 t=1999999999\n"+
            "shan chu yan zheng \t删除验证\tc=-6 d=2 t=1999999999\n",Charsets.UTF_8)
        WordLibrary.restore(connection,source,true)
        val merged=WordLibrary.snapshot(connection,"合并后学习记录")
        val content=root.resolve("${merged.id}/learning/rime_ice.userdb.txt").readLines(Charsets.UTF_8)
        for((text,count) in listOf("合并验证" to 7,"删除验证" to -6)) {
            val value=content.first { it.split('\t').getOrNull(1)==text }.split('\t')[2]
            val fields=value.split(' ').filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
            assertEquals(count,fields.getValue("c").toInt())
            assertTrue(fields.getValue("d").toDouble()>0)
            assertEquals(2000000000L,fields.getValue("t").toLong())
        }
        assertTrue(content.contains("#@/tick\t2000000000"))
    }

    @Test fun privateAiDraftsStayOutOfSavedHistory() {
        val previous=PrivacyMode.enabled
        try {
            if(!PrivacyMode.enabled) PrivacyMode.toggle()
            val session=AiStore.current().copy(draft="private draft",messages=listOf(AiStore.Message("user","private question")))
            AiStore.save(session)
            assertFalse(AiStore.all().any { it.id==session.id })
            assertEquals("private draft",AiStore.current().draft)
            PrivacyMode.toggle();AiStore.discardTemporary()
            PrivacyMode.toggle()
            assertNotEquals(session.id,AiStore.current().id)
        } finally { AiStore.discardTemporary();if(PrivacyMode.enabled!=previous) PrivacyMode.toggle() }
    }
}

/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.CheckBox
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import org.fcitx.fcitx5.android.data.WordLibrary
import org.fcitx.fcitx5.android.ui.main.WordLibraryActivity
import org.fcitx.fcitx5.android.ui.main.WordLibraryContents
import org.fcitx.fcitx5.android.ui.main.WordLibrarySource
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xxhdpi", application = FcitxApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WordLibraryActivityTest {
    private fun word(text: String, reading: String, dictionary: String = "rime_ice") =
        WordLibrary.Word(dictionary, text, reading, "c=13 d=0.4 t=900")

    private class Source(words: List<WordLibrary.Word>, rules: List<WordLibrary.Rule> = emptyList()) : WordLibrarySource {
        @Volatile var contents = WordLibraryContents(words, rules)
        val wordCalls = CopyOnWriteArrayList<Pair<List<WordLibrary.Word>, WordLibrary.WordAction>>()
        val restoreCalls = CopyOnWriteArrayList<List<WordLibrary.Rule>>()
        @Volatile var failWords: Set<WordLibrary.Word> = emptySet()
        @Volatile var failRules: Set<WordLibrary.Rule> = emptySet()
        @Volatile var loadFailure: RuntimeException? = null
        @Volatile var cancelled = false
        @Volatile var wordGate: CompletableDeferred<Unit>? = null
        override suspend fun load(): WordLibraryContents {
            loadFailure?.let { throw it }
            return contents
        }
        override suspend fun applyWordActions(words: List<WordLibrary.Word>, action: WordLibrary.WordAction): WordLibrary.BatchResult<WordLibrary.Word> {
            wordCalls += words to action
            try { wordGate?.await() } catch (error: CancellationException) { cancelled = true; throw error }
            return WordLibrary.BatchResult(words.filterNot { it in failWords },
                words.filter { it in failWords }.map { WordLibrary.BatchFailure(it, "测试中的单条失败") })
        }
        override suspend fun restoreRules(rules: List<WordLibrary.Rule>): WordLibrary.BatchResult<WordLibrary.Rule> {
            restoreCalls += rules
            val succeeded = rules.filterNot { it in failRules }
            contents = contents.copy(rules = contents.rules - succeeded.toSet())
            return WordLibrary.BatchResult(succeeded, rules.filter { it in failRules }.map {
                WordLibrary.BatchFailure(it, "测试中的恢复失败")
            })
        }
    }

    private inner class Screen(source: Source) : AutoCloseable {
        val controller: ActivityController<WordLibraryActivity> = Robolectric.buildActivity(WordLibraryActivity::class.java)
        val activity: WordLibraryActivity get() = controller.get()
        val root: View get() = activity.window.decorView
        val list: RecyclerView get() = tagged("library.list")
        init {
            activity.setTheme(R.style.Theme_FcitxAppTheme)
            activity.librarySource = source
            controller.create().start().resume().visible()
            settle()
        }
        fun <T : View> tagged(tag: String): T = root.findViewWithTag<T>(tag)
            ?: throw AssertionError("Missing view tag: $tag")
        fun layout() {
            root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 1080, 2400)
        }
        fun settle() {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            layout()
        }
        fun await(message: String, condition: () -> Boolean) {
            val deadline = System.nanoTime() + 5_000_000_000L
            while (System.nanoTime() < deadline) {
                settle()
                if (condition()) return
                Thread.sleep(10)
            }
            assertTrue(message, condition())
        }
        fun awaitRows(count: Int) = await("Expected $count result rows") { list.adapter?.itemCount == count }
        fun awaitFirstRow(count: Int, expected: String) = await("Expected $count rows beginning with $expected") {
            list.adapter?.itemCount == count && list.findViewHolderForAdapterPosition(0)?.itemView?.let { text(it).contains(expected) } == true
        }
        fun search(value: String) { tagged<EditText>("library.search").setText(value) }
        fun click(tag: String) {
            val view = tagged<View>(tag)
            assertTrue("Control is enabled: $tag", view.isEnabled)
            view.performClick(); settle()
        }
        fun row(position: Int): View {
            list.scrollToPosition(position); settle()
            return list.findViewHolderForAdapterPosition(position)?.itemView
                ?: throw AssertionError("No attached row at $position")
        }
        fun capture(name: String) {
            settle()
            allViews(root).forEach { it.jumpDrawablesToCurrentState() }
            val bitmap = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            assertTrue("Rendered UI contains controls and text", pixels.distinct().size > 20)
            val directory = File("build/reports/word-library-previews").apply { mkdirs() }
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        override fun close() { controller.pause().stop().destroy(); settle() }
    }

    private fun allViews(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { allViews(view.getChildAt(it)) } else emptyList()

    private fun text(view: View): String = when (view) {
        is ViewGroup -> (0 until view.childCount).joinToString("\n") { text(view.getChildAt(it)) }
        is TextView -> view.text.toString()
        else -> ""
    }
    private fun confirm() {
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertTrue("Confirmation is visible", dialog.isShowing)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
    }
    private fun chooseDialogItem(index: Int) {
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        val list = dialog.listView
        list.performItemClick(list.getChildAt(index), index, list.adapter.getItemId(index))
    }
    @Test fun searchAndGroupedDetailsUseTheRealControlsAndRetainEveryStoredReading() {
        val first = word("重庆", "chong qing")
        val second = word("重庆", "zhong qing", "custom_dictionary")
        Screen(Source(listOf(first, second, word("女儿", "nv er")))).use { screen ->
            screen.awaitRows(2)
            assertTrue(text(screen.row(0)).contains("重庆"))
            screen.row(0).performClick()
            chooseDialogItem(2)
            screen.settle()
            val details = ShadowDialog.getLatestDialog()
            assertTrue(details.isShowing)
            val detailText = text(details.window!!.decorView)
            assertTrue("All original records appear in details: $detailText", detailText.contains("chong qing"))
            assertTrue(detailText.contains("zhong qing"))
            assertTrue(detailText.contains("custom_dictionary"))
            details.dismiss()
            screen.search(" Nǚ Ér ")
            screen.awaitFirstRow(1, "女儿")
            assertTrue(text(screen.row(0)).contains("女儿"))
            screen.search("zhong'qing")
            screen.awaitFirstRow(1, "重庆")
            assertTrue(text(screen.row(0)).contains("重庆"))
            screen.search("")
            screen.awaitRows(2)
            screen.capture("word-library-normal")
        }
    }

    @Test fun selectedHiddenResultsJoinTheBatchAndPartialFailureKeepsOnlyTheFailedOriginalRecord() {
        val first = word("重庆", "chong qing")
        val failed = word("重庆", "zhong qing", "custom_dictionary")
        val next = word("女儿", "nv er")
        val source = Source(listOf(word("北京", "bei jing"), first, failed, next)).apply { failWords = setOf(failed) }
        Screen(source).use { screen ->
            screen.awaitRows(3)
            screen.search("重庆")
            screen.awaitFirstRow(1, "重庆")
            screen.click("library.select")
            screen.click("library.selectAll")
            screen.search("女儿")
            screen.awaitFirstRow(1, "女儿")
            assertTrue("The selection summary discloses hidden selections", text(screen.tagged<View>("library.selectionSummary")).contains("2"))
            screen.click("library.selectAll")
            assertTrue("Selected visible row has its checkbox checked", allViews(screen.row(0)).filterIsInstance<CheckBox>().single().isChecked)
            screen.capture("word-library-selection")
            screen.click("library.lower")
            confirm()
            screen.await("Batch call completed") { source.wordCalls.size == 1 }
            assertEquals(WordLibrary.WordAction.LOWER, source.wordCalls.single().second)
            assertEquals(setOf(first, failed, next), source.wordCalls.single().first.toSet())
            screen.await("Only one failed record remains selected") {
                text(screen.tagged<View>("library.selectionSummary")).contains("1 条学习记录")
            }
            ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }?.dismiss()
            screen.search("")
            screen.awaitRows(3)
            screen.await("Batch controls become available") { screen.tagged<View>("library.lower").isEnabled }
            // The retry must target only the failed dictionary/reading, never its grouped sibling.
            source.failWords = emptySet()
            screen.click("library.lower")
            confirm()
            screen.await("Retry call completed") { source.wordCalls.size == 2 }
            assertEquals(listOf(failed), source.wordCalls[1].first)
        }
    }

    @Test fun recycledListReachesPastTwoHundredAndSelectAllIncludesOffscreenFilteredResults() {
        val records = (0 until 650).map { word("批量术语$it", "pi liang shu yu") }
        val source = Source(records)
        Screen(source).use { screen ->
            screen.awaitRows(650)
            assertTrue("RecyclerView recycles rows rather than creating the entire dictionary", screen.list.childCount < 40)
            screen.list.scrollToPosition(600)
            screen.settle()
            assertNotNull(screen.list.findViewHolderForAdapterPosition(600))
            screen.click("library.select")
            screen.click("library.selectAll")
            screen.click("library.lower")
            confirm()
            screen.await("All filtered records reach the batch") { source.wordCalls.isNotEmpty() }
            assertEquals(records.toSet(), source.wordCalls.single().first.toSet())
            assertEquals(650, source.wordCalls.single().first.size)
        }
    }

    @Test fun selectionAndSuccessfulBatchKeepTheCurrentViewport() {
        val records = (0 until 650).map { word("词条$it", "ci tiao") }
        val source = Source(records)
        Screen(source).use { screen ->
            screen.awaitRows(650)
            val manager = screen.list.layoutManager as LinearLayoutManager
            manager.scrollToPositionWithOffset(300, -15)
            screen.settle()
            val position = manager.findFirstVisibleItemPosition()
            val offset = manager.findViewByPosition(position)!!.top
            val selectedRow = screen.list.findViewHolderForAdapterPosition(position)!!.itemView
            assertTrue(selectedRow.performLongClick())
            screen.settle()
            assertEquals(position, manager.findFirstVisibleItemPosition())
            assertEquals(offset, manager.findViewByPosition(position)!!.top)
            screen.click("library.lower")
            confirm()
            screen.await("Batch finished and selection mode closes") {
                source.wordCalls.size == 1 && screen.tagged<View>("library.select").visibility == View.VISIBLE
            }
            assertEquals(position, manager.findFirstVisibleItemPosition())
            assertEquals(offset, manager.findViewByPosition(position)!!.top)
        }
    }

    @Test fun ruleOnlyCandidatesCanBeRestoredWithoutAnyMatchingLearningRows() {
        val hidden = WordLibrary.Rule("anthy", "japanese", "仮名", "hide")
        val failed = WordLibrary.Rule("rime:rime_ice", "predict:你好", "世界", "lower")
        val third = WordLibrary.Rule("rime:other", "nihao", "你好", "hide")
        val source = Source(emptyList(), listOf(hidden, failed, third)).apply { failRules = setOf(failed) }
        Screen(source).use { screen ->
            screen.awaitRows(0)
            screen.tagged<TabLayout>("library.tabs").getTabAt(1)!!.select()
            screen.awaitRows(3)
            screen.capture("word-library-adjusted")
            assertTrue(text(screen.root).contains("日语候选"))
            assertTrue(text(screen.root).contains("联想：你好"))
            screen.click("library.select")
            screen.click("library.selectAll")
            screen.click("library.restore")
            assertTrue(text(ShadowDialog.getLatestDialog().window!!.decorView).contains("anthy"))
            confirm()
            screen.awaitRows(1)
            assertEquals(listOf(hidden, failed, third).toSet(), source.restoreCalls.single().toSet())
            assertTrue(source.wordCalls.isEmpty())
            assertTrue(text(screen.row(0)).contains("世界"))
            ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }?.dismiss()
            screen.tagged<TabLayout>("library.tabs").getTabAt(0)!!.select()
            screen.awaitRows(0)
            assertEquals(View.VISIBLE, screen.tagged<View>("library.select").visibility)
            screen.tagged<TabLayout>("library.tabs").getTabAt(1)!!.select()
            screen.awaitRows(1)
            assertEquals(View.VISIBLE, screen.tagged<View>("library.select").visibility)
        }
    }

    @Test fun sortAndFilterDialogsChangeTheActualListAndCanBeCleared() {
        val records = listOf(word("一", "a"), word("长长的术语", "z"),
            word("不同的读音", "b"), word("不同的读音", "c", "other"))
        Screen(Source(records)).use { screen ->
            screen.awaitRows(3)
            assertTrue(text(screen.row(0)).contains("一"))
            screen.click("library.sort")
            chooseDialogItem(1)
            screen.await("Length sort reaches the list") { text(screen.row(0)).contains("不同的读音") }
            screen.click("library.filters")
            chooseDialogItem(0)
            chooseDialogItem(1)
            confirm()
            screen.awaitRows(1)
            assertTrue(text(screen.row(0)).contains("不同的读音"))
            screen.click("library.filters")
            (ShadowDialog.getLatestDialog() as AlertDialog).getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
            screen.awaitRows(3)
            screen.click("library.sort")
            chooseDialogItem(0)
            screen.await("Pinyin sort reaches the list") { text(screen.row(0)).contains("一") }
        }
    }

    @Test fun cancellationDoesNotSubmitAndBusyControlsPreventRepeatedBatchActions() {
        val source = Source(listOf(word("你好", "ni hao"))).apply { wordGate = CompletableDeferred() }
        Screen(source).use { screen ->
            screen.awaitRows(1)
            screen.row(0).performLongClick()
            screen.click("library.lower")
            (ShadowDialog.getLatestDialog() as AlertDialog).getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            assertTrue(source.wordCalls.isEmpty())
            screen.click("library.lower")
            confirm()
            screen.await("Batch is waiting for its result") { source.wordCalls.size == 1 }
            assertFalse(screen.tagged<View>("library.lower").isEnabled)
            assertFalse(screen.tagged<View>("library.search").isEnabled)
            assertFalse(screen.tagged<View>("library.history").isEnabled)
            screen.tagged<View>("library.lower").performClick()
            assertEquals(1, source.wordCalls.size)
            source.wordGate!!.complete(Unit)
            screen.await("Successful batch exits selection") {
                screen.tagged<View>("library.select").visibility == View.VISIBLE
            }
        }
    }

    @Test fun failedBatchAndFailedReloadReportBothProblemsAndKeepRetrySelection() {
        val failed = word("女儿", "nv er")
        val source = Source(listOf(word("你好", "ni hao"), failed)).apply { failWords = setOf(failed) }
        Screen(source).use { screen ->
            screen.awaitRows(2)
            screen.click("library.select")
            screen.click("library.selectAll")
            source.loadFailure = IllegalStateException("测试中的列表读取失败")
            screen.click("library.lower")
            confirm()
            screen.await("Both partial failure and reload error are reported") {
                val dialog = ShadowDialog.getLatestDialog()
                dialog != null && dialog.isShowing && text(dialog.window!!.decorView).let {
                    it.contains("测试中的单条失败") && it.contains("测试中的列表读取失败")
                }
            }
            assertEquals(1, source.wordCalls.size)
            assertTrue(text(screen.tagged<View>("library.selectionSummary")).contains("1 条学习记录"))
            assertEquals(2, screen.list.adapter!!.itemCount)
        }
    }

    @Test fun selectAllCannotUseStaleResultsWhileANewerSearchIsPending() {
        val old = word("重庆", "chong qing")
        val next = word("女儿", "nv er")
        val source = Source(listOf(old, next))
        Screen(source).use { screen ->
            screen.awaitRows(2)
            screen.click("library.select")
            screen.search("女儿")
            assertFalse("Select all is disabled until the displayed results match the new query",
                screen.tagged<View>("library.selectAll").isEnabled)
            screen.tagged<View>("library.selectAll").performClick()
            screen.awaitFirstRow(1, "女儿")
            assertTrue(text(screen.tagged<View>("library.selectionSummary")).contains("0 条学习记录"))
            screen.click("library.selectAll")
            screen.click("library.lower")
            confirm()
            screen.await("Only the new query result is submitted") { source.wordCalls.size == 1 }
            assertEquals(listOf(next), source.wordCalls.single().first)
        }
    }

    @Test fun adjustedStatusControlsFilterRulesAndRetainSelectedHiddenResults() {
        val hidden = WordLibrary.Rule("anthy", "japanese", "仮名", "hide")
        val lowered = WordLibrary.Rule("rime:rime_ice", "predict:你好", "世界", "lower")
        val third = WordLibrary.Rule("rime:other", "nihao", "你好", "hide")
        Screen(Source(emptyList(), listOf(hidden, lowered, third))).use { screen ->
            screen.tagged<TabLayout>("library.tabs").getTabAt(1)!!.select()
            screen.awaitRows(3)
            screen.click("library.ruleStatus.lowered")
            screen.awaitFirstRow(1, "世界")
            screen.click("library.select")
            screen.click("library.selectAll")
            screen.click("library.ruleStatus.forgotten")
            screen.awaitRows(2)
            assertTrue(text(screen.tagged<View>("library.selectionSummary")).contains("1 条不在当前结果"))
            screen.click("library.selectAll")
            assertTrue(text(screen.tagged<View>("library.selectionSummary")).contains("3 条候选调整"))
            screen.click("library.ruleStatus.all")
            screen.awaitRows(3)
            screen.click("library.cancelSelection")
            assertEquals(View.VISIBLE, screen.tagged<View>("library.select").visibility)
        }
    }

    @Test fun singleRecordKeepsInternalLearningInDetailsAndLowersWithoutASecondDialog() {
        val record = word("你好", "ni hao", "custom_dictionary")
        val source = Source(listOf(record))
        Screen(source).use { screen ->
            screen.awaitRows(1)
            val rowText = text(screen.row(0))
            assertFalse(rowText.contains(record.weight))
            assertFalse(rowText.contains(record.dictionary))
            screen.row(0).performClick()
            chooseDialogItem(2)
            screen.settle()
            val info = ShadowDialog.getLatestDialog()
            val details = text(info.window!!.decorView)
            assertTrue(details.contains(record.dictionary))
            assertTrue(details.contains(record.weight))
            info.dismiss()
            screen.row(0).performClick()
            chooseDialogItem(1)
            screen.await("Single lower executes directly") { source.wordCalls.size == 1 }
            assertEquals(listOf(record), source.wordCalls.single().first)
            assertEquals(WordLibrary.WordAction.LOWER, source.wordCalls.single().second)
            assertFalse("No second confirmation is needed for a single lower action",
                ShadowDialog.getLatestDialog().isShowing)
        }
    }

    @Test fun backExitsNormalSelectionAndClosesAnInterruptedBatch() {
        val source = Source(listOf(word("你好", "ni hao"))).apply { wordGate = CompletableDeferred() }
        Screen(source).use { screen ->
            screen.awaitRows(1)
            screen.row(0).performLongClick()
            screen.activity.onBackPressedDispatcher.onBackPressed()
            screen.settle()
            assertFalse("Back first exits ordinary selection", screen.activity.isFinishing)
            assertEquals(View.VISIBLE, screen.tagged<View>("library.select").visibility)
            screen.row(0).performLongClick()
            screen.click("library.lower")
            confirm()
            screen.await("Batch waits for completion") { source.wordCalls.size == 1 }
            screen.activity.onBackPressedDispatcher.onBackPressed()
            screen.settle()
            assertTrue("Back can close the screen while a batch is pending", screen.activity.isFinishing)
        }
        assertTrue("Closing cancels the owning batch", source.cancelled)
        assertEquals(1, source.wordCalls.size)
    }

    @Test fun captureDenseDictionaryBrowsingWithRealRecycledRows() {
        val terms = listOf("阿里云" to "a li yun", "安装包" to "an zhuang bao", "备份" to "bei fen",
            "测试用例" to "ce shi yong li", "重庆" to "chong qing", "词库管理" to "ci ku guan li",
            "大模型" to "da mo xing", "电子邮箱" to "dian zi you xiang", "多线程" to "duo xian cheng",
            "翻译" to "fan yi", "工作计划" to "gong zuo ji hua", "候选词" to "hou xuan ci",
            "计算机" to "ji suan ji", "快捷键" to "kuai jie jian", "历史记录" to "li shi ji lu",
            "女儿" to "nv er", "拼音" to "pin yin", "权限管理" to "quan xian guan li",
            "软件更新" to "ruan jian geng xin", "输入法" to "shu ru fa", "同步" to "tong bu",
            "文本编辑" to "wen ben bian ji", "项目" to "xiang mu", "用户词典" to "yong hu ci dian")
        val records = terms.map { (text, reading) -> word(text, reading) } + word("重庆", "zhong qing", "other")
        Screen(Source(records)).use { screen ->
            screen.awaitRows(24)
            assertTrue("Several real dictionary rows fit on screen", screen.list.childCount >= 5)
            assertTrue("Rows beyond the viewport are recycled", screen.list.childCount < 24)
            screen.capture("word-library-dense")
        }
    }

}

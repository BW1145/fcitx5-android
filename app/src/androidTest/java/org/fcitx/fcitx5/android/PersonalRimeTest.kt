/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android

import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.testing.TestLifecycleOwner
import android.os.SystemClock
import android.text.Selection
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.widget.EditText
import android.widget.TextView
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.fcitx.fcitx5.android.core.Fcitx
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.core.FormattedText
import org.fcitx.fcitx5.android.core.KeyState
import org.fcitx.fcitx5.android.data.SavedContentStore
import org.fcitx.fcitx5.android.data.UserDataManager
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.editing.BulkDeletion
import org.fcitx.fcitx5.android.input.preedit.PreeditUi
import org.fcitx.fcitx5.android.input.keyboard.CustomGestureView
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.TextKeyboard
import org.fcitx.fcitx5.android.input.keyboard.AltTextKeyView
import org.fcitx.fcitx5.android.input.keyboard.AlphabetKey
import org.fcitx.fcitx5.android.input.keyboard.TextKeyView
import org.fcitx.fcitx5.android.input.keyboard.SymbolKey
import org.fcitx.fcitx5.android.input.keyboard.LayoutSwitchKey
import org.fcitx.fcitx5.android.input.keyboard.ImageTextKeyView
import org.fcitx.fcitx5.android.input.keyboard.CommaKey
import org.fcitx.fcitx5.android.input.keyboard.KeyDef
import org.fcitx.fcitx5.android.input.keyboard.ImageKeyView
import org.fcitx.fcitx5.android.input.keyboard.BackspaceKey
import org.fcitx.fcitx5.android.input.keyboard.ReturnKey
import org.fcitx.fcitx5.android.input.keyboard.SpaceKey
import java.io.File
import java.util.zip.ZipInputStream
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalRimeTest {
    @Test
    fun keyContentSizeScalesTextAndIconsAcrossOrientations() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val preference = AppPrefs.getInstance().keyboard.keyContentSize
        val original = preference.getValue()
        instrumentation.runOnMainSync {
            try {
                for (orientation in listOf(android.content.res.Configuration.ORIENTATION_PORTRAIT,
                    android.content.res.Configuration.ORIENTATION_LANDSCAPE)) {
                    val configuration = android.content.res.Configuration(instrumentation.targetContext.resources.configuration)
                    configuration.orientation = orientation
                    val context = instrumentation.targetContext.createConfigurationContext(configuration)
                    val theme = ThemeManager.DefaultTheme
                    val width = (context.resources.displayMetrics.density * 48).toInt()
                    val height = (context.resources.displayMetrics.density *
                        if (orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT) 64 else 44).toInt()
                    val views = listOf(
                        AltTextKeyView(context, theme,
                            AlphabetKey("a", "@").appearance as KeyDef.Appearance.AltText),
                        TextKeyView(context, theme,
                            SymbolKey(".").appearance as KeyDef.Appearance.Text),
                        TextKeyView(context, theme,
                            LayoutSwitchKey("?123").appearance as KeyDef.Appearance.Text),
                        TextKeyView(context, theme,
                            SpaceKey().appearance as KeyDef.Appearance.Text).apply { mainText.text = "雾凇" },
                        ImageTextKeyView(context, theme,
                            CommaKey(0.1f, KeyDef.Appearance.Variant.Normal).appearance as KeyDef.Appearance.ImageText),
                        ImageKeyView(context, theme,
                            BackspaceKey().appearance as KeyDef.Appearance.Image),
                        ImageKeyView(context, theme,
                            ReturnKey().appearance as KeyDef.Appearance.Image)
                    )
                    for (view in views) {
                        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                        view.layout(0, 0, width, height)
                        // Render content on transparency so antialiased edges contribute coverage.
                        view.getChildAt(0).apply { background = null; foreground = null }
                        fun inkCoverage(size: Int): Int {
                            preference.setValue(size)
                            val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
                            view.draw(android.graphics.Canvas(bitmap))
                            val pixels = IntArray(width * height)
                            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
                            bitmap.recycle()
                            return pixels.sumOf { android.graphics.Color.alpha(it) }
                        }
                        val small = inkCoverage(16)
                        val large = inkCoverage(38)
                        assertTrue("${view.javaClass.simpleName} must enlarge in orientation $orientation ($small -> $large)", large > small && small > 0)
                        assertEquals(width, view.width)
                        assertEquals(height, view.height)
                    }
                }
            } finally {
                preference.setValue(original)
            }
        }
    }

    private class EngineFixture(val scope:kotlinx.coroutines.CoroutineScope) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fcitx = Fcitx(context)

        suspend fun start() {
            val ready = scope.async(start = CoroutineStart.UNDISPATCHED) {
                fcitx.eventFlow.filterIsInstance<FcitxEvent.ReadyEvent>().first()
            }
            fcitx.start()
            withTimeout(30_000) { ready.await() }
            fcitx.activate(android.os.Process.myUid(), context.packageName)
            fcitx.focus()
        }

        suspend fun candidates(input: String, predicate: (List<String>) -> Boolean) {
            withTimeout(180_000) {
                while (true) {
                    fcitx.reset()
                    input.forEach { fcitx.sendKey(it) }
                    delay(200)
                    val words = fcitx.getCandidates(0, 10).map { it.text }
                    if (predicate(words)) break
                    delay(500)
                }
            }
            fcitx.reset()
        }

    }

    private fun withEngine(block:suspend EngineFixture.()->Unit)=runBlocking {
        val fixture=EngineFixture(this)
        fixture.start()
        try { fixture.block() } finally { fixture.fcitx.stop() }
    }

    @Test
    fun freshInstallProvidesOfflinePinyin()=withEngine {
            assertTrue(fcitx.availableIme().any { it.uniqueName == "rime" })
            assertTrue(fcitx.availableIme().any { it.uniqueName == "anthy" })
            assertEquals(listOf("keyboard-us", "rime", "anthy"), fcitx.enabledIme().map { it.uniqueName })
            assertEquals("rime", fcitx.currentIme().uniqueName)
            assertFalse(AppPrefs.getInstance().clipboard.clipboardListening.getValue())
            assertTrue("Toolbar defaults to expanded", AppPrefs.getInstance().keyboard.expandToolbarByDefault.getValue())
            assertTrue("An unused editor has no starter prediction candidates", fcitx.getCandidates(0, 10).isEmpty())
            candidates("nihao") { "你好" in it }
            candidates("shuawomenwan") { "耍我们玩" in it }
            candidates("mintian") { "明天" in it }
            candidates("ningtian") { "明天" in it }
    }

    @Test
    fun modelToggleChangesCandidatesAndPersists()=withEngine {
            fun allActions(actions:Array<org.fcitx.fcitx5.android.core.Action>):List<org.fcitx.fcitx5.android.core.Action> = actions.flatMap { listOf(it)+allActions(it.menu ?: emptyArray()) }
        try {
            fcitx.activateIme("rime");fcitx.reset()
            candidates("nihao") { "你好" in it }
            val modelToggle=allActions(fcitx.statusArea()).firstOrNull { it.shortText.startsWith("模型增强") }
            assertTrue("Octagram toggle must be exposed in Rime options",modelToggle!=null)
            "biezaishuawomenwan".forEach { fcitx.sendKey(it) }
            val enhancedWords = fcitx.getCandidates(0, 10).map { it.text }
            fcitx.activateAction(modelToggle!!.id)
            assertTrue("Model can be switched off",allActions(fcitx.statusArea()).any { it.shortText.startsWith("模型关闭") })
            val ordinaryWords = fcitx.getCandidates(0, 10).map { it.text }
            assertTrue("Model toggle must immediately change sentence candidates: $enhancedWords -> $ordinaryWords",
                enhancedWords.isNotEmpty() && ordinaryWords.isNotEmpty() && enhancedWords != ordinaryWords)
            fcitx.reset()
            fcitx.focus(false);fcitx.focus()
            assertTrue("Model toggle must survive focus changes",allActions(fcitx.statusArea()).any { it.shortText.startsWith("模型关闭") })
            fcitx.activateAction(allActions(fcitx.statusArea()).first { it.shortText.startsWith("模型关闭") }.id)
            assertTrue("Model can be switched back on",allActions(fcitx.statusArea()).any { it.shortText.startsWith("模型增强") })
            "biezaishuawomenwan".forEach { fcitx.sendKey(it) }
            assertEquals("Re-enabling the model restores enhanced sentence candidates",
                enhancedWords, fcitx.getCandidates(0, 10).map { it.text })
            fcitx.reset()
        } finally {
            fcitx.reset()
            allActions(fcitx.statusArea()).firstOrNull { it.shortText.startsWith("模型关闭") }?.let { fcitx.activateAction(it.id) }
        }
    }

    @Test
    fun predictionToggleAndBackspaceWorkImmediately()=withEngine {
            fun allActions(actions:Array<org.fcitx.fcitx5.android.core.Action>):List<org.fcitx.fcitx5.android.core.Action> = actions.flatMap { listOf(it)+allActions(it.menu ?: emptyArray()) }
        try {
            fcitx.activateIme("rime");fcitx.reset()
            candidates("nihao") { "你好" in it }
            "nihao".forEach { fcitx.sendKey(it) }
            val hello=fcitx.getCandidates(0,10).indexOfFirst { it.text=="你好" }
            assertTrue(hello>=0)
            fcitx.select(hello)
            val nextWords=fcitx.getCandidates(0,10).map { it.text }
            assertTrue("Predict should offer next-word candidates after committing a word",nextWords.isNotEmpty())
            assertTrue("Predict should follow simplified Chinese mode: $nextWords","吗" in nextWords && "嗎" !in nextWords)
            fcitx.activateAction(allActions(fcitx.statusArea()).first { it.shortText.startsWith("联想开启") }.id)
            assertTrue("Disabling prediction removes displayed next-word candidates immediately",
                fcitx.getCandidates(0, 10).isEmpty())
            fcitx.reset()
            fcitx.focus(false); fcitx.focus()
            assertTrue("Prediction remains disabled after focus changes",
                allActions(fcitx.statusArea()).any { it.shortText.startsWith("联想关闭") })
            "nihao".forEach { fcitx.sendKey(it) }
            fcitx.select(fcitx.getCandidates(0, 10).indexOfFirst { it.text == "你好" })
            assertTrue("Committing Chinese while prediction is disabled produces no next-word candidates",
                fcitx.getCandidates(0, 10).isEmpty())
            fcitx.activateAction(allActions(fcitx.statusArea()).first { it.shortText.startsWith("联想关闭") }.id)
            assertTrue("Re-enabling prediction immediately restores next-word candidates",
                fcitx.getCandidates(0, 10).isNotEmpty())
            val forwardedDelete = scope.async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(5_000) {
                    fcitx.eventFlow.filterIsInstance<FcitxEvent.KeyEvent>().first {
                        it.data.sym.sym == FcitxKeyMapping.FcitxKey_BackSpace
                    }
                }
            }
            fcitx.sendKey(FcitxKeyMapping.FcitxKey_BackSpace)
            forwardedDelete.await()
            assertTrue("Backspace clears prediction candidates while forwarding deletion to the editor",
                fcitx.getCandidates(0, 10).isEmpty())
            fcitx.reset()

        } finally {
            fcitx.reset()
            allActions(fcitx.statusArea()).firstOrNull { it.shortText.startsWith("联想关闭") }?.let { fcitx.activateAction(it.id) }
        }
    }

    @Test
    fun preeditEditingAndPagingWork()=withEngine {
            fcitx.activateIme("rime");fcitx.reset()
            candidates("rq") { words -> words.any { it.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) } }

            "niho".forEach { fcitx.sendKey(it) }
            val oldCandidate = fcitx.getCandidates(0, 1).first()
            val preedit = fcitx.inputPanelCached.preedit
            val insertion = preedit.toString().lastIndexOf('o')
            assertTrue("Expected pinyin preedit: $preedit", insertion >= 0)
            // Real-Rime regression: at raw2 the suffix is unformatted "ho".
            // The second tap's cp3 belongs to OLD "niho", not NEW "ni ho".
            fcitx.movePreeditCursor(2, preedit.toString())
            val firstTap = fcitx.inputPanelCached.preedit
            assertEquals("niho", firstTap.toString())
            assertEquals(2, firstTap.cursor)
            fcitx.movePreeditCursor(3, firstTap.toString())
            assertEquals(insertion, fcitx.inputPanelCached.preedit.cursor)
            fcitx.sendKey('a')
            val edited = fcitx.inputPanelCached.preedit
            assertEquals("nihao", edited.toString().replace(" ", ""))
            assertEquals(edited.toString().lastIndexOf('o'), edited.cursor)
            val editedCandidates = fcitx.getCandidates(0, 10).map { it.text }
            assertTrue("Expected 你好 without moving the edit caret to the end: $editedCandidates", "你好" in editedCandidates)
            assertFalse("An old candidate revision cannot select the new full menu", fcitx.select(0, oldCandidate))
            fcitx.sendKey('a', up = true)
            assertEquals("Key release preserves the visible editing caret", edited.cursor, fcitx.inputPanelCached.preedit.cursor)
            fcitx.sendKey(FcitxKeyMapping.FcitxKey_BackSpace)
            assertEquals("niho", fcitx.inputPanelCached.preedit.toString().replace(" ", ""))
            fcitx.sendKey('a')
            assertEquals("nihao", fcitx.inputPanelCached.preedit.toString().replace(" ", ""))
            val commit = scope.async(start = CoroutineStart.UNDISPATCHED) {
                fcitx.eventFlow.filterIsInstance<FcitxEvent.CommitStringEvent>().first()
            }
            assertTrue(fcitx.select(fcitx.getCandidates(0, 10).indexOfFirst { it.text == "你好" }))
            assertEquals("你好", withTimeout(5_000) { commit.await() }.data.text)
            assertTrue("Full candidate commit leaves no raw suffix", fcitx.inputPanelCached.preedit.isEmpty())
            fcitx.reset()
            "niho".forEach { fcitx.sendKey(it) }
            fcitx.movePreeditCursor(2, fcitx.inputPanelCached.preedit.toString())
            fcitx.sendKey(FcitxKeyMapping.FcitxKey_Shift_L)
            fcitx.sendKey('A', states = KeyState.Shift.state)
            fcitx.sendKey('A', states = KeyState.Shift.state, up = true)
            fcitx.sendKey(FcitxKeyMapping.FcitxKey_Shift_L, up = true)
            assertEquals("niAho", fcitx.inputPanelCached.preedit.toString().replace(" ", ""))
            fcitx.reset()
            "niho".forEach { fcitx.sendKey(it) }
            fcitx.movePreeditCursor(2, fcitx.inputPanelCached.preedit.toString())
            fcitx.sendKey(FcitxKeyMapping.FcitxKey_Control_L)
            fcitx.sendKey(FcitxKeyMapping.FcitxKey_BackSpace, states = KeyState.Ctrl.state)
            fcitx.sendKey(FcitxKeyMapping.FcitxKey_BackSpace, states = KeyState.Ctrl.state, up = true)
            fcitx.sendKey(FcitxKeyMapping.FcitxKey_Control_L, up = true)
            assertEquals("ho", fcitx.inputPanelCached.preedit.toString().replace(" ", ""))
            fcitx.reset()
            "yiduanzidawanlejiu".forEach { fcitx.sendKey(it) }
            val sentenceBaseline = fcitx.getCandidates(0, 10).map { it.text }
            fcitx.reset()
            "yiduanzidaanlejiu".forEach { fcitx.sendKey(it) }
            val sentence = fcitx.inputPanelCached.preedit
            val sentenceInsertion = (0..sentence.length).first { index ->
                sentence.toString().substring(0, index).count { it != ' ' } == 9
            }
            fcitx.movePreeditCursor(sentence.codePointCountUntil(sentenceInsertion), sentence.toString())
            fcitx.sendKey('w')
            val corrected = fcitx.inputPanelCached.preedit
            assertEquals("yiduanzidawanlejiu", corrected.toString().replace(" ", ""))
            assertEquals("yiduanzidaw", corrected.toString().substring(0, corrected.cursor).replace(" ", ""))
            assertEquals("Middle edit must expose the same whole-input candidates as end typing",
                sentenceBaseline, fcitx.getCandidates(0, 10).map { it.text })
            val sentenceCommit = scope.async(start = CoroutineStart.UNDISPATCHED) {
                fcitx.eventFlow.filterIsInstance<FcitxEvent.CommitStringEvent>().first()
            }
            fcitx.sendKey(' ')
            assertEquals("Space selects the displayed whole-input candidate, not the editing prefix",
                sentenceBaseline.first(), withTimeout(5_000) { sentenceCommit.await() }.data.text)
            assertTrue("Space commits the whole sentence without leaving its raw suffix",
                fcitx.inputPanelCached.preedit.isEmpty())
            fcitx.reset()
            "shi".forEach { fcitx.sendKey(it) }
            assertTrue("More candidates must be available beyond the first row", fcitx.getCandidates(16, 16).isNotEmpty())
            fcitx.reset()
    }

    @Test
    fun japaneseTypingAndConversionWork()=withEngine {
            fcitx.activateIme("rime");fcitx.reset()
            fcitx.activateIme("anthy")
            "nihongo".forEach {
                fcitx.sendKey(it)
                fcitx.sendKey(it, up = true)
            }
            assertEquals("にほんご", fcitx.inputPanelCached.preedit.toString())
            assertTrue("Japanese candidates must be visible while typing", fcitx.getCandidates(0, 20).isNotEmpty())
            fcitx.sendKey(' ')
            assertEquals("日本語", fcitx.inputPanelCached.preedit.toString())
            val japaneseCandidates = fcitx.getCandidates(0, 20).map { it.text }
            val japaneseIndex = japaneseCandidates.indexOf("日本語")
            assertTrue("Expected Japanese candidates: $japaneseCandidates", japaneseIndex >= 0)
            val japaneseCommit = scope.async(start = CoroutineStart.UNDISPATCHED) {
                fcitx.eventFlow.filterIsInstance<FcitxEvent.CommitStringEvent>().first()
            }
            assertTrue(fcitx.select(japaneseIndex))
            fcitx.sendKey(FcitxKeyMapping.FcitxKey_Return)
            assertEquals("日本語", withTimeout(5_000) { japaneseCommit.await() }.data.text)
            fcitx.reset()
            fcitx.activateIme("rime")

    }

    @Test
    fun upgradePreservesUserConfig()=withEngine {
            fcitx.activateIme("rime");fcitx.reset()
            val patch = (context.getExternalFilesDir(null) ?: context.filesDir)
                .resolve("data/rime/default.custom.yaml")
            val personalized = patch.readText() + "\n# personal configuration\n"
            patch.writeText(personalized)
            // Simulate upgrading an existing installation with its own Rime patch.
            fcitx.setEnabledIme(arrayOf("keyboard-us", "rime"))
            (context.getExternalFilesDir(null) ?: context.filesDir)
                .resolve("config/personal-anthy-enabled").delete()
            val japaneseConfig = fcitx.getAddonConfig("anthy")["cfg"]
            japaneseConfig["General"]["PredictOnInput"].value = "False"
            japaneseConfig["General"]["NTriggersToShowCandWin"].value = "2"
            fcitx.setAddonConfig("anthy", japaneseConfig)
            (context.getExternalFilesDir(null) ?: context.filesDir)
                .resolve("config/personal-anthy-candidates-v1").delete()
            fcitx.stop()
            start()
            withTimeout(10_000) {
                while (fcitx.enabledIme().none { it.uniqueName == "anthy" } ||
                    fcitx.getAddonConfig("anthy")["cfg"]["General"]["PredictOnInput"].value != "True") delay(50)
            }
            assertEquals("1", fcitx.getAddonConfig("anthy")["cfg"]["General"]["NTriggersToShowCandWin"].value)
            assertEquals(personalized, patch.readText())
            candidates("nihao") { "你好" in it }
    }

    @Test
    fun clipboardLinkCleanupPreservesPayloadAndOriginalContent() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val filter=org.fcitx.fcitx5.android.data.clipboard.ClearUrlsRuleFilter(context.assets.open("clearurls-rules.json").bufferedReader().use { it.readText() })
        val original="https://example.com/page?value=a%26b%3Dc&utm_source=clipboard&utm_medium=share"
        assertEquals("https://example.com/page?value=a%26b%3Dc",filter.transform(original))
        assertEquals("普通文本",filter.transform("普通文本"))
    }

    @Test
    fun bulkDeletionPreservesSuffixAfterRepeatedDeletion() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val editor = EditText(instrumentation.targetContext)
            val text = "前面的文字😀后面保留"
            editor.setText(text)
            val cursor = text.indexOf("后面")
            Selection.setSelection(editor.text, cursor)
            val connection = object : BaseInputConnection(editor, true) {
                override fun getEditable() = editor.text
            }
            val deletion = BulkDeletion()
            connection.deleteSurroundingText(2, 0)
            assertTrue(deletion.clear(connection, cursor - 2))
            assertEquals("后面保留", editor.text.toString())
        }
    }

    @Test
    fun preeditTapMapsToTextWithoutCountingCursorMarker() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val ui = PreeditUi(instrumentation.targetContext, ThemeManager.DefaultTheme)
            var requested = -1
            var requestedText = ""
            ui.onCursorRequested = { position, text -> requested = position; requestedText = text }
            ui.update(FcitxEvent.InputPanelEvent.Data(
                preedit = FormattedText(arrayOf("ni hao"), intArrayOf(0), 2),
                auxUp = FormattedText.Empty,
                auxDown = FormattedText.Empty,
                tabs = emptyArray()
            ))
            ui.root.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.AT_MOST))
            ui.root.layout(0, 0, 600, ui.root.measuredHeight)
            val line = (ui.root as android.view.ViewGroup).getChildAt(0) as TextView
            val x = line.layout.getPrimaryHorizontal(5) + line.totalPaddingLeft
            val y = line.height / 2f
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(0, 1, action, x, y, 0)
                line.dispatchTouchEvent(event)
                event.recycle()
            }
            assertEquals("ni hao", requestedText)
            assertEquals(4, requested)
        }
    }

    @Test
    fun savedContentCanBeEditedOrderedAndRestoredFromBackup() {
        val first = SavedContentStore.put(null, "地址", "示例地址\n第二行")
        val second = SavedContentStore.put(null, "回复", "稍后回复")
        val backup = File.createTempFile("saved-content-backup-", ".zip",
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
        try {
            SavedContentStore.put(first.id, "新地址", "修改后的内容")
            SavedContentStore.move(second.id, -1)
            assertEquals(second.id, SavedContentStore.load().first().id)
            backup.outputStream().use { UserDataManager.export(it).getOrThrow() }
            var savedFileFound = false
            ZipInputStream(backup.inputStream()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.name == "external/data/saved-content.json") savedFileFound = true
                }
            }
            assertTrue(savedFileFound)
            SavedContentStore.delete(first.id)
            SavedContentStore.delete(second.id)
            backup.inputStream().use { UserDataManager.import(it).getOrThrow() }
            assertEquals("修改后的内容", SavedContentStore.load().find { it.id == first.id }?.text)
            assertEquals(second.id, SavedContentStore.load().first().id)
        } finally {
            backup.delete()
            SavedContentStore.delete(first.id)
            SavedContentStore.delete(second.id)
        }
    }

    @Test
    fun holdingBackspaceThenSwipingUpClearsOnceAndStopsRepeat() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val actions = java.util.Collections.synchronizedList(mutableListOf<KeyAction>())
        lateinit var key: CustomGestureView
        var downAt = 0L
        fun touch(action: Int, y: Float) {
            val event = MotionEvent.obtain(downAt, SystemClock.uptimeMillis(), action, key.width / 2f, y, 0)
            key.dispatchTouchEvent(event)
            event.recycle()
        }
        instrumentation.runOnMainSync {
            val keyboard = TextKeyboard(instrumentation.targetContext, ThemeManager.DefaultTheme)
            keyboard.setViewTreeLifecycleOwner(TestLifecycleOwner(Lifecycle.State.RESUMED))
            keyboard.keyActionListener = KeyActionListener { action, _ -> actions.add(action) }
            keyboard.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY))
            keyboard.layout(0, 0, 600, 300)
            key = keyboard.findViewById(R.id.button_backspace)
            downAt = SystemClock.uptimeMillis()
            touch(MotionEvent.ACTION_DOWN, key.height / 2f)
        }
        Thread.sleep(CustomGestureView.longPressDelay.toLong() + 150)
        instrumentation.runOnMainSync {
            touch(MotionEvent.ACTION_MOVE, -key.height * 2f)
            touch(MotionEvent.ACTION_UP, -key.height * 2f)
        }
        assertEquals(1, actions.count { it is KeyAction.MacroAction && it.steps.any { step -> step.action == "clear" } })
        val repeats = actions.count { it is KeyAction.SymAction }
        assertTrue("Long press must still repeat ordinary backspace", repeats > 0)
        Thread.sleep(150)
        assertEquals(repeats, actions.count { it is KeyAction.SymAction })
        assertFalse(actions.any { it is KeyAction.DeleteSelectionAction })
    }
}

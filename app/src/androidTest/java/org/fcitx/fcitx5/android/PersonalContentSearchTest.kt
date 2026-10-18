/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.BaseInputConnection
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ActivityTestRule
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.core.FormattedText
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.data.KeyMacros
import org.fcitx.fcitx5.android.data.SavedContentStore
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.clipboard.ContentSearchEditor
import org.fcitx.fcitx5.android.input.keyboard.NumberKeyboard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@Suppress("DEPRECATION")
class PersonalContentSearchTest {
    @get:Rule
    val activityRule = ActivityTestRule(PersonalInputTestActivity::class.java, false, false)
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private val context get() = instrumentation.targetContext

    /** Also run on API 23. This exercises the production editor without starting an engine. */
    @Test
    fun searchEditorCompatibility() {
        instrumentation.runOnMainSync {
            val editor = ContentSearchEditor(context)
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val previousClip = clipboard.primaryClip
            try {
                val backspace = FcitxEvent.KeyEvent(FcitxEvent.KeyEvent.Data(
                    KeySym(FcitxKeyMapping.FcitxKey_BackSpace), KeyStates.Virtual, 0, false, -1))
                editor.commit("a😀b")
                editor.view.setSelection(3)
                assertTrue(editor.handle(backspace))
                assertEquals("Backspace deletes the whole supplementary character", "ab", editor.view.text.toString())
                assertEquals(1, editor.view.selectionStart)

                editor.clear()
                editor.commit("a😀b🦊c")
                editor.view.setSelection(3)
                editor.handle(FcitxEvent.DeleteSurroundingEvent(FcitxEvent.DeleteSurroundingEvent.Data(1, 2)))
                assertEquals("Native lengths count code points on API 23 too", "ac", editor.view.text.toString())
                editor.view.setSelection(0)
                editor.key(KeyEvent.KEYCODE_FORWARD_DEL)
                assertEquals("c", editor.view.text.toString())

                editor.clear()
                editor.commit("local 😀")
                editor.key(KeyEvent.KEYCODE_A, metaState = KeyEvent.META_CTRL_ON)
                editor.handle(FcitxEvent.ClientPreeditEvent(FormattedText.Empty))
                assertEquals("An empty engine reset preserves a local selection", 0, editor.selection.start)
                assertEquals(editor.view.length(), editor.selection.end)
                editor.key(KeyEvent.KEYCODE_C, metaState = KeyEvent.META_CTRL_ON)
                assertEquals("local 😀", clipboard.primaryClip!!.getItemAt(0).coerceToText(context).toString())
                editor.deleteSelection()
                assertEquals("", editor.view.text.toString())
                editor.key(KeyEvent.KEYCODE_V, metaState = KeyEvent.META_CTRL_ON)
                assertEquals("local 😀", editor.view.text.toString())

                editor.clear()
                editor.handle(FcitxEvent.ClientPreeditEvent(FormattedText(arrayOf("nihao"), intArrayOf(0), 2)))
                assertEquals("The local composing cursor follows the engine", 2, editor.view.selectionStart)
                editor.key(KeyEvent.KEYCODE_ENTER)
                assertEquals("nihao", editor.view.text.toString())
                assertEquals(-1, BaseInputConnection.getComposingSpanStart(editor.view.text))
                editor.clear()
                editor.handle(backspace)
                assertEquals("Deleting an empty query stays local", "", editor.view.text.toString())
            } finally {
                clipboard.setPrimaryClip(previousClip ?: ClipData.newPlainText("", ""))
            }
        }
    }

    private fun shell(command: String) {
        automation.executeShellCommand(command).use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        }
    }

    private fun nodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = buildList {
        add(root)
        for (i in 0 until root.childCount) root.getChild(i)?.let { addAll(nodes(it)) }
    }

    private fun imeNodes(): List<AccessibilityNodeInfo> = automation.windows
        .filter { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        .flatMap { it.root?.let(::nodes) ?: emptyList() }

    private fun await(description: String, timeout: Long = 30_000, predicate: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeout
        while (!predicate()) {
            assertTrue("$description. IME text: ${imeNodes().mapNotNull { it.text }}", SystemClock.uptimeMillis() < end)
            SystemClock.sleep(50)
        }
    }

    private fun touch(action: Int, down: Long, x: Float, y: Float) {
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        try { assertTrue(automation.injectInputEvent(event, true)) } finally { event.recycle() }
    }

    private fun tap(node: AccessibilityNodeInfo) {
        val bounds = Rect().also(node::getBoundsInScreen)
        val down = SystemClock.uptimeMillis()
        touch(MotionEvent.ACTION_DOWN, down, bounds.exactCenterX(), bounds.exactCenterY())
        touch(MotionEvent.ACTION_UP, down, bounds.exactCenterX(), bounds.exactCenterY())
        instrumentation.waitForIdleSync()
    }

    private fun key(id: Int) {
        val name = context.resources.getResourceEntryName(id)
        await("Keyboard key $name is visible") { imeNodes().any { it.viewIdResourceName?.endsWith(":id/$name") == true } }
        tap(imeNodes().first { it.viewIdResourceName?.endsWith(":id/$name") == true })
    }

    private fun keyText(text: String) {
        await("Keyboard key $text is visible") { imeNodes().any { it.text?.toString()?.equals(text, true) == true } }
        tap(imeNodes().filter { it.text?.toString()?.equals(text, true) == true }
            .maxBy { Rect().also(it::getBoundsInScreen).centerY() })
    }

    private fun type(text: String) { text.forEach { keyText(it.toString()) } }

    private fun query(): String? = imeNodes().firstOrNull {
        it.className?.toString() == "android.widget.EditText"
    }?.text?.toString()?.let { if (it == context.getString(R.string.content_search)) "" else it }

    private fun hostText(): String {
        var value = ""
        instrumentation.runOnMainSync { value = activityRule.activity.editor.text.toString() }
        return value
    }

    private fun openSearch() {
        val savedLabel = context.getString(R.string.saved_content)
        if (imeNodes().none { it.contentDescription?.toString() == savedLabel }) {
            val expand = context.getString(R.string.expand_toolbar)
            await("Toolbar can be expanded") { imeNodes().any { it.contentDescription?.toString() == expand } }
            tap(imeNodes().first { it.contentDescription?.toString() == expand })
        }
        await("Saved content entry is visible") { imeNodes().any { it.contentDescription?.toString() == savedLabel } }
        tap(imeNodes().first { it.contentDescription?.toString() == savedLabel })
        val search = context.getString(R.string.content_search)
        await("Content search entry is visible") { imeNodes().any { it.text?.toString() == search } }
        tap(imeNodes().first { it.text?.toString() == search })
        await("The local search editor is visible") { query() != null }
    }

    private fun swipeBackspaceLeft() {
        val node = imeNodes().first { it.viewIdResourceName?.endsWith(":id/button_backspace") == true }
        val bounds = Rect().also(node::getBoundsInScreen)
        val x = bounds.exactCenterX()
        val y = bounds.exactCenterY()
        val distance = context.resources.displayMetrics.density * 12f
        val down = SystemClock.uptimeMillis()
        touch(MotionEvent.ACTION_DOWN, down, x, y)
        touch(MotionEvent.ACTION_MOVE, down, x - distance, y)
        touch(MotionEvent.ACTION_UP, down, x - distance, y)
        instrumentation.waitForIdleSync()
    }

    @Test
    fun searchActionsStayInTheirEditorAcrossResetAndClose() = runBlocking {
        val previousMacros = KeyMacros.load()
        val layout = AppPrefs.getInstance().internal.lastSymbolLayout
        val previousLayout = layout.getValue()
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val previousClip = clipboard.primaryClip
        val entry = SavedContentStore.put(null, "Isolated search result", "<picked>")
        val releaseNative = CountDownLatch(1)
        val fcitx = FcitxDaemon.connect(javaClass.name)
        try {
            // Configure real key bindings before launching the view, then restore them below.
            instrumentation.runOnMainSync {
                KeyMacros.save(previousMacros.filterNot { it.trigger == "tap" && it.key in listOf("q", "z") } + listOf(
                    KeyMacros.Binding("q", "tap", listOf(KeyMacros.Step("clear"))),
                    KeyMacros.Binding("z", "tap", listOf(KeyMacros.Step("clear"), KeyMacros.Step("text", "LOCAL"),
                        KeyMacros.Step("selectAll"), KeyMacros.Step("copy"), KeyMacros.Step("clear"),
                        KeyMacros.Step("paste"), KeyMacros.Step("text", "!")))))
                layout.setValue(NumberKeyboard.Name)
            }
            val info = automation.serviceInfo
            info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            automation.serviceInfo = info
            val ime = "${context.packageName}/org.fcitx.fcitx5.android.input.FcitxInputMethodService"
            shell("settings put secure show_ime_with_hard_keyboard 1")
            shell("ime enable $ime")
            shell("ime set $ime")
            activityRule.launchActivity(null)
            await("Keyboard is visible") { imeNodes().any { it.viewIdResourceName?.endsWith(":id/button_space") == true } }
            fcitx.runOnReady { reset(); activateIme("keyboard-us") }
            await("English engine is active") { fcitx.runImmediately { inputMethodEntryCached.uniqueName == "keyboard-us" } }
            val original = "abcdef"
            instrumentation.runOnMainSync {
                activityRule.activity.editor.setText(original)
                activityRule.activity.editor.setSelection(original.length)
            }
            openSearch()
            swipeBackspaceLeft()
            type("abc")
            await("Characters are routed into search after the empty swipe") { query() == "abc" }
            assertEquals("An empty-query Backspace swipe must not delete original text", original, hostText())
            swipeBackspaceLeft()
            await("Backspace swipe deletes the local selection") { query() == "ab" }
            key(R.id.button_return)
            type("c")
            await("Return remains in the local editor") { query() == "abc" }
            assertEquals(original, hostText())

            keyText("z")
            await("Text, selectAll, copy, clear and paste macro steps all execute locally") { query() == "LOCAL!" }
            assertEquals("LOCAL", clipboard.primaryClip!!.getItemAt(0).coerceToText(context).toString())
            assertEquals(original, hostText())

            fcitx.runOnReady { reset(); activateIme("rime") }
            await("Rime Ice is deployed", 180_000) { fcitx.runImmediately { inputMethodEntryCached.subMode.name == "雾凇拼音" } }
            keyText("q")
            await("The clear macro empties search") { query() == "" }
            type("nihao")
            await("Pinyin is pending inside search") {
                fcitx.runImmediately { inputPanelCached.preedit.toString().replace(" ", "") == "nihao" }
            }
            keyText("?123")
            await("Numeric keypad is visible") { imeNodes().any { it.text?.toString() == "ABC" } }
            keyText("2")
            await("A digit follows the committed Chinese composition in search") { query() == "你好2" }
            assertTrue(fcitx.runImmediately { clientPreeditCached.isEmpty() && inputPanelCached.preedit.isEmpty() })
            assertEquals(original, hostText())
            keyText("ABC")
            keyText("q")
            await("Search is clear before another composition") { query() == "" }
            type("nihao")
            await("A second composition is pending") {
                fcitx.runImmediately { inputPanelCached.preedit.toString().replace(" ", "") == "nihao" }
            }
            keyText("q")
            await("Clear resets the engine as well as the query") {
                query() == "" && fcitx.runImmediately { clientPreeditCached.isEmpty() && inputPanelCached.preedit.isEmpty() }
            }
            type("ma")
            await("Typing after clear starts a fresh composition") {
                fcitx.runImmediately { inputPanelCached.preedit.toString().replace(" ", "") == "ma" }
            }
            val candidate = fcitx.runOnReady { getCandidates(0, 1).first().text }
            await("A search candidate is visible") { imeNodes().any { it.text?.toString() == candidate } }
            keyText(candidate)
            await("A candidate commits to the search session") {
                query() == candidate && fcitx.runImmediately { clientPreeditCached.isEmpty() && inputPanelCached.preedit.isEmpty() }
            }
            assertEquals(original, hostText())
            keyText("q")
            await("The saved result is visible") { imeNodes().any { it.text?.toString() == entry.title } }
            tap(imeNodes().first { it.text?.toString() == entry.title })
            val afterResult = original + entry.text
            await("Only the selected result is inserted into the original editor") { hostText() == afterResult }

            fcitx.runOnReady { reset(); activateIme("keyboard-us") }
            openSearch()
            // Hold the real native queue so keys remain pending when Cancel changes the target.
            val nativeEntered = CountDownLatch(1)
            val gate = async(Dispatchers.Default) {
                fcitx.runOnReady { enqueueInputBarrier {
                    nativeEntered.countDown()
                    assertTrue("Native test gate is released", releaseNative.await(30, TimeUnit.SECONDS))
                } }
            }
            assertTrue("Native queue is held", nativeEntered.await(10, TimeUnit.SECONDS))
            try {
                type("abcdefabcdefabcdef")
                val cancel = context.getString(android.R.string.cancel)
                tap(imeNodes().first { it.text?.toString() == cancel })
                type("w")
            } finally {
                releaseNative.countDown()
            }
            gate.await()
            await("Queued search keys cannot leak after Cancel; new app input follows the close barrier") {
                hostText() == afterResult + "w"
            }
            type("v")
            await("The original editor remains usable after the queue is drained") { hostText() == afterResult + "wv" }
        } finally {
            releaseNative.countDown()
            activityRule.finishActivity()
            instrumentation.runOnMainSync {
                KeyMacros.save(previousMacros)
                layout.setValue(previousLayout)
                clipboard.setPrimaryClip(previousClip ?: ClipData.newPlainText("", ""))
            }
            SavedContentStore.delete(entry.id)
            FcitxDaemon.disconnect(javaClass.name)
        }
    }
}

/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android

import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ActivityTestRule
import kotlinx.coroutines.runBlocking
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@Suppress("DEPRECATION")
class PersonalKeyboardUiTest {
    @get:Rule
    val activityRule = ActivityTestRule(PersonalInputTestActivity::class.java, false, false)
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation

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
            SystemClock.sleep(100)
        }
    }

    private fun tap(x: Float, y: Float) {
        val time = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            assertTrue(automation.injectInputEvent(event, true))
            event.recycle()
        }
        instrumentation.waitForIdleSync()
    }

    private fun tapNode(node: AccessibilityNodeInfo) {
        val bounds = Rect().also(node::getBoundsInScreen)
        tap(bounds.exactCenterX(), bounds.exactCenterY())
    }

    private fun key(id: Int) {
        val name = instrumentation.targetContext.resources.getResourceEntryName(id)
        await("Keyboard key $name is visible") { imeNodes().any { it.viewIdResourceName?.endsWith(":id/$name") == true } }
        tapNode(imeNodes().first { it.viewIdResourceName?.endsWith(":id/$name") == true })
    }

    private fun type(text: String) {
        text.forEach { letter ->
            val matches = imeNodes().filter { it.text?.toString()?.equals(letter.toString(), true) == true }
            assertTrue("Keyboard letter $letter is visible", matches.isNotEmpty())
            tapNode(matches.maxBy { node -> Rect().also(node::getBoundsInScreen).centerY() })
        }
    }

    private fun keyText(text: String) {
        await("Keyboard key $text is visible") { imeNodes().any { it.text?.toString() == text } }
        tapNode(imeNodes().filter { it.text?.toString() == text }
            .maxBy { node -> Rect().also(node::getBoundsInScreen).centerY() })
    }

    private fun screenshot(name: String) {
        val bitmap = requireNotNull(automation.takeScreenshot())
        val descriptors = automation.executeShellCommandRw("dd of=/data/local/tmp/fcitx5-$name.png")
        android.os.ParcelFileDescriptor.AutoCloseOutputStream(descriptors[1]).use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptors[0]).use { it.readBytes() }
        bitmap.recycle()
    }

    @Test
    fun screenTapEditsPinyinAndJapaneseCandidatesAppearWhileTyping() = runBlocking {
        val context = instrumentation.targetContext
        val ime = "${context.packageName}/org.fcitx.fcitx5.android.input.FcitxInputMethodService"
        val info = automation.serviceInfo
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
            AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        automation.serviceInfo = info
        shell("settings put secure show_ime_with_hard_keyboard 1")
        shell("ime enable $ime")
        shell("ime set $ime")
        activityRule.launchActivity(android.content.Intent(context, PersonalInputTestActivity::class.java)
            .putExtra(PersonalInputTestActivity.EXTRA_TEST_EDITOR_ENTRY, true))
        var savedSearchEntry: org.fcitx.fcitx5.android.data.SavedContentStore.Entry? = null
        val symbolLayout = org.fcitx.fcitx5.android.data.prefs.AppPrefs.getInstance().internal.lastSymbolLayout
        val originalSymbolLayout = symbolLayout.getValue()
        val fcitx = FcitxDaemon.connect(javaClass.name)
        try {
            await("Keyboard is visible") {
                automation.rootInActiveWindow?.let { root ->
                    val activeNodes = nodes(root)
                    if (activeNodes.any { it.packageName?.toString() == "android" &&
                            it.text?.toString()?.contains("Pixel Launcher") == true &&
                            it.text?.toString()?.contains("responding") == true }) {
                        activeNodes.firstOrNull { it.text?.toString() == "Close app" }
                            ?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    }
                }
                imeNodes().any { it.viewIdResourceName?.endsWith(":id/button_space") == true }
            }
            fcitx.runOnReady { }
            await("Initial keyboard engine is active") {
                fcitx.runImmediately { inputMethodEntryCached.uniqueName in listOf("keyboard-us", "rime") }
            }
            // Use the keyboard's ordered job queue after Android selects its initial subtype.
            if (fcitx.runImmediately { inputMethodEntryCached.uniqueName } != "rime") {
                key(R.id.button_lang)
            }
            await("Rime Ice has finished its initial deployment", timeout = 180_000) {
                fcitx.runImmediately { inputMethodEntryCached.subMode.name == "雾凇拼音" }
            }
            val clipboardLabel = context.getString(R.string.clipboard)
            await("Toolbar is expanded on entering the first editor") {
                imeNodes().any { it.contentDescription?.toString() == clipboardLabel }
            }
            key(R.id.button_backspace)
            await("First Backspace deletes existing editor text") {
                var value = ""
                instrumentation.runOnMainSync { value = activityRule.activity.editor.text.toString() }
                value == "测试A"
            }
            val hideToolbarLabel = context.getString(R.string.hide_toolbar)
            tapNode(imeNodes().first { it.contentDescription?.toString() == hideToolbarLabel })
            await("Toolbar can be collapsed manually") {
                imeNodes().none { it.contentDescription?.toString() == clipboardLabel }
            }
            instrumentation.runOnMainSync { activityRule.activity.otherEditor.requestFocus() }
            instrumentation.waitForIdleSync()
            await("Toolbar expands when entering another editor") {
                imeNodes().any { it.contentDescription?.toString() == clipboardLabel }
            }
            key(R.id.button_backspace)
            await("First Backspace deletes text in the newly focused editor") {
                var value = ""
                instrumentation.runOnMainSync { value = activityRule.activity.otherEditor.text.toString() }
                value == "切换C"
            }
            instrumentation.runOnMainSync { activityRule.activity.editor.requestFocus() }
            instrumentation.waitForIdleSync()
            await("Toolbar remains expanded when returning to the original editor") {
                imeNodes().any { it.contentDescription?.toString() == clipboardLabel }
            }
            type("niho")
            await("Pinyin bar is visible") { imeNodes().any { it.text?.toString()?.startsWith("ni ho") == true } }
            val preedit = fcitx.runOnReady { inputPanelCached.preedit }
            val insertion = preedit.toString().lastIndexOf('o')
            val pinyin = imeNodes().first { it.text?.toString()?.startsWith("ni ho") == true }
            val bounds = Rect().also(pinyin::getBoundsInScreen)
            val density = context.resources.displayMetrics
            val paint = Paint().apply { textSize = 16f * density.scaledDensity }
            val x = bounds.left + 8f * density.density + paint.measureText(preedit.toString().substring(0, insertion))
            // Inject a screen touch through Android's IME window, including its touchable region.
            tap(x, bounds.exactCenterY())
            await("Screen tap moves the pinyin caret") { fcitx.runImmediately { inputPanelCached.preedit.cursor } == insertion }
            type("a")
            await("Inserted letter appears at the tapped pinyin caret") {
                fcitx.runImmediately { inputPanelCached.preedit.toString().replace(" ", "") } == "nihao"
            }
            screenshot("pinyin-edit")

            fcitx.runOnReady { reset(); activateIme("anthy") }
            type("nihongo")
            await("Japanese candidates are loaded while typing") { fcitx.runImmediately { getCandidates(0, 20).isNotEmpty() } }
            val predictions = fcitx.runOnReady { getCandidates(0, 20).map { it.text } }
            await("Japanese candidates are displayed in the candidate bar") {
                imeNodes().any { it.text?.toString() in predictions && it.text?.toString() != "にほんご" }
            }
            screenshot("japanese-predictions")
            key(R.id.button_space)
            await("First space displays Japanese conversion candidates") { imeNodes().any { it.text?.toString() == "日本語" } }
            key(R.id.button_return)
            var committed = ""
            await("Japanese text is committed to the editor") {
                instrumentation.runOnMainSync { committed = activityRule.activity.editor.text.toString() }
                "日本語" in committed
            }
            fcitx.runOnReady { reset(); activateIme("rime") }
            savedSearchEntry = org.fcitx.fcitx5.android.data.SavedContentStore.put(null,"搜索回归","搜索插入成功")
            val savedLabel=context.getString(R.string.saved_content)
            if(imeNodes().none { it.contentDescription?.toString()==savedLabel }) {
                val expandLabel=context.getString(R.string.expand_toolbar)
                await("Toolbar can be expanded") { imeNodes().any { it.contentDescription?.toString()==expandLabel } }
                tapNode(imeNodes().first { it.contentDescription?.toString()==expandLabel })
            }
            await("Saved content toolbar entry is visible") { imeNodes().any { it.contentDescription?.toString()==savedLabel } }
            tapNode(imeNodes().first { it.contentDescription?.toString()==savedLabel })
            val searchLabel=context.getString(R.string.content_search)
            await("Saved content search entry is visible") { imeNodes().any { it.text?.toString()==searchLabel } }
            tapNode(imeNodes().first { it.text?.toString()==searchLabel })
            type("sousuo")
            await("Search keyword candidate is visible") { imeNodes().any { it.text?.toString()=="搜索" } }
            tapNode(imeNodes().first { it.text?.toString()=="搜索" })
            await("Search keyword is committed before selecting a result") {
                fcitx.runImmediately { clientPreeditCached.isEmpty() && inputPanelCached.preedit.isEmpty() } &&
                    imeNodes().any { it.className?.toString()=="android.widget.EditText" && it.text?.toString()=="搜索" }
            }
            await("Search matches saved content") { imeNodes().any { it.text?.toString()=="搜索回归" } }
            instrumentation.runOnMainSync { assertEquals(committed,activityRule.activity.editor.text.toString()) }
            // Candidate/preedit visibility changes the IME window's position.
            // Obtain result coordinates after that layout transition settles.
            automation.waitForIdle(100,5_000)
            tapNode(imeNodes().first { it.text?.toString()=="搜索回归" })
            await("Search result inserts into the original editor") {
                var value=""
                instrumentation.runOnMainSync { value=activityRule.activity.editor.text.toString() }
                value==committed+"搜索插入成功"
            }

            // Exercise the visible numeric keypad while next-word predictions are active.
            symbolLayout.setValue(org.fcitx.fcitx5.android.input.keyboard.NumberKeyboard.Name)
            fcitx.runOnReady { reset() }
            type("nihao")
            await("Chinese candidate is visible before entering numbers") {
                imeNodes().any { it.text?.toString() == "你好" }
            }
            keyText("你好")
            val beforeNumbers = committed + "搜索插入成功你好"
            await("Chinese text is committed before entering numbers") {
                var value = ""
                instrumentation.runOnMainSync { value = activityRule.activity.editor.text.toString() }
                value == beforeNumbers
            }
            await("Next-word predictions are active before switching layouts") {
                fcitx.runImmediately { getCandidates(0, 10).isNotEmpty() }
            }
            keyText("?123")
            await("Numeric keypad is visible") { imeNodes().any { it.text?.toString() == "ABC" } }
            type("1234567890")
            await("All numeric keypad digits insert after Chinese text") {
                var value = ""
                instrumentation.runOnMainSync { value = activityRule.activity.editor.text.toString() }
                value == beforeNumbers + "1234567890"
            }

            // A pending pinyin composition must be committed before the literal digit.
            keyText("ABC")
            await("Alphabet keyboard is visible again") {
                imeNodes().any { it.viewIdResourceName?.endsWith(":id/button_space") == true }
            }
            type("nihao")
            await("Pinyin is pending before entering a digit") {
                fcitx.runImmediately { inputPanelCached.preedit.toString().replace(" ", "") == "nihao" }
            }
            keyText("?123")
            await("Numeric keypad is visible with pending pinyin") { imeNodes().any { it.text?.toString() == "ABC" } }
            type("2")
            await("Pending Chinese composition is followed by the literal digit") {
                var value = ""
                instrumentation.runOnMainSync { value = activityRule.activity.editor.text.toString() }
                value == beforeNumbers + "1234567890你好2"
            }

        } finally {
            symbolLayout.setValue(originalSymbolLayout)
            savedSearchEntry?.let { org.fcitx.fcitx5.android.data.SavedContentStore.delete(it.id) }
            screenshot("keyboard-final")
            activityRule.finishActivity()
            FcitxDaemon.disconnect(javaClass.name)
        }
    }
}

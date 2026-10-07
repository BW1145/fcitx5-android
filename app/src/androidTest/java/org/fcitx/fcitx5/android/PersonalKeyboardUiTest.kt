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
        activityRule.launchActivity(null)
        var savedSearchEntry: org.fcitx.fcitx5.android.data.SavedContentStore.Entry? = null
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
            assertEquals("nihao", fcitx.runOnReady { inputPanelCached.preedit.toString().replace(" ", "") })
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
            await("Search matches saved content") { imeNodes().any { it.text?.toString()=="搜索回归" } }
            instrumentation.runOnMainSync { assertEquals(committed,activityRule.activity.editor.text.toString()) }
            tapNode(imeNodes().first { it.text?.toString()=="搜索回归" })
            await("Search result inserts into the original editor") {
                var value=""
                instrumentation.runOnMainSync { value=activityRule.activity.editor.text.toString() }
                value==committed+"搜索插入成功"
            }

        } finally {
            savedSearchEntry?.let { org.fcitx.fcitx5.android.data.SavedContentStore.delete(it.id) }
            screenshot("keyboard-final")
            activityRule.finishActivity()
            FcitxDaemon.disconnect(javaClass.name)
        }
    }
}

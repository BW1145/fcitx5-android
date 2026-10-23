/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android

import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Bitmap
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
class ArtKeyboardFrameTest {
    @get:Rule
    val activityRule = ActivityTestRule(PersonalInputTestActivity::class.java, false, false)
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation

    private fun shell(command: String):String {
        return automation.executeShellCommand(command).use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { String(it.readBytes(),Charsets.UTF_8) }
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

    /** Actual IME touches and window framestats, Latin engine isolates appearance cost. */
    @Test fun defaultAndRedBlackContinuousTouchFrames() = runBlocking {
        org.junit.Assume.assumeTrue("Frame benchmark is restricted to emulator fixtures",
            android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk",true))
        val context=instrumentation.targetContext
        val themes=org.fcitx.fcitx5.android.data.theme.ThemeManager
        val normal=themes.prefs.normalModeTheme.getValue()
        val follow=themes.prefs.followSystemDayNightTheme.getValue()
        val enabledAnimations=org.fcitx.fcitx5.android.data.prefs.AppPrefs.getInstance().advanced.disableAnimation
        val oldDisabled=enabledAnimations.getValue()
        val output=context.getExternalFilesDir(null)!!.resolve("art-frame-benchmark").apply { mkdirs() }
        val info=automation.serviceInfo
        info.flags=info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
            AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        automation.serviceInfo=info
        val ime="${context.packageName}/org.fcitx.fcitx5.android.input.FcitxInputMethodService"
        val previousSystemIme=shell("settings get secure default_input_method").trim()
        val previousHardware=shell("settings get secure show_ime_with_hard_keyboard").trim()
        shell("settings put secure show_ime_with_hard_keyboard 1")
        shell("ime enable $ime");shell("ime set $ime")
        activityRule.launchActivity(android.content.Intent(context,PersonalInputTestActivity::class.java)
            .putExtra(PersonalInputTestActivity.EXTRA_TEST_EDITOR_ENTRY,true))
        val fcitx=FcitxDaemon.connect(javaClass.name)
        var oldIme:String?=null
        try {
            await("Keyboard visible") { imeNodes().any { it.viewIdResourceName?.endsWith(":id/button_space")==true } }
            fcitx.runOnReady { oldIme=inputMethodEntryCached.uniqueName;reset();activateIme("keyboard-us") }
            await("Latin engine active") { fcitx.runImmediately { inputMethodEntryCached.uniqueName=="keyboard-us" } }
            instrumentation.runOnMainSync {
                themes.prefs.followSystemDayNightTheme.setValue(false)
                enabledAnimations.setValue(false)
            }
            val redBlack=org.fcitx.fcitx5.android.data.theme.ArtThemes.all.single {
                org.fcitx.fcitx5.android.data.theme.ArtStyle.of(it)==org.fcitx.fcitx5.android.data.theme.ArtStyle.Constructivism
            }
            val default=themes.DefaultTheme
            for ((round,theme) in listOf(default,redBlack,redBlack,default).withIndex()) {
                instrumentation.runOnMainSync {
                    themes.setNormalModeTheme(theme)
                    activityRule.activity.editor.setText("")
                    activityRule.activity.editor.requestFocus()
                }
                instrumentation.waitForIdleSync();SystemClock.sleep(500)
                await("Theme keyboard ready") { imeNodes().any { it.text?.toString()?.equals("a",true)==true } }
                assertEquals("Rendering fixture retains Latin engine","keyboard-us",
                    fcitx.runImmediately { inputMethodEntryCached.uniqueName })
                assertEquals("Requested theme active",theme.name,themes.activeTheme.name)
                val corpus="asdfjkl"
                val positions=corpus.associateWith { character ->
                    val node=imeNodes().filter { it.text?.toString()?.equals(character.toString(),true)==true }
                        .maxBy { Rect().also(it::getBoundsInScreen).centerY() }
                    Rect().also(node::getBoundsInScreen)
                }
                val bitmap=requireNotNull(automation.takeScreenshot())
                output.resolve("round-$round.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
                bitmap.recycle()
                shell("dumpsys gfxinfo ${context.packageName} reset")
                val starts=mutableListOf<Long>()
                val frameTimes=mutableListOf<Long>()
                val callback=object:android.view.Choreographer.FrameCallback {
                    override fun doFrame(frameTimeNanos:Long) {
                        frameTimes+=frameTimeNanos
                        android.view.Choreographer.getInstance().postFrameCallback(this)
                    }
                }
                instrumentation.runOnMainSync { android.view.Choreographer.getInstance().postFrameCallback(callback) }
                val start=SystemClock.uptimeMillis()
                try { repeat(70) { index ->
                    val due=start+index*100L
                    val remaining=due-SystemClock.uptimeMillis()
                    if(remaining>0) SystemClock.sleep(remaining)
                    val box=positions.getValue(corpus[index%corpus.length])
                    val down=SystemClock.uptimeMillis();starts+=down
                    for(action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)) {
                        val event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,box.exactCenterX(),box.exactCenterY(),0)
                        event.source=InputDevice.SOURCE_TOUCHSCREEN
                        assertTrue(automation.injectInputEvent(event,true));event.recycle()
                    }
                }
                SystemClock.sleep(300)
                } finally {
                    instrumentation.runOnMainSync { android.view.Choreographer.getInstance().removeFrameCallback(callback) }
                }
                instrumentation.waitForIdleSync()
                var text=""
                instrumentation.runOnMainSync { text=activityRule.activity.editor.text.toString() }
                output.resolve("round-$round-framestats.txt").writeText(shell("dumpsys gfxinfo ${context.packageName} framestats"))
                output.resolve("round-$round.json").writeText(org.json.JSONObject()
                    .put("theme",theme.name).put("engine","keyboard-us").put("round",round)
                    .put("scope","actual IME touchscreen injection and package window frame stats; Latin engine; animations enabled")
                    .put("touchUptimeMs",org.json.JSONArray(starts))
                    .put("mainVsyncFrameTimeNs",org.json.JSONArray(frameTimes))
                    .put("receivedCharacters",text.length).toString())
                assertEquals("Every synthetic key reaches editor in order",buildString {
                    repeat(70) { append(corpus[it%corpus.length]) }
                },text)
            }
        } finally {
            instrumentation.runOnMainSync {
                themes.setNormalModeTheme(normal)
                themes.prefs.followSystemDayNightTheme.setValue(follow)
                enabledAnimations.setValue(oldDisabled)
            }
            try { fcitx.runOnReady { reset();oldIme?.let { activateIme(it) } } }
            finally {
                FcitxDaemon.disconnect(javaClass.name)
                if(previousSystemIme.matches(Regex("[A-Za-z0-9._]+/[A-Za-z0-9._]+"))) shell("ime set $previousSystemIme")
                if(previousHardware=="null") shell("settings delete secure show_ime_with_hard_keyboard")
                else if(previousHardware in listOf("0","1")) shell("settings put secure show_ime_with_hard_keyboard $previousHardware")
            }
        }
    }
}

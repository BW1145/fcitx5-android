/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.fcitx.fcitx5.android.ui.main.AndroidSpeechBackend
import org.fcitx.fcitx5.android.ui.main.SpeechSession
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Test-only service; responds to IPC without opening a microphone or network connection. */
class SpeechFakeRecognitionService : RecognitionService() {
    override fun onStartListening(intent: Intent, listener: Callback) {
        if (intent.getBooleanExtra("test.error5", false)) listener.error(SpeechRecognizer.ERROR_CLIENT)
        else {
            listener.readyForSpeech(Bundle())
            listener.results(Bundle().apply { putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("测试成功")) })
        }
    }
    override fun onStopListening(listener: Callback) {}
    override fun onCancel(listener: Callback) {}
}

class SpeechServiceContractTest {
    @get:Rule val microphonePermission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)
    @Test fun explicitFakeServiceReturnsResultsThroughAndroidBinder() = runService(false)
    @Test fun errorFiveFromAndroidBinderHasActionablePresentation() = runService(true)

    private fun runService(errorFive: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val component = ComponentName(instrumentation.context.packageName, SpeechFakeRecognitionService::class.java.name)
        val completed = CountDownLatch(1)
        var message = ""
        var session: SpeechSession? = null
        instrumentation.runOnMainSync {
            session = SpeechSession({ target, events ->
                AndroidSpeechBackend(context, target, events,
                    Intent("android.speech.action.RECOGNIZE_SPEECH").putExtra("test.error5", errorFive))
            }) { text, state ->
                message = text
                if (state == SpeechSession.State.IDLE) completed.countDown()
            }
            session!!.start(SpeechSession.Target(component.flattenToString(), "测试服务"))
        }
        try {
            assertTrue("Fake recognition service callback timed out", completed.await(15, TimeUnit.SECONDS))
            if (errorFive) {
                assertTrue(message, message.contains("错误 5"))
                assertTrue(message, message.contains("手动选择"))
            } else assertEquals("测试成功", message)
        } finally { instrumentation.runOnMainSync { session?.cancel() } }
    }
}

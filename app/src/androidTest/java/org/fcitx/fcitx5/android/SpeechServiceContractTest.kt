/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionService
import android.util.Log
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

class SpeechServiceContractTest {
    @get:Rule val microphonePermission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)
    @Test fun explicitFakeServiceReturnsResultsThroughAndroidBinder() = runService(false)
    @Test fun errorFiveFromAndroidBinderHasActionablePresentation() = runService(true)

    private fun runService(errorFive: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val component = ComponentName(instrumentation.context.packageName, SpeechFakeRecognitionService::class.java.name)
        val info = context.packageManager.getServiceInfo(component, 0)
        assertEquals(component.packageName, info.packageName)
        assertTrue("Fake service must be exported for system-server binding", info.exported)
        assertTrue("Fake service is disabled", info.enabled && info.applicationInfo.enabled)
        assertEquals("android.permission.BIND_SPEECH_RECOGNITION_SERVICE", info.permission)
        val discovered = context.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
        assertTrue("Fake service missing from target-app service discovery: $component",
            discovered.any { ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) == component })
        Log.i("SpeechContractTest", "preflight component=$component process=${info.processName} uid=${info.applicationInfo.uid} exported=${info.exported} permission=${info.permission}")
        val completed = CountDownLatch(1)
        var message = ""
        var session: SpeechSession? = null
        instrumentation.runOnMainSync {
            session = SpeechSession({ target, events ->
                AndroidSpeechBackend(context, target, events,
                    Intent("android.speech.action.RECOGNIZE_SPEECH").putExtra("test.error5", errorFive))
            }) { text, state ->
                message = text
                Log.i("SpeechContractTest", "client state=$state errorCase=$errorFive")
                if (state == SpeechSession.State.IDLE) completed.countDown()
            }
            session!!.start(SpeechSession.Target(component.flattenToString(), "测试服务"))
        }
        try {
            val received = completed.await(15, TimeUnit.SECONDS)
            assertTrue("Fake recognition service callback timed out: component=$component state=${session?.state} lastMessage=$message; inspect speech-system-logcat.txt for service onCreate/onStartListening", received)
            if (errorFive) {
                assertTrue(message, message.contains("错误 5"))
                assertTrue(message, message.contains("手动选择"))
            } else assertEquals("测试成功", message)
        } finally { instrumentation.runOnMainSync { session?.cancel() } }
    }
}

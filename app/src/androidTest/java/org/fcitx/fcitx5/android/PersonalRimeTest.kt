/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.fcitx.fcitx5.android.core.Fcitx
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalRimeTest {
    @Test
    fun freshInstallProvidesOfflinePinyinAndKeepsUserConfig() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fcitx = Fcitx(context)

        suspend fun start() {
            val ready = async(start = CoroutineStart.UNDISPATCHED) {
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

        start()
        try {
            assertTrue(fcitx.availableIme().any { it.uniqueName == "rime" })
            assertEquals(listOf("keyboard-us", "rime"), fcitx.enabledIme().map { it.uniqueName })
            assertEquals("rime", fcitx.currentIme().uniqueName)
            assertFalse(AppPrefs.getInstance().clipboard.clipboardListening.getValue())
            candidates("nihao") { "你好" in it }
            candidates("rq") { words -> words.any { it.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) } }

            val patch = (context.getExternalFilesDir(null) ?: context.filesDir)
                .resolve("data/rime/default.custom.yaml")
            val personalized = patch.readText() + "\n# personal configuration\n"
            patch.writeText(personalized)
            fcitx.stop()
            start()
            assertEquals(personalized, patch.readText())
            candidates("nihao") { "你好" in it }
        } finally {
            fcitx.stop()
        }
    }
}

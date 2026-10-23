/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main

import org.junit.Assert.*
import org.junit.Test

class SpeechSessionTest {
    private class Fake(val events: SpeechSession.Events) : SpeechSession.Backend {
        var starts = 0; var stops = 0; var cancels = 0; var destroys = 0
        override fun start() { starts++ }
        override fun stop() { stops++ }
        override fun cancel() { cancels++; events.error(5) }
        override fun destroy() { destroys++; events.results("stale cleanup callback") }
    }
    private val target = SpeechSession.Target("test/.Service", "测试服务")
    private val backends = mutableListOf<Fake>()
    private var text = ""
    private val session = SpeechSession({ _, events -> Fake(events).also(backends::add) }) { message, _ -> text = message }

    @Test fun missingDefaultDoesNotChooseAnotherProvider() {
        assertNull(SpeechSession.resolveDefault(null, setOf("test/.Service")))
        assertNull(SpeechSession.resolveDefault("missing/.Service", setOf("test/.Service")))
        assertEquals("test/.Service", SpeechSession.resolveDefault("test/.Service", setOf("test/.Service")))
    }
    @Test fun explicitProviderIsPassedUnchanged() {
        var received: SpeechSession.Target? = null
        val explicit = SpeechSession({ t, e -> received = t; Fake(e) }) { _, _ -> }
        explicit.start(target)
        assertEquals(target, received)
    }
    @Test fun errorFiveHasClientDiagnosisAndCleansUp() {
        session.start(target); backends.single().events.error(5)
        assertTrue(text.contains("错误 5")); assertTrue(text.contains("测试服务")); assertTrue(text.contains("连接"))
        assertFalse(text.contains("识别服务返回错误"))
        assertEquals(SpeechSession.State.IDLE, session.state)
        assertEquals(1, backends.single().destroys)
    }
    @Test fun rapidStartOnlyCreatesOneSessionAndStopWaitsForReady() {
        repeat(20) { session.start(target) }; session.stop()
        assertEquals(1, backends.size); assertEquals(1, backends[0].starts); assertEquals(0, backends[0].stops)
        backends[0].events.ready(); session.stop(); session.stop()
        assertEquals(1, backends[0].stops)
        backends[0].events.ready()
        assertEquals(SpeechSession.State.PROCESSING, session.state)
    }
    @Test fun cancelReopenIgnoresLateCallbacksIncludingCleanupCallbacks() {
        session.start(target); val old = backends[0]
        session.cancel(); assertEquals("已取消", text)
        session.start(target); val fresh = backends[1]; fresh.events.ready()
        old.events.error(5); old.events.partial("late"); old.events.results("late"); old.events.end()
        assertEquals("请说一句中文", text); assertEquals(SpeechSession.State.LISTENING, session.state)
        fresh.events.results("成功")
        assertEquals("成功", text); assertEquals(1, old.cancels); assertEquals(1, old.destroys)
    }
    @Test fun startupExceptionReleasesBackendAndAllowsRetry() {
        var destroyed = false
        val failing = SpeechSession({ _, _ -> object : SpeechSession.Backend {
            override fun start() { throw IllegalStateException("private provider detail") }
            override fun stop() {}; override fun cancel() {}; override fun destroy() { destroyed = true }
        } }) { message, _ -> text = message }
        failing.start(target)
        assertTrue(destroyed); assertEquals(SpeechSession.State.IDLE, failing.state)
        assertFalse(text.contains("private provider detail"))
    }
    @Test fun finalResultDiscardsLaterErrors() {
        session.start(target); val events = backends[0].events
        events.results("完成"); events.error(5)
        assertEquals("完成", text)
    }
}

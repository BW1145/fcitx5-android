/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android

import android.os.Looper
import android.widget.EditText
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.fcitx.fcitx5.android.data.ai.AiClient
import org.fcitx.fcitx5.android.data.ai.AiStore
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.ai.AiPanel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowChoreographer
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=FcitxApplication::class,shadows=[AndroidAtomicFileShadow::class])
class AiPanelTest {
    private class Stream:AiClient() {
        val chunks=Channel<String?>(Channel.UNLIMITED)
        var started=false
        override suspend fun generate(config:AiStore.Config,key:String,messages:List<AiStore.Message>,update:suspend (String)->Unit):String {
            started=true
            var latest=""
            while(true) {
                val value=chunks.receive() ?: return latest
                latest=value;update(value)
            }
        }
    }
    private fun await(check:()->Boolean) {
        val until=System.nanoTime()+5_000_000_000L
        while(!check() && System.nanoTime()<until) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        assertTrue("The panel or its saved conversation finishes updating",check())
    }

    @Test fun streamingRendersLatestFrameAndSavesTheCompleteReplyAndNewDraft() {
        val oldDelay=ShadowChoreographer.getFrameDelay()
        val wasPaused=ShadowChoreographer.isPaused()
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        val service=Robolectric.buildService(FcitxInputMethodService::class.java).get()
        val input=EditText(service)
        val source=Stream()
        val panel=AiPanel(service,ThemeManager.DefaultTheme,input,{}, { _,_-> },source)
        val value=AiStore.Conversation(draft="问题")
        runBlocking { AiStore.read { AiStore.save(value);AiStore.open(value) } }
        val messages=AiPanel::class.java.getDeclaredField("messages").apply { isAccessible=true }.get(panel) as TextView
        var renders=0
        messages.doAfterTextChanged { renders++ }
        try {
            panel.open()
            await { input.text.toString()=="问题" && input.isEnabled }
            panel.send();await { source.started }
            renders=0
            repeat(2000) { source.chunks.trySend("完整回复-$it") }
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("Chunks wait for a display frame",0,renders)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
            assertEquals(1,renders)
            assertTrue(messages.text.endsWith("完整回复-1999"))
            input.setText("下一条问题草稿")
            source.chunks.trySend("完整回复-最终内容")
            source.chunks.trySend(null)
            await { runBlocking { AiStore.read { AiStore.get(value.id)?.messages?.lastOrNull()?.text=="完整回复-最终内容" } } }
            val saved=runBlocking { AiStore.read { AiStore.get(value.id) } }!!
            assertEquals(listOf(AiStore.Message("user","问题"),AiStore.Message("assistant","完整回复-最终内容")),saved.messages)
            assertEquals("下一条问题草稿",saved.draft)
            await { messages.text.endsWith("完整回复-最终内容") }
        } finally {
            panel.close()
            runBlocking { AiStore.read { AiStore.delete(value) } }
            source.chunks.cancel()
            ShadowChoreographer.setPaused(wasPaused)
            ShadowChoreographer.setFrameDelay(oldDelay)
        }
    }
}

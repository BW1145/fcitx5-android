/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android

import kotlinx.serialization.json.*
import org.fcitx.fcitx5.android.data.ai.AiClient
import org.fcitx.fcitx5.android.data.ai.AiStore
import org.junit.Assert.*
import org.junit.Test

class AiProtocolTest {
    private val messages=listOf(AiStore.Message("user","你好"),AiStore.Message("assistant","你好，有什么问题？"),AiStore.Message("user","继续"))
    @Test fun endpointsAcceptBaseAndCompleteAddresses() {
        assertEquals("https://example.com/v1/chat/completions",AiClient.endpoint(AiStore.Config(endpoint="https://example.com/v1/")))
        assertEquals("https://example.com/v1/chat/completions",AiClient.endpoint(AiStore.Config(endpoint="https://example.com/v1/chat/completions")))
        assertEquals("https://example.com/v1/messages",AiClient.endpoint(AiStore.Config(protocol="anthropic",endpoint="https://example.com")))
        assertEquals("https://example.com/v1/messages",AiClient.endpoint(AiStore.Config(protocol="anthropic",endpoint="https://example.com/v1")))
        assertEquals("https://example.com/v1beta/models/gemini-test:streamGenerateContent?alt=sse",AiClient.endpoint(AiStore.Config(protocol="gemini",endpoint="https://example.com/v1beta",model="gemini-test")))
    }
    @Test fun fixedPromptUsesEachProtocolsSystemField() {
        for(protocol in listOf("openai","anthropic","gemini")) {
            val config=AiStore.Config(protocol=protocol,model="test",fixedPrompt="固定指令")
            val body=AiClient.body(config,messages)
            when(protocol) {
                "openai" -> assertEquals("固定指令",body["messages"]!!.jsonArray.first().jsonObject["content"]!!.jsonPrimitive.content)
                "anthropic" -> { assertEquals("固定指令",body["system"]!!.jsonPrimitive.content);assertEquals(3,body["messages"]!!.jsonArray.size) }
                "gemini" -> {
                    assertEquals("固定指令",body["systemInstruction"]!!.jsonObject["parts"]!!.jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content)
                    assertEquals("model",body["contents"]!!.jsonArray[1].jsonObject["role"]!!.jsonPrimitive.content)
                }
            }
            val disabled=AiClient.body(config.copy(fixedEnabled=false),messages).toString()
            assertFalse(disabled.contains("固定指令"))
        }
    }
    @Test fun textDeltasDecodeWithoutExposingReasoningOrProtocolMetadata() {
        assertEquals("你",AiClient.delta("openai","""{"choices":[{"delta":{"content":"你"}}]}"""))
        assertEquals("好",AiClient.delta("anthropic","""{"type":"content_block_delta","delta":{"type":"text_delta","text":"好"}}"""))
        assertEquals("你好",AiClient.delta("gemini","""{"candidates":[{"content":{"parts":[{"thought":true,"text":"internal"},{"text":"你好"}]}}]}"""))
        assertEquals("",AiClient.delta("openai","[DONE]"))
        assertEquals("",AiClient.delta("anthropic","""{"type":"message_stop"}"""))
    }
    @Test(expected=IllegalStateException::class) fun serviceErrorsAreNotSavedAsAnswers() {
        AiClient.delta("openai","""{"error":{"message":"请求配额不足"}}""")
    }
}

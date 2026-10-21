/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Network requests begin only from an explicit send or connection test. */
class AiClient {
    @Volatile private var connection:HttpURLConnection?=null
    fun stop() { connection?.disconnect() }

    companion object {
        fun endpoint(config:AiStore.Config):String {
            val base=config.endpoint.trim().trimEnd('/')
            require(base.startsWith("https://")) { "服务地址请使用 https://" }
            return when(config.protocol) {
                "anthropic" -> if(base.endsWith("/messages")) base else "$base${if(base.endsWith("/v1")) "" else "/v1"}/messages"
                "gemini" -> "$base${if(Regex("/v1(?:beta)?$").containsMatchIn(base)) "" else "/v1beta"}/models/${URLEncoder.encode(config.model,"UTF-8")}:streamGenerateContent?alt=sse"
                else -> if(base.endsWith("/chat/completions")) base else "$base/chat/completions"
            }
        }
        fun body(config:AiStore.Config,messages:List<AiStore.Message>)=buildJsonObject {
            val prompt=config.fixedPrompt.takeIf { config.fixedEnabled && it.isNotBlank() }
            when(config.protocol) {
                "gemini" -> {
                    prompt?.let { putJsonObject("systemInstruction") { putJsonArray("parts") { addJsonObject { put("text",it) } } } }
                    putJsonArray("contents") { messages.forEach { message -> addJsonObject {
                        put("role",if(message.role=="assistant") "model" else "user")
                        putJsonArray("parts") { addJsonObject { put("text",message.text) } }
                    } } }
                }
                else -> {
                    put("model",config.model);put("stream",true)
                    if(config.protocol=="anthropic") { put("max_tokens",4096);prompt?.let { put("system",it) } }
                    putJsonArray("messages") {
                        if(config.protocol!="anthropic") prompt?.let { addJsonObject { put("role","system");put("content",it) } }
                        messages.forEach { message -> addJsonObject { put("role",message.role);put("content",message.text) } }
                    }
                }
            }
        }
        fun delta(protocol:String,data:String):String {
            if(data=="[DONE]") return ""
            val obj=Json.parseToJsonElement(data).jsonObject
            obj["error"]?.let { throw IllegalStateException(it.jsonObject["message"]?.jsonPrimitive?.content ?: "服务返回错误") }
            return when(protocol) {
                "anthropic" -> obj["delta"]?.jsonObject?.get("text")?.jsonPrimitive?.content.orEmpty()
                "gemini" -> obj["candidates"]?.jsonArray?.firstOrNull()?.jsonObject?.get("content")?.jsonObject?.get("parts")?.jsonArray
                    ?.filter { it.jsonObject["thought"]?.jsonPrimitive?.booleanOrNull!=true }
                    ?.joinToString("") { it.jsonObject["text"]?.jsonPrimitive?.content.orEmpty() }.orEmpty()
                else -> obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("delta")?.jsonObject?.get("content")
                    ?.let { if(it is JsonPrimitive) it.contentOrNull else null }.orEmpty()
            }
        }
    }

    suspend fun generate(config:AiStore.Config,key:String,messages:List<AiStore.Message>,update:suspend (String)->Unit):String = withContext(Dispatchers.IO) {
        require(config.model.isNotBlank()) { "请先设置模型名称" }
        require(key.isNotBlank()) { "请先填写 API Key" }
        val http=URL(endpoint(config)).openConnection() as HttpURLConnection
        connection=http
        try {
            http.requestMethod="POST";http.connectTimeout=20000;http.readTimeout=60000
            http.instanceFollowRedirects=false;http.doOutput=true
            http.setRequestProperty("Content-Type","application/json")
            http.setRequestProperty("Accept","text/event-stream")
            when(config.protocol) {
                "anthropic" -> { http.setRequestProperty("x-api-key",key);http.setRequestProperty("anthropic-version","2023-06-01") }
                "gemini" -> http.setRequestProperty("x-goog-api-key",key)
                else -> http.setRequestProperty("Authorization","Bearer $key")
            }
            http.outputStream.use { it.write(body(config,messages).toString().toByteArray(Charsets.UTF_8)) }
            val status=http.responseCode
            if(status !in 200..299) {
                val detail=runCatching {
                    http.errorStream?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                        Json.parseToJsonElement(reader.readText()).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
                    }
                }.getOrNull()?.take(300)
                error("请求失败（HTTP $status）${detail?.let { "：$it" } ?: "。请检查服务地址、模型、Key 和账户余额。"}")
            }
            val output=StringBuilder();val data=StringBuilder()
            suspend fun emit() {
                if(data.isEmpty()) return
                val payload=data.toString();data.setLength(0)
                output.append(delta(config.protocol,payload));update(output.toString())
            }
            http.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                while(true) {
                    currentCoroutineContext().ensureActive()
                    val line=reader.readLine() ?: break
                    if(line.isEmpty()) emit()
                    else if(line.startsWith("data:")) {
                        if(data.isNotEmpty()) data.append('\n')
                        data.append(line.substring(5).trimStart())
                    }
                }
                emit()
            }
            check(output.isNotBlank()) { "服务没有返回文字，请检查所选接口协议与模型。" }
            output.toString()
        } finally { http.disconnect();connection=null }
    }
}

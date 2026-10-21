/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.ai

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import org.fcitx.fcitx5.android.data.ExperienceData
import org.fcitx.fcitx5.android.data.PrivacyMode
import org.fcitx.fcitx5.android.utils.appContext
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object AiStore {
    @Serializable data class Command(val name:String,val prompt:String)
    @Serializable data class Config(val protocol:String="openai",val endpoint:String="",val model:String="",
        val fixedEnabled:Boolean=true,val fixedPrompt:String="用中文清晰、简洁地回答。",
        val commands:List<Command> = listOf(Command("翻译成日语","翻译成自然日语，只输出译文。"),
            Command("缩短表达","保持原意，把这段话写得更简洁。"),Command("修改错字","只修改错别字和明显的标点错误。")))
    @Serializable data class Message(val role:String,val text:String)
    @Serializable data class Conversation(val id:String=UUID.randomUUID().toString(),val title:String="新对话",
        val updated:Long=System.currentTimeMillis(),val messages:List<Message> = emptyList(),val draft:String="",
        val temporary:Boolean=false)
    private val root get()=ExperienceData.root.resolve("ai")
    private val configFile get()=root.resolve("settings.json")
    private val history get()=root.resolve("conversations")
    private var temporary:Conversation?=null
    private var activeId:String?=null
    fun config()=ExperienceData.read(configFile)?.let { ExperienceData.json.decodeFromString<Config>(it) } ?: Config()
    fun configure(value:Config)=ExperienceData.write(configFile,ExperienceData.json.encodeToString(value))
    fun all():List<Conversation> = history.listFiles()?.filter { it.extension=="json" }?.map {
        ExperienceData.json.decodeFromString<Conversation>(it.readText(Charsets.UTF_8))
    }?.sortedByDescending { it.updated }.orEmpty()
    fun current():Conversation {
        if(PrivacyMode.enabled) return temporary ?: Conversation(temporary=true).also { temporary=it }
        return all().find { it.id==activeId } ?: all().firstOrNull() ?: Conversation()
    }
    fun open(value:Conversation) { if(value.temporary) temporary=value else activeId=value.id }
    fun save(value:Conversation,activate:Boolean=true) {
        if(value.temporary) { if(PrivacyMode.enabled) temporary=value;return }
        if(!activate && !history.resolve("${value.id}.json").exists()) return
        ExperienceData.write(history.resolve("${value.id}.json"),ExperienceData.json.encodeToString(value));if(activate) activeId=value.id
    }
    fun delete(value:Conversation) { history.resolve("${value.id}.json").delete();if(activeId==value.id) activeId=null }
    fun discardTemporary() { temporary=null }
}

/** The exported settings retain encrypted bytes; another device asks for its own API key. */
object AiCredentials {
    private const val ALIAS="keyboard-ai-key"
    private val prefs get()=appContext.getSharedPreferences("ai-credentials",0)
    private fun key():SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS,null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun set(value:String) {
        if(value.isEmpty()) { prefs.edit().clear().apply();return }
        val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key())
        prefs.edit().putString("iv",Base64.encodeToString(cipher.iv,Base64.NO_WRAP))
            .putString("key",Base64.encodeToString(cipher.doFinal(value.toByteArray(Charsets.UTF_8)),Base64.NO_WRAP)).apply()
    }
    fun get():String {
        val data=prefs.getString("key",null) ?: return ""
        return try {
            val cipher=Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,Base64.decode(prefs.getString("iv",null),Base64.NO_WRAP)))
            cipher.doFinal(Base64.decode(data,Base64.NO_WRAP)).toString(Charsets.UTF_8)
        } catch(_:java.security.GeneralSecurityException) { "" }
    }
}

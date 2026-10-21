/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.ai

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import org.fcitx.fcitx5.android.data.ExperienceData
import org.fcitx.fcitx5.android.data.PrivacyMode
import org.fcitx.fcitx5.android.utils.appContext
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlinx.coroutines.*
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
    @OptIn(ExperimentalSerializationApi::class)
    @Serializable data class Conversation(val id:String=UUID.randomUUID().toString(),val title:String="新对话",
        @EncodeDefault val updated:Long=System.currentTimeMillis(),val messages:List<Message> = emptyList(),val draft:String="",
        val temporary:Boolean=false)
    private val root get()=ExperienceData.root.resolve("ai")
    private val configFile get()=root.resolve("settings.json")
    @Serializable data class Summary(val id:String,val title:String,val updated:Long,val count:Int)
    private var historyDirectory:java.io.File?=null
    private var storage:AiHistory?=null
    @get:Synchronized
    private val history:AiHistory get() {
        val directory=root.resolve("conversations")
        if(historyDirectory!=directory) {
            val changed=historyDirectory!=null
            storage?.close()
            storage=AiHistory(directory,appContext.cacheDir.resolve("ai-history-index.json"))
            historyDirectory=directory
            if(changed) { activeId=null;temporary=null }
        }
        return storage!!
    }
    private var worker:Thread?=null
    private val executor=Executors.newSingleThreadExecutor { task ->
        Thread(task,"ai-storage").apply { isDaemon=true;worker=this }
    }
    private val dispatcher=executor.asCoroutineDispatcher()
    private val scope=CoroutineScope(SupervisorJob()+dispatcher)
    private var temporary:Conversation?=null
    private var activeId:String?=null
    @Volatile var version=0
        private set
    fun config()=ExperienceData.read(configFile)?.let { ExperienceData.json.decodeFromString<Config>(it) } ?: Config()
    fun configure(value:Config)=ExperienceData.write(configFile,ExperienceData.json.encodeToString(value))
    @Synchronized fun all():List<Conversation> = history.all()
    @Synchronized fun get(id:String):Conversation?=history.get(id)
    @Synchronized fun current():Conversation {
        if(PrivacyMode.enabled) return temporary ?: Conversation(temporary=true).also { temporary=it }
        return activeId?.let { history.get(it) } ?: history.summaries().firstOrNull()?.let { history.get(it.id) }
            ?.also { activeId=it.id } ?: Conversation()
    }
    @Synchronized fun open(value:Conversation) { if(value.temporary) temporary=value else activeId=value.id }
    @Synchronized fun save(value:Conversation,activate:Boolean=true) {
        if(value.temporary) { if(PrivacyMode.enabled && temporary?.id==value.id) temporary=value;return }
        history.save(value,requireExisting=!activate)
        if(activate) activeId=value.id
    }
    @Synchronized fun delete(value:Conversation) { history.delete(value.id);if(activeId==value.id) activeId=null }
    @Synchronized fun discardTemporary() { temporary=null }
    @Synchronized fun invalidate() { version++;history.invalidate();activeId=null;temporary=null }
    @Synchronized fun saveDraft(value:Conversation,expectedVersion:Int=version) {
        if(expectedVersion!=version) return
        if(value.temporary && temporary?.id!=value.id) return
        val saved=if(value.temporary) temporary else history.get(value.id)
        save((saved ?: value).copy(draft=value.draft))
    }
    @Synchronized fun complete(request:Conversation,reply:String,draft:String?,expectedVersion:Int=version):Conversation? {
        if(expectedVersion!=version) return null
        val saved=if(request.temporary) temporary?.takeIf { it.id==request.id && PrivacyMode.enabled }
            else history.get(request.id)
        saved ?: return null
        val finished=saved.copy(messages=if(reply.isNotBlank()) request.messages+Message("assistant",reply) else request.messages,
            updated=System.currentTimeMillis(),draft=draft ?: saved.draft)
        save(finished,activate=false)
        return finished
    }

    suspend fun <T> read(block:()->T):T=withContext(dispatcher) { block() }
    fun write(block:()->Unit):Job {
        val expected=version
        return scope.launch { if(expected==version) block() }
    }
    suspend fun page(query:String="",offset:Int=0,limit:Int=20)=withContext(dispatcher) {
        history.page(query,offset,limit)
    }

    /** Wait for queued drafts, and keep export/import ordered with subsequent writes. */
    fun <T> withStableData(block:()->T):T = if(Thread.currentThread()===worker) block()
        else executor.submit(Callable { block() }).get()
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

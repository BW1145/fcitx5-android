/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.ai

import android.content.Intent
import android.view.View
import android.widget.*
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import org.fcitx.fcitx5.android.data.PrivacyMode
import org.fcitx.fcitx5.android.data.SavedContentStore
import org.fcitx.fcitx5.android.data.ai.*
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.panelDrawable
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.ui.main.AiSettingsActivity
import org.fcitx.fcitx5.android.utils.clipboardManager

/** This panel uses the same isolated editor/session boundary as content search. */
internal class AiPanel(
    private val service:FcitxInputMethodService, private val theme:Theme,
    private val input:EditText, private val prepareSend:()->Unit,
    private val finish:(String,Boolean)->Unit
) {
    private val client=AiClient()
    private var job:Job?=null
    private var generation=0
    private var conversation=AiStore.Conversation()
    private var config=AiStore.Config()
    private var loading:Job?=null
    private var ready=false
    private var storageVersion=0
    private var expanded=false
    private var originalDraft=""
    private var selected=""
    private val density=service.resources.displayMetrics.density
    private fun dp(value:Int)=(value*density).toInt()
    val root=LinearLayout(service).apply { orientation=LinearLayout.VERTICAL;background=theme.panelDrawable(density) }
    private val messages=TextView(service).apply {
        textSize=15f;setTextColor(theme.candidateTextColor);setPadding(dp(10),dp(4),dp(10),dp(4));setTextIsSelectable(true)
    }
    private val scroll=ScrollView(service).apply { addView(messages) }
    private val state=TextView(service).apply { textSize=12f;setTextColor(theme.candidateCommentColor) }
    private val send=button("发送") { prepareSend() }
    private val expand=button("展开") { expanded=!expanded;render() }
    private val actions=LinearLayout(service)
    private fun button(label:String,block:()->Unit)=Button(service).apply {
        text=label;textSize=12f;minWidth=0;minimumWidth=0;setTextColor(theme.keyTextColor)
        background=theme.panelDrawable(density);setOnClickListener { block() }
    }
    init {
        root.addView(state)
        root.addView(scroll,LinearLayout.LayoutParams(-1,dp(82)))
        root.addView(LinearLayout(service).apply {
            listOf(send,button("停止") { stop() },expand,button("更多") { menu(itView=this@AiPanel.root) }).forEach {
                addView(it,LinearLayout.LayoutParams(0,dp(40),1f))
            }
        })
        root.addView(actions)
    }
    fun open() {
        generation++
        val password=service.currentInputEditorInfo?.let {
            org.fcitx.fcitx5.android.core.CapabilityFlags.fromEditorInfo(it).has(org.fcitx.fcitx5.android.core.CapabilityFlag.Password)
        }==true
        selected=if(password) "" else service.currentInputConnection?.getSelectedText(0)?.toString().orEmpty()
        originalDraft=if(password) "" else service.currentInputConnection?.let { connection ->
            connection.getExtractedText(android.view.inputmethod.ExtractedTextRequest(),0)?.text?.toString()
                ?: (connection.getTextBeforeCursor(6000,0)?.toString().orEmpty()+selected+
                    connection.getTextAfterCursor(6000,0)?.toString().orEmpty())
        }.orEmpty()
        input.hint="向 AI 提问"
        load()
    }
    private fun load(id:String?=null,fresh:AiStore.Conversation?=null) {
        loading?.cancel()
        val token=++generation
        input.setText("")
        ready=false;input.isEnabled=false;render();state.text="正在读取对话…"
        loading=service.lifecycleScope.launch {
            try {
                job?.join()
                val loaded=AiStore.read {
                    val value=fresh ?: if(id==null) AiStore.current() else AiStore.get(id) ?: AiStore.Conversation()
                    Triple(value,AiStore.config(),AiStore.version)
                }
                if(token!=generation) return@launch
                if(loaded.third!=AiStore.version) { load(id,fresh);return@launch }
                conversation=loaded.first;config=loaded.second;storageVersion=loaded.third
                AiStore.open(conversation)
                // Keep anything typed while storage was loading, including composing spans.
                input.text.insert(0,conversation.draft)
                input.setSelection(input.length())
                ready=true;input.isEnabled=true;render()
            } catch(e:CancellationException) { throw e }
            catch(e:Exception) { if(token==generation) state.text=e.message ?: "读取失败" }
        }
    }
    fun rememberDraft() {
        if(!ready || storageVersion!=AiStore.version) return
        conversation=conversation.copy(draft=input.text.toString())
        val draft=conversation
        val version=storageVersion
        AiStore.write { AiStore.saveDraft(draft,version) }
    }
    fun close() { rememberDraft();stop();loading?.cancel();ready=false;generation++ }
    fun send() {
        if(!ready || job?.isCompleted==false) return
        if(storageVersion!=AiStore.version) { stop();load();return }
        val question=input.text.toString().trim();if(question.isEmpty()) return
        conversation=conversation.copy(messages=conversation.messages+AiStore.Message("user",question),
            title=if(conversation.messages.isEmpty()) question.take(28) else conversation.title,
            draft="",updated=System.currentTimeMillis())
        input.setText("")
        val pending=conversation
        val requestVersion=storageVersion
        AiStore.write { if(requestVersion==AiStore.version) AiStore.save(pending) }
        val before=conversation.messages
        val request=conversation
        val token=++generation
        send.isEnabled=false;state.text="正在请求…";renderMessages()
        job=service.lifecycleScope.launch {
            var reply=""
            try {
                val credentials=AiStore.read {
                    check(requestVersion==AiStore.version) { "会话数据已更新，请重新打开 AI" }
                    AiStore.config() to AiCredentials.get()
                }
                client.generate(credentials.first,credentials.second,before) { partial ->
                    reply=partial
                    withContext(Dispatchers.Main) { if(generation==token && requestVersion==AiStore.version) {
                        messages.text=display(before+AiStore.Message("assistant",partial));scroll.fullScroll(View.FOCUS_DOWN)
                    } }
                }
                if(generation==token) state.text="回复完成"
            } catch(_:CancellationException) { if(generation==token) state.text="已停止" }
            catch(e:Exception) { if(generation==token) state.text=e.message ?: "请求失败" }
            finally {
                val current=generation==token && conversation.id==request.id
                val draft=if(current) input.text.toString() else null
                withContext(NonCancellable) {
                    val finished=AiStore.read { AiStore.complete(request,reply,draft,requestVersion) }
                    if(generation==token) {
                        send.isEnabled=ready
                        if(finished!=null) { conversation=finished;renderMessages();renderActions() }
                    }
                }
            }
        }
    }
    private fun stop() { job?.cancel();client.stop() }
    private fun display(items:List<AiStore.Message>):String {
        val list=if(expanded) items else items.takeLast(if(items.lastOrNull()?.role=="assistant") 2 else 1)
        return list.joinToString("\n\n") { "${if(it.role=="user") "我" else "AI"}：${it.text}" }
    }
    private fun renderMessages() { messages.text=display(conversation.messages).ifBlank { "在这里提问，结果由你选择是否写入原输入框。" } }
    private fun renderActions() {
        actions.removeAllViews()
        val answer=conversation.messages.lastOrNull { it.role=="assistant" }?.text ?: return
        fun chosen():String {
            val start=messages.selectionStart;val end=messages.selectionEnd
            return if(start>=0 && end>start) messages.text.subSequence(start,end).toString() else answer
        }
        listOf("插入" to { finish(chosen(),false) },"替换草稿" to { finish(chosen(),true) },"收藏" to {
            val text=chosen();SavedContentStore.put(null,text.take(18),text);state.text="已保存到常用内容"
        }).forEach { (name,action) -> actions.addView(button(name,action),LinearLayout.LayoutParams(0,dp(36),1f)) }
    }
    private fun render() {
        send.isEnabled=ready && job?.isCompleted!=false
        state.text=if(PrivacyMode.enabled) "输入给 AI · 隐私临时对话" else "输入给 AI · 本机会话"
        expand.text=if(expanded) "收起" else "展开"
        scroll.layoutParams=LinearLayout.LayoutParams(-1,dp(if(expanded) 230 else 82))
        renderMessages();renderActions()
    }
    private fun setQuestion(text:String) { input.setText(text);input.setSelection(input.length()) }
    private fun menu(itView:View) {
        PopupMenu(service,itView).apply {
            fun item(label:String,action:()->Unit) { menu.add(label).setOnMenuItemClickListener { action();true } }
            item("拿当前草稿提问") { setQuestion(originalDraft) }
            item("拿选中文字提问") { setQuestion(selected) }
            item("拿剪贴板内容提问") { setQuestion(service.clipboardManager.primaryClip?.getItemAt(0)?.coerceToText(service)?.toString().orEmpty()) }
            config.commands.forEach { command -> item(command.name) {
                val text=input.text.toString().ifBlank { selected.ifBlank { originalDraft } }
                setQuestion("${command.prompt}\n\n$text")
            } }
            item("新对话") {
                close();load(fresh=AiStore.Conversation(temporary=PrivacyMode.enabled))
            }
            if(!PrivacyMode.enabled) {
                val popup=this
                val token=generation
                service.lifecycleScope.launch {
                    val previous=AiStore.page()
                    if(token!=generation) return@launch
                    previous.forEach { summary ->
                        popup.menu.add("继续：${summary.title}").setOnMenuItemClickListener { close();load(summary.id);true }
                    }
                }
            }
            item("接口、提示词与全部记录") { rememberDraft();service.startActivity(Intent(service,AiSettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            show()
        }
    }
}

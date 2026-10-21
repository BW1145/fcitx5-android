/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main

import android.os.Bundle
import android.text.InputType
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.data.ai.*

class AiSettingsActivity : ExperienceActivity() {
    private val client=AiClient()
    private var test:Job?=null
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState);title="AI 助手"
        val config=AiStore.config();val panel=column()
        panel.addView(label("在键盘工具栏布局中加入 AI 按钮，即可在当前应用中问答。只有点击发送或测试连接时才请求所选服务。"))
        val protocols=listOf("openai","anthropic","gemini")
        val protocol=Spinner(this).apply { adapter=ArrayAdapter(this@AiSettingsActivity,android.R.layout.simple_spinner_dropdown_item,
            listOf("OpenAI 兼容（包括 DeepSeek 等）","Anthropic Messages","Gemini"));setSelection(protocols.indexOf(config.protocol).coerceAtLeast(0)) }
        panel.addView(protocol)
        val endpoint=field("服务地址，例如 https://api.openai.com/v1",config.endpoint)
        endpoint.inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        val key=field("API Key",AiCredentials.get()).apply { inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        val model=field("模型名称",config.model)
        panel.addView(endpoint);panel.addView(key);panel.addView(model)
        panel.addView(label("地址包含接口版本路径；也可填写完整 chat/completions 或 messages 地址。Key 在本机加密保存，换机后重新填写。"))
        val fixed=Switch(this).apply { text="自动携带固定提示词";isChecked=config.fixedEnabled };panel.addView(fixed)
        val prompt=field("每次请求携带的固定提示词",config.fixedPrompt,true);panel.addView(prompt)
        fun save() { AiCredentials.set(key.text.toString().trim());AiStore.configure(AiStore.config().copy(
            protocol=protocols[protocol.selectedItemPosition],endpoint=endpoint.text.toString().trim(),model=model.text.toString().trim(),
            fixedEnabled=fixed.isChecked,fixedPrompt=prompt.text.toString())) }
        val state=label("")
        panel.addView(button("保存设置") { save();state.text="已保存" })
        panel.addView(button("测试连接") {
            save();test?.cancel();client.stop()
            test=lifecycleScope.launch {
                state.text="正在测试…"
                try { val result=client.generate(AiStore.config(),AiCredentials.get(),listOf(AiStore.Message("user","请只回复 OK。"))) {}
                    state.text="连接成功：$result"
                } catch(_:CancellationException) { state.text="已停止" }
                catch(e:Exception) { state.text=e.message }
            }
        })
        panel.addView(state)
        panel.addView(button("管理常用指令") { commands() })
        panel.addView(label("会话记录 · 保存在本机，可搜索、继续对话和删除"))
        val search=field("搜索会话");panel.addView(search)
        val history=column();panel.addView(history)
        fun render() {
            history.removeAllViews();val q=search.text.toString()
            AiStore.all().filter { (it.title+it.messages.joinToString { m->m.text }).contains(q,true) }.forEach { conversation ->
                history.addView(button("${conversation.title} · ${conversation.messages.size} 条") {
                    AlertDialog.Builder(this).setTitle(conversation.title).setItems(arrayOf("查看记录","下次打开键盘时继续此对话","删除")) { _,i->when(i) {
                        0 -> AlertDialog.Builder(this).setTitle(conversation.title).setView(ScrollView(this).apply {
                            addView(label(conversation.messages.joinToString("\n\n") { "${if(it.role=="user") "我" else "AI"}：${it.text}" }))
                        }).setPositiveButton("关闭",null).show()
                        1 -> { AiStore.open(conversation);finish() }
                        2 -> AlertDialog.Builder(this).setMessage("删除此对话？").setPositiveButton("删除") { _,_->AiStore.delete(conversation);render() }.setNegativeButton("取消",null).show()
                    } }.show()
                })
            }
        }
        search.doAfterTextChanged { render() };render()
        setContentView(ScrollView(this).apply { addView(panel) })
    }
    private fun commands() {
        val config=AiStore.config();val items=config.commands
        AlertDialog.Builder(this).setTitle("常用指令").setItems((items.map { it.name }+"添加指令").toTypedArray()) { _,index ->
            val old=items.getOrNull(index);val form=column();val name=field("名称",old?.name.orEmpty())
            val prompt=field("本次操作使用的指令",old?.prompt.orEmpty(),true);form.addView(name);form.addView(prompt)
            AlertDialog.Builder(this).setTitle(old?.name ?: "添加指令").setView(form).setPositiveButton("保存") { _,_->
                if(name.text.isNotBlank() && prompt.text.isNotBlank()) {
                    val updated=items.toMutableList();val command=AiStore.Command(name.text.toString(),prompt.text.toString())
                    if(old==null) updated.add(command) else updated[index]=command
                    AiStore.configure(config.copy(commands=updated))
                }
            }.setNegativeButton("取消",null).apply {
                if(old!=null) setNeutralButton("删除") { _,_->AiStore.configure(config.copy(commands=items-old)) }
            }.show()
        }.show()
    }
    override fun onDestroy() { client.stop();test?.cancel();super.onDestroy() }
}

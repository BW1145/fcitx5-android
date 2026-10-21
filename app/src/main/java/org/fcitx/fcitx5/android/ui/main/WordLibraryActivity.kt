/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main

import android.os.Bundle
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.data.WordLibrary
import java.text.DateFormat
import java.util.Date

class WordLibraryActivity : ExperienceActivity() {
    private val connection by lazy { FcitxDaemon.connect("word-library") }
    private lateinit var rows:LinearLayout
    private lateinit var query:EditText
    private var words=listOf<WordLibrary.Word>()
    private var history=false
    private var exporting:WordLibrary.Snapshot?=null
    private val exporter=registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val snapshot=exporting
        if(uri!=null && snapshot!=null) task { withContext(Dispatchers.IO) { contentResolver.openOutputStream(uri)!!.use { WordLibrary.export(snapshot,it) } } }
    }
    private val importer=registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if(uri!=null) task { withContext(Dispatchers.IO) { contentResolver.openInputStream(uri)!!.use { WordLibrary.import(it) } };render() }
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState);title="我的词库与历史备份"
        val panel=column()
        panel.addView(button("我的词库") { history=false;refresh() })
        panel.addView(button("历史备份") { history=true;render() })
        query=field("搜索词条、拼音或备份名称");panel.addView(query)
        query.doAfterTextChanged { render() }
        rows=column();panel.addView(rows)
        setContentView(ScrollView(this).apply { addView(panel) });refresh()
    }
    private fun task(block:suspend ()->Unit) { lifecycleScope.launch {
        try { block() } catch(e:Exception) { AlertDialog.Builder(this@WordLibraryActivity).setMessage(e.message ?: "操作未完成").setPositiveButton("知道了",null).show() }
    } }
    private fun refresh()=task { words=WordLibrary.words(connection);render() }
    private fun render() {
        if(!::rows.isInitialized) return
        rows.removeAllViews();val q=query.text.toString()
        if(history) {
            rows.addView(button("创建备份") { snapshot() })
            rows.addView(button("导入备份 ZIP") { importer.launch("application/zip") })
            WordLibrary.snapshots().filter { (it.name+it.note).contains(q,true) }.forEach { s ->
                rows.addView(button("${s.name}\n${DateFormat.getDateTimeInstance().format(Date(s.time))} · ${s.count} 个词\n${s.note}") { snapshotMenu(s) })
            }
            return
        }
        rows.addView(button("添加人名或术语") { addWord() })
        val rules=WordLibrary.rules().filter { (it.text+it.reading).contains(q,true) }
        if(rules.isNotEmpty()) rows.addView(label("主动调整的候选"))
        rules.forEach { rule -> rows.addView(button("${rule.text} · ${if(rule.mode=="hide") "已忘记" else "已降低优先级"}\n${rule.engine} · ${if(rule.reading=="japanese") "日语候选" else rule.reading.replace("predict:","联想：")}") {
            AlertDialog.Builder(this).setMessage("恢复“${rule.text}”的默认候选行为？").setPositiveButton("恢复") { _,_->WordLibrary.saveRules(WordLibrary.rules()-rule);render() }.setNegativeButton("取消",null).show()
        }) }
        rows.addView(label("个人学习词条"))
        val found=words.filter { (it.text+it.reading).contains(q,true) }
        if(found.isEmpty()) rows.addView(label("没有找到个人词条。基础词库和模型生成的候选，可在键盘上长按处理。"))
        found.take(200).forEach { word -> rows.addView(button("${word.text}\n${word.reading}") {
            AlertDialog.Builder(this).setTitle(word.text).setItems(arrayOf("忘记","降低优先级","学习信息")) { _,i -> when(i) {
                0 -> task { WordLibrary.forget(connection,word);refresh() }
                1 -> { WordLibrary.setRule(word,"lower");render() }
                2 -> AlertDialog.Builder(this).setMessage("词典：${word.dictionary}\n读音：${word.reading}\n学习权重：${word.weight}\n权重是输入引擎的内部记录，用于排序。").setPositiveButton("知道了",null).show()
            } }.show()
        }) }
        if(found.size>200) rows.addView(label("共 ${found.size} 个词，显示前 200 个；输入关键词查找。"))
    }
    private fun addWord() {
        val form=column();val text=field("词条，例如：耍我们玩");val reading=field("拼音，例如：shua wo men wan")
        reading.imeOptions=android.view.inputmethod.EditorInfo.IME_FLAG_FORCE_ASCII or android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        form.addView(text);form.addView(reading)
        val dictionaries=(words.map { it.dictionary }.distinct()+"rime_ice").distinct()
        val dict=Spinner(this).apply { adapter=ArrayAdapter(this@WordLibraryActivity,android.R.layout.simple_spinner_dropdown_item,dictionaries) };form.addView(dict)
        val dialog=AlertDialog.Builder(this).setTitle("添加词条").setView(form).setPositiveButton("保存",null).setNegativeButton("取消",null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            task { WordLibrary.add(connection,dictionaries[dict.selectedItemPosition],text.text.toString().trim(),reading.text.toString().trim().lowercase());dialog.dismiss();refresh() }
        } };dialog.show()
    }
    private fun snapshot() {
        val form=column();val name=field("备份名称","最近打字很顺手");val note=field("备注",multiline=true)
        form.addView(name);form.addView(note)
        AlertDialog.Builder(this).setTitle("创建输入习惯备份").setView(form).setPositiveButton("创建") { _,_->task { WordLibrary.snapshot(connection,name.text.toString(),note.text.toString());render() } }.setNegativeButton("取消",null).show()
    }
    private fun snapshotMenu(s:WordLibrary.Snapshot) {
        AlertDialog.Builder(this).setTitle(s.name).setItems(arrayOf("查看词条变化","导出 ZIP","合并学习数据","恢复此时状态","删除备份")) { _,i -> when(i) {
            0 -> { val (added,removed)=WordLibrary.diff(s)
                AlertDialog.Builder(this).setTitle("相较上次备份").setMessage("新增 ${added.size} 个\n${added.joinToString("\n")}\n\n减少 ${removed.size} 个\n${removed.joinToString("\n")}").setPositiveButton("知道了",null).show() }
            1 -> { exporting=s;exporter.launch("input-habits-${s.id}.zip") }
            2,3 -> AlertDialog.Builder(this).setMessage(if(i==2) "保留当前学习，并合入这份备份。操作前自动保存当前状态。" else "回到这份备份中的学习数据和候选偏好。操作前自动保存当前状态。")
                .setPositiveButton(if(i==2) "合并" else "恢复") { _,_->task { WordLibrary.restore(connection,s,i==2);refresh() } }.setNegativeButton("取消",null).show()
            4 -> AlertDialog.Builder(this).setMessage("删除这份历史备份？").setPositiveButton("删除") { _,_->WordLibrary.delete(s);render() }.setNegativeButton("取消",null).show()
        } }.show()
    }
    override fun onDestroy() { FcitxDaemon.disconnect("word-library");super.onDestroy() }
}

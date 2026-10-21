/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts

class SpeechTestActivity : ExperienceActivity(), RecognitionListener {
    private var recognizer:SpeechRecognizer?=null
    private lateinit var result:TextView
    private lateinit var services:Spinner
    private var components=listOf<ComponentName?>()
    private var pendingOnDevice=false
    private val permission=registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if(granted) start(pendingOnDevice) else result.text="录音权限未开启。可以在系统应用权限中开启后再试。"
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState);title="语音识别测试"
        val list=packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE),0)
        components=listOf(null)+list.map { ComponentName(it.serviceInfo.packageName,it.serviceInfo.name) }
        val panel=column()
        panel.addView(label("调用手机开放的识别服务。音频由所选服务处理，服务可能联网。"))
        panel.addView(label("系统识别：${if(SpeechRecognizer.isRecognitionAvailable(this)) "可用" else "未检测到"}\n设备端识别：${if(Build.VERSION.SDK_INT>=31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) "可用" else "未检测到"}"))
        services=Spinner(this).apply {
            adapter=ArrayAdapter(this@SpeechTestActivity,android.R.layout.simple_spinner_dropdown_item,
                listOf("系统默认服务")+list.map { "${it.loadLabel(packageManager)} (${it.serviceInfo.packageName})" })
        }
        panel.addView(services)
        panel.addView(button("开始识别一句中文") {
            start()
        })
        if(Build.VERSION.SDK_INT>=31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this))
            panel.addView(button("测试设备端中文识别") { start(true) })
        panel.addView(button("结束说话") { recognizer?.stopListening() })
        panel.addView(button("取消") { recognizer?.cancel();result.text="已取消" })
        result=label("等待测试");panel.addView(result)
        setContentView(ScrollView(this).apply { addView(panel) })
    }
    private fun start(onDevice:Boolean=false) {
        pendingOnDevice=onDevice
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) {
            permission.launch(Manifest.permission.RECORD_AUDIO);return
        }
        recognizer?.destroy();recognizer=null
        try {
            recognizer=if(onDevice && Build.VERSION.SDK_INT>=31) SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
            else components.getOrNull(services.selectedItemPosition)?.let { SpeechRecognizer.createSpeechRecognizer(this,it) }
                ?: SpeechRecognizer.createSpeechRecognizer(this)
            recognizer!!.setRecognitionListener(this)
            recognizer!!.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE,"zh-CN")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,true)
            })
            result.text="正在连接识别服务…"
        } catch(e:Exception) { result.text="服务启动失败：${e.message}";recognizer?.destroy();recognizer=null }
    }
    override fun onReadyForSpeech(params:Bundle?) { result.text="请说一句中文" }
    override fun onBeginningOfSpeech() { result.text="正在聆听…" }
    override fun onRmsChanged(rmsdB:Float) {}
    override fun onBufferReceived(buffer:ByteArray?) {}
    override fun onEndOfSpeech() { result.text="正在识别…" }
    override fun onError(error:Int) { result.text=when(error) {
        SpeechRecognizer.ERROR_AUDIO -> "无法录音，请检查麦克风是否被其他应用占用。"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "识别服务缺少录音权限。"
        SpeechRecognizer.ERROR_NETWORK,SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "识别服务联网失败，请检查网络。"
        SpeechRecognizer.ERROR_NO_MATCH -> "没有识别到文字，可以再试一句。"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "等待说话超时。"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "服务正忙，请稍后再试。"
        12,13 -> "服务不支持中文，或尚未安装中文识别数据。"
        else -> "识别服务返回错误 $error"
    } }
    override fun onResults(results:Bundle?) { result.text=results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.joinToString("\n") ?: "没有识别结果" }
    override fun onPartialResults(partialResults:Bundle?) { partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { result.text=it } }
    override fun onEvent(eventType:Int,params:Bundle?) {}
    override fun onStop() { recognizer?.cancel();super.onStop() }
    override fun onDestroy() { recognizer?.destroy();recognizer=null;super.onDestroy() }
}

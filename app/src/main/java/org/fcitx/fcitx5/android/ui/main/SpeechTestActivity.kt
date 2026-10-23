/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts

class SpeechTestActivity : ExperienceActivity() {
    private lateinit var result: TextView
    private lateinit var services: Spinner
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private var deviceButton: Button? = null
    private var choices = listOf<SpeechSession.Target>()
    private var pending: SpeechSession.Target? = null
    private val session = SpeechSession(::createBackend) { text, state ->
        result.text = text
        val idle = state == SpeechSession.State.IDLE && pending == null
        startButton.isEnabled = idle
        deviceButton?.isEnabled = idle
        services.isEnabled = idle
        stopButton.isEnabled = state == SpeechSession.State.LISTENING
    }
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val target = pending
        pending = null
        if (isFinishing || isDestroyed) return@registerForActivityResult
        session.cancel()
        if (target == null) return@registerForActivityResult
        if (granted) launch(target)
        else result.text = "录音权限未开启。可以在系统应用权限中开启后再试。"
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "语音识别测试"
        val list = packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
            .filter { it.serviceInfo.enabled && it.serviceInfo.applicationInfo.enabled }
        choices = listOf(SpeechSession.Target(null, "系统默认服务")) + list.map {
            SpeechSession.Target(ComponentName(it.serviceInfo.packageName, it.serviceInfo.name).flattenToString(),
                "${it.loadLabel(packageManager)} (${it.serviceInfo.packageName})")
        }
        val panel = column()
        panel.addView(label("调用手机开放的识别服务。音频由所选服务处理，服务可能联网。"))
        panel.addView(label("系统默认服务：${if (defaultComponent() != null) "可用" else "未配置或不可用，请手动选择已安装服务"}\n已检测到 ${list.size} 个识别服务"))
        services = Spinner(this).apply {
            adapter = ArrayAdapter(this@SpeechTestActivity, android.R.layout.simple_spinner_dropdown_item, choices.map { it.label })
        }
        panel.addView(services)
        startButton = button("开始识别一句中文") { requestStart(choices[services.selectedItemPosition]) }
        panel.addView(startButton)
        if (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
            deviceButton = button("测试设备端中文识别") { requestStart(SpeechSession.Target(null, "设备端服务", true)) }
            panel.addView(deviceButton)
        }
        stopButton = button("结束说话") { session.stop() }.apply { isEnabled = false }
        panel.addView(stopButton)
        panel.addView(button("取消") { pending = null; session.cancel() })
        result = label("等待测试")
        panel.addView(result)
        setContentView(ScrollView(this).apply { addView(panel) })
    }
    private fun defaultComponent(): String? {
        val configured = Settings.Secure.getString(contentResolver, "voice_recognition_service")
            ?.let(ComponentName::unflattenFromString)?.flattenToString()
        return SpeechSession.resolveDefault(configured, choices.mapNotNull { it.component }.toSet())
    }
    private fun requestStart(selected: SpeechSession.Target) {
        if (session.state != SpeechSession.State.IDLE || pending != null) return
        val target = if (selected.onDevice || selected.component != null) selected else {
            val component = defaultComponent()
            if (component == null) {
                result.text = if (choices.size == 1) "未检测到可用识别服务。请先安装或启用支持中文的语音识别服务。"
                else "系统默认识别服务未配置或不可用。请在上方手动选择一个已安装服务后开始。"
                return
            }
            selected.copy(component = component)
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pending = target
            startButton.isEnabled = false
            deviceButton?.isEnabled = false
            services.isEnabled = false
            permission.launch(Manifest.permission.RECORD_AUDIO)
        } else launch(target)
    }
    private fun launch(target: SpeechSession.Target) {
        if (!target.onDevice) {
            val component = target.component?.let(ComponentName::unflattenFromString)
            val available = component != null && packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
                .any { it.serviceInfo.enabled && it.serviceInfo.applicationInfo.enabled &&
                    ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) == component }
            if (!available) { result.text = "所选识别服务已停用或移除。请重新打开此页面后选择可用服务。"; return }
        }
        session.start(target)
    }
    private fun createBackend(target: SpeechSession.Target, events: SpeechSession.Events): SpeechSession.Backend =
        AndroidSpeechBackend(this, target, events)
    override fun onStop() { session.cancel(); super.onStop() }
    override fun onDestroy() { pending = null; session.cancel(); super.onDestroy() }
}

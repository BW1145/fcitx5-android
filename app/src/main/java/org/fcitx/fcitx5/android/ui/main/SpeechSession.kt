/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main

/** Main-thread session owner. Each backend belongs to exactly one generation. */
internal class SpeechSession(
    private val create: (Target, Events) -> Backend,
    private val render: (String, State) -> Unit
) {
    data class Target(val component: String?, val label: String, val onDevice: Boolean = false)
    enum class State { IDLE, CONNECTING, LISTENING, PROCESSING }
    interface Backend {
        fun start()
        fun stop()
        fun cancel()
        fun destroy()
    }
    interface Events {
        fun ready()
        fun beginning()
        fun end()
        fun partial(text: String)
        fun results(text: String)
        fun error(code: Int)
    }
    var state = State.IDLE
        private set
    private var generation = 0L
    private var backend: Backend? = null

    fun start(target: Target) {
        if (state != State.IDLE) return
        val id = ++generation
        update("正在连接${target.label}…", State.CONNECTING)
        val events = object : Events {
            private fun active() = id == generation && state != State.IDLE
            override fun ready() { if (active() && state == State.CONNECTING) update("请说一句中文", State.LISTENING) }
            override fun beginning() { if (active() && state == State.LISTENING) update("正在聆听…", State.LISTENING) }
            override fun end() { if (active()) update("正在识别…", State.PROCESSING) }
            override fun partial(text: String) { if (active()) update(text, state) }
            override fun results(text: String) { if (active()) finish(text) }
            override fun error(code: Int) { if (active()) finish(errorMessage(code, target.label)) }
        }
        try {
            val created = create(target, events)
            // A backend factory may synchronously report a terminal failure.
            if (id != generation) { created.destroy(); return }
            backend = created
            created.start()
        } catch (_: RuntimeException) {
            if (id == generation) finish("${target.label}启动失败。请检查该服务是否已启用及其录音权限，再重新尝试。")
        }
    }
    fun stop() {
        if (state != State.LISTENING) return
        update("正在识别…", State.PROCESSING)
        try { backend?.stop() } catch (_: RuntimeException) { finish("结束录音失败，请重新开始。") }
    }
    fun cancel() { finish("已取消", true) }
    private fun finish(text: String, cancel: Boolean = false) {
        ++generation // Invalidate callbacks BEFORE invoking service cleanup.
        val old = backend
        backend = null
        state = State.IDLE
        try { if (cancel) old?.cancel() } catch (_: RuntimeException) { /* still destroy */ }
        try { old?.destroy() } catch (_: RuntimeException) { /* detached */ }
        update(text, State.IDLE)
    }
    private fun update(text: String, next: State) { state = next; render(text, next) }
    companion object {
        fun errorMessage(code: Int, label: String): String = when (code) {
            3 -> "无法录音，请检查麦克风是否被其他应用占用。"
            9 -> "识别服务缺少录音权限。"
            1, 2 -> "识别服务联网失败，请检查网络。"
            5 -> "$label 的识别连接中断或启动失败（错误 5）。请检查该服务是否已启用；也可在上方手动选择其他已安装服务后重试。"
            7 -> "没有识别到文字，可以再试一句。"
            6 -> "等待说话超时。"
            8 -> "服务正忙，请稍后再试。"
            12, 13 -> "服务不支持中文，或尚未安装中文识别数据。"
            else -> "$label 识别失败（错误 $code），请稍后重试。"
        }
        fun resolveDefault(configured: String?, available: Set<String>): String? =
            configured?.takeIf { it in available }
    }
}

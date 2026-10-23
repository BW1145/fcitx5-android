/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/** Thin platform adapter, also exercised with a test-only RecognitionService. */
internal class AndroidSpeechBackend(
    context: Context,
    target: SpeechSession.Target,
    events: SpeechSession.Events,
    private val intent: Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
    }
) : SpeechSession.Backend {
    private val recognizer = if (target.onDevice && Build.VERSION.SDK_INT >= 31)
        SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
    else SpeechRecognizer.createSpeechRecognizer(context,
        requireNotNull(ComponentName.unflattenFromString(requireNotNull(target.component))))
    init {
        try {
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = events.ready()
                override fun onBeginningOfSpeech() = events.beginning()
                override fun onEndOfSpeech() = events.end()
                override fun onError(error: Int) = events.error(error)
                override fun onResults(results: Bundle?) = events.results(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.joinToString("\n") ?: "没有识别结果")
                override fun onPartialResults(partialResults: Bundle?) {
                    partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(events::partial)
                }
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        } catch (e: RuntimeException) { recognizer.destroy(); throw e }
    }
    override fun start() = recognizer.startListening(intent)
    override fun stop() = recognizer.stopListening()
    override fun cancel() = recognizer.cancel()
    override fun destroy() = recognizer.destroy()
}

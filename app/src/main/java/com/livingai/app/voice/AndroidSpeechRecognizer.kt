package com.livingai.app.voice

import android.content.Context
import android.content.Intent
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Real Android speech recognition (android.speech.SpeechRecognizer) — no cloud STT service of
 * our own, uses whatever recognizer the OS provides (Google's on this device). One-shot: starts
 * listening only when explicitly invoked, stops itself after a result or silence.
 */
class AndroidSpeechRecognizer(private val context: Context) {

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    suspend fun listenOnce(): Result<String> = suspendCancellableCoroutine { cont ->
        if (!isAvailable()) {
            cont.resume(Result.failure(IllegalStateException("Speech recognition not available on this device")))
            return@suspendCancellableCoroutine
        }

        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: android.os.Bundle) {
                val text = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!cont.isCompleted) {
                    cont.resume(if (text != null) Result.success(text) else Result.failure(IllegalStateException("No speech recognized")))
                }
                recognizer.destroy()
            }

            override fun onError(error: Int) {
                if (!cont.isCompleted) {
                    cont.resume(Result.failure(IllegalStateException("Speech recognition error code=$error")))
                }
                recognizer.destroy()
            }

            override fun onReadyForSpeech(params: android.os.Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(partialResults: android.os.Bundle?) = Unit
            override fun onEvent(eventType: Int, params: android.os.Bundle?) = Unit
        })

        cont.invokeOnCancellation { recognizer.destroy() }
        recognizer.startListening(intent)
    }
}

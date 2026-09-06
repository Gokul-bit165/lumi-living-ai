package com.livingai.app.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume

/** Real Android TTS. Speaks the AI's response; app remains usable via text if this fails. */
class AndroidTextToSpeech(context: Context) {

    private var tts: TextToSpeech? = null
    private var ready = false

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) tts?.language = Locale.getDefault()
        }
    }

    suspend fun speak(text: String): Result<Unit> {
        val engine = tts
        if (engine == null || !ready) return Result.failure(IllegalStateException("TTS not ready"))
        return suspendCancellableCoroutine { cont ->
            val utteranceId = System.currentTimeMillis().toString()
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) {
                    if (!cont.isCompleted) cont.resume(Result.success(Unit))
                }
                override fun onError(utteranceId: String?) {
                    if (!cont.isCompleted) cont.resume(Result.failure(IllegalStateException("TTS playback error")))
                }
            })
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        }
    }

    fun stop() {
        tts?.stop()
    }

    fun close() {
        tts?.shutdown()
    }
}

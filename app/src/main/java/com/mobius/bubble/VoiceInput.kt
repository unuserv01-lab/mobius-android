package com.mobius.bubble

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Voice input for the bubble: Android's built-in SpeechRecognizer (free, no new AI, no new
 * dependency). One-shot listen: returns the best recognized text, or null when recognition
 * is unavailable, denied, silent, or times out. Callers treat null as "type instead" —
 * voice is a convenience, never a requirement (P8).
 *
 * Must be called from the main thread (SpeechRecognizer requires the main looper). The
 * RECORD_AUDIO runtime permission is requested in onboarding; without it the recognizer
 * reports ERROR_INSUFFICIENT_PERMISSIONS and we return null.
 */
object VoiceInput {

    fun isAvailable(context: Context): Boolean =
        SpeechRecognizer.isRecognitionAvailable(context)

    suspend fun listenOnce(context: Context, timeoutMs: Long = 12_000): String? {
        if (!isAvailable(context)) return null
        return suspendCancellableCoroutine { cont ->
            val recognizer = try {
                SpeechRecognizer.createSpeechRecognizer(context.applicationContext)
            } catch (e: Exception) {
                null
            }
            if (recognizer == null) {
                if (cont.isActive) cont.resume(null)
                return@suspendCancellableCoroutine
            }
            val handler = Handler(Looper.getMainLooper())
            var done = false
            fun finish(result: String?) {
                if (done) return
                done = true
                handler.removeCallbacksAndMessages(null)
                runCatching { recognizer.destroy() }
                if (cont.isActive) cont.resume(result)
            }
            handler.postDelayed({ finish(null) }, timeoutMs)
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    finish(list?.firstOrNull()?.takeIf { it.isNotBlank() })
                }

                override fun onError(error: Int) = finish(null)
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "id-ID")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            }
            try {
                recognizer.startListening(intent)
            } catch (e: Exception) {
                finish(null)
            }
            cont.invokeOnCancellation { finish(null) }
        }
    }
}

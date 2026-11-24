package com.mobius.ai

import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.mobius.core.AppConfig
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * Thrown when the cloud call does not answer within [CloudAI.CLOUD_TIMEOUT_MS].
 * A NORMAL failure, not a coroutine cancellation: callers show [message] to the
 * user verbatim as the task outcome.
 */
class CloudTimeoutException(message: String) : Exception(message)

/**
 * Gemini cloud through Firebase AI Logic (Gemini Developer API backend = free tier, P9).
 * "Borrowed intelligence" (P4): this class has no gate of its own. It must only ever be called
 * from [AIRouter], which checks the "Allow cloud AI" setting and logs every call.
 * App Check (debug provider, installed in MobiusApp) protects the backend call.
 */
class CloudAI {
    private val model by lazy {
        Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(AppConfig.CLOUD_MODEL)
    }

    suspend fun generate(prompt: String): String {
        val text = try {
            withTimeout(CLOUD_TIMEOUT_MS) { model.generateContent(prompt) }.text
        } catch (e: TimeoutCancellationException) {
            // v7.1: convert the timeout into a normal failure with a user-facing
            // message. If it escaped as a CancellationException it would bypass
            // the normal outcome path and could strand the bubble on
            // "Understanding your request..." forever on a slow connection.
            throw CloudTimeoutException(CLOUD_TIMEOUT_MESSAGE)
        }
        require(!text.isNullOrBlank()) { "Gemini cloud returned an empty response" }
        return text
    }

    companion object {
        /**
         * v7.1: Tuan's work connection runs at ~0,1 KB/d — a bare generateContent()
         * hangs forever instead of failing fast. 30 s is enough for a healthy
         * connection and short enough to fail fast on a bad one.
         */
        const val CLOUD_TIMEOUT_MS = 30_000L
        const val CLOUD_TIMEOUT_MESSAGE =
            "Cloud tidak merespons dalam 30 detik (koneksi lambat). Coba lagi saat sinyal lebih baik."
    }
}

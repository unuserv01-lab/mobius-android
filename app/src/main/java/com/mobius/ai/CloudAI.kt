package com.mobius.ai

import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.mobius.core.AppConfig

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
        val text = model.generateContent(prompt).text
        require(!text.isNullOrBlank()) { "Gemini cloud returned an empty response" }
        return text
    }
}

package com.mobius.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Thrown when the OpenRouter call fails (HTTP error, unreadable response, timeout).
 * A NORMAL failure, like [CloudTimeoutException]: the caller ([AIRouter]) treats it
 * as "fallback did not work either" and surfaces the ORIGINAL Gemini failure.
 * The message never contains the API key — only the HTTP status / a short reason.
 */
class OpenRouterException(message: String) : Exception(message)

/**
 * v7.2 — Fallback cloud model via OpenRouter (https://openrouter.ai/api/v1).
 * Called ONLY from [AIRouter] after Gemini cloud has already failed, and only when
 * Tuan stored an OpenRouter API key in Settings. No key = no call, ever.
 *
 * Stdlib only: HttpURLConnection + org.json, no new dependencies (same pattern as
 * [com.mobius.core.RemoteGateway]). The key stays on the phone: it is sent as a
 * Bearer header and never logged, never put in any message, never stored anywhere
 * except the app-private DataStore.
 */
class OpenRouterAI {
    suspend fun generate(prompt: String, apiKey: String, model: String): String {
        val text = try {
            withTimeout(OPENROUTER_TIMEOUT_MS) {
                withContext(Dispatchers.IO) { post(prompt, apiKey, model) }
            }
        } catch (e: TimeoutCancellationException) {
            // Same rule as CloudAI: a timeout is a normal failure, not a coroutine
            // cancellation — it must reach the outcome path, never strand the bubble.
            throw OpenRouterException("OpenRouter timeout ${OPENROUTER_TIMEOUT_MS / 1000} detik")
        }
        require(text.isNotBlank()) { "OpenRouter returned an empty response" }
        return text
    }

    private fun post(prompt: String, apiKey: String, model: String): String {
        val body = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            })
        }
        val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = OPENROUTER_TIMEOUT_MS.toInt()
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("HTTP-Referer", "https://mobius.local")
            setRequestProperty("X-Title", "MOBIUS")
        }
        try {
            conn.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body.toString()) }
            val code = conn.responseCode
            val raw = readBody(conn)
            if (code != HttpURLConnection.HTTP_OK) {
                throw OpenRouterException("OpenRouter HTTP $code")
            }
            return JSONObject(raw)
                .optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.optString("content")
                ?.takeIf { it.isNotBlank() }
                ?: throw OpenRouterException("OpenRouter: respons kosong / tak terbaca")
        } finally {
            conn.disconnect()
        }
    }

    private fun readBody(conn: HttpURLConnection): String {
        val stream = if (conn.responseCode < HttpURLConnection.HTTP_BAD_REQUEST) {
            conn.inputStream
        } else {
            conn.errorStream
        } ?: return ""
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    companion object {
        const val ENDPOINT = "https://openrouter.ai/api/v1/chat/completions"
        /** Same 30 s as the v7.1 Gemini timeout: fail fast on a bad connection. */
        const val OPENROUTER_TIMEOUT_MS = 30_000L
    }
}

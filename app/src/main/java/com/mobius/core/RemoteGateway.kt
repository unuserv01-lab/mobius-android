package com.mobius.core

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

private val Context.remoteGatewayDataStore: DataStore<Preferences> by preferencesDataStore(name = "remote_gateway")

/**
 * v6 Workstream K part 2 — minimal Android client for the laptop bridge
 * (~/workspace/mobius-extension/bridge/README.md).
 *
 *   HP Android --(Wi-Fi/LAN, HTTP)--> bridge (laptop, port 8787) --> extension Chrome
 *
 * Stdlib only: HttpURLConnection + org.json, no new dependencies. No Nano involved.
 *
 * Settings (address + token) live in this class's OWN DataStore file "remote_gateway"
 * — separate from PermissionManager's "mobius_prefs" and from the "feature_flags" store.
 * The token is entered by Tuan in Settings and stored in DataStore only: it is never
 * hard-coded, never logged, and never sent anywhere except the bridge itself.
 *
 * Degradation contract: if the bridge cannot be reached, [sendTaskAndWait] returns a
 * CLEAR Indonesian message ("Bridge tidak terjangkau — lanjut lokal saja") instead of
 * throwing or hanging. When the REMOTE_GATEWAY flag is OFF, no remote call is made at
 * all — every entry point refuses with the local-only message (v5 behavior).
 */
class RemoteGateway(
    context: Context,
    private val featureFlags: FeatureFlags = FeatureFlags(context),
) {
    private val appContext = context.applicationContext
    private val store: DataStore<Preferences> get() = appContext.remoteGatewayDataStore

    // ---- Settings (DataStore "remote_gateway") ----

    /** Stored bridge address, or [DEFAULT_BRIDGE_ADDRESS] when nothing is stored yet. */
    fun bridgeAddressFlow(): Flow<String> =
        store.data.map { it[KEY_ADDRESS] ?: DEFAULT_BRIDGE_ADDRESS }

    /** Stored bridge token, or "" when nothing is stored yet. */
    fun bridgeTokenFlow(): Flow<String> =
        store.data.map { it[KEY_TOKEN] ?: "" }

    suspend fun getBridgeAddress(): String = bridgeAddressFlow().first()

    suspend fun getBridgeToken(): String = bridgeTokenFlow().first()

    suspend fun setBridgeAddress(address: String) {
        store.edit { it[KEY_ADDRESS] = address.trim() }
    }

    suspend fun setBridgeToken(token: String) {
        store.edit { it[KEY_TOKEN] = token.trim() }
    }

    /** Save address + token together (one DataStore edit). */
    suspend fun saveSettings(address: String, token: String) {
        store.edit {
            it[KEY_ADDRESS] = address.trim()
            it[KEY_TOKEN] = token.trim()
        }
    }

    /** True when the REMOTE_GATEWAY flag is ON (default true). OFF = local-only, no calls. */
    suspend fun isGatewayEnabled(): Boolean =
        featureFlags.isEnabled(FeatureFlag.REMOTE_GATEWAY).first()

    // ---- Bridge API ----

    /** GET /health (no auth). False when the flag is OFF, the address is blank, or the bridge does not answer. */
    suspend fun health(): Boolean {
        if (!isGatewayEnabled()) return false
        val base = normalizeAddress(getBridgeAddress()) ?: return false
        return withContext(Dispatchers.IO) {
            try {
                val conn = openConnection("$base/health", token = null, HEALTH_TIMEOUT_MS, HEALTH_TIMEOUT_MS)
                try {
                    conn.requestMethod = "GET"
                    conn.connect()
                    conn.responseCode == HttpURLConnection.HTTP_OK
                } finally {
                    conn.disconnect()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "health() failed", e)
                false
            }
        }
    }

    /**
     * POST /task {"text": ..., "source": "android"} with header X-Mobius-Token.
     * Returns the taskId on HTTP 202, or null on any failure (unreachable, wrong token,
     * rejected request, bad response). Never throws (except coroutine cancellation).
     */
    suspend fun submitTask(text: String): String? {
        return when (val r = submitTaskInternal(text)) {
            is SubmitResult.Success -> r.taskId
            else -> null
        }
    }

    /**
     * GET /task/:id until status is done/failed/expired or [timeoutMs] elapses (poll every 3s).
     * Returns the result text when done, or null when failed/expired/timed out/unreachable.
     * Never throws (except coroutine cancellation).
     */
    suspend fun pollResult(taskId: String, timeoutMs: Long = DEFAULT_POLL_TIMEOUT_MS): String? {
        return when (val outcome = pollInternal(taskId, timeoutMs)) {
            is PollOutcome.Done -> outcome.result
            else -> null
        }
    }

    /**
     * Submit + poll in one call. Returns the result text on success, or a CLEAR message
     * (never a crash, never a silent hang) when:
     * - the REMOTE_GATEWAY flag is OFF -> [MSG_GATEWAY_OFF]
     * - the bridge is unreachable -> [MSG_UNREACHABLE]
     * - the token is wrong -> [MSG_UNAUTHORIZED]
     * - the task failed on the laptop -> [MSG_TASK_FAILED] (+ laptop's error text, if any)
     * - the bridge does not answer within the poll timeout -> [MSG_TIMEOUT]
     * The phone degrades to local-only with that message.
     */
    suspend fun sendTaskAndWait(text: String): String {
        if (!isGatewayEnabled()) return MSG_GATEWAY_OFF
        return when (val submitted = submitTaskInternal(text)) {
            is SubmitResult.Success -> {
                when (val outcome = pollInternal(submitted.taskId, DEFAULT_POLL_TIMEOUT_MS)) {
                    is PollOutcome.Done ->
                        outcome.result?.takeIf { it.isNotBlank() }
                            ?: "Tugas selesai di laptop (tanpa hasil teks) — lanjut lokal saja"
                    is PollOutcome.Failed ->
                        if (outcome.result.isNullOrBlank()) MSG_TASK_FAILED
                        else "$MSG_TASK_FAILED_PREFIX: ${outcome.result} — lanjut lokal saja"
                    is PollOutcome.TimedOut -> MSG_TIMEOUT
                }
            }
            is SubmitResult.Unauthorized -> MSG_UNAUTHORIZED
            is SubmitResult.Unreachable -> MSG_UNREACHABLE
        }
    }

    // ---- Internals ----

    private sealed interface SubmitResult {
        data class Success(val taskId: String) : SubmitResult
        data object Unreachable : SubmitResult
        data object Unauthorized : SubmitResult
    }

    private sealed interface PollOutcome {
        data class Done(val result: String?) : PollOutcome
        data class Failed(val result: String?) : PollOutcome
        data object TimedOut : PollOutcome
    }

    private suspend fun submitTaskInternal(text: String): SubmitResult {
        if (!isGatewayEnabled()) return SubmitResult.Unreachable
        val base = normalizeAddress(getBridgeAddress()) ?: return SubmitResult.Unreachable
        val token = getBridgeToken()
        if (token.isBlank()) return SubmitResult.Unauthorized
        // Bridge rejects text longer than 10.000 chars; truncate instead of failing.
        val body = JSONObject().apply {
            put("text", text.take(MAX_TASK_TEXT_CHARS))
            put("source", "android")
        }.toString()
        return withContext(Dispatchers.IO) {
            try {
                val conn = openConnection("$base/task", token, CONNECT_TIMEOUT_MS, SUBMIT_READ_TIMEOUT_MS)
                try {
                    conn.requestMethod = "POST"
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                    when (conn.responseCode) {
                        202 -> {
                            val taskId = runCatching {
                                JSONObject(readBody(conn)).optString("taskId", "")
                            }.getOrDefault("")
                            if (taskId.isNotBlank()) SubmitResult.Success(taskId)
                            else SubmitResult.Unreachable
                        }
                        HttpURLConnection.HTTP_UNAUTHORIZED -> SubmitResult.Unauthorized
                        else -> SubmitResult.Unreachable
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "submitTask failed", e)
                SubmitResult.Unreachable
            }
        }
    }

    private suspend fun pollInternal(taskId: String, timeoutMs: Long): PollOutcome {
        if (!isGatewayEnabled()) return PollOutcome.TimedOut
        val base = normalizeAddress(getBridgeAddress()) ?: return PollOutcome.TimedOut
        val token = getBridgeToken()
        if (token.isBlank()) return PollOutcome.TimedOut
        val deadline = System.currentTimeMillis() + timeoutMs
        val encodedId = URLEncoder.encode(taskId, "UTF-8")
        while (true) {
            val outcome: PollOutcome? = withContext(Dispatchers.IO) {
                try {
                    val conn = openConnection("$base/task/$encodedId", token, CONNECT_TIMEOUT_MS, POLL_READ_TIMEOUT_MS)
                    try {
                        conn.requestMethod = "GET"
                        conn.connect()
                        if (conn.responseCode != HttpURLConnection.HTTP_OK) return@withContext null
                        val json = JSONObject(readBody(conn))
                        when (json.optString("status", "")) {
                            "done" -> PollOutcome.Done(json.optString("result", null as String?))
                            "failed" -> PollOutcome.Failed(json.optString("result", null as String?))
                            "expired" -> PollOutcome.TimedOut
                            else -> null // queued / running: keep polling
                        }
                    } finally {
                        conn.disconnect()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "pollResult failed", e)
                    null // transient network error: retry until the deadline
                }
            }
            if (outcome != null) return outcome
            if (System.currentTimeMillis() >= deadline) return PollOutcome.TimedOut
            delay(POLL_INTERVAL_MS)
            if (System.currentTimeMillis() >= deadline) return PollOutcome.TimedOut
        }
    }

    private fun openConnection(
        url: String,
        token: String?,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = connectTimeoutMs
        conn.readTimeout = readTimeoutMs
        conn.setRequestProperty("Accept", "application/json")
        if (!token.isNullOrBlank()) conn.setRequestProperty("X-Mobius-Token", token)
        return conn
    }

    private fun readBody(conn: HttpURLConnection): String {
        val stream = if (conn.responseCode < HttpURLConnection.HTTP_BAD_REQUEST) {
            conn.inputStream
        } else {
            conn.errorStream
        } ?: return ""
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    /**
     * Trims trailing slashes; prepends "http://" when no scheme is given.
     * Returns null when the address is blank.
     */
    private fun normalizeAddress(raw: String): String? {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.isBlank()) return null
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed
        else "http://$trimmed"
    }

    companion object {
        private const val TAG = "RemoteGateway"

        /** Example default — Tuan MUST replace 192.168.1.100 with his laptop's LAN IP. */
        const val DEFAULT_BRIDGE_ADDRESS = "http://192.168.1.100:8787"

        /** Bridge rejects task text longer than 10.000 chars (bridge README). */
        const val MAX_TASK_TEXT_CHARS = 10_000

        /** Clear user-facing messages (Indonesian). The phone degrades to local-only with these. */
        const val MSG_GATEWAY_OFF = "Remote gateway dimatikan — lanjut lokal saja"
        const val MSG_UNREACHABLE = "Bridge tidak terjangkau — lanjut lokal saja"
        const val MSG_UNAUTHORIZED = "Token bridge salah — periksa token di Settings, lanjut lokal saja"
        const val MSG_TASK_FAILED = "Tugas gagal di laptop — lanjut lokal saja"
        const val MSG_TASK_FAILED_PREFIX = "Tugas gagal di laptop"
        const val MSG_TIMEOUT = "Bridge tidak menjawab dalam 60 detik — lanjut lokal saja"

        const val DEFAULT_POLL_TIMEOUT_MS = 60_000L
        private const val POLL_INTERVAL_MS = 3_000L
        private const val HEALTH_TIMEOUT_MS = 5_000
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val SUBMIT_READ_TIMEOUT_MS = 10_000
        private const val POLL_READ_TIMEOUT_MS = 5_000

        private val KEY_ADDRESS = stringPreferencesKey("bridge_address")
        private val KEY_TOKEN = stringPreferencesKey("bridge_token")
    }
}

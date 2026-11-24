package com.mobius.ai

import com.mobius.core.AppConfig
import com.mobius.core.MemoryStore
import com.mobius.core.PermissionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import org.json.JSONObject

/** Result of one routed generation. */
data class AIResponse(val text: String, val model: String, val reason: String)

/** Nano's triage verdict (v6 L): light = stay on-device, heavy = goes to cloud. */
data class NanoTriageVerdict(val light: Boolean, val reason: String)

/**
 * Thrown when a task needs cloud AI but the user has not allowed it. Never calls the cloud.
 * The message is the exact Indonesian outcome text the user sees (bubble + Home) —
 * it names the toggle to flip and the reason Nano cannot cover for it. [reason] is
 * kept for the routing log / debugging; it is NOT shown to the user.
 */
class CloudRequiredException(val reason: String) : Exception(CLOUD_GATE_MESSAGE) {
    companion object {
        const val CLOUD_GATE_MESSAGE =
            "Butuh cloud AI tapi 'Izinkan AI cloud' masih OFF. Nyalakan di Pengaturan, lalu coba lagi. (Nano tidak tersedia di HP ini.)"
    }
}

/**
 * Decides on-device vs cloud for every prompt and LOGS every decision (which model, why).
 *
 * Rules (spec sections 6 and 7):
 *  1. Gemini Nano first, for light tasks, whenever it is available.
 *  2. Cloud only if the task is heavy OR Nano is unavailable/failed, AND "Allow cloud AI" is ON.
 *  3. If cloud would be needed but is OFF: never call it, log "blocked", throw [CloudRequiredException].
 *     (Exception: a heavy task while Nano IS available and cloud is OFF simply stays on-device.)
 */
class AIRouter(
    private val onDevice: OnDeviceAI,
    private val cloud: CloudAI,
    private val permissions: PermissionManager,
    private val memory: MemoryStore,
    private val openRouter: OpenRouterAI,
) {
    suspend fun generate(
        taskId: String,
        prompt: String,
        needsHeavyReasoning: Boolean = false,
    ): AIResponse {
        val nano = onDevice.status()
        val nanoReady = nano == NanoStatus.AVAILABLE
        var nanoFailure: String? = null

        // 1) On-device first (light tasks).
        if (nanoReady && !needsHeavyReasoning) {
            try {
                val text = onDevice.generate(prompt)
                return record(taskId, MODEL_ON_DEVICE, "Gemini Nano available, light task", text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                nanoFailure = "Nano error: ${e.message?.take(80)}"
            }
        }

        // 2) Why would we need the cloud?
        val cloudReason = when {
            needsHeavyReasoning -> "heavy reasoning"
            !nanoReady -> "Nano ${nano.label}"
            else -> nanoFailure ?: "on-device failure"
        }

        // 3) Cloud gate (default OFF).
        if (!permissions.isCloudAiAllowed()) {
            if (nanoReady && needsHeavyReasoning) {
                // Heavy task but cloud is off: degrade to on-device instead of failing.
                try {
                    val text = onDevice.generate(prompt)
                    return record(taskId, MODEL_ON_DEVICE, "Heavy task, cloud AI OFF -> staying on-device", text)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    nanoFailure = "Nano error: ${e.message?.take(80)}"
                }
            }
            val why = "$cloudReason -> cloud needed but \"Allow cloud AI\" is OFF"
            log(taskId, MODEL_BLOCKED, why)
            throw CloudRequiredException(nanoFailure ?: cloudReason)
        }

        // 4) Cloud allowed by the user: call it and log it.
        // v7.2: on ANY Gemini failure (timeout or error), try the OpenRouter fallback
        // ONCE — but only when Tuan stored a key. No key = exactly the v7.1 behavior
        // (the original Gemini exception escapes to the normal outcome path).
        try {
            val text = cloud.generate(prompt)
            return record(taskId, MODEL_CLOUD, "$cloudReason -> cloud (allowed by user)", text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val key = permissions.getOpenRouterApiKey()
            if (key.isBlank()) throw e
            val model = permissions.getOpenRouterModel()
                .ifBlank { AppConfig.DEFAULT_OPENROUTER_MODEL }
            try {
                val text = openRouter.generate(prompt, key, model)
                return record(
                    taskId, MODEL_OPENROUTER,
                    "Gemini failed (${e.message?.take(80)}) -> OpenRouter $model", text,
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (oe: Exception) {
                log(taskId, MODEL_OPENROUTER, "OpenRouter $model failed: ${oe.message?.take(80)}")
                throw e // original Gemini failure: v7.1 outcome message, unchanged
            }
        }
    }

    /**
     * v6 (L) — Nano triage: BEFORE the planning loop / heavy work starts, ask Nano (when it is
     * AVAILABLE) for a tiny structured verdict: light tasks stay on-device, heavy tasks go to
     * cloud. Returns null when Nano is UNAVAILABLE (e.g. Tuan's Xiaomi has no AICore — the
     * real device) or when Nano fails / answers unparsably: the caller then skips triage and
     * everything goes through the normal [generate] path (cloud if allowed, else a clear
     * CloudRequiredException). Every triage decision is logged in the routing log via [log].
     *
     * This calls [OnDeviceAI.generate] DIRECTLY, never [generate], so triage itself can never
     * fall through to the cloud: a classification prompt must not cost a cloud call, and on
     * a Nano-less device it must cost nothing at all.
     */
    suspend fun triage(taskId: String, userText: String): NanoTriageVerdict? {
        if (onDevice.status() != NanoStatus.AVAILABLE) return null
        return try {
            val text = onDevice.generate(PromptBuilder.triagePrompt(userText))
            val json = JSONObject(text.substring(text.indexOf('{'), text.lastIndexOf('}') + 1))
            val light = when (json.optString("verdict").lowercase().trim()) {
                "light" -> true
                "heavy" -> false
                else -> return null // unparsable verdict: skip triage, do not guess
            }
            val verdict = NanoTriageVerdict(light, json.optString("reason", "").take(120))
            log(
                taskId, "nano-triage",
                "Nano triage: ${if (light) "light — stays on-device" else "heavy — goes to cloud"}" +
                    (if (verdict.reason.isNotBlank()) " (${verdict.reason})" else ""),
            )
            verdict
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null // Nano failed mid-triage: skip triage, never fail the task for it
        }
    }

    private suspend fun record(taskId: String, model: String, reason: String, text: String): AIResponse {
        log(taskId, model, reason)
        return AIResponse(text.trim(), model, reason)
    }

    private suspend fun log(taskId: String, model: String, reason: String) {
        memory.logRouting(taskId, model, reason, permissions.memoryTtlSeconds.first())
    }

    companion object {
        const val MODEL_ON_DEVICE = "on-device (Gemini Nano)"
        const val MODEL_CLOUD = "cloud (Gemini)"
        const val MODEL_OPENROUTER = "openrouter"
        const val MODEL_BLOCKED = "blocked"
    }
}

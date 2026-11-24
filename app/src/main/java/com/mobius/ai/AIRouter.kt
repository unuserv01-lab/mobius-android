package com.mobius.ai

import com.mobius.core.MemoryStore
import com.mobius.core.PermissionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/** Result of one routed generation. */
data class AIResponse(val text: String, val model: String, val reason: String)

/** Thrown when a task needs cloud AI but the user has not allowed it. Never calls the cloud. */
class CloudRequiredException(val reason: String) : Exception(
    "This task needs cloud AI ($reason), but \"Allow cloud AI\" is OFF. " +
        "Turn it on in Settings if you want to continue; the request text may then be sent to Gemini cloud."
)

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
        val text = cloud.generate(prompt)
        return record(taskId, MODEL_CLOUD, "$cloudReason -> cloud (allowed by user)", text)
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
        const val MODEL_BLOCKED = "blocked"
    }
}

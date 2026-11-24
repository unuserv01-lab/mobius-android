package com.mobius.agent

import com.mobius.ai.AIRouter
import com.mobius.ai.PromptBuilder
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/**
 * v6 (B) — ORCHESTRATION helpers (pure planning + decomposition; execution lives in the
 * Orchestrator, which reuses the existing skill / planning-loop / ChatAgent dispatch per job).
 *
 * One instruction -> ordered job list -> jobs run in order -> combined result + obstacles.
 * Progress persistence uses ONLY the existing MemoryStore.recordTrail() + remember() APIs
 * (the Orchestrator records one trail row per job, plus the usual task_result line) — see
 * the seam note in BUILD-LOG-v6-core-runtime.md: a separate MEMORY-V2 agent adds durable
 * cross-task progress storage in parallel, and this file must NOT grow its own store.
 */

/** One job of an orchestrated task: a complete instruction that makes sense on its own. */
data class Job(val title: String, val instruction: String)

/** The ordered job list for one instruction. */
data class OrchestrationPlan(val jobs: List<Job>)

object Orchestration {

    /**
     * Cheap heuristic gate: only instructions that LOOK multi-part get a decompose LLM call.
     * A decompose call on every chat message would burn cloud calls for nothing; single
     * requests ("berapa harga sepatu di shopee", "balas wa ...") skip orchestration and take
     * the normal skill/agent path. False negatives just mean "runs as one job" (v5 behavior);
     * false positives cost one extra LLM call and still work (decompose returns one job).
     */
    fun looksMultiStep(userText: String): Boolean {
        val t = userText.lowercase()
        if (userText.count { it == '\n' } >= 1) return true
        if (Regex("(^|\\s)(\\d+[.)]|[-•])\\s").containsMatchIn(userText)) return true // numbered/bulleted list
        val sequencers = listOf(" lalu ", " kemudian ", " setelah itu ", " terus ", " then ", " after that ", " and then ")
        if (sequencers.any { it in " $t " }) return true
        if (userText.length > 220) return true
        // Two different action verbs in one request, e.g. "cari ... dan kirim ...".
        val verbs = listOf("cari", "cek", "buka", "kirim", "balas", "beli", "pesan", "bandingkan", "search", "open", "send", "reply", "buy", "check")
        return verbs.count { " $t ".contains(" $it ") || t.startsWith("$it ") } >= 2
    }

    /**
     * Asks the cloud LLM to split the instruction into jobs (heavy reasoning — orchestration
     * is a planning task, Nano cannot do it). Returns a single-job plan when the LLM says
     * the request is one job, when its answer is unparsable, or when the AI layer fails:
     * decomposition must never fail the user's task — the single job then runs the normal
     * dispatch path (skill / planning loop / agent) unchanged.
     */
    suspend fun decompose(ai: AIRouter, taskId: String, userText: String, ctx: TaskContext): OrchestrationPlan {
        val single = OrchestrationPlan(listOf(Job(userText.take(80), userText)))
        return try {
            val response = ai.generate(taskId, PromptBuilder.decomposePrompt(userText, ctx), needsHeavyReasoning = true)
            val json = JSONObject(response.text.substring(response.text.indexOf('{'), response.text.lastIndexOf('}') + 1))
            val arr = json.optJSONArray("jobs") ?: return single
            val jobs = mutableListOf<Job>()
            for (i in 0 until minOf(arr.length(), MAX_JOBS)) {
                val o = arr.optJSONObject(i) ?: continue
                val title = o.optString("title").takeIf { it.isNotBlank() } ?: "Langkah ${i + 1}"
                val instruction = o.optString("instruction").takeIf { it.isNotBlank() } ?: continue
                jobs += Job(title.take(120), instruction)
            }
            if (jobs.isEmpty()) single else OrchestrationPlan(jobs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            single // AI unavailable / unparsable: one job = the whole request, normal path
        }
    }

    private const val MAX_JOBS = 5
}

package com.mobius.core

import org.json.JSONObject

/**
 * LAYER 2 — SKILLS (declarative JSON recipes).
 *
 * A skill is DATA, not code: a JSON file that composes Layer 1 tools into one app or task.
 * Skills live in app/src/main/assets/skills/ (one JSON file per skill) and are loaded by [SkillRegistry]. Anyone
 * can write a skill without touching Kotlin; the closed [SkillRunner] executes it
 * deterministically. A skill NEVER calls the LLM and NEVER touches the network itself.
 *
 * Schema:
 * {
 *   "id": "shopee_price_search",            // unique, lowercase_snake
 *   "name": "Shopee price search",
 *   "description": "Search Shopee for a product and return the top 5 prices found on screen.",
 *   "triggers": ["shopee", "cari harga"],   // keyword phrases the registry scores against
 *   "params": [{ "name": "query", "required": true, "default": null }],
 *   "steps": [{
 *     "tool": "launchApp",                  // one of the Layer 1 tool names
 *     "args": { "package": "com.shopee.id" }, // values may reference $params and $vars
 *     "storeAs": "screen",                  // optional: store result message + data.* into vars
 *     "when": "last.success",               // optional: run only if the condition holds
 *     "onFail": "fail",                     // fail (default) | skip | stop
 *     "maxRetries": 0
 *   }],
 *   "maxSteps": 20,                         // hard cap on steps executed
 *   "timeoutMs": 60000,                     // hard wall-clock cap for the whole skill
 *   "summarize": false                      // if true, the core asks the LLM to summarize the result
 * }
 *
 * `when` conditions (evaluated against the previous step result and current vars):
 *   "last.success" | "last.failed" | "!last.success"
 *   "vars.<name>"                 (true when the var exists and is non-blank)
 *   "vars.<name> == \"value\"" | "vars.<name> != \"value\"" | "vars.<name> contains \"value\""
 *   Clauses may be joined with "&&" (all must hold).
 */
data class SkillParam(
    val name: String,
    val required: Boolean = false,
    val default: String? = null,
)

data class SkillStep(
    val tool: String,
    val args: Map<String, String> = emptyMap(),
    val storeAs: String? = null,
    val whenExpr: String? = null,
    /** "fail" (default): stop, outcome failed. "skip": continue, outcome partial. "stop": stop, outcome partial. */
    val onFail: String = "fail",
    val maxRetries: Int = 0,
)

data class Skill(
    val id: String,
    val name: String,
    val description: String,
    val triggers: List<String>,
    val params: List<SkillParam>,
    val steps: List<SkillStep>,
    val maxSteps: Int = 20,
    val timeoutMs: Long = 60_000,
    val summarize: Boolean = false,
)

/** One executed step, kept for the outcome report and debugging. */
data class StepResult(
    val index: Int,
    val tool: String,
    val success: Boolean,
    val message: String,
    val durationMs: Long,
)

/** What the deterministic runner reports. `vars` holds every param + stored step result. */
data class SkillOutcome(
    val success: Boolean,
    val partial: Boolean,
    val message: String,
    val steps: List<StepResult>,
    val vars: Map<String, String>,
)

/** Parses one skill JSON object. Returns null when the file is malformed (id/steps missing). */
fun parseSkill(json: JSONObject): Skill? = try {
    val id = json.optString("id").trim().takeIf { it.isNotEmpty() } ?: return null
    val stepsJson = json.optJSONArray("steps") ?: return null
    val steps = (0 until stepsJson.length()).mapNotNull { i ->
        val s = stepsJson.optJSONObject(i) ?: return@mapNotNull null
        val tool = s.optString("tool").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val argsJson = s.optJSONObject("args")
        val args = LinkedHashMap<String, String>()
        argsJson?.keys()?.forEach { k -> args[k] = argsJson.optString(k) }
        SkillStep(
            tool = tool,
            args = args,
            storeAs = s.optString("storeAs").takeIf { it.isNotBlank() },
            whenExpr = s.optString("when").takeIf { it.isNotBlank() },
            onFail = s.optString("onFail", "fail").lowercase().takeIf { it in setOf("fail", "skip", "stop") } ?: "fail",
            maxRetries = s.optInt("maxRetries", 0).coerceIn(0, 3),
        )
    }
    if (steps.isEmpty()) return null
    val paramsJson = json.optJSONArray("params")
    val params = (0 until (paramsJson?.length() ?: 0)).mapNotNull { i ->
        val p = paramsJson!!.optJSONObject(i) ?: return@mapNotNull null
        val name = p.optString("name").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        SkillParam(name, p.optBoolean("required", false), p.optString("default").takeIf { it.isNotBlank() })
    }
    val triggersJson = json.optJSONArray("triggers")
    val triggers = (0 until (triggersJson?.length() ?: 0)).mapNotNull { i ->
        triggersJson!!.optString(i).trim().takeIf { it.isNotEmpty() }
    }
    Skill(
        id = id,
        name = json.optString("name", id),
        description = json.optString("description", ""),
        triggers = triggers,
        params = params,
        steps = steps,
        maxSteps = json.optInt("maxSteps", 20).coerceIn(1, 100),
        timeoutMs = json.optLong("timeoutMs", 60_000).coerceIn(1_000, 300_000),
        summarize = json.optBoolean("summarize", false),
    )
} catch (e: Exception) {
    null
}

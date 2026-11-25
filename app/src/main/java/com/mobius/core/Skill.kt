package com.mobius.core

import org.json.JSONObject

/**
 * LAYER 2 — SKILLS (declarative recipes, dual format since v8.1).
 *
 * A skill is DATA, not code: it composes Layer 1 tools into one app or task.
 * Skills live in app/src/main/assets/skills/ and are loaded by [SkillRegistry]. Anyone
 * can write a skill without touching Kotlin; the closed [SkillRunner] executes it
 * deterministically. A skill NEVER calls the LLM and NEVER touches the network itself.
 *
 * TWO FORMATS (dual-format loader, v8.1 — Priority 5 of the competitor breakdown;
 * pattern adapted from OpenMinis, written from scratch, no copied code):
 *
 * 1. JSON (legacy, unchanged): one `<id>.json` file per skill, schema below.
 *    Existing JSON skills keep running with no forced migration.
 * 2. SKILL.md (new): one `<id>.md` file, or a folder `<id>/SKILL.md` (folder layout
 *    also allows `scripts/`, `references/`, `assets/` next to it). Layout:
 *      ---
 *      name: shopee_price_search        # skill id (lowercase_snake, required)
 *      description: Search Shopee ...   # shown to the user / router
 *      version: 1                       # optional, default 1
 *      triggers: shopee, cari harga     # comma list, or YAML inline/block list
 *      ---
 *      # Shopee price search
 *      Markdown instructions (what the skill does, when to use it, notes).
 *      Kept under ~500 lines; loaded only when the skill is triggered.
 *      ```json
 *      { ...same JSON schema as format 1: id, name, description, triggers,
 *        params, steps, maxSteps, timeoutMs, summarize... }
 *      ```
 *    Frontmatter wins for metadata (name/description/version/triggers); the
 *    embedded ```json block wins for the executable part (params/steps/...).
 *    The Markdown body (JSON block removed) is kept on [Skill.body] for the
 *    router/prompt layer; the runner never reads it.
 *
 * PRECEDENCE when two files describe the same id: folder `SKILL.md` > flat
 * `.md` > `.json`. The shadowed file stays on disk (the JSON original is never
 * deleted) and the shadowing is logged. A malformed `.md` falls back to the
 * JSON file of the same id when one exists.
 *
 * PROGRESSIVE DISCLOSURE (3 levels, same shape as OpenMinis):
 *   1. Metadata only (name, description, version, source, triggers) is held
 *      in memory at startup and used for trigger scoring.
 *   2. The SKILL.md body is loaded only when the skill is triggered
 *      ([SkillRegistry.byId]/[SkillRegistry.match]).
 *   3. Bundled resources (`scripts/`, `references/`) are loaded only on
 *      explicit request ([SkillRegistry.loadResource]).
 *
 * JSON schema:
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
 *     "maxRetries": 0,
 *     "risk": "irreversible"               // optional (v5): this step needs user approval
 *                                          // (ActionGate) before it runs; any other value
 *                                          // is ignored. Read-only steps are never
 *                                          // irreversible by themselves.
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
    /** "irreversible" (v5): the ActionGate asks the user before this step runs. Null = no risk. */
    val risk: String? = null,
)

/** Where a skill came from. JSON = legacy format; MARKDOWN = SKILL.md (v8.1). */
enum class SkillSource { JSON, MARKDOWN }

/**
 * Level-1 metadata of a skill (progressive disclosure): everything the router
 * needs for trigger scoring, without the body or the steps. This is also the
 * shape persisted in the `skill_meta` Room table (see [SkillMetaStore]).
 */
data class SkillMeta(
    val id: String,
    val name: String,
    val description: String,
    val version: Int = 1,
    val source: SkillSource = SkillSource.JSON,
    val triggers: List<String> = emptyList(),
    /** Asset path, e.g. "skills/shopee_price_search.json" or "skills/shopee/SKILL.md". */
    val assetPath: String = "",
    /** Effective toggle (DB-backed when wired, default true). Disabled skills never match. */
    val enabled: Boolean = true,
    /** How many times the skill has run (DB-backed when wired, default 0). */
    val useCount: Int = 0,
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
    /** SKILL.md frontmatter `version` (JSON skills: 1). */
    val version: Int = 1,
    /** Which file format this skill was loaded from. */
    val source: SkillSource = SkillSource.JSON,
    /** SKILL.md Markdown body (JSON block removed); empty for JSON skills. */
    val body: String = "",
)

/** One executed step, kept for the outcome report and debugging. */
data class StepResult(
    val index: Int,
    val tool: String,
    val success: Boolean,
    val message: String,
    val durationMs: Long,
    /** (v8.1) How the step's effect was confirmed — see [VerificationStatus].
     *  Read-only steps are VERIFIED by the observation itself; mutating steps
     *  carry whatever read-back the tool could do. Default UNVERIFIED keeps
     *  every pre-v8.1 construction site honest (unclassified = not verified). */
    val verification: VerificationStatus = VerificationStatus.UNVERIFIED,
)

/** What the deterministic runner reports. `vars` holds every param + stored step result. */
data class SkillOutcome(
    val success: Boolean,
    val partial: Boolean,
    val message: String,
    val steps: List<StepResult>,
    val vars: Map<String, String>,
    /** Every ActionGate decision made during this run (v5 audit trail). */
    val gateDecisions: List<GateDecisionRecord> = emptyList(),
    /** (v8.1) Done-gate classification — see [RunOutcome]. `success` is true
     *  ONLY when this is DONE; anything else must be reported as partial /
     *  not possible / unknown, never as success. Default UNKNOWN: a result
     *  built without going through the gate is "not classified", not "done". */
    val outcome: RunOutcome = RunOutcome.UNKNOWN,
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
            // Only the exact value "irreversible" counts; anything else (typos included) is ignored.
            risk = s.optString("risk").takeIf { it == ActionGate.RISK_IRREVERSIBLE },
        )
    }
    if (steps.isEmpty()) return null
    val paramsJson = json.optJSONArray("params")
    val params = (0 until (paramsJson?.length() ?: 0)).mapNotNull { i ->
        val p = paramsJson!!.optJSONObject(i) ?: return@mapNotNull null
        val name = p.optString("name").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        // (v8.1-hotfix-3) Blank defaults are REAL defaults: "" means "no value"
        // (empty Gmail query = list latest inbox; empty calendar end = start+1h).
        // The old `p.optString("default").takeIf { it.isNotBlank() }` silently
        // discarded them, so a skill run without that param failed in
        // SkillRunner.resolveRefs ("missing parameter/var '$name'") even though
        // the skill JSON declared a default. Absent key / JSON null still mean
        // "no default" (fail-closed preserved); numbers keep the old optString
        // coercion.
        val defRaw = p.opt("default")
        val def = when (defRaw) {
            is String -> defRaw
            is Number -> defRaw.toString()
            else -> null
        }
        SkillParam(name, p.optBoolean("required", false), def)
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

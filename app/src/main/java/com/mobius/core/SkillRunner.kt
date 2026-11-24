package com.mobius.core

import android.os.SystemClock
import kotlinx.coroutines.delay

/**
 * LAYER 2 — the deterministic Runner.
 *
 * Executes one skill step by step, in order, with no LLM calls and no branching beyond the
 * skill's own `when` conditions. The runner is a pure interpreter:
 *   - resolves $params / $vars in step args (a missing reference fails the step, loudly);
 *   - calls exactly one Layer 1 tool per step;
 *   - evaluates `when` (see [Skill] for the condition language);
 *   - honors `onFail` (fail | skip | stop) and `maxRetries`;
 *   - enforces the skill's `maxSteps` and wall-clock `timeoutMs`;
 *   - (v5) asks the [ActionGate] before EVERY step: a step into a sensitive app is DENIED
 *     (skill stops at once); a step marked risk="irreversible" pauses for the user's
 *     approval via [onConfirmStep] — no hook means deny (fail closed). Every gate decision
 *     is collected into the outcome for the audit trail.
 *
 * The LLM never runs here. Its only jobs stay where they were: picking the skill + filling
 * params ([TaskRouter]) and summarizing the structured result ([AIRouter], only when the skill
 * sets "summarize": true). Same input + same screen state = same step sequence: deterministic.
 */
class SkillRunner {

    suspend fun run(
        skill: Skill,
        params: Map<String, String>,
        tool: Tool,
        onStatus: (String) -> Unit = {},
        gate: ActionGate = ActionGate,
        onConfirmStep: (suspend (String) -> Boolean)? = null,
    ): SkillOutcome {
        if (skill.steps.size > skill.maxSteps) {
            return SkillOutcome(
                success = false,
                partial = false,
                message = "Skill '${skill.id}' has ${skill.steps.size} steps but maxSteps is ${skill.maxSteps}.",
                steps = emptyList(),
                vars = params,
            )
        }

        val vars = LinkedHashMap<String, String>()
        for (p in skill.params) {
            params[p.name]?.let { vars[p.name] = it } ?: p.default?.let { vars[p.name] = it }
        }

        val deadline = SystemClock.elapsedRealtime() + skill.timeoutMs
        val results = mutableListOf<StepResult>()
        val gateDecisions = mutableListOf<GateDecisionRecord>()
        var last: ToolResult? = null
        var failed = false
        var partial = false
        var failMessage = ""

        for ((index, step) in skill.steps.withIndex()) {
            if (SystemClock.elapsedRealtime() > deadline) {
                failed = true
                failMessage = "Skill timed out after ${skill.timeoutMs}ms at step ${index + 1}."
                results += StepResult(index, step.tool, false, failMessage, 0)
                break
            }
            if (step.whenExpr != null && !evalWhen(step.whenExpr, vars, last)) {
                results += StepResult(index, step.tool, true, "skipped (when false)", 0)
                continue
            }

            onStatus("Step ${index + 1}/${skill.steps.size}: ${step.tool}...")

            // Resolve $params/$vars ONCE, before the gate (the gate needs the resolved package).
            var resolveError: String? = null
            val resolved: Map<String, String>? = try {
                step.args.mapValues { (_, v) -> resolveRefs(v, vars) }
            } catch (e: Exception) {
                resolveError = e.message ?: e.javaClass.simpleName
                null
            }

            var result: ToolResult? = null
            var attempt = 0
            val started = SystemClock.elapsedRealtime()
            if (resolved == null) {
                // Missing $ref: fails the step exactly as before (no tool call, no gate check).
                result = ToolResult(false, resolveError ?: "missing parameter")
            } else {
                // --- ActionGate (v5): every step is checked BEFORE it runs. ---
                val gateResult = gate.check(step.tool, resolved["package"], step.risk)
                when (gateResult.decision) {
                    GateDecision.DENIED -> {
                        gateDecisions += GateDecisionRecord(
                            index, step.tool, resolved["package"],
                            GateDecision.DENIED, null, gateResult.reason,
                        )
                        val msg = "Ditolak ActionGate: ${gateResult.reason}"
                        results += StepResult(index, step.tool, false, msg, 0)
                        failed = true
                        failMessage = "Langkah ${index + 1} (${step.tool}) ditolak: ${gateResult.reason}"
                        break
                    }
                    GateDecision.NEEDS_APPROVAL -> {
                        val desc = "Langkah ${index + 1}: ${step.tool} (${describeArgs(resolved)}) — aksi tak terbalikkan."
                        // No approval hook means deny: never run an irreversible step blind.
                        val ok = onConfirmStep?.invoke(desc) ?: false
                        if (!ok) {
                            gateDecisions += GateDecisionRecord(
                                index, step.tool, resolved["package"],
                                GateDecision.NEEDS_APPROVAL, "rejected", gateResult.reason,
                            )
                            val msg = "Ditolak pengguna: ${gateResult.reason}"
                            results += StepResult(index, step.tool, false, msg, 0)
                            failed = true
                            failMessage = "Dibatalkan pengguna pada langkah ${index + 1} (${step.tool}): ${gateResult.reason}"
                            break
                        }
                        gateDecisions += GateDecisionRecord(
                            index, step.tool, resolved["package"],
                            GateDecision.NEEDS_APPROVAL, "approved", gateResult.reason,
                        )
                    }
                    GateDecision.ALLOW -> Unit
                }
                while (true) {
                    result = try {
                        callTool(tool, step.tool, resolved)
                    } catch (e: Exception) {
                        ToolResult(false, e.message ?: e.javaClass.simpleName)
                    }
                    if (result.success || attempt >= step.maxRetries) break
                    attempt++
                    delay(300)
                }
            }
            val duration = SystemClock.elapsedRealtime() - started
            val r = result
            results += StepResult(index, step.tool, r.success, r.message, duration)

            if (r.success) {
                last = r
                step.storeAs?.let { key ->
                    vars[key] = r.message
                    r.data.forEach { (k, v) -> vars["$key.$k"] = v }
                }
            } else {
                last = r
                when (step.onFail) {
                    "skip" -> partial = true
                    "stop" -> {
                        partial = true
                        failMessage = "Stopped at step ${index + 1} (${step.tool}): ${r.message}"
                        break
                    }
                    else -> {
                        failed = true
                        failMessage = "Step ${index + 1} (${step.tool}) failed: ${r.message}"
                        break
                    }
                }
            }
        }

        val lastOk = results.lastOrNull { it.success }
        val message = when {
            failed -> failMessage
            partial -> if (failMessage.isNotEmpty()) "Partially done — $failMessage"
                else "Partially done (some steps skipped): ${lastOk?.message ?: skill.name}."
            else -> lastOk?.message?.takeIf { it.isNotBlank() } ?: "Done: ${skill.name}."
        }
        return SkillOutcome(!failed, partial, message, results, vars, gateDecisions)
    }

    /** "package=com.shopee.id, text=sepatu" — for the approval dialog and the plan card. */
    private fun describeArgs(args: Map<String, String>): String =
        args.entries.joinToString(", ") { (k, v) -> "$k=$v" }

    private suspend fun callTool(tool: Tool, name: String, args: Map<String, String>): ToolResult = when (name) {
        "launchApp" -> tool.launchApp(args.require("package"))
        "tapText" -> tool.tapText(args.require("package"), args.require("text"), args["exact"]?.toBooleanStrictOrNull() ?: true)
        "type" -> tool.type(args.require("package"), args.require("text"))
        "submit" -> tool.submit(args.require("package"))
        "readScreen" -> tool.readScreen(args.require("package"), args["limit"]?.toIntOrNull() ?: 200)
        "findText" -> tool.findText(args.require("package"), args.require("text"), args["exact"]?.toBooleanStrictOrNull() ?: true)
        "extract" -> tool.extract(args.require("package"), args.require("pattern"), args["max"]?.toIntOrNull() ?: 5)
        "scroll" -> tool.scroll(args.require("package"))
        "swipe" -> tool.swipe(args.require("direction"), args["durationMs"]?.toLongOrNull() ?: 300)
        "back" -> tool.back()
        "home" -> tool.home()
        "wait" -> tool.waitMs(args["ms"]?.toLongOrNull() ?: 1_000)
        "waitForText" -> tool.waitForText(args.require("package"), args.require("text"), args["timeoutMs"]?.toLongOrNull() ?: 5_000)
        // v6 CodeAgent — the gate (flag + ActionGate) is enforced inside the Tool
        // implementation; "args" for runCommand is one comma-separated string in JSON
        // (e.g. "args": "script.sh"), because skill args are strings.
        "readFileScoped" -> tool.readFileScoped(args.require("path"))
        "writeFileScoped" -> tool.writeFileScoped(args.require("path"), args.require("content"))
        "runCommand" -> tool.runCommand(
            args.require("command"),
            args["args"]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList(),
        )
        else -> ToolResult(false, "Unknown tool: $name")
    }

    private fun Map<String, String>.require(key: String): String =
        this[key] ?: throw IllegalArgumentException("missing argument '$key'")

    /** Replaces $name with vars[name]; a missing reference fails the step. */
    private fun resolveRefs(value: String, vars: Map<String, String>): String {
        var missing: String? = null
        val out = REF.replace(value) { m ->
            vars[m.groupValues[1]] ?: run { missing = m.groupValues[1]; m.value }
        }
        if (missing != null) throw IllegalArgumentException("missing parameter/var '\$$missing'")
        return out
    }

    /** "a && b": every clause must hold. */
    private fun evalWhen(expr: String, vars: Map<String, String>, last: ToolResult?): Boolean =
        expr.split("&&").all { part -> evalAtom(part.trim(), vars, last) }

    private fun evalAtom(atom: String, vars: Map<String, String>, last: ToolResult?): Boolean {
        val negated = atom.startsWith("!")
        val core = if (negated) atom.substring(1).trim() else atom
        val value = when {
            core == "last.success" -> last?.success == true
            core == "last.failed" -> last?.success == false
            core.startsWith("vars.") -> {
                val rest = core.removePrefix("vars.")
                when {
                    rest.contains(" contains ") -> {
                        val (k, v) = rest.split(" contains ", limit = 2)
                        vars[k.trim()]?.contains(unquote(v.trim())) == true
                    }
                    rest.contains("==") -> {
                        val (k, v) = rest.split("==", limit = 2)
                        vars[k.trim()] == unquote(v.trim())
                    }
                    rest.contains("!=") -> {
                        val (k, v) = rest.split("!=", limit = 2)
                        vars[k.trim()] != unquote(v.trim())
                    }
                    else -> !vars[rest].isNullOrBlank()
                }
            }
            else -> false
        }
        return if (negated) !value else value
    }

    private fun unquote(s: String): String = s.removeSurrounding("\"").removeSurrounding("'")

    private companion object {
        val REF = Regex("\\\$([A-Za-z0-9_.]+)")
    }
}

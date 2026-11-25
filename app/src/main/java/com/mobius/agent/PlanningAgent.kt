package com.mobius.agent

import android.os.SystemClock
import com.mobius.ai.PromptBuilder
import com.mobius.core.ActionGate
import com.mobius.core.AppConfig
import com.mobius.core.GateDecision
import com.mobius.core.GateDecisionRecord
import com.mobius.core.KnownApps
import com.mobius.core.RoutedIntent
import com.mobius.core.StepResult
import com.mobius.core.Tool
import com.mobius.core.ToolResult
import com.mobius.ui.stringsFor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * v6 (A) — PLANNING LOOP (ReAct agent).
 *
 * For tasks with NO matching skill: the cloud LLM observes the screen (readScreen summary
 * via the Layer-1 [Tool] interface), picks ONE Layer-1 tool, executes it, observes again,
 * and repeats until it reports done or the step cap / timeout is hit.
 *
 * Rules that are NOT negotiable in this loop:
 *  - Runs on CLOUD Gemini only ([tools.ai] is called with needsHeavyReasoning = true, so
 *    AIRouter never tries Nano for the loop — Nano cannot do multi-step planning). If cloud
 *    is not allowed, the loop fails with the usual CloudRequiredException message instead
 *    of silently degrading.
 *  - EVERY tool call goes through [ActionGate.check] first, exactly the way SkillRunner
 *    does: DENIED stops the loop at once, NEEDS_APPROVAL asks the user (confirmStep), and
 *    every decision is recorded in [gateDecisions] for the v5 task trail.
 *  - SENDS: a step that would send something (tapping a Send/Kirim button, or submit inside
 *    a messaging app) is treated as irreversible AND is confirmed via
 *    [AgentTools.confirmSend] (which already returns true immediately when
 *    confirm-before-send is OFF, and asks the user when it is ON). Nothing is ever sent
 *    without that approval. Other irreversible steps use [onConfirmStep]; no hook means deny.
 *  - The screen is a SHARED resource: every tool call (including the observation reads)
 *    runs inside [screenLock], so two parallel workers (v6 E) never drive the screen at
 *    the same time, while their AI calls still run in parallel.
 *
 * The loop never writes memory itself (P5): it reports [steps]/[gateDecisions] and the
 * Orchestrator persists them into the v5 task trail via MemoryStore.recordTrail().
 *
 * CODE AGENT SEAM: a step with tool "code" is handed to [codeExecutor] when one was
 * supplied (the Orchestrator builds it by reflection over agent.CodeAgent, which another
 * v6 workstream owns — see Orchestrator.buildCodeExecutor). When no executor is present
 * the step fails honestly ("Code agent not available yet") instead of pretending to code.
 * This file deliberately contains NO reference to the CodeAgent class, so it compiles
 * whether or not that file exists yet.
 */
class PlanningAgent(
    taskId: String,
    tools: AgentTools,
    private val tool: Tool?,
    private val screenLock: Mutex,
    private val onConfirmStep: (suspend (String) -> Boolean)? = null,
    private val codeExecutor: (suspend (String) -> AgentResult)? = null,
    private val maxSteps: Int = DEFAULT_MAX_STEPS,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    /**
     * v7.7 bug 3 — why there is no tool (toggle off vs toggle on but the
     * service not running), supplied by the Orchestrator. Null = fall back
     * to the old generic message.
     */
    private val toolUnavailableMessage: String? = null,
) : SubAgent(taskId, tools) {
    override val name = "PlanningLoop"

    /** Step results + gate decisions, in order — the Orchestrator persists these to the trail. */
    val steps = mutableListOf<StepResult>()
    val gateDecisions = mutableListOf<GateDecisionRecord>()

    override suspend fun execute(ctx: TaskContext, intent: RoutedIntent): AgentResult {
        val tool = this.tool
            ?: return AgentResult(
                false,
                toolUnavailableMessage
                    ?: "Accessibility access is off, so I can't operate other apps. Enable it in " +
                        "Settings > Accessibility > ${AppConfig.APP_NAME}.",
            )
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        val history = mutableListOf<String>()
        var lastTypedText: String? = null
        var consecutiveFailures = 0

        var screenSummary = observe(tool, ctx)
        for (index in 0 until maxSteps) {
            if (SystemClock.elapsedRealtime() > deadline) {
                return AgentResult(
                    false,
                    "Planning loop timed out after ${timeoutMs / 1000}s at step ${index + 1}. " +
                        "Last screen: ${screenSummary.take(200)}",
                )
            }
            tools.status("Planning step ${index + 1}/$maxSteps...")
            val prompt = PromptBuilder.planningStepPrompt(
                ctx.userText, screenSummary, history.takeLast(6).joinToString("\n"), index + 1, maxSteps,
            )
            val response = try {
                tools.ai.generate(taskId, prompt, needsHeavyReasoning = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return AgentResult(false, "Planning failed at step ${index + 1}: ${e.message ?: e.javaClass.simpleName}")
            }
            val action = parseAction(response.text)
                ?: run {
                    // Unparsable LLM output: count it as a failed step, do not guess a tool.
                    record(index, "llm", false, "LLM answer was not valid JSON, skipped.", 0)
                    history += "Step ${index + 1}: LLM answer unparsable, no tool run."
                    if (++consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                        return AgentResult(false, "Planning stopped: the model kept answering in an unusable format.")
                    }
                    continue
                }
            if (action.done) {
                val summary = action.summary?.takeIf { it.isNotBlank() }
                    ?: "Done after ${index + 1} step(s)."
                return AgentResult(true, summary)
            }
            val toolName = action.tool ?: run {
                record(index, "llm", false, "LLM picked no tool and did not report done.", 0)
                history += "Step ${index + 1}: no tool chosen."
                if (++consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    return AgentResult(false, "Planning stopped: the model kept choosing no tool.")
                }
                continue
            }

            // --- CODE AGENT SEAM (tool "code"): no screen involved, no gate, no screen lock. ---
            if (toolName == "code") {
                val codeTask = action.args["task"].orEmpty()
                val started = SystemClock.elapsedRealtime()
                val result = if (codeExecutor == null) {
                    AgentResult(false, "Code agent not available yet (workstream terpisah, belum terintegrasi).")
                } else if (codeTask.isBlank()) {
                    AgentResult(false, "Code step had no task description.")
                } else {
                    try {
                        codeExecutor.invoke(codeTask)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        AgentResult(false, "Code step failed: ${e.message ?: e.javaClass.simpleName}")
                    }
                }
                val duration = SystemClock.elapsedRealtime() - started
                record(index, "code", result.success, result.message, duration)
                history += "Step ${index + 1}: code(${codeTask.take(80)}) -> ${result.message.take(200)}"
                if (result.success) consecutiveFailures = 0 else if (++consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    return AgentResult(false, "Planning stopped after $MAX_CONSECUTIVE_FAILURES failed code steps: ${result.message}")
                }
                continue
            }

            if (toolName !in KNOWN_TOOLS) {
                record(index, toolName, false, "Unknown tool: $toolName", 0)
                history += "Step ${index + 1}: unknown tool \"$toolName\"."
                if (++consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    return AgentResult(false, "Planning stopped: the model kept picking unknown tools.")
                }
                continue
            }

            // --- v7.5: resolve app names to real packages BEFORE the gate and the
            // tool call. The model may pass a name ("youtube") or invent an id
            // ("com.youtube.app"); the gate then also checks the REAL package (a
            // wrong string could otherwise slip past the sensitive-apps list), and
            // the model sees the resolved package in its own history next step.
            val args = if (toolName == "launchApp") {
                val raw = action.args["package"].orEmpty()
                val resolved = KnownApps.resolvePackage(raw, ctx.userText)
                if (resolved != null && resolved != raw) action.args + ("package" to resolved) else action.args
            } else {
                action.args
            }

            // --- ActionGate (v5 rule, same as SkillRunner): check BEFORE the tool runs. ---
            val pkg = args["package"]?.takeIf { it.isNotBlank() } ?: currentPackage(ctx)
            val isSend = isSendStep(toolName, args, pkg)
            val risk = if (isSend) ActionGate.RISK_IRREVERSIBLE else null
            val gate = ActionGate.check(toolName, pkg?.takeIf { it.isNotBlank() }, risk)
            when (gate.decision) {
                GateDecision.DENIED -> {
                    gateDecisions += GateDecisionRecord(index, toolName, pkg, GateDecision.DENIED, null, gate.reason)
                    record(index, toolName, false, "Ditolak ActionGate: ${gate.reason}", 0)
                    return AgentResult(false, "Ditolak ActionGate: ${gate.reason}")
                }
                GateDecision.NEEDS_APPROVAL -> {
                    // Sends go through confirm-before-send; other irreversible steps via confirmStep.
                    val desc = "Langkah ${index + 1}: $toolName (${describeArgs(args)}) — aksi tak terbalikkan."
                    val ok = if (isSend) {
                        val draft = lastTypedText?.takeIf { it.isNotBlank() }
                            ?: "Kirim di ${pkg ?: "aplikasi ini"}"
                        tools.confirmSend(pkg ?: "app", draft)
                    } else {
                        onConfirmStep?.invoke(desc) ?: false // no hook = deny (fail closed)
                    }
                    gateDecisions += GateDecisionRecord(
                        index, toolName, pkg, GateDecision.NEEDS_APPROVAL,
                        if (ok) "approved" else "rejected", gate.reason,
                    )
                    if (!ok) {
                        record(index, toolName, false, "Dibatalkan pengguna: ${gate.reason}", 0)
                        return AgentResult(false, "Dibatalkan pengguna pada langkah ${index + 1} (${toolName}).")
                    }
                }
                GateDecision.ALLOW -> Unit
            }

            // --- Execute: the screen lock serializes this with other workers' screen use. ---
            val started = SystemClock.elapsedRealtime()
            val result = try {
                screenLock.withLock { callTool(tool, toolName, args, pkg) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ToolResult(false, e.message ?: e.javaClass.simpleName)
            }
            val duration = SystemClock.elapsedRealtime() - started
            record(index, toolName, result.success, result.message, duration)
            history += "Step ${index + 1}: $toolName(${describeArgs(args)}) -> ${result.message.take(200)}"
            if (toolName == "type" && result.success) lastTypedText = args["text"]
            if (result.success) {
                consecutiveFailures = 0
            } else if (++consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                return AgentResult(false, failureMessage(toolName, args, result))
            }
            // Observe again for the next iteration (also under the screen lock).
            screenSummary = observe(tool, ctx)
            delay(200) // let the screen settle before the next observation
        }
        return AgentResult(
            false,
            "Planning loop reached the $maxSteps-step cap without finishing. " +
                "Last screen: ${screenSummary.take(200)}",
        )
    }

    /** Reads the current screen through the Layer-1 tool (never the raw reader). */
    private suspend fun observe(tool: Tool, ctx: TaskContext): String {
        // v7.7 bug 4b — a showing keyboard owns the active window, so a plain
        // readScreen would read Gboard ("com.google.android.inputmethod.latin")
        // instead of the app. Dismiss it first — but ONLY when an IME is
        // actually detected: a blind back() could exit the app the agent is
        // working in, or cancel an edit in progress. One press per observe;
        // if the keyboard is still there afterwards, the read fails honestly
        // and the model sees "screen unreadable" instead of a keyboard dump.
        if (tools.reader?.isKeyboardVisible() == true) {
            screenLock.withLock { tool.back() }
            delay(300) // let the keyboard close before reading again
        }
        val pkg = currentPackage(ctx) ?: return "No app in foreground (screen unreadable)."
        return try {
            screenLock.withLock {
                val r = tool.readScreen(pkg, 100)
                if (r.success) "App $pkg — ${r.message}" else "App $pkg — screen unreadable: ${r.message}"
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "Screen read failed: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** Foreground app from the task snapshot, else whatever owns the active window now. */
    private fun currentPackage(ctx: TaskContext): String? =
        ctx.foregroundApp?.takeIf { it.isNotBlank() }
            ?: tools.reader?.currentPackage()?.takeIf { it.isNotBlank() }

    /** A step counts as "sending" when it presses a Send/Kirim button or submits in a chat app. */
    private fun isSendStep(toolName: String, args: Map<String, String>, pkg: String?): Boolean = when {
        toolName == "tapText" && args["text"]?.trim()?.lowercase() in SEND_LABELS -> true
        toolName == "submit" && pkg in AppConfig.WHATSAPP_PACKAGES -> true
        else -> false
    }

    private fun record(index: Int, toolName: String, success: Boolean, message: String, durationMs: Long) {
        steps += StepResult(index, toolName, success, message, durationMs)
    }

    /** Same tool-name dispatch as SkillRunner.callTool (names match the skill JSON schema). */
    private suspend fun callTool(tool: Tool, name: String, args: Map<String, String>, pkg: String?): ToolResult {
        // The LLM may omit "package" — fall back to the app that is actually on screen.
        val a = if (pkg.isNullOrBlank() || "package" in args) args else args + ("package" to pkg)
        return when (name) {
            "launchApp" -> tool.launchApp(a.require("package"))
            "tapText" -> tool.tapText(a.require("package"), a.require("text"), a["exact"]?.toBooleanStrictOrNull() ?: true)
            "type" -> tool.type(a.require("package"), a.require("text"))
            "submit" -> tool.submit(a.require("package"))
            "readScreen" -> tool.readScreen(a.require("package"), a["limit"]?.toIntOrNull() ?: 200)
            "findText" -> tool.findText(a.require("package"), a.require("text"), a["exact"]?.toBooleanStrictOrNull() ?: true)
            "extract" -> tool.extract(a.require("package"), a.require("pattern"), a["max"]?.toIntOrNull() ?: 5)
            "scroll" -> tool.scroll(a.require("package"))
            "swipe" -> tool.swipe(a.require("direction"), a["durationMs"]?.toLongOrNull() ?: 300)
            "back" -> tool.back()
            "home" -> tool.home()
            "wait" -> tool.waitMs(a["ms"]?.toLongOrNull() ?: 1_000)
            "waitForText" -> tool.waitForText(a.require("package"), a.require("text"), a["timeoutMs"]?.toLongOrNull() ?: 5_000)
            else -> ToolResult(false, "Unknown tool: $name")
        }
    }

    private fun Map<String, String>.require(key: String): String =
        this[key] ?: throw IllegalArgumentException("missing argument '$key'")

    private fun describeArgs(args: Map<String, String>): String =
        args.entries.joinToString(", ") { (k, v) -> "$k=$v" }

    /**
     * v7.5 — the 3-strikes failure message. For a failed launch it names the exact
     * package that was tried, in the active language (so a missing/disabled app is
     * diagnosable from the message alone, instead of the vague English guess the
     * model used to write); other tools keep the generic message.
     */
    private fun failureMessage(toolName: String, args: Map<String, String>, result: ToolResult): String {
        if (toolName != "launchApp") {
            return "Planning stopped after $MAX_CONSECUTIVE_FAILURES failed steps. " +
                "Last failure: ${result.message}"
        }
        val s = stringsFor(tools.language)
        val pkg = args["package"].orEmpty()
        val display = KnownApps.displayName(pkg) ?: pkg.ifBlank { "?" }
        return s.planLaunchFailed.format(display, pkg)
    }

    /** Parses one LLM step: {"done": bool, "summary"?, "tool"?, "args"?}. Null = unparsable. */
    private fun parseAction(raw: String): PlannedAction? = try {
        val json = JSONObject(raw.substring(raw.indexOf('{'), raw.lastIndexOf('}') + 1))
        val done = json.optBoolean("done", false)
        val argsObj = json.optJSONObject("args")
        val args = LinkedHashMap<String, String>()
        argsObj?.keys()?.forEach { k -> args[k] = argsObj.optString(k) }
        PlannedAction(
            done = done,
            summary = json.optString("summary").takeIf { it.isNotBlank() },
            tool = json.optString("tool").takeIf { it.isNotBlank() },
            args = args,
        )
    } catch (e: Exception) {
        null
    }

    private data class PlannedAction(
        val done: Boolean,
        val summary: String?,
        val tool: String?,
        val args: Map<String, String>,
    )

    companion object {
        /** Default step cap (task spec A). */
        const val DEFAULT_MAX_STEPS = 15
        /** Default wall-clock budget for one loop. */
        const val DEFAULT_TIMEOUT_MS = 300_000L
        /** Consecutive failures (bad tool / failed tool / unparsable answer) before giving up. */
        const val MAX_CONSECUTIVE_FAILURES = 3
        val KNOWN_TOOLS = setOf(
            "launchApp", "tapText", "type", "submit", "readScreen", "findText", "extract",
            "scroll", "swipe", "back", "home", "wait", "waitForText",
        )
        val SEND_LABELS = setOf("send", "kirim", "enviar", "envoyer", "senden", "invia")
    }
}

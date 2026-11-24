package com.mobius.agent

import com.mobius.ai.AIRouter
import com.mobius.core.FeatureFlag
import com.mobius.core.FeatureFlags
import com.mobius.core.MemoryType
import com.mobius.core.ScopedFileStore
import com.mobius.core.Tool
import kotlinx.coroutines.flow.first
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v6 CodeAgent — the "Artifact & Tool Builder" worker.
 *
 * Job, kept deliberately small: write a script as text -> save it to the scoped
 * directory -> run it -> return the output. No new dependencies, no Nano dependency
 * (the phone has no Nano — the script is drafted through [AIRouter], which routes
 * on-device first and falls back to cloud exactly like every other agent).
 *
 * Flow of [execute]:
 *   1. Flag check: when CODE_AGENT is OFF, refuse at once (the tools would deny
 *      anyway — this is the early, clear message). [featureFlags] is optional so
 *      the agent can also be built with just (taskId, tool, ai); when it is null
 *      the Tool layer is the only gate.
 *   2. Ask the AI to write ONE POSIX `sh` script for the task (heavy reasoning:
 *      writing code is a heavy task for the router). The prompt forbids network
 *      and destructive commands; the runner scans the script text again.
 *   3. Save it under a FRESH, unique name (`code-<taskId>-<timestamp>.sh`) inside
 *      the scoped directory — a fresh name means the write is always a new file,
 *      never an overwrite, so it never hits the ActionGate NEEDS_APPROVAL path.
 *   4. Run `sh <script>` through the Tool (allowlist + 30 s timeout + kill).
 *   5. Return the output (stdout, exit code, stderr on failure) as the message.
 *      The message is capped so a chatty script cannot flood the bubble.
 *
 * This class is NOT a [SubAgent]: the planning loop (built by another workstream)
 * calls [execute] directly with a plain task description, so the public API is
 * exactly `suspend fun execute(taskDescription: String): AgentResult` — do not
 * change the signature. Every step fails honestly (a ToolResult(false, ...) becomes
 * AgentResult(false, ...) with the reason) instead of throwing.
 */
class CodeAgent(
    private val taskId: String,
    private val tool: Tool,
    private val ai: AIRouter,
    private val featureFlags: FeatureFlags? = null,
) {

    suspend fun execute(taskDescription: String): AgentResult {
        val task = taskDescription.trim()
        if (task.isEmpty()) return AgentResult(false, "No task given.")

        // 1) Feature flag (early, clear refusal; the tools deny as well).
        if (featureFlags != null) {
            val enabled = try {
                featureFlags.isEnabled(FeatureFlag.CODE_AGENT).first()
            } catch (e: Exception) {
                true // read failure = default ON, same as the flag's own default
            }
            if (!enabled) {
                return AgentResult(
                    false,
                    "Code agent is turned off (feature flag CODE_AGENT). Turn it on in Settings to run code tasks.",
                )
            }
        }

        // 2) Draft the script with the AI. Plain sh only: no network, no deletion,
        // no privilege commands — the runner enforces this again on the script text.
        val scriptRaw = try {
            ai.generate(taskId, scriptPrompt(task), needsHeavyReasoning = true).text
        } catch (e: Exception) {
            return AgentResult(false, "Could not draft the script: ${e.message ?: e.javaClass.simpleName}")
        }
        val script = stripFences(scriptRaw).trim()
        if (script.isBlank()) return AgentResult(false, "The AI returned an empty script.")
        if (script.length > ScopedFileStore.MAX_FILE_CHARS) {
            return AgentResult(false, "The drafted script is too long (${script.length} chars, max ${ScopedFileStore.MAX_FILE_CHARS}).")
        }

        // 3) Save under a fresh unique name (never overwrites, so no approval is needed).
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val fileName = "code-$taskId-$stamp.sh"
        val saved = try {
            tool.writeFileScoped(fileName, script)
        } catch (e: Exception) {
            return AgentResult(false, "Could not save the script: ${e.message ?: e.javaClass.simpleName}")
        }
        if (!saved.success) return AgentResult(false, "Could not save the script: ${saved.message}")

        // 4) Run it: `sh <script>`, exactly one argument, 30 s timeout, killed on timeout.
        val ran = try {
            tool.runCommand("sh", listOf(fileName))
        } catch (e: Exception) {
            return AgentResult(false, "Could not run the script: ${e.message ?: e.javaClass.simpleName}")
        }
        val exitCode = ran.data["exitCode"]?.toIntOrNull()
        val stdout = ran.data["stdout"].orEmpty()
        val stderr = ran.data["stderr"].orEmpty()
        val timedOut = ran.data["timedOut"]?.toBooleanStrictOrNull() ?: false

        // 5) Return the output, capped for the bubble.
        val message = buildString {
            if (timedOut) {
                append("Script timed out after 30s and was killed.")
            } else if (!ran.success) {
                append("Script failed (exit code ${exitCode ?: "?"}).")
            } else {
                append("Script ran OK (exit code 0).")
            }
            if (stdout.isNotBlank()) append("\nOutput:\n").append(stdout.take(2000))
            if (stderr.isNotBlank()) append("\nError:\n").append(stderr.take(1000))
            if (stdout.isBlank() && stderr.isBlank() && !timedOut) append(" (no output)")
        }
        return AgentResult(
            success = ran.success,
            message = message,
            proposals = listOf(
                MemoryProposal(
                    MemoryType.TASK_RESULT,
                    "CodeAgent ${if (ran.success) "ok" else "failed"}: \"${task.take(60)}\" (script $fileName, exit ${exitCode ?: "?"})",
                ),
            ),
        )
    }

    /** The AI is asked for script text only — no explanation, no markdown fences. */
    private fun scriptPrompt(task: String): String = """
        Write ONE short POSIX sh script that does this task, and nothing else:
        $task

        Rules:
        - Output ONLY the script text. No explanation, no markdown fences, no backticks.
        - Plain sh only: echo, printf, cat, grep, sort, uniq, head, tail, wc, date, test.
        - NEVER use network commands (curl, wget, nc, ssh, ftp) or destructive/privilege
          commands (rm, dd, su, sudo, mkfs). The script runs in a private app directory
          and will be rejected if it contains them.
        - Keep it short. Print the result to stdout.
    """.trimIndent()

    /** Strips a surrounding ```sh / ``` fence when the model adds one anyway. */
    private fun stripFences(text: String): String {
        var t = text.trim()
        if (t.startsWith("```")) {
            t = t.removePrefix("```")
            // Drop the language tag on the first line ("sh"), if there is one.
            t = t.substringAfter("\n", t)
            if (t.endsWith("```")) t = t.removeSuffix("```")
        }
        return t.trim()
    }
}

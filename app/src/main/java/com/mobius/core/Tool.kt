package com.mobius.core

import android.accessibilityservice.AccessibilityService
import com.mobius.accessibility.ActionExecutor
import com.mobius.accessibility.ScreenReader
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/**
 * LAYER 1 — TOOLS (closed primitives).
 *
 * The complete set of things a MOBIUS worker may DO on the phone. Every tool is a small,
 * deterministic Kotlin primitive over the AccessibilityService: no LLM calls inside tools,
 * no app-specific knowledge, no hidden state. A tool returns a [ToolResult] and NEVER throws
 * (P8: callers degrade gracefully instead of crashing).
 *
 * Community code CANNOT add tools: new capabilities arrive only as LAYER 2 skills (declarative
 * JSON recipes that compose these primitives). Adding a primitive means editing this file and
 * the closed implementation below.
 */
data class ToolResult(
    val success: Boolean,
    val message: String = "",
    /** Structured data the caller may store, e.g. {"0": "Rp12.000", "1": "Rp15.500", "count": "2"}. */
    val data: Map<String, String> = emptyMap(),
)

interface Tool {
    /** Opens an app through its launcher intent. */
    suspend fun launchApp(packageName: String): ToolResult

    /** Taps the first node whose visible text matches (exact or contains). */
    suspend fun tapText(packageName: String, text: String, exact: Boolean = true): ToolResult

    /** Types [text] into the first editable field on screen (replaces its content). */
    suspend fun type(packageName: String, text: String): ToolResult

    /**
     * Presses Enter/Search on the focused text field (submits a search or a form).
     * Implemented with AccessibilityNodeInfo.ACTION_IME_ENTER (API 31+); on older Android
     * there is no supported way to send Enter from an accessibility service, so it fails
     * honestly instead of pretending success.
     */
    suspend fun submit(packageName: String): ToolResult

    /** Visible texts on screen, as indexed data entries plus "count". */
    suspend fun readScreen(packageName: String, limit: Int = 200): ToolResult

    /** Checks whether [text] is visible on screen right now. */
    suspend fun findText(packageName: String, text: String, exact: Boolean = true): ToolResult

    /**
     * Extracts up to [max] distinct matches of regex [pattern] from the visible screen text.
     * Generic text mining (e.g. prices "Rp[0-9.,]+"); the pattern comes from the skill, not the tool.
     */
    suspend fun extract(packageName: String, pattern: String, max: Int = 5): ToolResult

    /** Scrolls the first scrollable node on screen forward. */
    suspend fun scroll(packageName: String): ToolResult

    /** Directional swipe ("up" | "down" | "left" | "right") across the middle of the screen. */
    suspend fun swipe(direction: String, durationMs: Long = 300): ToolResult

    /** System Back. */
    suspend fun back(): ToolResult

    /** System Home. */
    suspend fun home(): ToolResult

    /** Sleeps [ms] milliseconds (screens load asynchronously). */
    suspend fun waitMs(ms: Long): ToolResult

    /** Waits up to [timeoutMs] for [text] to appear on screen. */
    suspend fun waitForText(packageName: String, text: String, timeoutMs: Long): ToolResult

    // --- v6 CodeAgent (Layer 1, closed primitives — see ScopedFileStore / CommandRunner) ---
    // All three are gated by the CODE_AGENT feature flag AND by ActionGate; when the flag
    // is OFF they deny with a clear message and no code runs at all (v5 behavior).

    /**
     * Reads a text file inside the scoped directory (filesDir/mobius-code).
     * [path] is relative; absolute paths, ".." traversal and symlinks out are rejected.
     */
    suspend fun readFileScoped(path: String): ToolResult

    /**
     * Writes [content] to a text file inside the scoped directory.
     * Overwriting an existing file needs user approval (ActionGate NEEDS_APPROVAL) and
     * is refused without it — callers should use a fresh name per write.
     */
    suspend fun writeFileScoped(path: String, content: String): ToolResult

    /**
     * Runs a command. Allowlist: only `sh` with exactly one argument, a scoped `.sh`
     * script path, no extra args. No shell is involved (argv list, not a shell line),
     * so metacharacters / pipes / redirects in [args] are rejected outright.
     */
    suspend fun runCommand(command: String, args: List<String> = emptyList()): ToolResult
}

/**
 * The closed implementation: every primitive delegates to [ScreenReader] / [ActionExecutor],
 * which are themselves thin wrappers over the AccessibilityService. All failures become
 * ToolResult(false, ...) — nothing here throws.
 */
class AccessibilityTools(
    private val reader: ScreenReader,
    private val executor: ActionExecutor,
    @Suppress("unused") private val service: AccessibilityService,
) : Tool {

    override suspend fun launchApp(packageName: String): ToolResult = resultOf(
        okMessage = "Opened $packageName.",
        failMessage = "Could not open $packageName.",
    ) { executor.launchApp(packageName) }

    override suspend fun tapText(packageName: String, text: String, exact: Boolean): ToolResult = resultOf(
        okMessage = "Tapped \"$text\".",
        failMessage = "Could not find \"$text\" on screen.",
    ) { executor.tapByText(packageName, text, exact) }

    override suspend fun type(packageName: String, text: String): ToolResult = try {
        val field = reader.findEditable(packageName)
            ?: return ToolResult(false, "No text field on screen to type into.")
        if (executor.setText(field, text)) ToolResult(true, "Typed text.")
        else ToolResult(false, "The text field did not accept the text.")
    } catch (e: Exception) {
        ToolResult(false, e.message ?: e.javaClass.simpleName)
    }

    override suspend fun submit(packageName: String): ToolResult = try {
        if (executor.pressEnter(packageName)) ToolResult(true, "Submitted.")
        else ToolResult(
            false,
            "Could not submit: no focused text field, or the field did not accept Enter " +
                "(needs Android 12+).",
        )
    } catch (e: Exception) {
        ToolResult(false, e.message ?: e.javaClass.simpleName)
    }

    override suspend fun readScreen(packageName: String, limit: Int): ToolResult = try {
        val texts = reader.readScreen(packageName, limit)
        val data = LinkedHashMap<String, String>()
        data["count"] = texts.size.toString()
        texts.forEachIndexed { i, t -> data[i.toString()] = t }
        ToolResult(
            success = texts.isNotEmpty(),
            message = if (texts.isEmpty()) "No text found on screen for $packageName."
                else texts.take(10).joinToString(" | ").take(500),
            data = data,
        )
    } catch (e: Exception) {
        ToolResult(false, e.message ?: e.javaClass.simpleName)
    }

    override suspend fun findText(packageName: String, text: String, exact: Boolean): ToolResult = try {
        val node = if (exact) reader.findByText(packageName, text) else reader.findByTextContains(packageName, text)
        if (node != null) ToolResult(true, "Found \"$text\".")
        else ToolResult(false, "\"$text\" is not on screen.")
    } catch (e: Exception) {
        ToolResult(false, e.message ?: e.javaClass.simpleName)
    }

    override suspend fun extract(packageName: String, pattern: String, max: Int): ToolResult = try {
        val regex = Regex(pattern)
        val texts = reader.readScreen(packageName, 200)
        val matches = regex.findAll(texts.joinToString("\n")).map { it.value }.distinct().take(max).toList()
        val data = LinkedHashMap<String, String>()
        data["count"] = matches.size.toString()
        matches.forEachIndexed { i, m -> data[i.toString()] = m }
        ToolResult(
            success = matches.isNotEmpty(),
            message = if (matches.isEmpty()) "No match for '$pattern' on screen."
                else matches.joinToString(", "),
            data = data,
        )
    } catch (e: Exception) {
        ToolResult(false, "Invalid pattern '$pattern': ${e.message}")
    }

    override suspend fun scroll(packageName: String): ToolResult = resultOf(
        okMessage = "Scrolled.",
        failMessage = "Nothing scrollable on screen.",
    ) { executor.scrollForwardOnScreen(packageName) }

    override suspend fun swipe(direction: String, durationMs: Long): ToolResult = resultOf(
        okMessage = "Swiped $direction.",
        failMessage = "Swipe $direction was not accepted.",
    ) { executor.swipeDirection(direction, durationMs) }

    override suspend fun back(): ToolResult = resultOf(
        okMessage = "Back.",
        failMessage = "Back was not accepted.",
    ) { executor.back() }

    override suspend fun home(): ToolResult = resultOf(
        okMessage = "Home.",
        failMessage = "Home was not accepted.",
    ) { executor.home() }

    override suspend fun waitMs(ms: Long): ToolResult {
        delay(ms.coerceIn(0, 30_000))
        return ToolResult(true, "Waited ${ms}ms.")
    }

    override suspend fun waitForText(packageName: String, text: String, timeoutMs: Long): ToolResult = try {
        val found = executor.waitFor(timeoutMs) { reader.findByTextContains(packageName, text) }
        if (found != null) ToolResult(true, "Found \"$text\".")
        else ToolResult(false, "\"$text\" did not appear within ${timeoutMs}ms.")
    } catch (e: Exception) {
        ToolResult(false, e.message ?: e.javaClass.simpleName)
    }

    // --- v6 CodeAgent ---
    // The scoped store lives under the app-private filesDir, reached through
    // service.applicationContext — the constructor is NOT changed (Orchestrator.buildTool
    // is owned by another workstream). Flag OFF = deny, no code runs at all.

    /** Lazily built from the service's application context; no new constructor params. */
    private val scopedStore: ScopedFileStore by lazy { ScopedFileStore(service.applicationContext) }
    private val commandRunner: CommandRunner by lazy { CommandRunner(scopedStore) }
    private val featureFlags: FeatureFlags by lazy { FeatureFlags(service.applicationContext) }

    /** True unless the CODE_AGENT flag was explicitly turned OFF (default ON; read failure = ON). */
    private suspend fun codeAgentEnabled(): Boolean = try {
        featureFlags.isEnabled(FeatureFlag.CODE_AGENT).first()
    } catch (e: Exception) {
        true
    }

    private fun codeAgentOffResult(): ToolResult =
        ToolResult(false, "Code agent is turned off (feature flag CODE_AGENT). Turn it on in Settings to run code tasks.")

    override suspend fun readFileScoped(path: String): ToolResult = try {
        if (!codeAgentEnabled()) return codeAgentOffResult()
        val content = scopedStore.read(path)
            ?: return ToolResult(false, "Could not read '$path': not found, or outside the scoped directory (mobius-code).")
        ToolResult(true, content.take(500), mapOf("path" to path, "content" to content))
    } catch (e: Exception) {
        ToolResult(false, e.message ?: e.javaClass.simpleName)
    }

    override suspend fun writeFileScoped(path: String, content: String): ToolResult = try {
        if (!codeAgentEnabled()) return codeAgentOffResult()
        val gate = ActionGate.checkFileWrite(path, scopedStore.exists(path))
        when (gate.decision) {
            GateDecision.DENIED ->
                return ToolResult(false, "Ditolak ActionGate: ${gate.reason}")
            GateDecision.NEEDS_APPROVAL ->
                // No approval hook exists at the Tool layer: fail closed. The caller
                // (CodeAgent) writes under a fresh name per run, so this is not hit
                // in normal operation; a skill step hitting it must go through the
                // SkillRunner confirmStep hook instead.
                return ToolResult(false, "Butuh persetujuan: ${gate.reason} Tulis ke nama file baru, atau minta persetujuan dulu.")
            GateDecision.ALLOW -> Unit
        }
        if (scopedStore.write(path, content)) ToolResult(true, "Wrote $path (${content.length} chars).", mapOf("path" to path))
        else ToolResult(false, "Could not write '$path': unsafe path, or file too large (max ${ScopedFileStore.MAX_FILE_CHARS} chars).")
    } catch (e: Exception) {
        ToolResult(false, e.message ?: e.javaClass.simpleName)
    }

    override suspend fun runCommand(command: String, args: List<String>): ToolResult = try {
        if (!codeAgentEnabled()) return codeAgentOffResult()
        val gate = ActionGate.checkCommand(command, args)
        if (gate.decision == GateDecision.DENIED) {
            return ToolResult(false, "Ditolak ActionGate: ${gate.reason}")
        }
        val result = commandRunner.run(command, args)
        val data = mapOf(
            "exitCode" to result.exitCode.toString(),
            "stdout" to result.stdout,
            "stderr" to result.stderr,
            "timedOut" to result.timedOut.toString(),
        )
        when {
            result.timedOut -> ToolResult(false, "Command timed out after ${CommandRunner.TIMEOUT_MS / 1000}s and was killed.", data)
            result.exitCode == 0 -> ToolResult(true, result.stdout.ifBlank { "(no output)" }.take(2000), data)
            else -> ToolResult(
                false,
                "Command exited with code ${result.exitCode}." +
                    (if (result.stderr.isNotBlank()) " ${result.stderr.take(500)}" else ""),
                data,
            )
        }
    } catch (e: Exception) {
        ToolResult(false, e.message ?: e.javaClass.simpleName)
    }

    private suspend fun resultOf(okMessage: String, failMessage: String, block: suspend () -> Boolean): ToolResult = try {
        if (block()) ToolResult(true, okMessage) else ToolResult(false, failMessage)
    } catch (e: Exception) {
        ToolResult(false, e.message ?: e.javaClass.simpleName)
    }
}

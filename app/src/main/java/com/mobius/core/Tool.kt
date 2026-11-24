package com.mobius.core

import android.accessibilityservice.AccessibilityService
import com.mobius.accessibility.ActionExecutor
import com.mobius.accessibility.ScreenReader
import kotlinx.coroutines.delay

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

    private suspend fun resultOf(okMessage: String, failMessage: String, block: () -> Boolean): ToolResult = try {
        if (block()) ToolResult(true, okMessage) else ToolResult(false, failMessage)
    } catch (e: Exception) {
        ToolResult(false, e.message ?: e.javaClass.simpleName)
    }
}

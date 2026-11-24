package com.mobius.agent

import com.mobius.accessibility.ActionExecutor
import com.mobius.accessibility.ScreenReader
import com.mobius.ai.AIRouter
import com.mobius.core.RoutedIntent
import com.mobius.notification.CapturedNotification
import com.mobius.ui.AppLanguage

/** Everything a subagent may look at for one task. Built by the core's ContextManager. */
data class TaskContext(
    val taskId: String,
    val userText: String,
    val foregroundApp: String?,
    val recentNotifications: List<CapturedNotification>,
    val time: String,
    val recentTasks: List<String>,
)

/** A suggestion the subagent reports to the core. The core may drop it (P5). */
data class MemoryProposal(
    val type: String,
    val content: String,
    val ttlSeconds: Long? = null,
)

/** What a subagent reports back before it is destroyed. */
data class AgentResult(
    val success: Boolean,
    /** Shown to the user in the bubble. */
    val message: String,
    val proposals: List<MemoryProposal> = emptyList(),
)

/**
 * The only capabilities a subagent receives. Notice what is missing: NO MemoryStore.
 * Subagents report results; the core decides what is stored (P5).
 * [reader]/[executor] are null when the Accessibility permission is off (P8).
 */
interface AgentTools {
    val ai: AIRouter
    val reader: ScreenReader?
    val executor: ActionExecutor?

    /** UI language for this task's agent messages (v7.4). Default ID; the core overrides it. */
    val language: AppLanguage get() = AppLanguage.ID

    /** Short progress text shown next to the bubble. */
    fun status(message: String)

    /** Ask the user to approve an outgoing message. Returns true immediately if confirmation is off. */
    suspend fun confirmSend(sender: String, draft: String): Boolean
}

/** Base class for temporary workers: one instance per task, destroyed when the task ends (P2). */
abstract class SubAgent(val taskId: String, protected val tools: AgentTools) {
    abstract val name: String

    /** Lifecycle + temp data for this one worker. */
    val scope = AgentScope(taskId)

    protected abstract suspend fun execute(ctx: TaskContext, intent: RoutedIntent): AgentResult

    /** Runs the task inside its own scope and always destroys the scope afterwards. */
    suspend fun run(ctx: TaskContext, intent: RoutedIntent): AgentResult =
        scope.runAndDestroy { execute(ctx, intent) }
}

package com.mobius.core

import android.content.Context
import com.mobius.accessibility.ActionExecutor
import com.mobius.accessibility.MobiusAccessibilityService
import com.mobius.accessibility.ScreenReader
import com.mobius.agent.AgentFactory
import com.mobius.agent.AgentResult
import com.mobius.agent.AgentTools
import com.mobius.ai.AIRouter
import com.mobius.ai.CloudRequiredException
import com.mobius.ai.PromptBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import java.util.UUID

/** How the UI wants to hear about a running task. Implemented by the bubble and the Home screen. */
interface TaskHooks {
    fun status(message: String)
    suspend fun confirmSend(sender: String, draft: String): Boolean
}

data class TaskOutcome(val success: Boolean, val message: String)

/**
 * The CORE: task intake, routing, subagent lifecycle and the only code path that writes memory (P1, P5).
 * Runs entirely on the device. One task at a time.
 */
class Orchestrator(
    private val context: Context,
    private val permissions: PermissionManager,
    private val memory: MemoryStore,
    private val contextManager: ContextManager,
    private val taskRouter: TaskRouter,
    private val agentFactory: AgentFactory,
    private val aiRouter: AIRouter,
    private val skillRegistry: SkillRegistry,
    private val skillRunner: SkillRunner,
) {
    private val busy = Mutex()

    suspend fun submit(userText: String, hooks: TaskHooks): TaskOutcome {
        val text = userText.trim()
        if (text.isEmpty()) return TaskOutcome(false, "Type a request first.")
        if (!busy.tryLock()) return TaskOutcome(false, "I'm still working on the previous task.")

        val taskId = UUID.randomUUID().toString().take(8)
        try {
            hooks.status("Understanding your request...")
            val ctx = contextManager.snapshot(taskId, text)

            // Intent parsing: Gemini Nano first, via AIRouter (every decision is logged).
            // FIX 24 Nov 00:45: previously only CloudRequiredException was caught here, so any
            // other failure from the router (e.g. ServerException "Firebase App Check token is
            // invalid" from a cloud call) escaped submit() and crashed the app. Now every
            // non-cancellation failure becomes a friendly outcome instead of a crash.
            val intent = try {
                taskRouter.route(taskId, text, ctx)
            } catch (e: CancellationException) {
                throw e
            } catch (e: CloudRequiredException) {
                return finish(taskId, "router", text, TaskOutcome(false, e.message ?: "Cloud AI required."), emptyList())
            } catch (e: Exception) {
                return finish(taskId, "router", text, TaskOutcome(false, "Something went wrong: ${e.message ?: e.javaClass.simpleName}"), emptyList())
            }

            // LAYER 2 — a registry skill matched: run its deterministic recipe instead of an
            // LLM-driven agent. The runner makes no AI calls; the LLM only filled the params
            // (TaskRouter) and, if the skill asks for it, summarizes the result below.
            if (intent.type == AgentType.SKILL) {
                val skillOutcome = runSkill(taskId, intent, hooks)
                return finish(
                    taskId, "Skill:${intent.skillId}", text,
                    TaskOutcome(skillOutcome.success, skillOutcome.message), emptyList(),
                )
            }

            // One temporary worker for this task.
            val agent = agentFactory.create(intent.type, taskId, buildTools(hooks))
            val result: AgentResult = try {
                agent.run(ctx, intent) // the agent's scope is cancelled and cleared inside run()
            } catch (e: CancellationException) {
                throw e
            } catch (e: CloudRequiredException) {
                AgentResult(false, e.message ?: "Cloud AI required.")
            } catch (e: Exception) {
                AgentResult(false, "Something went wrong: ${e.message ?: e.javaClass.simpleName}")
            }

            return finish(taskId, agent.name, text, TaskOutcome(result.success, result.message), result.proposals)
        } finally {
            busy.unlock()
        }
    }

    /** Runs a matched skill through the deterministic runner (no AI inside the runner). */
    private suspend fun runSkill(taskId: String, intent: RoutedIntent, hooks: TaskHooks): SkillOutcome {
        val skill = intent.skillId?.let { skillRegistry.byId(it) }
            ?: return SkillOutcome(false, false, "Skill not found.", emptyList(), emptyMap())
        val tool = buildTool()
            ?: return SkillOutcome(
                false, false,
                "Accessibility access is off, so I can't operate other apps. Enable it in " +
                    "Settings > Accessibility > ${AppConfig.APP_NAME}.",
                emptyList(), emptyMap(),
            )
        hooks.status("Running ${skill.name}...")
        val outcome = try {
            skillRunner.run(skill, intent.skillParams, tool, hooks::status)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SkillOutcome(false, false, "Something went wrong: ${e.message ?: e.javaClass.simpleName}", emptyList(), emptyMap())
        }
        if (!skill.summarize || !outcome.success) return outcome
        // LLM summarizes the structured result; any AI failure falls back to the raw outcome.
        return try {
            val summary = aiRouter.generate(taskId, PromptBuilder.skillSummaryPrompt(skill, outcome))
            outcome.copy(message = summary.text.ifBlank { outcome.message })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            outcome
        }
    }

    /** Builds the Layer 1 tool set from the live accessibility service (null when it is off). */
    private fun buildTool(): Tool? {
        val service = MobiusAccessibilityService.instance ?: return null
        val reader = ScreenReader(service)
        val executor = ActionExecutor(context, service, reader)
        return AccessibilityTools(reader, executor, service)
    }

    /** What the subagent is allowed to use. No memory access, by construction (P5). */
    private fun buildTools(hooks: TaskHooks): AgentTools {
        val service = MobiusAccessibilityService.instance
        val reader = service?.let { ScreenReader(it) }
        val executor = if (service != null && reader != null) ActionExecutor(context, service, reader) else null
        return object : AgentTools {
            override val ai: AIRouter = aiRouter
            override val reader: ScreenReader? = reader
            override val executor: ActionExecutor? = executor
            override fun status(message: String) = hooks.status(message)
            override suspend fun confirmSend(sender: String, draft: String): Boolean =
                if (permissions.confirmBeforeSend.first()) hooks.confirmSend(sender, draft) else true
        }
    }

    /**
     * Memory policy (P5). The core, not the agent, decides what is stored:
     *  - always one short task_result line (outcome only, never message bodies or AI answers);
     *  - agent proposals are accepted only if they are the allowed types and small.
     */
    private suspend fun finish(
        taskId: String,
        agentName: String,
        userText: String,
        outcome: TaskOutcome,
        proposals: List<com.mobius.agent.MemoryProposal>,
    ): TaskOutcome {
        try {
            val ttl = permissions.memoryTtlSeconds.first()
            val source = "task:$taskId/agent:$agentName"
            val accepted = proposals
                .filter { it.type in ALLOWED_TYPES && it.content.isNotBlank() }
                .take(3)
            val hasResultLine = accepted.any { it.type == MemoryType.TASK_RESULT }
            if (!hasResultLine) {
                val mark = if (outcome.success) "ok" else "failed"
                memory.remember(MemoryType.TASK_RESULT, "$agentName $mark: \"${userText.take(60)}\"", source, ttl)
            }
            accepted.forEach {
                memory.remember(it.type, it.content.take(200), source, it.ttlSeconds ?: ttl)
            }
        } catch (e: Exception) {
            // Memory is best-effort; never fail the user's task because of it.
        }
        return outcome
    }

    private companion object {
        val ALLOWED_TYPES = setOf(MemoryType.FACT, MemoryType.PREFERENCE, MemoryType.TASK_RESULT)
    }
}

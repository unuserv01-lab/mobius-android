package com.mobius.core

import android.content.Context
import com.mobius.accessibility.ActionExecutor
import com.mobius.accessibility.MobiusAccessibilityService
import com.mobius.accessibility.ScreenReader
import com.mobius.agent.AgentFactory
import com.mobius.agent.AgentResult
import com.mobius.agent.AgentTools
import com.mobius.agent.CodeAgent
import com.mobius.agent.Orchestration
import com.mobius.agent.PlanningAgent
import com.mobius.agent.SubAgent
import com.mobius.ai.AIRouter
import com.mobius.ai.CloudRequiredException
import com.mobius.ai.CloudTimeoutException
import com.mobius.ai.PromptBuilder
import com.mobius.ui.AppLanguage
import com.mobius.ui.LanguageManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** How the UI wants to hear about a running task. Implemented by the bubble and the Home screen. */
interface TaskHooks {
    fun status(message: String)
    suspend fun confirmSend(sender: String, draft: String): Boolean
    /** (v5) Shows the skill's plan ("Langkah yang akan dijalankan"); true = Jalankan. */
    suspend fun confirmPlan(skillName: String, steps: List<String>): Boolean
    /** (v5) Asks approval for one irreversible step (ActionGate); true = run the step. */
    suspend fun confirmStep(description: String): Boolean
}

data class TaskOutcome(val success: Boolean, val message: String)

/**
 * The CORE: task intake, routing, subagent lifecycle and the only code path that writes memory (P1, P5).
 * Runs entirely on the device.
 *
 * v6 changes (each behind its own feature flag, default ON; flag OFF = v5 behavior exactly):
 *  - (L) Nano triage before routing: when Nano is available it classifies light/heavy; the
 *    verdict is logged in the routing log and steers the intent classification to cloud
 *    when heavy. Nano unavailable (Tuan's Xiaomi) -> triage skipped, normal routing.
 *  - (B) Orchestration: a multi-part instruction is decomposed into ordered jobs (shown in
 *    the existing plan card first), each job runs through the normal dispatch below, and
 *    the combined result + obstacles are reported. Progress is persisted with the existing
 *    MemoryStore.recordTrail() + remember() APIs only — see the MEMORY-V2 seam note in
 *    BUILD-LOG-v6-core-runtime.md.
 *  - (A) Planning loop: CHAT intents that need screen work (and no skill matched) run the
 *    ReAct PlanningAgent instead of ChatAgent. ChatAgent / ReplyWhatsAppAgent / SkillRunner
 *    paths are intact as fallbacks.
 *  - (E) Parallel workers: the v5 single-task `busy` lock is replaced by a task queue +
 *    worker pool (2 workers) when the flag is ON. Only the shared SCREEN is serialized
 *    ([screenLock]): skills and ReplyWhatsAppAgent hold it for their run, the planning
 *    loop holds it per tool call, and AI calls run in parallel across workers.
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
    private val featureFlags = FeatureFlags(context)

    /** v5 single-task lock — used ONLY when PARALLEL_WORKERS is OFF. */
    private val busy = Mutex()

    /** (E) Serializes the shared screen resource across parallel workers. NOT reentrant:
     *  callers must never hold it while entering code that locks it again (the planning
     *  loop locks per tool call, so its run is never wrapped in it from outside). */
    private val screenLock = Mutex()

    /** (E) Task queue + worker pool. Workers live as long as the app process. */
    private val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val taskChannel = Channel<QueuedTask>(Channel.UNLIMITED)
    private val workersStarted = AtomicBoolean(false)

    private data class QueuedTask(
        val text: String,
        val hooks: TaskHooks,
        val done: CompletableDeferred<TaskOutcome>,
    )

    suspend fun submit(userText: String, hooks: TaskHooks): TaskOutcome {
        val text = userText.trim()
        if (text.isEmpty()) return TaskOutcome(false, "Type a request first.")
        // (E) Flag OFF = v5 exactly: one task at a time, second submit refused.
        if (!featureFlags.isEnabled(FeatureFlag.PARALLEL_WORKERS).first()) {
            if (!busy.tryLock()) return TaskOutcome(false, "I'm still working on the previous task.")
            try {
                return executeTask(text, hooks)
            } finally {
                busy.unlock()
            }
        }
        // Flag ON: queue the task; the caller still awaits its own outcome.
        ensureWorkers()
        val done = CompletableDeferred<TaskOutcome>()
        taskChannel.send(QueuedTask(text, hooks, done))
        return try {
            done.await()
        } catch (e: CancellationException) {
            done.cancel() // the worker keeps running; its complete() on a cancelled deferred is a no-op
            throw e
        }
    }

    /** Starts the worker pool once (2 workers, spec E). */
    private fun ensureWorkers() {
        if (!workersStarted.compareAndSet(false, true)) return
        repeat(MAX_WORKERS) {
            workerScope.launch {
                for (q in taskChannel) {
                    try {
                        q.done.complete(executeTask(q.text, q.hooks))
                    } catch (e: CancellationException) {
                        q.done.completeExceptionally(e)
                        // v7.1 (c): do NOT rethrow — the cancellation is already
                        // delivered to the caller via the deferred. Rethrowing
                        // would kill this worker and strand every later task
                        // (bubble stuck on "Understanding your request..."
                        // forever). A genuinely cancelled worker scope still
                        // ends the worker at the next channel receive.
                    } catch (e: Exception) {
                        q.done.complete(TaskOutcome(false, "Something went wrong: ${e.message ?: e.javaClass.simpleName}"))
                    }
                }
            }
        }
    }

    /** One task, end to end. Never throws except on cancellation (v2 crash fix preserved). */
    private suspend fun executeTask(text: String, hooks: TaskHooks): TaskOutcome {
        val taskId = UUID.randomUUID().toString().take(8)
        return try {
            executeSingle(taskId, text, hooks, allowOrchestration = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: CloudTimeoutException) {
            // v7.1 (a): the 30 s cloud timeout surfaces verbatim — no
            // "Something went wrong:" prefix. (Covers paths with no inner
            // catch, e.g. Orchestration.decompose.)
            TaskOutcome(false, e.message ?: "Cloud tidak merespons.")
        } catch (e: Exception) {
            TaskOutcome(false, "Something went wrong: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /**
     * Route + dispatch for ONE instruction (top-level task or one orchestration job).
     * Jobs recurse here with allowOrchestration = false, so orchestration can never nest.
     */
    private suspend fun executeSingle(
        taskId: String,
        text: String,
        hooks: TaskHooks,
        allowOrchestration: Boolean,
    ): TaskOutcome {
        hooks.status("Understanding your request...")
        val ctx = contextManager.snapshot(taskId, text)

        // (L) Nano triage — skipped when the flag is OFF or Nano is unavailable.
        val triageOn = featureFlags.isEnabled(FeatureFlag.NANO_TRIAGE).first()
        val triage = if (triageOn) {
            try {
                aiRouter.triage(taskId, text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }
        // heavyHint steers only the intent classification (TaskRouter); the actual model
        // choice for every later call stays with AIRouter.generate.
        val heavyHint = triage?.light == false
        if (triage != null) {
            hooks.status(
                if (triage.light) "Tugas ringan — tetap di perangkat."
                else "Tugas berat — ke cloud.",
            )
        }

        // Intent parsing: Gemini Nano first, via AIRouter (every decision is logged).
        // FIX 24 Nov 00:45: previously only CloudRequiredException was caught here, so any
        // other failure from the router (e.g. ServerException "Firebase App Check token is
        // invalid" from a cloud call) escaped submit() and crashed the app. Now every
        // non-cancellation failure becomes a friendly outcome instead of a crash.
        val intent = try {
            taskRouter.route(taskId, text, ctx, heavyHint)
        } catch (e: CancellationException) {
            throw e
        } catch (e: CloudRequiredException) {
            return finish(taskId, "router", text, TaskOutcome(false, e.message ?: "Cloud AI required."), emptyList())
        } catch (e: CloudTimeoutException) {
            // v7.1 (a): 30 s cloud timeout, verbatim outcome text.
            return finish(taskId, "router", text, TaskOutcome(false, e.message ?: "Cloud tidak merespons."), emptyList())
        } catch (e: Exception) {
            return finish(taskId, "router", text, TaskOutcome(false, "Something went wrong: ${e.message ?: e.javaClass.simpleName}"), emptyList())
        }

        // LAYER 2 — a registry skill matched: run its deterministic recipe instead of an
        // LLM-driven agent. The runner makes no AI calls; the LLM only filled the params
        // (TaskRouter) and, if the skill asks for it, summarizes the result below.
        // The whole skill run holds the screen lock: skills drive the screen continuously.
        if (intent.type == AgentType.SKILL) {
            val skillOutcome = screenLock.withLock { runSkill(taskId, intent, text, hooks) }
            return finish(
                taskId, "Skill:${intent.skillId}", text,
                TaskOutcome(skillOutcome.success, skillOutcome.message), emptyList(),
            )
        }

        // (B) Orchestration: multi-part instruction -> ordered jobs, plan card first.
        // Decompose failure never fails the task: one job = the whole request = normal path.
        if (allowOrchestration && featureFlags.isEnabled(FeatureFlag.ORCHESTRATION).first()
            && Orchestration.looksMultiStep(text)
        ) {
            val plan = Orchestration.decompose(aiRouter, taskId, text, ctx)
            if (plan.jobs.size >= 2) {
                val combined = runOrchestration(taskId, text, hooks, plan, heavyHint)
                return finish(taskId, "Orchestration", text, combined, emptyList())
            }
        }

        return dispatchAgent(taskId, text, ctx, intent, hooks)
    }

    /**
     * (B) Runs the plan's jobs in order (each through [executeSingle], so every job gets
     * the normal skill / planning-loop / agent dispatch), then reports the combined result
     * + obstacles. Progress persistence: one trail row per job (existing recordTrail API)
     * + one task_result line via [finish] at the call site; durable cross-task progress
     * storage is the MEMORY-V2 workstream's seam, not this file's.
     */
    private suspend fun runOrchestration(
        taskId: String,
        text: String,
        hooks: TaskHooks,
        plan: com.mobius.agent.OrchestrationPlan,
        heavyHint: Boolean,
    ): TaskOutcome {
        hooks.status("Showing plan (${plan.jobs.size} jobs)...")
        val planOk = try {
            hooks.confirmPlan("Rencana tugas", plan.jobs.mapIndexed { i, job -> "${i + 1}. ${job.title}" })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false // fail closed: if the dialog itself breaks, do not run anything
        }
        if (!planOk) {
            persistOrchestrationTrail(taskId, text, emptyList(), "cancelled")
            return TaskOutcome(false, "Dibatalkan.")
        }
        val jobSteps = mutableListOf<StepResult>()
        val lines = mutableListOf<String>()
        val obstacles = mutableListOf<String>()
        for ((i, job) in plan.jobs.withIndex()) {
            hooks.status("Job ${i + 1}/${plan.jobs.size}: ${job.title}...")
            val outcome = executeSingle(taskId, job.instruction, hooks, allowOrchestration = false)
            jobSteps += StepResult(i, "job", outcome.success, "${job.title} — ${outcome.message}", 0)
            lines += "${if (outcome.success) "✓" else "✗"} ${job.title} — ${outcome.message}"
            if (!outcome.success) obstacles += "${job.title}: ${outcome.message}"
        }
        persistOrchestrationTrail(taskId, text, jobSteps, "confirmed")
        val combined = lines.joinToString("\n") +
            if (obstacles.isNotEmpty()) "\nKendala:\n" + obstacles.joinToString("\n") else ""
        return TaskOutcome(obstacles.isEmpty(), combined)
    }

    /**
     * Non-skill dispatch (v5 paths intact):
     *  - REPLY_WHATSAPP -> ReplyWhatsAppAgent, whole run under the screen lock.
     *  - CHAT that needs screen work + PLANNING_LOOP on -> PlanningAgent (ReAct loop).
     *  - CHAT otherwise (or flag off) -> ChatAgent, exactly as v5.
     */
    private suspend fun dispatchAgent(
        taskId: String,
        text: String,
        ctx: com.mobius.agent.TaskContext,
        intent: RoutedIntent,
        hooks: TaskHooks,
    ): TaskOutcome {
        val tools = buildTools(hooks)
        if (intent.type == AgentType.CHAT &&
            featureFlags.isEnabled(FeatureFlag.PLANNING_LOOP).first() &&
            looksLikeScreenTask(text)
        ) {
            val agent = PlanningAgent(
                taskId, tools, buildTool(), screenLock,
                onConfirmStep = hooks::confirmStep,
                codeExecutor = buildCodeExecutor(taskId),
            )
            val result = runAgent(agent, ctx, intent)
            persistPlanningTrail(taskId, text, agent)
            return finish(taskId, agent.name, text, TaskOutcome(result.success, result.message), result.proposals)
        }
        val agent = agentFactory.create(intent.type, taskId, tools)
        val result = if (intent.type == AgentType.REPLY_WHATSAPP) {
            // Cross-app agent drives the screen for its whole run (incl. confirmSend dialog).
            screenLock.withLock { runAgent(agent, ctx, intent) }
        } else {
            runAgent(agent, ctx, intent)
        }
        return finish(taskId, agent.name, text, TaskOutcome(result.success, result.message), result.proposals)
    }

    /** One agent run; AI/cloud failures become a friendly result, never a crash (v2 fix). */
    private suspend fun runAgent(
        agent: SubAgent,
        ctx: com.mobius.agent.TaskContext,
        intent: RoutedIntent,
    ): AgentResult = try {
        agent.run(ctx, intent) // the agent's scope is cancelled and cleared inside run()
    } catch (e: CancellationException) {
        throw e
    } catch (e: CloudRequiredException) {
        AgentResult(false, e.message ?: "Cloud AI required.")
    } catch (e: CloudTimeoutException) {
        // v7.1 (a): 30 s cloud timeout, verbatim outcome text.
        AgentResult(false, e.message ?: "Cloud tidak merespons.")
    } catch (e: Exception) {
        AgentResult(false, "Something went wrong: ${e.message ?: e.javaClass.simpleName}")
    }

    /**
     * (A) Heuristic gate for the planning loop: only CHAT intents that plausibly need to
     * TOUCH the screen take the loop. Pure questions ("apa itu ...", "jelaskan ...") stay
     * on ChatAgent — a false positive would make the loop tap around a screen for a
     * question that needs no screen at all, while a false negative just means "answered
     * as chat", i.e. v5 behavior. "cari/cek/bandingkan" alone do NOT trigger the loop:
     * they need a named app too ("cari harga sepatu di shopee").
     */
    private fun looksLikeScreenTask(text: String): Boolean {
        val t = " ${text.lowercase()} "
        val apps = listOf(
            "whatsapp", "shopee", "tokopedia", "instagram", "tiktok", "youtube",
            "chrome", "gmail", "maps", "gojek", "grab", "dana", "ovo",
        )
        if (apps.any { it in t }) return true
        val actionVerbs = listOf(
            "buka", "kirim", "balas", "pesan", "beli", "ketuk", "klik", "isi",
            "tekan", "scroll", "unduh", "open", "send", "reply", "order",
            "buy", "tap", "click", "fill", "press", "download",
        )
        return actionVerbs.any { " $it " in t || t.trimStart().startsWith("$it ") }
    }

    /**
     * CODE AGENT SEAM (A, integration fix): CodeAgent now exists with the real constructor
     * `CodeAgent(taskId, tool, ai, featureFlags?)`, so this builds it directly instead of
     * by reflection (the reflection seam looked for an (AgentTools) or no-arg constructor,
     * which CodeAgent never had, so code steps would always have failed at runtime).
     * Returns null when no accessibility Tool is available — the loop then reports code
     * steps as unavailable instead of crashing.
     */
    private fun buildCodeExecutor(taskId: String): (suspend (String) -> AgentResult)? {
        val tool = buildTool() ?: return null
        val agent = CodeAgent(taskId, tool, aiRouter, featureFlags)
        return { desc: String -> agent.execute(desc) }
    }

    /** Runs a matched skill through the deterministic runner (no AI inside the runner). */
    private suspend fun runSkill(taskId: String, intent: RoutedIntent, userText: String, hooks: TaskHooks): SkillOutcome {
        val skill = intent.skillId?.let { skillRegistry.byId(it) }
            ?: return SkillOutcome(false, false, "Skill not found.", emptyList(), emptyMap())
        val tool = buildTool()
            ?: return SkillOutcome(
                false, false,
                "Accessibility access is off, so I can't operate other apps. Enable it in " +
                    "Settings > Accessibility > ${AppConfig.APP_NAME}.",
                emptyList(), emptyMap(),
            )
        // ITEM 2 (v5) — plan card: show the resolved steps BEFORE anything runs. Batal means
        // nothing runs at all; the cancellation is recorded in the audit trail.
        hooks.status("Showing plan for ${skill.name}...")
        val planOk = try {
            hooks.confirmPlan(skill.name, describePlan(skill, intent.skillParams))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false // fail closed: if the dialog itself breaks, do not run the skill
        }
        if (!planOk) {
            persistTrail(taskId, skill, userText, emptyList(), emptyList(), "cancelled")
            return SkillOutcome(false, false, "Dibatalkan.", emptyList(), intent.skillParams)
        }
        hooks.status("Running ${skill.name}...")
        val outcome = try {
            skillRunner.run(skill, intent.skillParams, tool, hooks::status, ActionGate, hooks::confirmStep)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SkillOutcome(false, false, "Something went wrong: ${e.message ?: e.javaClass.simpleName}", emptyList(), emptyMap())
        }
        persistTrail(taskId, skill, userText, outcome.steps, outcome.gateDecisions, "confirmed")
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
    private suspend fun buildTools(hooks: TaskHooks): AgentTools {
        val service = MobiusAccessibilityService.instance
        val reader = service?.let { ScreenReader(it) }
        val executor = if (service != null && reader != null) ActionExecutor(context, service, reader) else null
        // v7.4 — the task's UI language, so agents can use the ID/EN strings table
        // (SummarizePageAgent). A read failure keeps the default (ID).
        val language = runCatching { LanguageManager(context).language.first() }.getOrDefault(AppLanguage.ID)
        return object : AgentTools {
            override val ai: AIRouter = aiRouter
            override val reader: ScreenReader? = reader
            override val executor: ActionExecutor? = executor
            override val language: AppLanguage = language
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

    /**
     * Human-readable plan lines: "1. launchApp — package=com.shopee.id". $params are resolved
     * best-effort (an unresolvable reference is shown as-is, never hidden). Irreversible
     * steps are marked so the user sees the approval coming.
     */
    private fun describePlan(skill: Skill, params: Map<String, String>): List<String> {
        val vars = LinkedHashMap<String, String>()
        for (p in skill.params) {
            params[p.name]?.let { vars[p.name] = it } ?: p.default?.let { vars[p.name] = it }
        }
        return skill.steps.mapIndexed { i, step ->
            val args = step.args.entries.joinToString(", ") { (k, v) -> "$k=${resolveBestEffort(v, vars)}" }
            val risk = if (step.risk == ActionGate.RISK_IRREVERSIBLE) " [tak terbalikkan]" else ""
            "${i + 1}. ${step.tool}" + (if (args.isNotEmpty()) " — $args" else "") + risk
        }
    }

    /** Like the runner's resolver, but leaves an unknown $ref visible instead of failing. */
    private fun resolveBestEffort(value: String, vars: Map<String, String>): String =
        PLAN_REF.replace(value) { m -> vars[m.groupValues[1]] ?: m.value }

    /** Best-effort audit trail (v5): step results + gate decisions, same TTL as task memory. */
    private suspend fun persistTrail(
        taskId: String,
        skill: Skill,
        userText: String,
        steps: List<StepResult>,
        gateDecisions: List<GateDecisionRecord>,
        planDecision: String?,
    ) {
        try {
            val ttl = permissions.memoryTtlSeconds.first()
            memory.recordTrail(taskId, skill.name, userText, steps, gateDecisions, planDecision, ttl)
        } catch (e: Exception) {
            // Audit trail is best-effort; never fail the user's task because of it.
        }
    }

    /** (A) Best-effort trail for a planning-loop run (skillName "PlanningLoop"). */
    private suspend fun persistPlanningTrail(taskId: String, userText: String, agent: PlanningAgent) {
        try {
            val ttl = permissions.memoryTtlSeconds.first()
            memory.recordTrail(taskId, "PlanningLoop", userText, agent.steps, agent.gateDecisions, null, ttl)
        } catch (e: Exception) {
            // Audit trail is best-effort; never fail the user's task because of it.
        }
    }

    /** (B) Best-effort trail for an orchestration run (one row per job + the plan row). */
    private suspend fun persistOrchestrationTrail(
        taskId: String,
        userText: String,
        jobSteps: List<StepResult>,
        planDecision: String,
    ) {
        try {
            val ttl = permissions.memoryTtlSeconds.first()
            memory.recordTrail(taskId, "Orchestration", userText, jobSteps, emptyList(), planDecision, ttl)
        } catch (e: Exception) {
            // Audit trail is best-effort; never fail the user's task because of it.
        }
    }

    private companion object {
        val ALLOWED_TYPES = setOf(MemoryType.FACT, MemoryType.PREFERENCE, MemoryType.TASK_RESULT)
        val PLAN_REF = Regex("\\\$([A-Za-z0-9_.]+)")
        /** (E) Worker pool size, spec E default. */
        const val MAX_WORKERS = 2
    }
}

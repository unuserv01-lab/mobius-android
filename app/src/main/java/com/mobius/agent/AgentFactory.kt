package com.mobius.agent

import com.mobius.ai.PromptBuilder
import com.mobius.core.AgentType
import com.mobius.core.AppConfig
import com.mobius.core.FileExtractor
import com.mobius.core.MemoryType
import com.mobius.core.RoutedIntent
import com.mobius.ui.AppLanguage
import com.mobius.ui.stringsFor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Creates ONE subagent per task. Nothing is cached or reused (P2). */
class AgentFactory {
    fun create(type: AgentType, taskId: String, tools: AgentTools): SubAgent = when (type) {
        AgentType.REPLY_WHATSAPP -> ReplyWhatsAppAgent(taskId, tools)
        AgentType.CHAT -> ChatAgent(taskId, tools)
        AgentType.SUMMARIZE_PAGE -> SummarizePageAgent(taskId, tools)
        // Skills never reach the factory: the Orchestrator runs them via SkillRunner.
        AgentType.SKILL -> throw IllegalArgumentException("Skills run via SkillRunner, not AgentFactory")
    }
}

/** Plain AI answer. Works with no cross-app permission at all. */
class ChatAgent(taskId: String, tools: AgentTools) : SubAgent(taskId, tools) {
    override val name = "ChatTask"

    override suspend fun execute(ctx: TaskContext, intent: RoutedIntent): AgentResult {
        tools.status("Thinking...")
        val heavy = ctx.userText.length > 400
        val answer = tools.ai.generate(taskId, PromptBuilder.chatPrompt(ctx.userText, ctx), heavy)
        return AgentResult(success = true, message = answer.text)
    }
}

/**
 * v7.4 — 3rd agent type: summarize the page currently on screen ("ringkas halaman ini").
 * 1) Reads the visible screen text of the foreground app through the existing
 *    readScreen primitive (NO taps, NO typing — read-only, so no screen lock and no
 *    ActionGate involvement). 2) Truncates to FileExtractor.MAX_CHARS (8000, the same
 *    convention as attached files). 3) Asks the AI through the SAME fallback chain as
 *    chat (AIRouter.generate: Nano -> Gemini cloud 30s -> OpenRouter if a key is stored),
 *    with heavy=true — an 8000-char summary is heavy reasoning, never a Nano job.
 * 4) Returns the summary as the outcome.
 *
 * Honest failure (never an invented summary): no accessibility reader, no foreground
 * app, or too little readable text (canvas-drawn apps, games, video players) all
 * return a plain-language failure in the user's language.
 */
class SummarizePageAgent(taskId: String, tools: AgentTools) : SubAgent(taskId, tools) {
    override val name = "SummarizePageTask"

    override suspend fun execute(ctx: TaskContext, intent: RoutedIntent): AgentResult {
        val s = stringsFor(tools.language)
        val reader = tools.reader
            ?: return AgentResult(false, s.sumNoAccess)

        // Foreground app from UsageStats (already excludes MOBIUS itself); fall back to
        // the active window. Never read our own bubble window: its text is our UI, not
        // the page the user asked about.
        val pkg = ctx.foregroundApp ?: reader.currentPackage()
        if (pkg.isNullOrBlank() || pkg == MOBIUS_PACKAGE) {
            return AgentResult(false, s.sumNoApp)
        }

        tools.status(s.sumReading)
        val texts = reader.readScreen(pkg, READ_LIMIT)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct() // WebView trees often repeat the same node text
        val (pageText, _) = FileExtractor.truncateText(texts.joinToString("\n").trim())
        if (pageText.length < MIN_READABLE_CHARS) {
            return AgentResult(false, s.sumNoText)
        }

        tools.status(s.sumSummarizing)
        val languageName = if (tools.language == AppLanguage.EN) "English" else "Bahasa Indonesia"
        val answer = tools.ai.generate(
            taskId,
            PromptBuilder.summarizePrompt(pageText, languageName, ctx),
            needsHeavyReasoning = true,
        )
        val summary = answer.text.trim()
        if (summary.isBlank()) return AgentResult(false, s.sumFailed)
        return AgentResult(success = true, message = summary)
    }

    private companion object {
        /** Same node cap as the readScreen primitive's default. */
        const val READ_LIMIT = 200
        /** Below this the screen holds only nav labels/buttons — not a summarizable page. */
        const val MIN_READABLE_CHARS = 50
        /** Our own package (aapt2: com.mobius) — never summarize our own bubble. */
        const val MOBIUS_PACKAGE = "com.mobius"
    }
}

/**
 * End-to-end cross-app task: read the last WhatsApp notification, draft/confirm a reply, then
 * open the chat, type and send it through the AccessibilityService.
 */
class ReplyWhatsAppAgent(taskId: String, tools: AgentTools) : SubAgent(taskId, tools) {
    override val name = "ReplyTask"

    override suspend fun execute(ctx: TaskContext, intent: RoutedIntent): AgentResult {
        // 1) Read the last WhatsApp notification (NotificationListenerService).
        tools.status("Reading last WhatsApp message...")
        val msg = ctx.recentNotifications.lastOrNull { it.packageName in AppConfig.WHATSAPP_PACKAGES }
            ?: return AgentResult(
                false,
                "I couldn't find a recent WhatsApp notification. Check that Notification access is " +
                    "enabled and that a message arrived in the last " +
                    "${AppConfig.NOTIFICATION_TTL_MS / 60_000} minutes."
            )

        // 2) Cross-app capability check BEFORE spending any AI call (P8: degrade, don't crash).
        val reader = tools.reader
        val executor = tools.executor
        if (reader == null || executor == null) {
            return AgentResult(
                false,
                "Accessibility access is off, so I can't operate WhatsApp. Enable it in " +
                    "Settings > Accessibility > ${AppConfig.APP_NAME}."
            )
        }

        // 3) Draft the reply (user-dictated text wins; otherwise the AI layer drafts it).
        val draft = (intent.replyText ?: run {
            tools.status("Drafting a reply...")
            tools.ai.generate(taskId, PromptBuilder.replyPrompt(ctx.userText, msg, ctx)).text
        }).trim().trim('"').take(500)
        if (draft.isBlank()) return AgentResult(false, "I couldn't draft a reply.")

        // 4) Human-in-the-loop (setting, default ON): never message someone without approval.
        if (!tools.confirmSend(msg.sender, draft)) {
            return AgentResult(false, "Cancelled. Nothing was sent.")
        }

        // 5) Open the exact chat: tap the notification's own PendingIntent (fallback: launch app + tap name).
        tools.status("Opening WhatsApp...")
        val pkg = msg.packageName
        val hasIntent = msg.contentIntent != null
        val opened = if (hasIntent) executor.firePendingIntent(msg.contentIntent!!) else executor.launchApp(pkg)
        if (!opened) return AgentResult(false, "I couldn't open WhatsApp.")

        val input = if (hasIntent) {
            executor.waitFor(8_000) { reader.findChatInput(pkg) }
        } else {
            executor.waitFor(8_000) {
                reader.findChatInput(pkg) ?: run {
                    reader.findByText(pkg, msg.sender)?.let { executor.click(it) }
                    null
                }
            }
        } ?: return AgentResult(false, "WhatsApp's chat did not open in time.")

        // 6) Type, then find the Send button (only matches a button literally labelled Send).
        tools.status("Typing reply...")
        if (!executor.setText(input, draft)) {
            return AgentResult(false, "WhatsApp did not accept the text.")
        }
        val send = executor.waitFor(3_000) { reader.findSendButton(pkg) }
            ?: return AgentResult(false, "I typed the reply but couldn't find WhatsApp's Send button. It was NOT sent.")

        tools.status("Sending...")
        if (!executor.click(send)) return AgentResult(false, "I couldn't press Send. The reply was NOT sent.")

        // 7) Verify: the input box should no longer contain our draft.
        val cleared = executor.waitFor(3_000) {
            reader.findChatInput(pkg)?.let { if (it.text?.toString() != draft) true else null }
        }
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        return if (cleared == true) {
            AgentResult(
                success = true,
                message = "Done. Replied to ${msg.sender} at $time.",
                // Outcome only: the message text itself is not proposed for storage.
                proposals = listOf(MemoryProposal(MemoryType.TASK_RESULT, "Replied to ${msg.sender} at $time")),
            )
        } else {
            AgentResult(false, "I pressed Send but couldn't confirm delivery on screen. Please check WhatsApp.")
        }
    }
}

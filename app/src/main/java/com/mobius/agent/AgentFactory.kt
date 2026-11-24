package com.mobius.agent

import com.mobius.ai.PromptBuilder
import com.mobius.core.AgentType
import com.mobius.core.AppConfig
import com.mobius.core.MemoryType
import com.mobius.core.RoutedIntent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Creates ONE subagent per task. Nothing is cached or reused (P2). */
class AgentFactory {
    fun create(type: AgentType, taskId: String, tools: AgentTools): SubAgent = when (type) {
        AgentType.REPLY_WHATSAPP -> ReplyWhatsAppAgent(taskId, tools)
        AgentType.CHAT -> ChatAgent(taskId, tools)
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

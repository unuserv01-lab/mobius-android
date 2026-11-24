package com.mobius.ai

import com.mobius.agent.TaskContext
import com.mobius.core.AppConfig
import com.mobius.notification.CapturedNotification

/**
 * Builds short, single-turn prompts (Gemini Nano is limited to small single-turn text).
 * Only the minimum context is included, and external text is clipped. The intent prompt never
 * includes notification contents.
 */
object PromptBuilder {

    private fun clip(s: String, max: Int = AppConfig.MAX_PROMPT_FIELD_CHARS): String =
        s.replace('\n', ' ').trim().let { if (it.length > max) it.take(max) + "..." else it }

    fun intentPrompt(userText: String, ctx: TaskContext): String = """
        You classify a phone assistant request. Answer with ONE line of JSON and nothing else.
        Schema: {"intent": "reply_whatsapp" | "chat", "reply_text": string or null}
        - "reply_whatsapp": the user wants to reply to their latest WhatsApp message.
        - "chat": anything else (questions, writing help, general conversation).
        - "reply_text": ONLY if the user dictated the exact words to send; otherwise null.
        Current app: ${ctx.foregroundApp ?: "unknown"}
        Request: "${clip(userText)}"
    """.trimIndent()

    fun replyPrompt(userText: String, msg: CapturedNotification, ctx: TaskContext): String = """
        You draft a short WhatsApp reply on behalf of the user.
        Message from ${clip(msg.sender, 60)}: "${clip(msg.text)}"
        The user's instruction: "${clip(userText)}"
        Time now: ${ctx.time}
        Rules: reply in the same language as the message, at most two short sentences, natural tone.
        Output ONLY the reply text. No quotes, no explanations.
    """.trimIndent()

    fun chatPrompt(userText: String, ctx: TaskContext): String = """
        You are a concise phone assistant. Time now: ${ctx.time}.
        Answer in a few sentences, in the language of the request.
        Request: "${clip(userText, 800)}"
    """.trimIndent()

    /** Asks the LLM to fill a skill's declared params from the request. ONE line of JSON only. */
    fun skillParamsPrompt(userText: String, skill: com.mobius.core.Skill): String = """
        You extract parameters for a phone task. Answer with ONE line of JSON and nothing else.
        Schema: {"params": {"<name>": "<value>"}}
        Task: ${skill.name} — ${clip(skill.description, 200)}
        Parameters: ${skill.params.joinToString(", ") { "${it.name}${if (it.required) " (required)" else ""}" }}
        - Copy the value from the request; never invent one. Omit a parameter if the request does not give it.
        Request: "${clip(userText)}"
    """.trimIndent()

    /** Asks the LLM to summarize a skill run's structured result in one or two short sentences. */
    fun skillSummaryPrompt(skill: com.mobius.core.Skill, outcome: com.mobius.core.SkillOutcome): String = """
        You summarize the result of a phone task in one or two short sentences, in the user's language.
        Task: ${skill.name}.
        Result data: ${outcome.vars.entries.joinToString("; ") { "${it.key}=${clip(it.value, 200)}" }}
        Rules: state the key facts only (for example the prices found). No preamble, no advice.
    """.trimIndent()
}

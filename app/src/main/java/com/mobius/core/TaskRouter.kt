package com.mobius.core

import com.mobius.agent.TaskContext
import com.mobius.ai.AIRouter
import com.mobius.ai.PromptBuilder
import org.json.JSONObject

/** Which kind of temporary worker a request needs. */
enum class AgentType { REPLY_WHATSAPP, CHAT, SKILL }

data class RoutedIntent(
    val type: AgentType,
    /** Exact words to send if the user dictated them; null means "draft something for me". */
    val replyText: String?,
    /** Raw model output, kept only for debugging. */
    val raw: String,
    /** Skill id when type == SKILL. */
    val skillId: String? = null,
    /** Params for the skill, filled by the LLM (or the keyword heuristic fallback). */
    val skillParams: Map<String, String> = emptyMap(),
)

/**
 * Intent -> subagent type. Intent parsing is done by the AI layer (Gemini Nano first, via AIRouter).
 * If the model answers with something unparsable we fall back to a tiny keyword heuristic;
 * if the AI layer is BLOCKED (cloud needed but not allowed) we let the exception reach the user.
 *
 * SKILL routing (two layers, 24 Nov 2026): the [SkillRegistry] scores the request against
 * skill trigger phrases FIRST (pure keyword scoring, no LLM). On a confident match the LLM is
 * only asked to fill the skill's params; if the LLM is unavailable the keyword heuristic
 * fills them best-effort instead, so a skill can still run with no AI at all. No clear
 * keyword match -> the existing LLM classification below.
 */
class TaskRouter(private val ai: AIRouter, private val registry: SkillRegistry) {

    /** @throws com.mobius.ai.CloudRequiredException when cloud is needed but not allowed. */
    suspend fun route(taskId: String, userText: String, ctx: TaskContext): RoutedIntent {
        registry.match(userText)?.let { match ->
            val params = extractSkillParams(taskId, match.skill, userText)
            return RoutedIntent(
                type = AgentType.SKILL,
                replyText = null,
                raw = "skill:${match.skill.id} (keyword score ${match.score})",
                skillId = match.skill.id,
                skillParams = params,
            )
        }
        val response = ai.generate(taskId, PromptBuilder.intentPrompt(userText, ctx))
        return parse(response.text, userText)
    }

    /** LLM fills the skill params; falls back to the keyword heuristic when the AI is unavailable. */
    private suspend fun extractSkillParams(taskId: String, skill: Skill, userText: String): Map<String, String> {
        val fromLlm = try {
            val response = ai.generate(taskId, PromptBuilder.skillParamsPrompt(userText, skill))
            val json = JSONObject(response.text.substring(response.text.indexOf('{'), response.text.lastIndexOf('}') + 1))
            val paramsObj = json.optJSONObject("params") ?: return heuristicParams(skill, userText)
            val out = LinkedHashMap<String, String>()
            for (param in skill.params) {
                paramsObj.optString(param.name, "").takeIf { it.isNotBlank() }?.let { out[param.name] = it }
                    ?: param.default?.let { out[param.name] = it }
            }
            out
        } catch (e: Exception) {
            heuristicParams(skill, userText)
        }
        // Required params the LLM skipped still get the heuristic treatment.
        if (skill.params.any { it.required && it.name !in fromLlm }) {
            return fromLlm + heuristicParams(skill, userText).filterKeys { it !in fromLlm }
        }
        return fromLlm
    }

    /**
     * Best-effort param filling without any AI: strip the skill's trigger phrases (plus common
     * filler words) from the request; what is left is the value of the single required param
     * (if there is exactly one). E.g. "cari harga sepatu di shopee" -> query = "sepatu".
     */
    private fun heuristicParams(skill: Skill, userText: String): Map<String, String> {
        val required = skill.params.filter { it.required }
        if (required.size != 1) return emptyMap()
        var rest = userText
        for (trigger in skill.triggers.sortedByDescending { it.length }) {
            rest = rest.replace(trigger, " ", ignoreCase = true)
        }
        for (filler in FILLER_WORDS) {
            rest = rest.replace(Regex("\\b$filler\\b", RegexOption.IGNORE_CASE), " ")
        }
        val value = rest.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.joinToString(" ")
        return if (value.isNotBlank()) mapOf(required.first().name to value) else emptyMap()
    }

    private companion object {
        val FILLER_WORDS = setOf("di", "ke", "dari", "tolong", "dong", "ya", "please", "cari", "cek", "harga", "bandingkan")
    }

    private fun parse(raw: String, userText: String): RoutedIntent = try {
        val json = JSONObject(raw.substring(raw.indexOf('{'), raw.lastIndexOf('}') + 1))
        val type = when (json.optString("intent").lowercase()) {
            "reply_whatsapp" -> AgentType.REPLY_WHATSAPP
            "chat" -> AgentType.CHAT
            else -> throw IllegalArgumentException("unknown intent")
        }
        val reply = if (json.isNull("reply_text")) null else json.optString("reply_text")
        RoutedIntent(type, reply?.trim()?.takeIf { it.isNotEmpty() && !it.equals("null", true) }, raw)
    } catch (e: Exception) {
        heuristic(raw, userText)
    }

    private fun heuristic(raw: String, userText: String): RoutedIntent {
        val t = userText.lowercase()
        val mentionsWa = "whatsapp" in t || Regex("\\bwa\\b").containsMatchIn(t)
        val wantsReply = listOf("reply", "balas", "jawab", "respond").any { it in t }
        return RoutedIntent(
            if (mentionsWa && wantsReply) AgentType.REPLY_WHATSAPP else AgentType.CHAT,
            replyText = null,
            raw = raw,
        )
    }
}

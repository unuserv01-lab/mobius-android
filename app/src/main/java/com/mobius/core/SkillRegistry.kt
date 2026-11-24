package com.mobius.core

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * Loads every skill JSON from app/src/main/assets/skills/ (one JSON file per skill) at startup and indexes them
 * by trigger phrases. Loading is best-effort: a malformed file is logged and skipped, never
 * fatal (P8).
 *
 * Matching is KEYWORD SCORING only — no LLM involved:
 *   - a multi-word trigger phrase contained in the request: +3
 *   - a single-word trigger contained in the request: +2
 *   - any word of a trigger phrase present in the request: +1
 * A match needs score >= 3 AND to beat the runner-up strictly. Anything else is "no clear
 * match" and the caller falls back to the LLM classifier ([TaskRouter]).
 *
 * Trigger hygiene (why "harga" alone must NOT be a trigger): a lone generic word scores on
 * unrelated requests ("harga saham" would match a Shopee skill). Triggers should be an app
 * name ("shopee") or a multi-word phrase ("cari harga", "bandingkan harga").
 */
class SkillRegistry(context: Context) {

    private val skills: List<Skill> = load(context)

    fun all(): List<Skill> = skills

    fun byId(id: String): Skill? = skills.find { it.id == id }

    data class Match(val skill: Skill, val score: Int)

    fun match(userText: String): Match? {
        val t = userText.lowercase()
        var best: Skill? = null
        var bestScore = 0
        var secondScore = 0
        for (skill in skills) {
            var score = 0
            for (rawTrigger in skill.triggers) {
                val trigger = rawTrigger.lowercase().trim()
                if (trigger.isEmpty()) continue
                if (t.contains(trigger)) {
                    score += if (' ' in trigger) 3 else 2
                } else {
                    val words = trigger.split(Regex("\\s+")).filter { it.length > 2 }
                    if (words.isNotEmpty() && words.any { it in t }) score += 1
                }
            }
            if (score > bestScore) {
                secondScore = bestScore
                bestScore = score
                best = skill
            } else if (score > secondScore) {
                secondScore = score
            }
        }
        return if (best != null && bestScore >= 3 && bestScore > secondScore) Match(best, bestScore) else null
    }

    private fun load(context: Context): List<Skill> {
        val out = mutableListOf<Skill>()
        val names = try {
            context.assets.list("skills")?.filter { it.endsWith(".json") } ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "Could not list assets/skills", e)
            return out
        }
        for (name in names) {
            try {
                val text = context.assets.open("skills/$name").bufferedReader().readText()
                val skill = parseSkill(JSONObject(text))
                if (skill == null) Log.w(TAG, "Skipping malformed skill file: skills/$name")
                else out += skill
            } catch (e: Exception) {
                Log.w(TAG, "Could not load skill file: skills/$name", e)
            }
        }
        Log.i(TAG, "Loaded ${out.size} skill(s): ${out.joinToString { it.id }}")
        return out
    }

    private companion object {
        const val TAG = "SkillRegistry"
    }
}

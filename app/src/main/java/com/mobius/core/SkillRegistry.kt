package com.mobius.core

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * Persistence for skill metadata, when the registry is wired to the Room
 * database (see [MemoryStore] / `skill_meta` table, migration 3→4). The
 * registry works fully WITHOUT a store (in-memory metadata only); the store
 * only makes `enabled` / `useCount` survive restarts. Wiring is the
 * coordinator's job: `SkillRegistry(context, memoryStore)` — [MemoryStore]
 * implements this interface.
 */
interface SkillMetaStore {
    /** Upserts file metadata; must NOT reset the stored `enabled`/`useCount`. */
    suspend fun upsertSkillMeta(meta: SkillMeta)

    /** All stored rows (effective enabled/useCount included). */
    suspend fun allSkillMeta(): List<SkillMeta>

    suspend fun setSkillEnabled(id: String, enabled: Boolean)
    suspend fun incrementSkillUseCount(id: String)
}

/**
 * Loads every skill from app/src/main/assets/skills/ at startup and indexes them
 * by trigger phrases. Loading is best-effort: a malformed file is logged and skipped, never
 * fatal (P8).
 *
 * DUAL FORMAT (v8.1): `.json` (legacy) and `.md` (SKILL.md, see [SkillMdParser])
 * live side by side. A skill may also be a folder `<id>/SKILL.md` with optional
 * `scripts/`, `references/`, `assets/` next to it. Precedence for one id:
 * folder `SKILL.md` > flat `.md` > `.json`; the shadowed file stays on disk
 * and the shadowing is logged. A malformed `.md` falls back to the `.json`
 * file of the same id when one exists.
 *
 * PROGRESSIVE DISCLOSURE (3 levels):
 *   1. Startup holds ONLY metadata ([SkillMeta]: name, description, version,
 *      source, triggers) — for `.md` skills only the frontmatter is parsed.
 *      Trigger scoring runs on metadata alone.
 *   2. The SKILL.md body + steps are loaded only when the skill is actually
 *      triggered ([match]/[byId]); the result is cached, and is the exact
 *      same [Skill] object [SkillRunner] already consumes for JSON skills.
 *      JSON skills are small and fully loaded at startup, as before.
 *   3. Bundled resources are loaded only via [loadResource], on demand.
 *
 * Matching is KEYWORD SCORING only — no LLM involved:
 *   - a multi-word trigger phrase contained in the request: +3
 *   - a single-word trigger contained in the request: +2
 *   - any word of a trigger phrase present in the request: +1
 * A match needs score >= 3 AND to beat the runner-up strictly. Anything else is "no clear
 * match" and the caller falls back to the LLM classifier ([TaskRouter]).
 * Disabled skills (see [setEnabled]) never match.
 *
 * (v8.1-hotfix-4) The +1 word branch is DEDUPED per skill: one generic word may
 * count at most once per skill, no matter how many triggers contain it. Before
 * this, "buka" (in 3 laptop-skill triggers) stacked to a confident score 3 on
 * a plain "buka youtube" — with no laptop word anywhere in the request — and
 * stole every "buka <app>" command to the bridge. The +3/+2 phrase branches
 * are untouched: a real laptop phrase ("buka di laptop") still scores +3.
 * The same dedupe also defuses every other repeated generic word (alarm 5x,
 * jadwal 5x, drive 5x, email 5x, harga 4x — see the audit in BUILD-LOG.md).
 *
 * Trigger hygiene (why "harga" alone must NOT be a trigger): a lone generic word scores on
 * unrelated requests ("harga saham" would match a Shopee skill). Triggers should be an app
 * name ("shopee") or a multi-word phrase ("cari harga", "bandingkan harga").
 */
class SkillRegistry(context: Context, private val metaStore: SkillMetaStore? = null) {

    private val appContext = context.applicationContext

    /** Effective metadata for every skill (file metadata + DB enabled/useCount when wired). */
    private val metas = mutableListOf<SkillMeta>()

    /** Fully loaded skills. JSON skills are all here from startup; .md skills land here lazily. */
    private val fullSkills = LinkedHashMap<String, Skill>()

    /**
     * JSON fallback for an id whose .md file shadows the .json one: when the
     * .md turns out unparseable at full-load time, the JSON skill runs instead
     * (logged). The JSON original is never deleted.
     */
    private val jsonFallbacks = mutableMapOf<String, Skill>()

    /** Level-2 bodies of loaded .md skills, by skill id. */
    private val bodies = mutableMapOf<String, String>()

    init {
        loadAll()
    }

    /** Level 1 — metadata of every skill (cheap; never loads a SKILL.md body). */
    fun metadata(): List<SkillMeta> = metas.toList()

    /**
     * All skills, fully loaded. NOTE: this loads every SKILL.md body — prefer
     * [metadata] for listing and [byId]/[match] for running. Kept for
     * backward compatibility; currently no caller uses it.
     */
    fun all(): List<Skill> = metas.mapNotNull { fullSkillFor(it) }

    fun byId(id: String): Skill? {
        val meta = metas.find { it.id == id } ?: return null
        return fullSkillFor(meta)
    }

    /** Level 2 — the Markdown body of a SKILL.md skill (null for JSON skills / unknown ids). */
    fun body(id: String): String? {
        val meta = metas.find { it.id == id } ?: return null
        if (meta.source != SkillSource.MARKDOWN) return null
        if (id !in bodies) fullSkillFor(meta) // loads + caches the body as a side effect
        return bodies[id]
    }

    /** Level 3 — one bundled resource of a folder-layout skill, on demand (null when absent). */
    fun loadResource(skillId: String, relativePath: String): String? =
        SkillMdParser.loadSkillResource(appContext, skillId, relativePath)

    data class Match(val skill: Skill, val score: Int)

    fun match(userText: String): Match? {
        val t = userText.lowercase()
        var best: SkillMeta? = null
        var bestScore = 0
        var secondScore = 0
        for (meta in metas) {
            if (!meta.enabled) continue
            var score = 0
            // (v8.1-hotfix-4) Dedupe kata antar trigger: satu kata generik
            // ("buka", "jadwal", "email", ...) hanya dihitung SEKALI per skill.
            // Tanpa ini, "buka" di 3 trigger skill laptop menumpuk jadi skor 3
            // pada "buka youtube" — padahal tidak ada satu pun kata laptop
            // ("di laptop"/"komputer"/"pc") di perintah itu.
            val countedWords = mutableSetOf<String>()
            for (rawTrigger in meta.triggers) {
                val trigger = rawTrigger.lowercase().trim()
                if (trigger.isEmpty()) continue
                if (t.contains(trigger)) {
                    score += if (' ' in trigger) 3 else 2
                } else {
                    val words = trigger.split(Regex("\\s+")).filter { it.length > 2 }
                    val fresh = words.filter { it in t && it !in countedWords }
                    if (fresh.isNotEmpty()) {
                        score += 1
                        countedWords += fresh
                    }
                }
            }
            if (score > bestScore) {
                secondScore = bestScore
                bestScore = score
                best = meta
            } else if (score > secondScore) {
                secondScore = score
            }
        }
        if (best == null || bestScore < 3 || bestScore <= secondScore) return null
        // Level 2: the body + steps are loaded only now that the skill is triggered.
        val skill = fullSkillFor(best) ?: return null
        return Match(skill, bestScore)
    }

    // ----- SQLite-backed runtime state (no-op when no store is wired) -----

    /**
     * Persists file metadata to the `skill_meta` table (preserving the stored
     * enabled/useCount), then merges the stored runtime state back into the
     * in-memory metadata. Call once at startup from a coroutine scope after
     * constructing the registry with a store. Safe to call repeatedly.
     */
    suspend fun syncMetadataToDb() {
        val store = metaStore ?: return
        for (meta in metas) {
            runCatching { store.upsertSkillMeta(meta) }
                .onFailure { Log.w(TAG, "Could not persist skill meta ${meta.id}", it) }
        }
        refreshFromDb()
    }

    /** Re-reads enabled/useCount from the DB into the in-memory metadata. */
    suspend fun refreshFromDb() {
        val store = metaStore ?: return
        val rows = runCatching { store.allSkillMeta() }.getOrNull() ?: return
        val byId = rows.associateBy { it.id }
        for (i in metas.indices) {
            val row = byId[metas[i].id] ?: continue
            metas[i] = metas[i].copy(enabled = row.enabled, useCount = row.useCount)
        }
    }

    /** Enables/disables a skill (in memory + DB). Disabled skills never match. */
    suspend fun setEnabled(id: String, enabled: Boolean) {
        val i = metas.indexOfFirst { it.id == id }
        if (i < 0) return
        metas[i] = metas[i].copy(enabled = enabled)
        metaStore?.let { store ->
            runCatching { store.setSkillEnabled(id, enabled) }
                .onFailure { Log.w(TAG, "Could not persist enabled=$enabled for $id", it) }
        }
    }

    /**
     * Records one run of a skill (in memory + DB). Call this from the
     * orchestrator AFTER a skill run finishes — matching alone is not a run.
     */
    suspend fun recordUse(id: String) {
        val i = metas.indexOfFirst { it.id == id }
        if (i < 0) return
        metas[i] = metas[i].copy(useCount = metas[i].useCount + 1)
        metaStore?.let { store ->
            runCatching { store.incrementSkillUseCount(id) }
                .onFailure { Log.w(TAG, "Could not persist useCount for $id", it) }
        }
    }

    // ----- loading -----

    /** One load candidate for a skill id (file wins by [priority]; see [loadAll]). */
    private data class Candidate(val priority: Int, val meta: SkillMeta, val jsonSkill: Skill?)

    /** Fully loads a skill for metadata (cached). Null when the file cannot be parsed. */
    private fun fullSkillFor(meta: SkillMeta): Skill? {
        fullSkills[meta.id]?.let { return it }
        if (meta.source != SkillSource.MARKDOWN) return null // JSON skills are pre-loaded
        val text = try {
            appContext.assets.open(meta.assetPath).bufferedReader().readText()
        } catch (e: Exception) {
            Log.w(TAG, "Could not read skill file: ${meta.assetPath}", e)
            return jsonFallbacks[meta.id]?.also { fullSkills[meta.id] = it }
        }
        val parsed = SkillMdParser.parseSkillMd(text, meta.assetPath)
            ?: return jsonFallbacks[meta.id]?.also {
                Log.w(TAG, "SKILL.md ${meta.assetPath} unparseable; falling back to JSON skill '${meta.id}'")
                fullSkills[meta.id] = it
            }
        val skill = SkillMdParser.skillFromMd(parsed)
            ?: return jsonFallbacks[meta.id]?.also {
                Log.w(TAG, "SKILL.md ${meta.assetPath} has invalid steps JSON; falling back to JSON skill '${meta.id}'")
                fullSkills[meta.id] = it
            }
        fullSkills[meta.id] = skill
        bodies[meta.id] = parsed.body
        return skill
    }

    private fun loadAll() {
        val entries = try {
            appContext.assets.list("skills")?.toList() ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "Could not list assets/skills", e)
            return
        }
        // Candidates keyed by skill id; higher priority wins, loser is logged.
        val candidates = LinkedHashMap<String, Candidate>()
        fun offer(meta: SkillMeta, jsonSkill: Skill?, priority: Int, label: String) {
            val prev = candidates[meta.id]
            if (prev == null || priority > prev.priority) {
                if (prev != null) {
                    Log.i(TAG, "Skill '${meta.id}': $label shadows ${prev.meta.assetPath} (same id, lower priority)")
                    // The shadowed JSON stays as a runnable fallback (see fullSkillFor).
                    prev.jsonSkill?.let { jsonFallbacks[meta.id] = it }
                }
                candidates[meta.id] = Candidate(priority, meta, jsonSkill)
            } else {
                Log.i(TAG, "Skill '${meta.id}': ignoring $label, shadowed by ${prev.meta.assetPath}")
                // A losing JSON file is still the fallback for the winning .md.
                jsonSkill?.let { jsonFallbacks[meta.id] = it }
            }
        }
        for (name in entries) {
            when {
                name.endsWith(".json") -> {
                    val path = "skills/$name"
                    try {
                        val text = appContext.assets.open(path).bufferedReader().readText()
                        val skill = parseSkill(JSONObject(text))
                        if (skill == null) Log.w(TAG, "Skipping malformed skill file: $path")
                        else offer(
                            SkillMeta(
                                id = skill.id, name = skill.name, description = skill.description,
                                version = 1, source = SkillSource.JSON, triggers = skill.triggers,
                                assetPath = path,
                            ),
                            skill, PRIORITY_JSON, path,
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not load skill file: $path", e)
                    }
                }
                name.endsWith(".md") -> {
                    val path = "skills/$name"
                    try {
                        val text = appContext.assets.open(path).bufferedReader().readText()
                        val meta = SkillMdParser.parseSkillMdMeta(text, path)
                        if (meta == null) Log.w(TAG, "Skipping SKILL.md without usable frontmatter: $path")
                        else offer(meta, null, PRIORITY_MD_FLAT, path)
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not load skill file: $path", e)
                    }
                }
                '.' !in name -> {
                    // Possible folder-layout skill: <id>/SKILL.md (+ scripts/, references/).
                    val path = "skills/$name/SKILL.md"
                    try {
                        val inner = appContext.assets.list("skills/$name")?.toList() ?: emptyList()
                        if ("SKILL.md" !in inner) continue
                        val text = appContext.assets.open(path).bufferedReader().readText()
                        val meta = SkillMdParser.parseSkillMdMeta(text, path)
                        if (meta == null) Log.w(TAG, "Skipping SKILL.md without usable frontmatter: $path")
                        else offer(meta, null, PRIORITY_MD_FOLDER, path)
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not load skill file: $path", e)
                    }
                }
            }
        }
        for ((_, c) in candidates) {
            metas += c.meta
            c.jsonSkill?.let { fullSkills[it.id] = it }
        }
        Log.i(
            TAG,
            "Loaded ${metas.size} skill(s): " +
                metas.joinToString { "${it.id}(${it.source.name.lowercase()} v${it.version})" },
        )
    }

    private companion object {
        const val TAG = "SkillRegistry"
        const val PRIORITY_JSON = 1
        const val PRIORITY_MD_FLAT = 2
        const val PRIORITY_MD_FOLDER = 3
    }
}

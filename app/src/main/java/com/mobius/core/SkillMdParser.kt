package com.mobius.core

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * SKILL.md parser (v8.1, Priority 5 of the competitor breakdown).
 *
 * Format (pattern adapted from OpenMinis / the Anthropic SKILL.md convention —
 * written from scratch here, no copied code; MOBIUS itself is Apache 2.0 and
 * the OpenMinis repo is GPLv3, so only the *pattern* is adopted):
 *
 *   ---
 *   name: shopee_price_search        # skill id, lowercase_snake (REQUIRED)
 *   description: Search Shopee ...   # one-line description
 *   version: 1                       # optional, default 1
 *   triggers: shopee, cari harga     # comma list, inline [a, b], or block "- " list
 *   title: Shopee price search       # optional display name (defaults to name)
 *   ---
 *   # Shopee price search
 *   Markdown instructions ...        # body, kept under ~500 lines
 *   ```json
 *   { ...same JSON schema as a legacy skill file... }
 *   ```
 *
 * Rules:
 * - Frontmatter wins for metadata (name/description/version/triggers/title);
 *   the embedded ```json block wins for the executable part
 *   (params/steps/maxSteps/timeoutMs/summarize). If the frontmatter `name`
 *   and the JSON `id` disagree, frontmatter wins and a warning is logged.
 * - The frontmatter is parsed with a deliberately tiny YAML subset (no YAML
 *   library — no new Gradle dependency): `key: value`, quoted values,
 *   inline lists `[a, b]`, comma-separated values, and block lists (`- item`
 *   on the lines after an empty `key:`). Unknown keys are ignored.
 * - `parseSkillMdMeta` reads ONLY the frontmatter (level 1, cheap — used at
 *   startup for trigger scoring). `parseSkillMd` reads the whole file
 *   (level 2, only when the skill is triggered).
 * - A missing frontmatter, a missing `name`, or a missing/invalid ```json
 *   block makes the file unparseable (null); the registry then falls back to
 *   a JSON file of the same id when one exists.
 * - A body over 500 lines only logs a warning (OpenMinis guidance), it is
 *   not rejected — rejecting would break a skill the user wrote by hand.
 */
object SkillMdParser {

    private const val TAG = "SkillMdParser"
    const val MAX_BODY_LINES = 500

    /** Full parse result: metadata + body + the raw steps JSON (null when absent). */
    data class ParsedSkillMd(
        val meta: SkillMeta,
        /** Markdown body with the frontmatter and the ```json block removed. */
        val body: String,
        val stepsJson: JSONObject?,
    )

    /** Level 1 — frontmatter only. Returns null when there is no usable frontmatter. */
    fun parseSkillMdMeta(text: String, assetPath: String): SkillMeta? {
        val fm = extractFrontmatter(text) ?: return null
        val name = fm["name"]?.trim()?.takeIf { it.isNotEmpty() } ?: run {
            Log.w(TAG, "SKILL.md $assetPath has no frontmatter 'name'; skipping")
            return null
        }
        return SkillMeta(
            id = name,
            name = fm["title"]?.trim()?.takeIf { it.isNotBlank() } ?: name,
            description = fm["description"]?.trim().orEmpty(),
            version = fm["version"]?.trim()?.toIntOrNull() ?: 1,
            source = SkillSource.MARKDOWN,
            triggers = parseStringList(fm["triggers"]),
            assetPath = assetPath,
        )
    }

    /** Level 2 — full parse. Returns null when the file cannot become a runnable skill. */
    fun parseSkillMd(text: String, assetPath: String): ParsedSkillMd? {
        val meta = parseSkillMdMeta(text, assetPath) ?: return null
        val body = extractBody(text)
        val stepsJson = extractJsonBlock(text)
        if (stepsJson == null) {
            Log.w(TAG, "SKILL.md $assetPath has no ```json steps block; skipping")
            return null
        }
        val bodyLines = body.lines().size
        if (bodyLines > MAX_BODY_LINES) {
            Log.w(TAG, "SKILL.md $assetPath body is $bodyLines lines (guidance: <$MAX_BODY_LINES)")
        }
        return ParsedSkillMd(meta, body, stepsJson)
    }

    /**
     * Turns a parsed SKILL.md into the exact [Skill] object [SkillRunner]
     * consumes — same type as a JSON skill, so the runner needs no change.
     * Returns null when the embedded JSON is not a valid skill.
     */
    fun skillFromMd(parsed: ParsedSkillMd): Skill? {
        val json = parsed.stepsJson ?: return null
        val base = parseSkill(json) ?: return null
        if (json.optString("id") != parsed.meta.id) {
            Log.w(
                TAG,
                "SKILL.md ${parsed.meta.assetPath}: frontmatter name '${parsed.meta.id}' " +
                    "differs from JSON id '${json.optString("id")}'; frontmatter wins",
            )
        }
        return base.copy(
            id = parsed.meta.id,
            name = parsed.meta.name,
            description = parsed.meta.description.ifBlank { base.description },
            triggers = parsed.meta.triggers.ifEmpty { base.triggers },
            version = parsed.meta.version,
            source = SkillSource.MARKDOWN,
            body = parsed.body,
        )
    }

    /**
     * Level 3 — loads one bundled resource of a folder-layout skill on demand,
     * e.g. `loadSkillResource(ctx, "shopee", "scripts/search.sh")` reads
     * `assets/skills/shopee/scripts/search.sh`. Returns null when the file
     * does not exist or the path tries to escape the skill folder ("..",
     * leading "/"). Flat `.md` skills have no resource folder, so this
     * always returns null for them.
     */
    fun loadSkillResource(context: Context, skillId: String, relativePath: String): String? {
        val clean = relativePath.trim().trimStart('/')
        if (clean.isEmpty() || ".." in clean.split('/') || clean.startsWith("/")) {
            Log.w(TAG, "Refusing resource path outside skill folder: '$relativePath'")
            return null
        }
        val path = "skills/$skillId/$clean"
        return try {
            context.assets.open(path).bufferedReader().readText()
        } catch (e: Exception) {
            null // absent resource: not an error, the caller decides what to do
        }
    }

    // ----- frontmatter (tiny YAML subset, no dependency) -----

    /** Returns the frontmatter key/value map, or null when there is no leading `---` block. */
    fun extractFrontmatter(text: String): Map<String, String>? {
        val lines = text.lines()
        if (lines.isEmpty() || lines[0].trim() != "---") return null
        val out = LinkedHashMap<String, String>()
        var i = 1
        var currentKey: String? = null
        while (i < lines.size) {
            val raw = lines[i]
            val line = raw.trim()
            if (line == "---") return out
            if (line.isEmpty() || line.startsWith("#")) {
                i++
                continue
            }
            // Block list item ("- foo") belonging to the previous key, but only
            // when that key had no inline value (a "key:" with nothing after it).
            if (line.startsWith("- ") && currentKey != null && out[currentKey].isNullOrBlank()) {
                val prev = out[currentKey].orEmpty()
                out[currentKey] = if (prev.isBlank()) unquote(line.removePrefix("- ").trim())
                else "$prev, ${unquote(line.removePrefix("- ").trim())}"
                i++
                continue
            }
            val colon = line.indexOf(':')
            if (colon <= 0) {
                // Not a key line and not a list item: ignore, keep scanning.
                currentKey = null
                i++
                continue
            }
            val key = line.substring(0, colon).trim()
            val value = unquote(line.substring(colon + 1).trim())
            out[key] = value
            currentKey = key
            i++
        }
        // Unterminated frontmatter (no closing ---): treat as absent.
        return null
    }

    /** Everything after the closing `---`, with the ```json steps block removed. */
    fun extractBody(text: String): String {
        val lines = text.lines()
        if (lines.isEmpty() || lines[0].trim() != "---") return text
        var start = -1
        for (i in 1 until lines.size) {
            if (lines[i].trim() == "---") {
                start = i + 1
                break
            }
        }
        if (start < 0) return text
        val body = lines.subList(start, lines.size).joinToString("\n")
        return removeJsonBlock(body).trim()
    }

    /** First fenced ```json (or ``` JSON) block, parsed as JSON; null when absent/invalid. */
    fun extractJsonBlock(text: String): JSONObject? {
        val fence = Regex("```\\s*json\\s*\\n(.*?)\\n?```", RegexOption.DOT_MATCHES_ALL)
        val match = fence.find(text) ?: return null
        return try {
            JSONObject(match.groupValues[1])
        } catch (e: Exception) {
            Log.w(TAG, "SKILL.md ```json block is not valid JSON: ${e.message}")
            null
        }
    }

    /** Removes the first ```json fenced block from Markdown text (keeps the rest intact). */
    private fun removeJsonBlock(markdown: String): String {
        val fence = Regex("```\\s*json\\s*\\n.*?\\n?```", RegexOption.DOT_MATCHES_ALL)
        return fence.replace(markdown, "").trim()
    }

    /** "a, b" | "[a, b]" | single value -> list; blank -> empty list. */
    private fun parseStringList(raw: String?): List<String> {
        val s = raw?.trim().orEmpty()
        if (s.isBlank()) return emptyList()
        val inner = s.removePrefix("[").removeSuffix("]").trim()
        return inner.split(",").map { unquote(it.trim()) }.filter { it.isNotEmpty() }
    }

    /** Strips one pair of matching single/double quotes; leaves anything else alone. */
    private fun unquote(s: String): String {
        val t = s.trim()
        return if (t.length >= 2 &&
            ((t.startsWith("\"") && t.endsWith("\"")) || (t.startsWith("'") && t.endsWith("'")))
        ) t.substring(1, t.length - 1) else t
    }
}

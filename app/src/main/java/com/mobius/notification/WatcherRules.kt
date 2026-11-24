package com.mobius.notification

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

private val Context.watcherRulesDataStore: DataStore<Preferences> by preferencesDataStore(name = "watcher_rules")

/**
 * WORKSTREAM D (v6) — WA watcher standing rules.
 *
 * A rule is a standing approval created EXPLICITLY by the user in the app: creating a rule
 * IS the approval to send that exact reply template when a WhatsApp message matches.
 * NO rule exists until Tuan creates one, so nothing auto-replies by itself. Ever.
 *
 * Storage: this class's OWN DataStore file ("watcher_rules"), one JSON string holding the
 * whole rule list. Deliberately NOT Room — the Room migration is owned by another v6
 * workstream, and a second store keeps this workstream independent of it. org.json is
 * used on purpose (built into Android) so no new dependency is added.
 */
data class WatcherRule(
    val id: String,
    val name: String,
    /** Substring of the incoming message text, case-insensitive. Blank = don't filter on text. */
    val matchText: String,
    /** Substring of the sender name, case-insensitive. Blank = any sender. */
    val sender: String,
    /** The EXACT text sent as the reply. Never altered by AI (see WatcherEngine). */
    val replyTemplate: String,
    /** True = send immediately on match, no confirmation. False = hold a draft for approval. */
    val autoSend: Boolean,
    val enabled: Boolean,
)

class WatcherRules(context: Context) {
    private val appContext = context.applicationContext
    private val store: DataStore<Preferences> get() = appContext.watcherRulesDataStore

    /** All rules, in creation order. Empty when the user has created none (the safe default). */
    fun observeRules(): Flow<List<WatcherRule>> =
        store.data.map { prefs -> parse(prefs[Keys.RULES_JSON].orEmpty()) }

    /**
     * Adds a rule. Returns the created rule, or null when the input is invalid:
     * replyTemplate must be non-blank, and at least one of matchText / sender must be
     * non-blank (a rule matching EVERYTHING is refused — it would reply to all of WA).
     * A blank name becomes "Aturan N". autoSend defaults to FALSE (safe default).
     */
    suspend fun addRule(
        name: String,
        matchText: String,
        sender: String,
        replyTemplate: String,
        autoSend: Boolean = false,
    ): WatcherRule? {
        val template = replyTemplate.trim()
        if (template.isEmpty()) return null
        val match = matchText.trim()
        val from = sender.trim()
        if (match.isEmpty() && from.isEmpty()) return null
        val existing = currentRules()
        val rule = WatcherRule(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifEmpty { "Aturan ${existing.size + 1}" },
            matchText = match,
            sender = from,
            replyTemplate = template,
            autoSend = autoSend,
            enabled = true,
        )
        save(existing + rule)
        return rule
    }

    suspend fun setEnabled(id: String, enabled: Boolean) {
        save(currentRules().map { if (it.id == id) it.copy(enabled = enabled) else it })
    }

    suspend fun setAutoSend(id: String, autoSend: Boolean) {
        save(currentRules().map { if (it.id == id) it.copy(autoSend = autoSend) else it })
    }

    suspend fun deleteRule(id: String) {
        save(currentRules().filter { it.id != id })
    }

    suspend fun currentRules(): List<WatcherRule> =
        // One-shot read of the same Flow observeRules() exposes; kept private to this class
        // so callers use the Flow for UI and this suspend read for the engine path.
        parse(readRaw())

    private suspend fun readRaw(): String {
        // Flow.first(): DataStore's data Flow never completes, so collect() would hang forever.
        return store.data.map { it[Keys.RULES_JSON].orEmpty() }.first()
    }

    private suspend fun save(rules: List<WatcherRule>) {
        val arr = JSONArray()
        for (r in rules) {
            arr.put(
                JSONObject()
                    .put("id", r.id)
                    .put("name", r.name)
                    .put("matchText", r.matchText)
                    .put("sender", r.sender)
                    .put("replyTemplate", r.replyTemplate)
                    .put("autoSend", r.autoSend)
                    .put("enabled", r.enabled)
            )
        }
        store.edit { it[Keys.RULES_JSON] = arr.toString() }
    }

    companion object {
        /**
         * A rule matches when it is enabled and EVERY non-blank criterion matches
         * (case-insensitive substring). First enabled match wins in the engine.
         */
        fun matches(rule: WatcherRule, sender: String, text: String): Boolean {
            if (!rule.enabled) return false
            if (rule.matchText.isNotBlank() &&
                !text.contains(rule.matchText, ignoreCase = true)
            ) return false
            if (rule.sender.isNotBlank() &&
                !sender.contains(rule.sender, ignoreCase = true)
            ) return false
            return true
        }

        fun parse(raw: String): List<WatcherRule> {
            if (raw.isBlank()) return emptyList()
            return try {
                val arr = JSONArray(raw)
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val template = o.optString("replyTemplate").trim()
                    if (template.isEmpty()) return@mapNotNull null
                    WatcherRule(
                        id = o.optString("id").ifEmpty { UUID.randomUUID().toString() },
                        name = o.optString("name").ifEmpty { "Aturan" },
                        matchText = o.optString("matchText"),
                        sender = o.optString("sender"),
                        replyTemplate = template,
                        autoSend = o.optBoolean("autoSend", false),
                        enabled = o.optBoolean("enabled", true),
                    )
                }
            } catch (e: Exception) {
                // Corrupt JSON must never crash the listener: treat as "no rules".
                emptyList()
            }
        }
    }

    private object Keys {
        val RULES_JSON = stringPreferencesKey("rules_json")
    }
}

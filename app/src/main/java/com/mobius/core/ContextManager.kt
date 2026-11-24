package com.mobius.core

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import com.mobius.accessibility.MobiusAccessibilityService
import com.mobius.agent.TaskContext
import com.mobius.notification.NotificationCache
import kotlinx.coroutines.flow.first
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Builds a per-task [TaskContext] snapshot: foreground app, recent notifications, time, recent tasks.
 * Nothing here is persisted; the snapshot lives only as long as the task (P6).
 * Each source degrades gracefully when its permission is missing (P8).
 *
 * v6 Memory V2: [relevantMemories] ranks stored memories by deterministic keyword overlap
 * with the task text (no LLM, no network) when the MEMORY_V2 flag is ON. When the flag is
 * OFF, [snapshot] behaves exactly like v5 — the last 3 task summaries, no relevance ranking.
 * [featureFlags] is optional: when null, flags are read from the app's [FeatureFlags]
 * DataStore via the given [Context]. Existing construction
 * `ContextManager(context, memory, permissions)` keeps working unchanged.
 */
class ContextManager(
    private val context: Context,
    private val memory: MemoryStore,
    private val permissions: PermissionManager,
    featureFlags: FeatureFlags? = null,
) {
    private val flags: FeatureFlags = featureFlags ?: FeatureFlags(context.applicationContext)

    suspend fun snapshot(taskId: String, userText: String): TaskContext {
        val status = permissions.status()

        val foreground = (if (status.usageStats) foregroundFromUsageStats() else null)
            ?: MobiusAccessibilityService.lastPackage // fallback when Usage access is denied

        val notifications = if (status.notificationListener) NotificationCache.recent() else emptyList()

        val time = SimpleDateFormat("EEE yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())

        return TaskContext(
            taskId = taskId,
            userText = userText,
            foregroundApp = foreground,
            recentNotifications = notifications,
            time = time,
            recentTasks = relevantMemories(userText, 3),
        )
    }

    /**
     * The MOST RELEVANT stored memories for [userText], as their content strings, top [limit].
     *
     * Scoring is simple and deterministic: tokenize the task text and each memory's content
     * (lowercase, split on non-alphanumeric, tokens shorter than 3 chars dropped); score =
     * number of DISTINCT task tokens that appear in the memory's tokens. Rank by score
     * descending, ties broken by recency (newest first). Memories with zero overlap are
     * ignored; if none overlap at all, fall back to the most recent task summaries.
     *
     * MEMORY_V2 OFF -> v5 behavior exactly: the last [limit] task summaries, no ranking.
     * A flag read that fails behaves as ON (the flag's default).
     */
    suspend fun relevantMemories(userText: String, limit: Int = 3): List<String> {
        if (limit <= 0) return emptyList()
        val memoryV2On = runCatching { flags.isEnabled(FeatureFlag.MEMORY_V2).first() }
            .getOrDefault(true)
        if (!memoryV2On) return memory.recentTaskSummaries(limit)

        val queryTokens = tokenize(userText)
        if (queryTokens.isEmpty()) return memory.recentTaskSummaries(limit)

        val scored = memory.allMemories()
            .map { entry -> entry to tokenize(entry.content).intersect(queryTokens).size }
            .filter { (_, score) -> score > 0 }
            .sortedWith(
                compareByDescending<Pair<MemoryEntry, Int>> { it.second }
                    .thenByDescending { it.first.createdAt }
            )
            .take(limit)
            .map { (entry, _) -> entry.content }
        return if (scored.isEmpty()) memory.recentTaskSummaries(limit) else scored
    }

    private fun tokenize(text: String): Set<String> =
        text.lowercase(Locale.ROOT)
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 3 }
            .toSet()

    @Suppress("DEPRECATION")
    private fun foregroundFromUsageStats(): String? = try {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val end = System.currentTimeMillis()
        val events = usm.queryEvents(end - 60_000, end)
        val event = UsageEvents.Event()
        var last: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND &&
                event.packageName != context.packageName
            ) {
                last = event.packageName
            }
        }
        last
    } catch (e: Exception) {
        null
    }
}

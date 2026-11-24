package com.mobius.core

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import com.mobius.accessibility.MobiusAccessibilityService
import com.mobius.agent.TaskContext
import com.mobius.notification.NotificationCache
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Builds a per-task [TaskContext] snapshot: foreground app, recent notifications, time, recent tasks.
 * Nothing here is persisted; the snapshot lives only as long as the task (P6).
 * Each source degrades gracefully when its permission is missing (P8).
 */
class ContextManager(
    private val context: Context,
    private val memory: MemoryStore,
    private val permissions: PermissionManager,
) {
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
            recentTasks = memory.recentTaskSummaries(3),
        )
    }

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

package com.mobius.notification

import android.app.Notification
import android.app.PendingIntent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.mobius.MobiusApp
import com.mobius.core.AppConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList

/** A notification as the core sees it. Held in memory only; never persisted, TTL-pruned (P6, P10). */
data class CapturedNotification(
    val key: String,
    val packageName: String,
    /** Notification title: contact or group name. */
    val sender: String,
    val text: String,
    val postTime: Long,
    /** Tapping this opens the exact chat. May be null. */
    val contentIntent: PendingIntent?,
)

/** Bounded in-memory buffer shared between the listener service and the ContextManager. */
object NotificationCache {
    private const val MAX_ITEMS = 30
    private val items = CopyOnWriteArrayList<CapturedNotification>()

    @Volatile
    var listenerConnected: Boolean = false

    fun add(n: CapturedNotification) {
        items.removeIf { it.key == n.key }
        items.add(n)
        while (items.size > MAX_ITEMS) items.removeAt(0)
    }

    /** Newest last. */
    fun recent(limit: Int = 10): List<CapturedNotification> {
        prune()
        return items.takeLast(limit)
    }

    fun prune(now: Long = System.currentTimeMillis()) {
        items.removeIf { now - it.postTime > AppConfig.NOTIFICATION_TTL_MS }
    }

    fun clear() = items.clear()
}

/**
 * Reads notifications (e.g. WhatsApp) once the user enables Notification access (P8).
 * Group-summary and ongoing notifications are skipped. Our own notifications are ignored.
 */
class NotificationListener : NotificationListenerService() {

    // v6 Workstream D: the WA watcher runs off this service's own scope. A failure inside
    // the watcher must never break notification capture, so the call is wrapped in runCatching.
    private val watcherScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val watcherEngine by lazy { (application as MobiusApp).watcherEngine() }

    override fun onListenerConnected() {
        NotificationCache.listenerConnected = true
        try {
            activeNotifications?.forEach { capture(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read active notifications", e)
        }
    }

    override fun onListenerDisconnected() {
        NotificationCache.listenerConnected = false
    }

    override fun onDestroy() {
        watcherScope.cancel()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = capture(sbn)

    // Intentionally keep cached entries when a notification is dismissed: the user may swipe it
    // away and then ask the assistant to reply. The TTL still bounds how long it is kept.

    private fun capture(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return

        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return

        val captured = CapturedNotification(
            key = sbn.key,
            packageName = sbn.packageName,
            sender = title,
            text = text,
            postTime = sbn.postTime,
            contentIntent = n.contentIntent,
        )
        NotificationCache.add(captured)

        // v6 Workstream D: hand the captured notification + the raw Notification (for its
        // RemoteInput reply action) to the WA watcher. The watcher itself checks the
        // WA_WATCHER flag, the WhatsApp packages and the standing rules; when the flag is
        // OFF or no rule matches it does nothing, so v5 behavior is unchanged.
        watcherScope.launch {
            runCatching { watcherEngine.onNotification(captured, n) }
                .onFailure { Log.w(TAG, "WA watcher failed for ${sbn.key}", it) }
        }
    }

    private companion object {
        const val TAG = "NotificationListener"
    }
}

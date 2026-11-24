package com.mobius.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay

/**
 * Performs actions in other apps on behalf of a subagent: click, type, scroll, navigate, open apps.
 * Every method returns a success flag instead of throwing, so callers can degrade gracefully (P8).
 */
class ActionExecutor(
    private val context: Context,
    private val service: AccessibilityService,
    private val reader: ScreenReader,
) {
    /** Clicks the node, or its nearest clickable ancestor (list rows are often clickable parents). */
    fun click(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 6) {
            if (current.isClickable && current.isEnabled) {
                return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            current = current.parent
            depth++
        }
        return false
    }

    /** Replaces the text of an editable node. */
    fun setText(node: AccessibilityNodeInfo, text: String): Boolean {
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun scrollForward(node: AccessibilityNodeInfo): Boolean =
        node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)

    /** Scrolls the first scrollable node on screen. */
    fun scrollForwardOnScreen(pkg: String): Boolean {
        val node = reader.findScrollable(pkg) ?: return false
        return scrollForward(node)
    }

    /** Taps the first node whose visible text matches (exact or contains). */
    fun tapByText(pkg: String, text: String, exact: Boolean = true): Boolean {
        val node = (if (exact) reader.findByText(pkg, text) else reader.findByTextContains(pkg, text))
            ?: return false
        return click(node)
    }

    /**
     * Swipe gesture via dispatchGesture. Coordinates are screen pixels.
     * Returns whether the system accepted the gesture for dispatch.
     */
    fun swipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long = 300): Boolean = try {
        val path = Path().apply { moveTo(startX, startY); lineTo(endX, endY) }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        service.dispatchGesture(gesture, null, null)
    } catch (e: Exception) {
        false
    }

    /**
     * Directional swipe across the middle of the screen. Uses screen proportions, never
     * app-specific coordinates, so the same call works in any app. "up" moves content up
     * (the gesture for scrolling a feed down).
     */
    fun swipeDirection(direction: String, durationMs: Long = 300): Boolean {
        val metrics = service.resources.displayMetrics
        val w = metrics.widthPixels.toFloat()
        val h = metrics.heightPixels.toFloat()
        return when (direction.lowercase()) {
            "up" -> swipe(w * 0.5f, h * 0.7f, w * 0.5f, h * 0.3f, durationMs)
            "down" -> swipe(w * 0.5f, h * 0.3f, w * 0.5f, h * 0.7f, durationMs)
            "left" -> swipe(w * 0.7f, h * 0.5f, w * 0.3f, h * 0.5f, durationMs)
            "right" -> swipe(w * 0.3f, h * 0.5f, w * 0.7f, h * 0.5f, durationMs)
            else -> false
        }
    }

    fun back(): Boolean = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)

    fun home(): Boolean = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)

    /** Opens an app through its launcher intent. */
    fun launchApp(packageName: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: return false
        return try {
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Fires a notification's content PendingIntent (opens the exact chat). Android 14+ requires the
     * sender to opt in to background activity start; the SYSTEM_ALERT_WINDOW permission the user
     * already granted for the bubble is what makes the system allow it.
     */
    fun firePendingIntent(pendingIntent: PendingIntent): Boolean = try {
        if (Build.VERSION.SDK_INT >= 34) {
            val options = ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
            )
            pendingIntent.send(context, 0, null, null, null, null, options.toBundle())
        } else {
            pendingIntent.send()
        }
        true
    } catch (e: Exception) {
        false
    }

    /** Polls [probe] until it returns non-null or [timeoutMs] passes (screens load asynchronously). */
    suspend fun <T : Any> waitFor(timeoutMs: Long, pollMs: Long = 250, probe: () -> T?): T? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            probe()?.let { return it }
            if (SystemClock.elapsedRealtime() >= deadline) return null
            delay(pollMs)
        }
    }
}

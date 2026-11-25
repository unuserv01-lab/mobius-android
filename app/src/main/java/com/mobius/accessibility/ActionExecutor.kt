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
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.mobius.core.AppRegistry
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

    /**
     * Presses the IME Enter/Search action on the first editable field of [pkg] (submits a
     * search or a form — the missing primitive that made the Shopee skill never submit).
     *
     * How: AccessibilityNodeInfo.ACTION_IME_ENTER (API 31+). There is NO key-event dispatch
     * API on AccessibilityService — `dispatchKeyEvent` does not exist; only `onKeyEvent`
     * (the inbound callback) does — so on Android < 12 there is no supported way to send
     * Enter and we return false honestly instead of faking it. The Throwable catch guards
     * the ACTION_IME_ENTER field itself, which does not exist on old OS versions
     * (NoSuchFieldError is an Error, not an Exception).
     */
    fun pressEnter(pkg: String): Boolean {
        if (Build.VERSION.SDK_INT < 31) return false
        val field = reader.findEditable(pkg) ?: return false
        return try {
            val actionId = AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
            field.performAction(actionId)
        } catch (e: Throwable) {
            false
        }
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

    /**
     * Opens an app through its launcher intent — and VERIFIES it actually opened.
     *
     * v7.8 (audit ChatGPT, Bug 2 P0): the old code returned true as soon as
     * startActivity() did not throw, which only proves the call was accepted, not that
     * the app is on screen. Now true means the target package was observed as the
     * foreground window (via [ScreenReader.currentPackage]) within 3 seconds; false
     * means it never appeared (app missing/disabled, start blocked, service dead).
     * Fails closed: when the service is not running, currentPackage() is null and we
     * return false instead of claiming success.
     *
     * v7.9-tambahan: the registry is checked FIRST (default-deny — see
     * [AppRegistry]). An app the user has not allowed is never started at all,
     * from ANY caller (skill, planning loop, reply agent): this is the last
     * enforcement point, so a path that forgets the check still cannot open the
     * app. The honest "not allowed yet" message itself is produced one layer up
     * ([SkillRunner] via [com.mobius.core.ActionGate.checkRegistry]) — this
     * function can only return a Boolean, so a registry denial here is a plain
     * false. A DataStore read failure also fails closed (false) and is logged.
     */
    suspend fun launchApp(packageName: String): Boolean {
        val allowed = runCatching { AppRegistry(context).isAllowed(packageName) }
            .onFailure { Log.w(TAG, "registry read failed for $packageName, denying launch", it) }
            .getOrDefault(false)
        if (!allowed) return false
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: return false
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            return false
        }
        // Verified, not just accepted: poll until the target owns the active window.
        return waitFor(3_000) { reader.currentPackage().takeIf { it == packageName } } != null
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

    /**
     * (v8.1) Deterministic expect helpers — kode yang menunggu, bukan model
     * (pola Mika BatchRunner expect / Nanobrowser wait). Dipakai verifikasi
     * pasca-aksi di [com.mobius.core.Verification] dan tool alarm.
     */
    /** True bila [packageName] menjadi aplikasi foreground dalam [timeoutMs]. */
    suspend fun waitForForeground(packageName: String, timeoutMs: Long): Boolean =
        waitFor(timeoutMs) { reader.currentPackage().takeIf { it == packageName } } != null

    /** True bila [text] HILANG dari layar dalam [timeoutMs] (expect "teks hilang"). */
    suspend fun waitForTextGone(pkg: String, text: String, timeoutMs: Long): Boolean =
        waitFor(timeoutMs) {
            if (reader.findByTextContains(pkg, text) == null) true else null
        } != null

    private companion object {
        private const val TAG = "ActionExecutor"
    }
}

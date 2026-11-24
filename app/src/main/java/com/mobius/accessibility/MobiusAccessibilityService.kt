package com.mobius.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * Cross-app operation: lets the assistant read the screen and tap/type in other apps, ONLY while
 * the user has enabled it in Settings > Accessibility (P8). It is passive: it never acts on its own.
 * Actions are driven by [ScreenReader] and [ActionExecutor] on behalf of a running subagent.
 *
 * NOTE: using AccessibilityService for non-accessibility automation is restricted on Google Play.
 * This project is a hackathon demo (public repo + video), not a Play Store release.
 */
class MobiusAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Only track which app is in front; used as a context fallback when Usage access is denied.
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString() ?: return
            if (pkg != packageName && !pkg.startsWith("com.android.systemui")) lastPackage = pkg
        }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    companion object {
        /** Non-null only while the user has the service enabled and it is connected. */
        @Volatile
        var instance: MobiusAccessibilityService? = null
            private set

        @Volatile
        var lastPackage: String? = null
            private set
    }
}

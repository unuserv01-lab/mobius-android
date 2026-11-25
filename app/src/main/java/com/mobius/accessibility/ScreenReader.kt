package com.mobius.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Reads other apps' screens through the AccessibilityService. All lookups are scoped to one package
 * so we never act on (or read) a different app than the one the task is about.
 */
class ScreenReader(private val service: AccessibilityService) {

    /** Root node of the window that belongs to [pkg], or null if that app is not on screen. */
    fun rootFor(pkg: String): AccessibilityNodeInfo? {
        try {
            for (window in service.windows) {
                val root = window.root ?: continue
                if (root.packageName?.toString() == pkg) return root
            }
        } catch (e: Exception) {
            // windows can throw while the screen is changing; fall through to the active window
        }
        return service.rootInActiveWindow?.takeIf { it.packageName?.toString() == pkg }
    }

    /** Which package owns the active window right now. */
    fun currentPackage(): String? = service.rootInActiveWindow?.packageName?.toString()

    /**
     * v7.7 bug 4b — true when a software keyboard (IME) window is showing.
     * Primary check is the window TYPE ([AccessibilityWindowInfo.TYPE_INPUT_METHOD]),
     * not a package name, so it works for any keyboard. The package-name
     * fallback covers the case where the window list itself is unavailable:
     * when the keyboard is up, the active window belongs to the IME.
     */
    fun isKeyboardVisible(): Boolean {
        try {
            if (service.windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }) {
                return true
            }
        } catch (e: Exception) {
            // windows can throw while the screen is changing; fall through
        }
        return currentPackage() in IME_PACKAGES
    }

    /** Plain-text dump of visible texts (debugging aid / possible prompt context). */
    fun readScreen(pkg: String, limit: Int = 200): List<String> {
        val out = mutableListOf<String>()
        val root = rootFor(pkg) ?: return out
        walk(root, intArrayOf(500)) { node ->
            (node.text ?: node.contentDescription)?.toString()?.takeIf { it.isNotBlank() }?.let { out += it }
            out.size >= limit
        }
        return out
    }

    fun find(pkg: String, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val root = rootFor(pkg) ?: return null
        var found: AccessibilityNodeInfo? = null
        walk(root, intArrayOf(800)) { node ->
            if (predicate(node)) {
                found = node
                true
            } else false
        }
        return found
    }

    fun findByIdSuffix(pkg: String, suffix: String): AccessibilityNodeInfo? =
        find(pkg) { it.viewIdResourceName?.endsWith(suffix) == true }

    fun findByText(pkg: String, text: String): AccessibilityNodeInfo? =
        find(pkg) { it.text?.toString()?.equals(text, ignoreCase = true) == true }

    /** First node whose visible text CONTAINS [text] (case-insensitive). */
    fun findByTextContains(pkg: String, text: String): AccessibilityNodeInfo? =
        find(pkg) { it.text?.toString()?.contains(text, ignoreCase = true) == true }

    /** All nodes matching [predicate], up to [limit]. */
    fun findAll(pkg: String, predicate: (AccessibilityNodeInfo) -> Boolean, limit: Int = 50): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        val root = rootFor(pkg) ?: return out
        walk(root, intArrayOf(1000)) { node ->
            if (predicate(node)) out += node
            out.size >= limit
        }
        return out
    }

    /** First node that can scroll (a list, a feed, a web view). */
    fun findScrollable(pkg: String): AccessibilityNodeInfo? = find(pkg) { it.isScrollable }

    /** First editable field on screen (any app's text input, not just a chat box). */
    fun findEditable(pkg: String): AccessibilityNodeInfo? = find(pkg) { it.isEditable }

    /** WhatsApp's message box: id ".../entry", else the first editable field. */
    fun findChatInput(pkg: String): AccessibilityNodeInfo? =
        findByIdSuffix(pkg, ":id/entry") ?: find(pkg) { it.isEditable }

    /**
     * WhatsApp's Send button. Deliberately strict: the node must be labelled Send (in a few
     * languages). WhatsApp reuses the same slot for the voice-message button, and we must never
     * press that one, so an unlabelled/unknown button is treated as "not found".
     */
    fun findSendButton(pkg: String): AccessibilityNodeInfo? = find(pkg) { node ->
        val label = node.contentDescription?.toString()?.trim()?.lowercase()
        node.isClickable && label != null && label in SEND_LABELS
    }

    /** Depth-first walk. [visit] returns true to stop. [budget] caps total nodes visited. */
    private fun walk(node: AccessibilityNodeInfo, budget: IntArray, visit: (AccessibilityNodeInfo) -> Boolean): Boolean {
        if (budget[0]-- <= 0) return true
        if (visit(node)) return true
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (walk(child, budget, visit)) return true
        }
        return false
    }

    private companion object {
        val SEND_LABELS = setOf("send", "kirim", "enviar", "envoyer", "senden", "invia")

        /**
         * Known keyboard packages (fallback for [isKeyboardVisible] only).
         * Gboard is what Tuan uses; the Xiaomi entries are best-effort —
         * the TYPE_INPUT_METHOD check above is what normally catches them.
         */
        val IME_PACKAGES = setOf(
            "com.google.android.inputmethod.latin", // Gboard
            "com.sohu.inputmethod.sogou.xiaomi", // Sogou input (some Xiaomi ROMs)
            "com.xiaomi.inputmethod", // Xiaomi keyboard (some ROMs)
        )
    }
}

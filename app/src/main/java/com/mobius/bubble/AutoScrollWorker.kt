package com.mobius.bubble

import android.content.Context
import android.util.Log
import com.mobius.accessibility.ActionExecutor
import com.mobius.accessibility.MobiusAccessibilityService
import com.mobius.accessibility.ScreenReader
import com.mobius.core.AccessibilityTools
import com.mobius.core.Tool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * v6 workstream G — deterministic auto-scroll loop. NO AI, NO tokens.
 *
 * Every [DEFAULT_INTERVAL_MS] (default 30s) it swipes up once (feed-style:
 * next content scrolls into view) via the Layer 1 [Tool.swipe] primitive.
 * The swipe is a FAST FLING ([FLING_DURATION_MS] = 120ms), NOT the default
 * 300ms drag: snap pagers (YouTube Shorts, Reels — ViewPager2) need fling
 * velocity to advance; a slow 300ms drag never crosses the velocity
 * threshold, snaps back to the current video, and dispatchGesture still
 * returns true — so the worker would count it as success forever.
 * Stops on [stop] (user taps Stop, or a new task starts) and stops itself
 * after [MAX_FAILURES] consecutive failed swipes (e.g. nothing scrollable
 * on screen) instead of swiping forever at a dead screen.
 *
 * FILE LOCATION — bubble/ (not core/): the loop is driven from the bubble
 * UI (start button in the panel, Stop button on the bubble chip) and it
 * builds its [Tool] the same way BubbleService / Orchestrator.buildTool()
 * do — from [MobiusAccessibilityService.instance]. Orchestrator is NOT
 * changed to expose its tool; this file constructs what it needs itself.
 * ActionGate is not involved: swipe/scroll are read-only screen moves, and
 * the loop never taps, types, or sends anything.
 *
 * FLAG — the UI only offers auto-scroll when AUTO_SCROLL is ON; the worker
 * itself just runs when started. Starting requires the accessibility
 * service to be live; otherwise [start] returns false and [status]
 * explains why (no crash, no silent no-op).
 */
class AutoScrollWorker(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    /**
     * Starts the loop. Returns false (and sets [status]) when already
     * running or when the accessibility service is off.
     */
    fun start(intervalMs: Long = DEFAULT_INTERVAL_MS): Boolean {
        if (job?.isActive == true) return false
        val tool = buildTool() ?: run {
            _status.value = "Auto-scroll tidak bisa jalan: aksesibilitas belum aktif."
            return false
        }
        _running.value = true
        _status.value = "Auto-scroll berjalan."
        job = scope.launch {
            var failures = 0
            try {
                while (isActive) {
                    delay(intervalMs)
                    val result = tool.swipe("up", FLING_DURATION_MS)
                    if (result.success) {
                        failures = 0
                    } else {
                        failures++
                        Log.w(TAG, "auto-scroll swipe failed ($failures/$MAX_FAILURES): ${result.message}")
                        if (failures >= MAX_FAILURES) {
                            _status.value = "Auto-scroll berhenti: layar tidak bisa di-scroll."
                            break
                        }
                    }
                }
            } finally {
                _running.value = false
                job = null
            }
        }
        return true
    }

    /** Stops the loop. Safe to call when not running. */
    fun stop() {
        job?.cancel()
        job = null
        _running.value = false
        _status.value = null
    }

    fun shutdown() {
        stop()
        scope.cancel()
    }

    /** Same construction as Orchestrator.buildTool(): live service, or null. */
    private fun buildTool(): Tool? {
        val service = MobiusAccessibilityService.instance ?: return null
        val reader = ScreenReader(service)
        val executor = ActionExecutor(context, service, reader)
        return AccessibilityTools(reader, executor, service)
    }

    companion object {
        /** Default swipe interval: 30 seconds. */
        const val DEFAULT_INTERVAL_MS = 30_000L
        /**
         * Auto-scroll stroke duration: 120ms fling. Snap pagers (YouTube
         * Shorts, Reels) only advance on a fast fling; the default 300ms
         * drag snaps back. Skill `swipe` stays at 300ms (precise drags for
         * lists/forms) — this constant is auto-scroll only.
         */
        const val FLING_DURATION_MS = 120L
        private const val MAX_FAILURES = 3
        private const val TAG = "AutoScrollWorker"
    }
}

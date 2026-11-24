package com.mobius.bubble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.mobius.MobiusApp
import com.mobius.R
import com.mobius.ai.VoiceOutput
import com.mobius.core.AppConfig
import com.mobius.core.ChatMessage
import com.mobius.core.FeatureFlag
import com.mobius.core.TaskHooks
import com.mobius.core.TaskOutcome
import com.mobius.ui.MainActivity
import com.mobius.ui.MobiusTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * The single foreground service. It keeps the LOCAL core alive and hosts the bubble overlay.
 * The bubble is only the entry point (P7): taps and typed text go straight to the Orchestrator.
 *
 * Bubble technology: a SYSTEM_ALERT_WINDOW overlay (Messenger-style chat head). The Android
 * "Bubbles API" is NOT implemented (see README, "Deviations").
 */
class BubbleService : Service() {

    private lateinit var windowManager: WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val owner = OverlayOwner()

    private var bubbleRoot: FrameLayout? = null
    private var panelRoot: FrameLayout? = null
    private val bubbleParams by lazy { newBubbleParams() }

    private val panelState = mutableStateOf<PanelState>(PanelState.Hidden)
    private val chipText = mutableStateOf<String?>(null)
    private val working = mutableStateOf(false)
    private var chipJob: Job? = null

    // v6 (F): voice output — speaks the assistant's short result (no-op when VOICE_OUTPUT is OFF).
    private val voiceOutput by lazy { VoiceOutput(this).also { it.start() } }
    // v6 (G): deterministic auto-scroll loop (no AI, no tokens).
    private val autoScrollWorker by lazy { AutoScrollWorker(this) }
    private val autoScrolling = mutableStateOf(false)
    // v6 (M-UI): same conversation as Home, fed by observeChatMessages().
    private val chatMessages = mutableStateOf<List<ChatMessage>>(emptyList())
    // v6 flags (default true; OFF hides the matching UI, v5 behavior).
    private val autoScrollOn = mutableStateOf(true)
    private val chatHistoryOn = mutableStateOf(true)
    private val localFilesOn = mutableStateOf(true)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        owner.create()
        startAsForeground()
        running = true
        // v6: keep flag + chat-history state fresh for the overlay compositions.
        serviceScope.launch {
            val app = application as MobiusApp
            launch { app.featureFlags.isEnabled(FeatureFlag.AUTO_SCROLL).collect { autoScrollOn.value = it } }
            launch { app.featureFlags.isEnabled(FeatureFlag.CHAT_HISTORY).collect { chatHistoryOn.value = it } }
            launch { app.featureFlags.isEnabled(FeatureFlag.LOCAL_FILES).collect { localFilesOn.value = it } }
            launch { app.memoryStore.observeChatMessages().collect { chatMessages.value = it } }
            launch { autoScrollWorker.running.collect { autoScrolling.value = it } }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            serviceScope.launch { (application as MobiusApp).permissions.setBubbleEnabled(false) }
            stopSelf()
            return START_NOT_STICKY
        }
        showBubble()
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        autoScrollWorker.shutdown()
        voiceOutput.shutdown()
        removeView(bubbleRoot); bubbleRoot = null
        removeView(panelRoot); panelRoot = null
        owner.destroy()
        serviceScope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ foreground notification

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "${AppConfig.APP_NAME} assistant", NotificationManager.IMPORTANCE_MIN)
        )
        val stop = PendingIntent.getService(
            this, 0, Intent(this, BubbleService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("${AppConfig.APP_NAME} is ready")
            .setContentText("Tap the floating bubble to ask for something.")
            .setOngoing(true)
            .addAction(0, "Stop", stop)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    // ------------------------------------------------------------------ bubble window

    private fun showBubble() {
        if (bubbleRoot != null) return
        if (!Settings.canDrawOverlays(this)) {
            // P8: no overlay permission -> no bubble. The app still works from the launcher.
            Log.w(TAG, "Draw-over-apps permission missing; bubble hidden")
            return
        }
        val root = composeHost {
            MobiusTheme {
                BubbleView(
                    status = chipText.value,
                    working = working.value,
                    // v6 (G): visible Stop button while the loop runs.
                    autoScrolling = autoScrolling.value,
                    onStopAutoScroll = { autoScrollWorker.stop() },
                )
            }
        }
        root.setOnTouchListener(
            DragTouchListener(bubbleParams, windowManager, ViewConfiguration.get(this).scaledTouchSlop) {
                openPanel(PanelState.Input)
            }
        )
        windowManager.addView(root, bubbleParams)
        bubbleRoot = root
    }

    private fun newBubbleParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = dp(12)
        y = dp(220)
    }

    // ------------------------------------------------------------------ panel window

    private fun openPanel(state: PanelState) {
        panelState.value = state
        if (panelRoot != null) return
        // Rebuilt on every open: a detached ComposeView disposes its composition.
        val root = composeHost {
            MobiusTheme {
                BubblePanel(
                    state = panelState.value,
                    onSubmit = ::onSubmit,
                    onClose = ::onPanelClose,
                    onVoice = { consumer ->
                        serviceScope.launch {
                            consumer(VoiceInput.listenOnce(this@BubbleService))
                        }
                    },
                    // v6 (M-UI): same conversation as Home.
                    messages = chatMessages.value,
                    chatHistoryOn = chatHistoryOn.value,
                    // v6 (N-UI): attach via MainActivity's picker.
                    localFilesOn = localFilesOn.value,
                    onAttach = ::onAttachRequest,
                    // v6 (G): auto-scroll toggle.
                    autoScrollOn = autoScrollOn.value,
                    autoScrolling = autoScrolling.value,
                    onToggleAutoScroll = ::onToggleAutoScroll,
                )
            }
        }
        val params = WindowManager.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.92f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, // focusable so the keyboard works
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(96)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
        }
        windowManager.addView(root, params)
        panelRoot = root
    }

    private fun closePanel() {
        removeView(panelRoot)
        panelRoot = null
        panelState.value = PanelState.Hidden
    }

    private fun onPanelClose() {
        when (val state = panelState.value) {
            is PanelState.Confirm -> state.decide(false)
            is PanelState.Plan -> state.decide(false)
            is PanelState.StepConfirm -> state.decide(false)
            else -> closePanel()
        }
    }

    // ------------------------------------------------------------------ running a task

    private fun onSubmit(text: String) {
        // Close the focusable panel FIRST: while it is on screen it is the active window, and the
        // accessibility service must see the target app, not our own overlay.
        closePanel()
        chipJob?.cancel()
        working.value = true
        chipText.value = "Working..."
        // v6 (F + G): a new task starts — stop any speech and the scroll loop.
        voiceOutput.stop()
        autoScrollWorker.stop()
        val app = application as MobiusApp
        serviceScope.launch {
            try {
                // v6 (M-UI): record the user turn; no-op when CHAT_HISTORY is OFF.
                app.memoryStore.addChatMessage("user", text)
                val outcome = app.orchestrator.submit(text, hooks)
                showOutcome(outcome)
            } catch (e: Exception) {
                // submit() already converts AI failures into outcomes; this is only a
                // last-resort guard so a stray exception never kills the bubble service.
                showOutcome(TaskOutcome(false, "Something went wrong: ${e.message ?: e.javaClass.simpleName}"))
            } finally {
                working.value = false
            }
        }
    }

    /**
     * v6 (N-UI): a Service cannot receive ACTION_OPEN_DOCUMENT results, so
     * the attach button opens MainActivity, which opens the system picker
     * (see MainActivity.EXTRA_OPEN_FILE_PICKER). The picked file still goes
     * through extract -> scan -> alert -> submit in Home.
     */
    private fun onAttachRequest() {
        closePanel()
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(MainActivity.EXTRA_OPEN_FILE_PICKER, true)
        startActivity(intent)
    }

    /**
     * v6 (G): toggles the deterministic swipe loop. Starting closes the
     * panel so the underlying app is visible; Stop lives on the bubble.
     */
    private fun onToggleAutoScroll() {
        if (autoScrollWorker.running.value) {
            autoScrollWorker.stop()
            return
        }
        if (autoScrollWorker.start()) {
            closePanel()
            chipText.value = "Auto-scroll berjalan — ketuk Stop di bubble untuk berhenti."
        } else {
            closePanel()
            chipText.value = autoScrollWorker.status.value ?: "Auto-scroll tidak bisa dimulai."
        }
    }

    private val hooks = object : TaskHooks {
        override fun status(message: String) {
            chipText.value = message
        }

        override suspend fun confirmSend(sender: String, draft: String): Boolean =
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine<Boolean> { cont ->
                    openPanel(PanelState.Confirm(sender, draft) { approved ->
                        closePanel()
                        if (cont.isActive) cont.resume(approved)
                    })
                    cont.invokeOnCancellation { mainHandler.post { closePanel() } }
                }
            }

        /** (v5) Plan card in the bubble panel; same decide/close pattern as confirmSend. */
        override suspend fun confirmPlan(skillName: String, steps: List<String>): Boolean =
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine<Boolean> { cont ->
                    openPanel(PanelState.Plan(skillName, steps) { approved ->
                        closePanel()
                        if (cont.isActive) cont.resume(approved)
                    })
                    cont.invokeOnCancellation { mainHandler.post { closePanel() } }
                }
            }

        /** (v5) Irreversible-step approval in the bubble panel. */
        override suspend fun confirmStep(description: String): Boolean =
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine<Boolean> { cont ->
                    openPanel(PanelState.StepConfirm(description) { approved ->
                        closePanel()
                        if (cont.isActive) cont.resume(approved)
                    })
                    cont.invokeOnCancellation { mainHandler.post { closePanel() } }
                }
            }
    }

    private fun showOutcome(outcome: TaskOutcome) {
        // v6 (M-UI + F): record the assistant turn, then speak the short
        // result. Recording is a no-op when CHAT_HISTORY is OFF; speaking is
        // a no-op when VOICE_OUTPUT is OFF. Only outcome.message is spoken —
        // never another person's message body.
        serviceScope.launch {
            runCatching {
                (application as MobiusApp).memoryStore.addChatMessage("assistant", outcome.message)
            }
            voiceOutput.speak(outcome.message)
        }
        val short = outcome.message.length <= 80 && '\n' !in outcome.message
        if (short) {
            chipText.value = outcome.message
            chipJob = serviceScope.launch {
                delay(5_000)
                chipText.value = null
            }
        } else {
            chipText.value = null
            openPanel(PanelState.Result(outcome.message))
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Hosts Compose in a plain window (a Service has no ViewTree owners of its own). */
    private fun composeHost(content: @androidx.compose.runtime.Composable () -> Unit): FrameLayout {
        val frame = FrameLayout(this)
        frame.setViewTreeLifecycleOwner(owner)
        frame.setViewTreeViewModelStoreOwner(owner)
        frame.setViewTreeSavedStateRegistryOwner(owner)
        val compose = ComposeView(this)
        compose.setContent(content)
        frame.addView(compose)
        return frame
    }

    private fun removeView(view: android.view.View?) {
        if (view == null) return
        try {
            windowManager.removeView(view)
        } catch (e: IllegalArgumentException) {
            // already removed
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /** Minimal Lifecycle/SavedState/ViewModelStore owner so ComposeView can run inside a Service. */
    private class OverlayOwner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
        private val registry = LifecycleRegistry(this)
        private val savedStateController = SavedStateRegistryController.create(this)
        override val viewModelStore = ViewModelStore()
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

        fun create() {
            savedStateController.performAttach()
            savedStateController.performRestore(null)
            registry.currentState = Lifecycle.State.RESUMED
        }

        fun destroy() {
            registry.currentState = Lifecycle.State.DESTROYED
            viewModelStore.clear()
        }
    }

    companion object {
        private const val TAG = "BubbleService"
        private const val CHANNEL_ID = "mobius_core"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.mobius.action.STOP"

        @Volatile
        var running: Boolean = false
            private set

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, BubbleService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BubbleService::class.java))
        }
    }
}

/** Restarts the core + bubble after reboot (only if the user had it on and granted overlay). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        val app = context.applicationContext as MobiusApp
        CoroutineScope(Dispatchers.Default).launch {
            try {
                if (app.permissions.bubbleEnabled.first() && Settings.canDrawOverlays(context)) {
                    BubbleService.start(context)
                }
            } catch (e: Exception) {
                Log.w("BootReceiver", "Could not restart the core after boot", e)
            } finally {
                pending.finish()
            }
        }
    }
}

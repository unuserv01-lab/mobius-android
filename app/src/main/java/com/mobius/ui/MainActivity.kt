package com.mobius.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mobius.MobiusApp
import com.mobius.ai.VoiceOutput
import com.mobius.bubble.BubbleService
import com.mobius.core.AppConfig
import com.mobius.core.FeatureFlag
import com.mobius.core.FilePickerHelper
import com.mobius.core.TaskHooks
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

private val MobiusColors: ColorScheme = darkColorScheme(
    primary = Color(0xFF7C9CFF),
    onPrimary = Color(0xFF0B1030),
    background = Color(0xFF0F1115),
    surface = Color(0xFF151922),
    surfaceVariant = Color(0xFF1E2430),
    onBackground = Color(0xFFE6E8EE),
    onSurface = Color(0xFFE6E8EE),
    onSurfaceVariant = Color(0xFFB8BECC),
    tertiary = Color(0xFFFFB86B),
)

/** Shared by the app screens and the overlay windows. */
@Composable
fun MobiusTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = MobiusColors, content = content)
}

class MainActivity : ComponentActivity() {
    /**
     * v6 UI wiring — the bubble cannot receive ACTION_OPEN_DOCUMENT results
     * (a Service has no ActivityResultLauncher), so its attach button starts
     * this activity with [EXTRA_OPEN_FILE_PICKER] and Home opens the system
     * picker. Incremented in onCreate/onNewIntent; HomeScreen collects it.
     */
    val pickFileRequests = MutableStateFlow(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.getBooleanExtra(EXTRA_OPEN_FILE_PICKER, false) == true) {
            pickFileRequests.value++
        }
        setContent { MobiusTheme { MobiusRoot(pickFileRequests) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_FILE_PICKER, false)) {
            pickFileRequests.value++
        }
    }

    companion object {
        const val EXTRA_OPEN_FILE_PICKER = "com.mobius.extra.OPEN_FILE_PICKER"
    }
}

@Composable
private fun MobiusRoot(pickFileRequests: StateFlow<Int>) {
    val app = LocalContext.current.applicationContext as MobiusApp
    val onboardingDone by app.permissions.onboardingDone.collectAsState(initial = null)
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        when (onboardingDone) {
            null -> Unit // still reading preferences
            false -> OnboardingScreen(app) { }
            true -> MainTabs(app, pickFileRequests)
        }
    }
}

@Composable
private fun MainTabs(app: MobiusApp, pickFileRequests: StateFlow<Int>) {
    var tab by remember { mutableStateOf(0) }
    val labels = listOf("Home", "History", "Settings")
    Scaffold(
        bottomBar = {
            NavigationBar {
                labels.forEachIndexed { index, label ->
                    NavigationBarItem(
                        selected = tab == index,
                        onClick = { tab = index },
                        icon = { Text(listOf("⌂", "⧖", "⚙")[index]) },
                        label = { Text(label) },
                    )
                }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (tab) {
                0 -> HomeScreen(app, pickFileRequests)
                1 -> HistoryScreen(app)
                else -> SettingsScreen(app, onRerunSetup = { })
            }
        }
    }
}

/**
 * Launcher-side entry point. It uses the SAME Orchestrator as the bubble, so MOBIUS works even when
 * the overlay permission is denied ("app works from launcher only").
 */
@Composable
private fun HomeScreen(app: MobiusApp, pickFileRequests: StateFlow<Int>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val perms by rememberPermissionStatus(app.permissions)
    val bubbleOn by app.permissions.bubbleEnabled.collectAsState(initial = false)
    // v6 flags (Compose: collectAsState, default true).
    val chatHistoryOn by app.featureFlags.isEnabled(FeatureFlag.CHAT_HISTORY).collectAsState(initial = true)
    val localFilesOn by app.featureFlags.isEnabled(FeatureFlag.LOCAL_FILES).collectAsState(initial = true)
    val chatMessages by app.memoryStore.observeChatMessages().collectAsState(initial = emptyList())

    var input by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }
    var pendingConfirm by remember { mutableStateOf<Triple<String, String, CompletableDeferred<Boolean>>?>(null) }
    // v5: plan card ("Langkah yang akan dijalankan") + irreversible-step approval.
    var pendingPlan by remember { mutableStateOf<Triple<String, List<String>, CompletableDeferred<Boolean>>?>(null) }
    var pendingStep by remember { mutableStateOf<Pair<String, CompletableDeferred<Boolean>>?>(null) }
    // v6 (F): voice output — speaks the assistant's short result after it is shown.
    val voiceOutput = remember { VoiceOutput(context).also { it.start() } }
    DisposableEffect(Unit) { onDispose { voiceOutput.shutdown() } }
    // v6 (N-UI + O-ALERT): staged file + sensitive-data confirmation + error.
    var attachedFile by remember { mutableStateOf<AttachedFileData?>(null) }
    var pendingAttachConfirm by remember { mutableStateOf<Pair<AttachedFileData, List<com.mobius.core.SensitiveFinding>>?>(null) }
    var attachError by remember { mutableStateOf<String?>(null) }
    val pickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            when (val r = processPickedFile(context, uri, app)) {
                is AttachResult.Ready -> {
                    attachedFile = r.file
                    attachError = null
                }
                is AttachResult.NeedsConfirm -> {
                    pendingAttachConfirm = r.file to r.findings
                    attachError = null
                }
                is AttachResult.Failed -> attachError = r.message
            }
        }
    }
    // v6: the bubble's attach button asks Home to open the picker (a Service
    // cannot receive ACTION_OPEN_DOCUMENT results). Counts only upward, so a
    // request is never re-fired by recomposition.
    LaunchedEffect(pickFileRequests) {
        pickFileRequests.collect { count ->
            if (count > 0) pickerLauncher.launch(FilePickerHelper.SUPPORTED_MIME_TYPES)
        }
    }

    val hooks = remember {
        object : TaskHooks {
            override fun status(message: String) { status = message }
            override suspend fun confirmSend(sender: String, draft: String): Boolean {
                val decision = CompletableDeferred<Boolean>()
                pendingConfirm = Triple(sender, draft, decision)
                return decision.await().also { pendingConfirm = null }
            }
            override suspend fun confirmPlan(skillName: String, steps: List<String>): Boolean {
                val decision = CompletableDeferred<Boolean>()
                pendingPlan = Triple(skillName, steps, decision)
                return decision.await().also { pendingPlan = null }
            }
            override suspend fun confirmStep(description: String): Boolean {
                val decision = CompletableDeferred<Boolean>()
                pendingStep = Pair(description, decision)
                return decision.await().also { pendingStep = null }
            }
        }
    }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(AppConfig.APP_NAME, style = MaterialTheme.typography.headlineMedium)
        Text(AppConfig.TAGLINE, style = MaterialTheme.typography.bodyMedium)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Floating bubble", style = MaterialTheme.typography.titleMedium)
                if (!perms.overlay) {
                    Text(
                        "Draw-over-apps is not granted, so the bubble is hidden. You can still run tasks below.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (bubbleOn && BubbleService.running) {
                    OutlinedButton(onClick = {
                        BubbleService.stop(context)
                        scope.launch { app.permissions.setBubbleEnabled(false) }
                    }) { Text("Stop bubble") }
                } else {
                    Button(onClick = {
                        scope.launch { app.permissions.setBubbleEnabled(true) }
                        BubbleService.start(context)
                    }) { Text("Start bubble") }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Ask ${AppConfig.APP_NAME}", style = MaterialTheme.typography.titleMedium)
                // v6 (M-UI): same conversation as the bubble; hidden when CHAT_HISTORY is OFF.
                if (chatHistoryOn) {
                    ChatHistoryView(messages = chatMessages)
                }
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("e.g. Reply to the last WhatsApp message") },
                )
                // v6 (N-UI): attach a file/photo via the system picker (no broad
                // storage permission). Hidden when LOCAL_FILES is OFF (v5: no attach).
                if (localFilesOn) {
                    OutlinedButton(
                        onClick = { pickerLauncher.launch(FilePickerHelper.SUPPORTED_MIME_TYPES) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Lampirkan file / foto") }
                }
                attachedFile?.let { file ->
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(
                            "File: ${file.fileName}" +
                                if (file.isImage) " (foto — isi belum terverifikasi)"
                                else if (file.truncated) " (dipotong)"
                                else "",
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = { attachedFile = null }) { Text("Hapus") }
                    }
                }
                attachError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Button(
                    enabled = input.isNotBlank() && !running,
                    onClick = {
                        running = true
                        result = null
                        voiceOutput.stop() // v6 (F): a new task starts — stop any speech.
                        val text = input
                        // v6 (N-UI): the staged file is consumed now — its text is
                        // prepended as a delimited DATA block (never instructions)
                        // and the task still goes through orchestrator.submit(),
                        // so ActionGate and every other gate still apply.
                        val file = attachedFile
                        attachedFile = null
                        val submitText = if (file != null) {
                            buildFileDataBlock(file) + "\n\n" + text
                        } else {
                            text
                        }
                        val userRecord = if (file != null) {
                            "$text\n[File terlampir: ${file.fileName}]"
                        } else {
                            text
                        }
                        scope.launch {
                            try {
                                // v6 (M-UI): record both turns; no-op when CHAT_HISTORY is OFF.
                                app.memoryStore.addChatMessage("user", userRecord)
                                val outcome = app.orchestrator.submit(submitText, hooks)
                                result = outcome.message
                                app.memoryStore.addChatMessage("assistant", outcome.message)
                                // v6 (F): speak the assistant's short result (no-op when
                                // VOICE_OUTPUT is OFF; never speaks other people's messages).
                                voiceOutput.speak(outcome.message)
                            } catch (e: Exception) {
                                // submit() already converts AI failures into outcomes, but never
                                // let a stray exception kill the app from this button.
                                result = "Something went wrong: ${e.message ?: e.javaClass.simpleName}"
                            } finally {
                                status = null
                                running = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (running) "Working..." else "Run") }
                status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                result?.let { Text(it) }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Capabilities", style = MaterialTheme.typography.titleMedium)
                CapabilityRow("Bubble overlay", perms.overlay)
                CapabilityRow("Cross-app actions (Accessibility)", perms.accessibility)
                CapabilityRow("Notifications (WhatsApp reading)", perms.notificationListener)
                CapabilityRow("Foreground-app context (Usage access)", perms.usageStats)
                CapabilityRow("Microphone (voice input)", perms.microphone)
            }
        }
    }

    pendingConfirm?.let { (sender, draft, decision) ->
        AlertDialog(
            onDismissRequest = { decision.complete(false) },
            title = { Text("Send this reply to $sender?") },
            text = { Text(draft) },
            confirmButton = { TextButton(onClick = { decision.complete(true) }) { Text("Send") } },
            dismissButton = { TextButton(onClick = { decision.complete(false) }) { Text("Don't send") } },
        )
    }

    pendingPlan?.let { (skillName, steps, decision) ->
        AlertDialog(
            onDismissRequest = { decision.complete(false) },
            title = { Text("Langkah yang akan dijalankan") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()).heightIn(max = 320.dp)) {
                    Text(skillName, style = MaterialTheme.typography.titleSmall)
                    steps.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = { TextButton(onClick = { decision.complete(true) }) { Text("Jalankan") } },
            dismissButton = { TextButton(onClick = { decision.complete(false) }) { Text("Batal") } },
        )
    }

    pendingStep?.let { (description, decision) ->
        AlertDialog(
            onDismissRequest = { decision.complete(false) },
            title = { Text("Langkah tak terbalikkan") },
            text = { Text("$description\n\nLanjutkan langkah ini?") },
            confirmButton = { TextButton(onClick = { decision.complete(true) }) { Text("Lanjutkan") } },
            dismissButton = { TextButton(onClick = { decision.complete(false) }) { Text("Batalkan") } },
        )
    }

    // v6 (O-ALERT): the file is NOT passed on until the user confirms.
    // Only the finding TYPES are named — never a matched value.
    pendingAttachConfirm?.let { (file, findings) ->
        AlertDialog(
            onDismissRequest = { pendingAttachConfirm = null },
            title = { Text("File mengandung data sensitif") },
            text = {
                Text(
                    "Ditemukan: ${findings.joinToString(", ") { it.type }}.\n\n" +
                        "Nilai yang ditemukan tidak ditampilkan. Lanjutkan hanya jika Anda " +
                        "yakin file ini aman dipakai.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    attachedFile = file
                    pendingAttachConfirm = null
                }) { Text("Tetap lanjut") }
            },
            dismissButton = {
                TextButton(onClick = { pendingAttachConfirm = null }) { Text("Batal") }
            },
        )
    }
}

@Composable
private fun CapabilityRow(label: String, on: Boolean) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(
            if (on) "on" else "off",
            color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

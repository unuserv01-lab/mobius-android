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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
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

/**
 * v7 — MOBIUS palette (from Tuan's logo: glowing purple-blue loop on dark).
 * Background #04000F, surface #17122E, surface variant #221A3E,
 * primary #7C4DFF, secondary #4D7CFE, accent/glow #B388FF,
 * text #F2EDFF / muted #B9B3D6.
 * Semantic (dark-tuned): success #66BB6A, error #EF5350, warning #FFCA28.
 */
private val MobiusColors: ColorScheme = darkColorScheme(
    primary = Color(0xFF7C4DFF),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF3D2A7A),
    onPrimaryContainer = Color(0xFFE6DEFF),
    secondary = Color(0xFF4D7CFE),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFF1E2F6E),
    onSecondaryContainer = Color(0xFFDCE6FF),
    tertiary = Color(0xFFB388FF),
    onTertiary = Color(0xFF1B1030),
    tertiaryContainer = Color(0xFF2E1F5C),
    onTertiaryContainer = Color(0xFFE9DDFF),
    background = Color(0xFF04000F),
    onBackground = Color(0xFFF2EDFF),
    surface = Color(0xFF17122E),
    onSurface = Color(0xFFF2EDFF),
    surfaceVariant = Color(0xFF221A3E),
    onSurfaceVariant = Color(0xFFB9B3D6),
    surfaceContainerLowest = Color(0xFF04000F),
    surfaceContainerLow = Color(0xFF100A24),
    surfaceContainer = Color(0xFF17122E),
    surfaceContainerHigh = Color(0xFF221A3E),
    surfaceContainerHighest = Color(0xFF2C2350),
    error = Color(0xFFEF5350),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFF5A1F1D),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF4A3F73),
    outlineVariant = Color(0xFF322A55),
)

/** Semantic colors outside Material's ColorScheme (dark-tuned, meanings kept). */
val MobiusSuccess: Color = Color(0xFF66BB6A)
val MobiusWarning: Color = Color(0xFFFFCA28)

/**
 * Logo gradient: deep blue → blue → purple. Used for the app title, the
 * bubble chip background, and primary buttons.
 */
val MobiusGradientBrush: Brush = Brush.linearGradient(
    listOf(Color(0xFF1A73E8), Color(0xFF4D7CFE), Color(0xFF7C4DFF)),
)

/** Logo glow color (also the unread-badge background). */
val MobiusGlow: Color = Color(0xFFB388FF)

/** Shared by the app screens and the overlay windows. */
@Composable
fun MobiusTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = MobiusColors, content = content)
}

/**
 * v7 — primary action button: logo gradient background, white label.
 * Disabled state uses a muted violet gradient (never plain grey).
 */
@Composable
fun GradientButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    androidx.compose.material3.Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.background(
            brush = if (enabled) MobiusGradientBrush
                else Brush.linearGradient(listOf(Color(0xFF3A2E63), Color(0xFF2A2350))),
            shape = RoundedCornerShape(16.dp),
        ),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent,
            contentColor = Color.White,
            disabledContentColor = Color(0xFF8F87B3),
        ),
        content = content,
    )
}

// ------------------------------------------------------------------ bottom-nav icons
//
// Drawn from the canonical Material path data (ImageVector.Builder) so the
// nav needs NO new dependency: material-icons-core is only a *runtime*
// dependency of material3, so `Icons.Filled.*` does not compile here.
// Path strings were validated as well-formed SVG path data before use.

private const val HOME_PATH_DATA = "M10 20v-6h4v6h5v-8h3L12 3 2 12h3v8z"
private const val HISTORY_PATH_DATA =
    "M13,3c-4.97,0-9,4.03-9,9H1l3.89,3.89L9,12H6c0-3.87,3.13-7,7-7s7,3.13,7,7-3.13,7-7,7c-1.93,0-3.68-0.79-4.94-2.06l-1.42,1.42C8.27,19.99,10.51,21,13,21c4.97,0,9-4.03,9-9S17.97,3,13,3zM12,8v5l4.28,2.54l0.72-1.21l-3.5-2.08V8H12z"
private const val SETTINGS_PATH_DATA =
    "M19.14,12.94c0.04-0.3,0.06-0.61,0.06-0.94c0-0.32-0.02-0.63-0.06-0.94l2.03-1.58c0.18-0.14,0.23-0.41,0.12-0.61 l-1.92-3.32c-0.12-0.2-0.37-0.29-0.59-0.22l-2.39,0.96c-0.5-0.38-1.03-0.7-1.62-0.94L14.4,2.81c-0.04-0.24-0.24-0.41-0.48-0.41 h-3.84c-0.24,0-0.44,0.17-0.48,0.41L9.25,5.35C8.66,5.59,8.12,5.92,7.63,6.29L5.24,5.33c-0.22-0.07-0.47,0.02-0.59,0.22L2.74,8.87 C2.62,9.08,2.66,9.34,2.86,9.48l2.03,1.58C4.84,11.36,4.8,11.69,4.8,12s0.02,0.64,0.06,0.94l-2.03,1.58 c-0.18,0.14-0.23,0.41-0.12,0.61l1.92,3.32c0.12,0.2,0.37,0.29,0.59,0.22l2.39-0.96c0.5,0.38,1.03,0.7,1.62,0.94l0.36,2.54 c0.05,0.24,0.24,0.41,0.48,0.41h3.84c0.24,0,0.44-0.17,0.48-0.41l0.36-2.54c0.59-0.24,1.13-0.56,1.62-0.94l2.39,0.96 c0.22,0.07,0.47-0.02,0.59-0.22l1.92-3.32c0.12-0.2,0.08-0.47-0.12-0.61L19.14,12.94z M12,15.6c-1.98,0-3.6-1.62-3.6-3.6 s1.62-3.6,3.6-3.6s3.6,1.62,3.6,3.6S13.98,15.6,12,15.6z"

private fun mobiusIcon(name: String, pathData: String): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        addPath(
            pathData = PathParser().parsePathString(pathData).toNodes(),
            fill = SolidColor(Color.Black),
        )
    }.build()

val MobiusHomeIcon: ImageVector by lazy { mobiusIcon("Home", HOME_PATH_DATA) }
val MobiusHistoryIcon: ImageVector by lazy { mobiusIcon("History", HISTORY_PATH_DATA) }
val MobiusSettingsIcon: ImageVector by lazy { mobiusIcon("Settings", SETTINGS_PATH_DATA) }

// ------------------------------------------------------------------ activity

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
    // v7 — language: DataStore setting, default Bahasa Indonesia; both roots
    // (this Activity and the bubble Service) read the same store, so a change
    // recomposes everywhere immediately — no restart, no reinstall.
    val languageManager = remember { LanguageManager(app.applicationContext) }
    val lang by languageManager.language.collectAsState(initial = AppLanguage.ID)
    CompositionLocalProvider(LocalStrings provides stringsFor(lang)) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            when (onboardingDone) {
                null -> Unit // still reading preferences
                false -> OnboardingScreen(app) { }
                true -> MainTabs(app, pickFileRequests)
            }
        }
    }
}

@Composable
private fun MainTabs(app: MobiusApp, pickFileRequests: StateFlow<Int>) {
    val strings = LocalStrings.current
    var tab by remember { mutableStateOf(0) }
    val labels = listOf(strings.tabHome, strings.tabHistory, strings.tabSettings)
    val icons = listOf(MobiusHomeIcon, MobiusHistoryIcon, MobiusSettingsIcon)
    Scaffold(
        bottomBar = {
            NavigationBar {
                labels.forEachIndexed { index, label ->
                    NavigationBarItem(
                        selected = tab == index,
                        onClick = { tab = index },
                        icon = {
                            Icon(
                                imageVector = icons[index],
                                contentDescription = label,
                            )
                        },
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
    val strings = LocalStrings.current
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
    var resultOk by remember { mutableStateOf<Boolean?>(null) }
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
            when (val r = processPickedFile(context, uri, app, strings)) {
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
        Text(
            AppConfig.APP_NAME,
            style = MaterialTheme.typography.headlineMedium.copy(brush = MobiusGradientBrush),
        )
        Text(
            AppConfig.TAGLINE,
            style = MaterialTheme.typography.bodyMedium.copy(brush = MobiusGradientBrush),
        )

        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(strings.homeFloatingBubble, style = MaterialTheme.typography.titleMedium)
                if (!perms.overlay) {
                    Text(strings.homeBubbleNoOverlay, style = MaterialTheme.typography.bodySmall)
                }
                if (bubbleOn && BubbleService.running) {
                    OutlinedButton(onClick = {
                        BubbleService.stop(context)
                        scope.launch { app.permissions.setBubbleEnabled(false) }
                    }) { Text(strings.homeStopBubble) }
                } else {
                    GradientButton(onClick = {
                        scope.launch { app.permissions.setBubbleEnabled(true) }
                        BubbleService.start(context)
                    }) { Text(strings.homeStartBubble) }
                }
            }
        }

        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(strings.homeAsk, style = MaterialTheme.typography.titleMedium)
                // v6 (M-UI): same conversation as the bubble; hidden when CHAT_HISTORY is OFF.
                if (chatHistoryOn) {
                    ChatHistoryView(messages = chatMessages)
                }
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(strings.homePlaceholder) },
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                        cursorColor = MaterialTheme.colorScheme.primary,
                    ),
                )
                // v6 (N-UI): attach a file/photo via the system picker (no broad
                // storage permission). Hidden when LOCAL_FILES is OFF (v5: no attach).
                if (localFilesOn) {
                    OutlinedButton(
                        onClick = { pickerLauncher.launch(FilePickerHelper.SUPPORTED_MIME_TYPES) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(strings.homeAttach) }
                }
                attachedFile?.let { file ->
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(
                            strings.homeFilePrefix + file.fileName +
                                if (file.isImage) strings.homePhotoUnverified
                                else if (file.truncated) strings.homeTruncated
                                else "",
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(
                            onClick = { attachedFile = null },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        ) { Text(strings.homeRemove) }
                    }
                }
                attachError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                GradientButton(
                    enabled = input.isNotBlank() && !running,
                    onClick = {
                        running = true
                        result = null
                        resultOk = null
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
                                resultOk = outcome.success
                                app.memoryStore.addChatMessage("assistant", outcome.message)
                                // v6 (F): speak the assistant's short result (no-op when
                                // VOICE_OUTPUT is OFF; never speaks other people's messages).
                                voiceOutput.speak(outcome.message)
                            } catch (e: Exception) {
                                // submit() already converts AI failures into outcomes, but never
                                // let a stray exception kill the app from this button.
                                result = strings.homeErrorPrefix + (e.message ?: e.javaClass.simpleName)
                                resultOk = false
                            } finally {
                                status = null
                                running = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (running) strings.homeWorking else strings.homeRun) }
                status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                result?.let {
                    val resultColor = when (resultOk) {
                        true -> MobiusSuccess
                        false -> MaterialTheme.colorScheme.error
                        null -> MaterialTheme.colorScheme.onSurface
                    }
                    Text(it, color = resultColor)
                }
            }
        }

        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(strings.homeCapabilities, style = MaterialTheme.typography.titleMedium)
                CapabilityRow(strings.capBubble, perms.overlay)
                CapabilityRow(strings.capAccessibility, perms.accessibility)
                CapabilityRow(strings.capNotifications, perms.notificationListener)
                CapabilityRow(strings.capUsage, perms.usageStats)
                CapabilityRow(strings.capMicrophone, perms.microphone)
            }
        }
    }

    pendingConfirm?.let { (sender, draft, decision) ->
        AlertDialog(
            onDismissRequest = { decision.complete(false) },
            title = { Text(strings.dlgSendTitle.format(sender)) },
            text = { Text(draft) },
            confirmButton = {
                GradientButton(onClick = { decision.complete(true) }) { Text(strings.dlgSend) }
            },
            dismissButton = {
                TextButton(
                    onClick = { decision.complete(false) },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) { Text(strings.dlgDontSend) }
            },
        )
    }

    pendingPlan?.let { (skillName, steps, decision) ->
        AlertDialog(
            onDismissRequest = { decision.complete(false) },
            title = { Text(strings.dlgPlanTitle) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()).heightIn(max = 320.dp)) {
                    Text(skillName, style = MaterialTheme.typography.titleSmall)
                    steps.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = {
                GradientButton(onClick = { decision.complete(true) }) { Text(strings.dlgPlanRun) }
            },
            dismissButton = {
                TextButton(
                    onClick = { decision.complete(false) },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) { Text(strings.dlgPlanCancel) }
            },
        )
    }

    pendingStep?.let { (description, decision) ->
        AlertDialog(
            onDismissRequest = { decision.complete(false) },
            title = { Text(strings.dlgStepTitle) },
            text = { Text(description + "\n\n" + strings.dlgStepQuestion) },
            confirmButton = {
                GradientButton(onClick = { decision.complete(true) }) { Text(strings.dlgStepContinue) }
            },
            dismissButton = {
                TextButton(
                    onClick = { decision.complete(false) },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) { Text(strings.dlgStepCancel) }
            },
        )
    }

    // v6 (O-ALERT): the file is NOT passed on until the user confirms.
    // Only the finding TYPES are named — never a matched value.
    pendingAttachConfirm?.let { (file, findings) ->
        AlertDialog(
            onDismissRequest = { pendingAttachConfirm = null },
            title = { Text(strings.dlgAttachTitle) },
            text = {
                Text(strings.dlgAttachBody.format(findings.joinToString(", ") { it.type }))
            },
            confirmButton = {
                GradientButton(onClick = {
                    attachedFile = file
                    pendingAttachConfirm = null
                }) { Text(strings.dlgAttachKeep) }
            },
            dismissButton = {
                TextButton(
                    onClick = { pendingAttachConfirm = null },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) { Text(strings.dlgAttachCancel) }
            },
        )
    }
}

@Composable
private fun CapabilityRow(label: String, on: Boolean) {
    val strings = LocalStrings.current
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(
            if (on) strings.capOn else strings.capOff,
            color = if (on) MobiusSuccess else MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

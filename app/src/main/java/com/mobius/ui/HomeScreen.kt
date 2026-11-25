package com.mobius.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.mobius.MobiusApp
import com.mobius.R
import com.mobius.ai.AIRouter
import com.mobius.ai.GoogleAuth
import com.mobius.ai.VoiceOutput
import com.mobius.bubble.VoiceInput
import com.mobius.core.AppConfig
import com.mobius.core.ApprovalDecision
import com.mobius.core.ApprovalRequest
import com.mobius.core.RiskTier
import com.mobius.core.FeatureFlag
import com.mobius.core.FilePickerHelper
import com.mobius.core.SensitiveFinding
import com.mobius.core.TaskHooks
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ------------------------------------------------------------------ v8 icons
//
// Same no-new-dependency pattern as MainActivity.kt: canonical Material path
// data, drawn with ImageVector.Builder. The chat/tag/doc/search/sparkle/mic
// paths below are copied verbatim from MainActivity.kt (v7.6); the menu,
// chevron-down, edit and add paths are the canonical Material ones.

private const val MENU_PATH_DATA = "M3 18h18v-2H3v2zm0-5h18v-2H3v2zm0-7v2h18V6H3z"
private const val CHEVRON_DOWN_PATH_DATA =
    "M7.41 8.59L12 13.17l4.59-4.58L18 10l-6 6-6-6 1.41-1.41z"
private const val EDIT_PATH_DATA =
    "M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25zM20.71 7.04c.39-.39.39-1.02 0-1.41l-2.34-2.34c-.39-.39-1.02-.39-1.41 0l-1.83 1.83 3.75 3.75 1.83-1.83z"
private const val ADD_PATH_DATA = "M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z"
private const val CHAT_PATH_DATA =
    "M20 2H4c-1.1 0-1.99.9-1.99 2L2 22l4-4h14c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2z"
private const val TAG_PATH_DATA =
    "M21.41 11.58l-9-9C12.05 2.22 11.55 2 11 2H4c-1.1 0-2 .9-2 2v7c0 .55.22 1.05.59 1.42l9 9c.36.36.86.58 1.41.58.55 0 1.05-.22 1.41-.59l7-7c.37-.36.59-.86.59-1.41 0-.55-.23-1.06-.59-1.42zM5.5 7C4.67 7 4 6.33 4 5.5S4.67 4 5.5 4 7 4.67 7 5.5 6.33 7 5.5 7z"
private const val DOC_PATH_DATA =
    "M14 2H6c-1.1 0-1.99.9-1.99 2L4 20c0 1.1.89 2 1.99 2H18c1.1 0 2-.9 2-2V8l-6-6zm2 16H8v-2h8v2zm0-4H8v-2h8v2zm-3-5V3.5L18.5 9H13z"
private const val SEARCH_PATH_DATA =
    "M15.5 14h-.79l-.28-.27C15.41 12.59 16 11.11 16 9.5 16 5.91 13.09 3 9.5 3S3 5.91 3 9.5 5.91 16 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z"
private const val SPARKLE_PATH_DATA =
    "M12 2 L14.5 9.5 L22 12 L14.5 14.5 L12 22 L9.5 14.5 L2 12 L9.5 9.5 Z"
private const val MIC_PATH_DATA_1 =
    "M12 14c1.66 0 3-1.34 3-3V5c0-1.66-1.34-3-3-3S9 3.34 9 5v6c0 1.66 1.34 3 3 3z"
private const val MIC_PATH_DATA_2 =
    "M17 11c0 2.76-2.24 5-5 5s-5-2.24-5-5H5c0 3.53 2.61 6.43 6 6.92V21h2v-3.08c3.39-.49 6-3.39 6-6.92h-2z"

/** Copied from MainActivity.kt — that file's mobiusIcon is private to its file. */
private fun mobiusIcon(name: String, vararg pathData: String): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        pathData.forEach { path ->
            addPath(
                pathData = PathParser().parsePathString(path).toNodes(),
                fill = SolidColor(Color.Black),
            )
        }
    }.build()

private val HomeMenuIcon: ImageVector by lazy { mobiusIcon("Menu", MENU_PATH_DATA) }
private val HomeChevronDownIcon: ImageVector by lazy { mobiusIcon("ChevronDown", CHEVRON_DOWN_PATH_DATA) }
private val HomeEditIcon: ImageVector by lazy { mobiusIcon("Edit", EDIT_PATH_DATA) }
private val HomeAddIcon: ImageVector by lazy { mobiusIcon("Add", ADD_PATH_DATA) }
private val HomeChatIcon: ImageVector by lazy { mobiusIcon("Chat", CHAT_PATH_DATA) }
private val HomeTagIcon: ImageVector by lazy { mobiusIcon("Tag", TAG_PATH_DATA) }
private val HomeDocIcon: ImageVector by lazy { mobiusIcon("Doc", DOC_PATH_DATA) }
private val HomeSearchIcon: ImageVector by lazy { mobiusIcon("Search", SEARCH_PATH_DATA) }
private val HomeSparkleIcon: ImageVector by lazy { mobiusIcon("Sparkle", SPARKLE_PATH_DATA) }
private val HomeMicIcon: ImageVector by lazy { mobiusIcon("Mic", MIC_PATH_DATA_1, MIC_PATH_DATA_2) }

/**
 * v8.1 — Home greeting pool (no name): one random sentence each time the
 * empty Home is shown, rotating every 5 minutes while Home stays open.
 * Pure composable-side timing (LaunchedEffect + delay); no WorkManager,
 * no background work when the app is closed. [except] avoids repeating
 * the sentence that is currently shown.
 */
private fun randomGreeting(strings: MobiusStrings, except: String?): String {
    val pool = strings.homeModel.homeGreetings
    if (pool.isEmpty()) return ""
    val candidates = if (pool.size > 1 && except != null) pool.filter { it != except } else pool
    return candidates.random()
}

/**
 * v8 — Gemini-style Home. ALL logic moved verbatim from the old HomeScreen
 * in MainActivity.kt (submitTask, runWaGroupChip, voice input, file attach,
 * the four dialogs, LaunchedEffect pickFileRequests); only the layout
 * changed. The bubble start/stop card and the Capabilities card are NOT
 * rendered here any more (bubble -> drawer, capabilities -> Settings);
 * their logic lives where it was and is untouched.
 *
 * v8 bug fix: [submitTask] clears the text field as its very first line,
 * so the field empties the instant a task is submitted instead of waiting
 * for the agent's reply. The text itself was already captured in [rawText],
 * and the chat-history recording (addChatMessage user + assistant) is
 * untouched — messages still land in history exactly as before.
 *
 * The model picker is HONEST, not a fake selector: it only shows which
 * engine the routing log says was used last and the LIVE status of each
 * link in the chain (Nano / Gemini cloud / OpenRouter). Nothing in it can
 * be picked — routing is still decided by AIRouter per task. It only
 * offers a way into Settings.
 *
 * NOTE (coordinator): MainActivity.MainTabs still calls the OLD 2-arg
 * private HomeScreen in MainActivity.kt. That file was not edited (per
 * task). The call site must be switched to this 4-arg HomeScreen
 * (onOpenDrawer / onOpenSettings) before the v8 build, and the old
 * HomeScreen in MainActivity.kt can then be deleted.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    app: MobiusApp,
    pickFileRequests: StateFlow<Int>,
    onOpenDrawer: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val strings = LocalStrings.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // v6 flags (Compose: collectAsState, default true).
    val chatHistoryOn by app.featureFlags.isEnabled(FeatureFlag.CHAT_HISTORY).collectAsState(initial = true)
    val localFilesOn by app.featureFlags.isEnabled(FeatureFlag.LOCAL_FILES).collectAsState(initial = true)
    val chatMessages by app.memoryStore.observeChatMessages().collectAsState(initial = emptyList())
    // v8 — routing log, newest first (ORDER BY createdAt DESC): the first
    // entry is the last AI call, and its model names the last engine used.
    val routing by app.memoryStore.observeRoutingLog().collectAsState(initial = emptyList())
    val lastEngineLabel = when (routing.firstOrNull()?.model) {
        AIRouter.MODEL_ON_DEVICE -> "Nano"
        AIRouter.MODEL_CLOUD -> "Gemini"
        AIRouter.MODEL_OPENROUTER -> "OpenRouter"
        else -> strings.homeModel.homeModelAuto
    }

    var input by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<String?>(null) }
    var resultOk by remember { mutableStateOf<Boolean?>(null) }
    var running by remember { mutableStateOf(false) }
    // v7.6 — voice input on Home (same SpeechRecognizer as the bubble).
    var listening by remember { mutableStateOf(false) }
    var voiceHint by remember { mutableStateOf<String?>(null) }
    var pendingConfirm by remember { mutableStateOf<Triple<String, String, CompletableDeferred<Boolean>>?>(null) }
    // v5: plan card ("Langkah yang akan dijalankan") + irreversible-step approval.
    var pendingPlan by remember { mutableStateOf<Triple<String, List<String>, CompletableDeferred<Boolean>>?>(null) }
    var pendingStep by remember { mutableStateOf<Pair<ApprovalRequest, CompletableDeferred<ApprovalDecision>>?>(null) }
    // v6 (F): voice output — speaks the assistant's short result after it is shown.
    val voiceOutput = remember { VoiceOutput(context).also { it.start() } }
    DisposableEffect(Unit) { onDispose { voiceOutput.shutdown() } }
    // v6 (N-UI + O-ALERT): staged file + sensitive-data confirmation + error.
    var attachedFile by remember { mutableStateOf<AttachedFileData?>(null) }
    var pendingAttachConfirm by remember { mutableStateOf<Pair<AttachedFileData, List<SensitiveFinding>>?>(null) }
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

    // v8 — Google sign-in state. Login happens in the browser: the user
    // leaves the app and comes back, so the auth state is re-read on every
    // ON_RESUME (same pattern as GoogleAccountSection in
    // GoogleAccountSettings.kt).
    var signedIn by remember { mutableStateOf(GoogleAuth.isSignedIn()) }
    var email by remember { mutableStateOf(GoogleAuth.accountEmail()) }
    fun refreshAuth() {
        signedIn = GoogleAuth.isSignedIn()
        email = GoogleAuth.accountEmail()
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshAuth()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // v8.1 — greeting: a random nameless sentence from the pool. Re-picked
    // whenever the empty Home is (re)shown, on language change, and rotated
    // every 5 minutes while Home stays open.
    var greeting by remember { mutableStateOf(randomGreeting(strings, null)) }
    LaunchedEffect(chatMessages.isEmpty()) {
        if (chatMessages.isEmpty()) greeting = randomGreeting(strings, greeting)
    }
    LaunchedEffect(strings) { greeting = randomGreeting(strings, null) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(5 * 60 * 1000L)
            greeting = randomGreeting(strings, greeting)
        }
    }

    // v8 — model sheet state. Nano's status is a suspend call, loaded when
    // the sheet opens; the cloud gate and the OpenRouter key/model are live
    // Flows from PermissionManager.
    var showModelSheet by remember { mutableStateOf(false) }
    var showQuickActions by remember { mutableStateOf(false) }
    val modelSheetState = rememberModalBottomSheetState()
    val quickActionsSheetState = rememberModalBottomSheetState()
    var nanoStatusLabel by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(showModelSheet) {
        if (showModelSheet) {
            nanoStatusLabel = runCatching { app.onDeviceAI.status().label }.getOrNull()
        }
    }
    val cloudAiAllowed by app.permissions.cloudAiAllowed.collectAsState(initial = false)
    val openRouterModel by app.permissions.openRouterModel.collectAsState(initial = AppConfig.DEFAULT_OPENROUTER_MODEL)
    val openRouterKey by app.permissions.openRouterApiKey.collectAsState(initial = "")

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
            // (v8.1) Caller Boolean lama: delegasikan ke dialog tier (grant = pilihan
            // pengguna di dialog; caller Boolean hanya memakai approved).
            override suspend fun confirmStep(description: String): Boolean =
                confirmStepWithGrant(
                    ApprovalRequest(description, RiskTier.SENSITIVE, null, "local"),
                ).approved

            /**
             * (v8.1) Approval bertier + grant: dialog ApprovalDialog menampilkan
             * badge tier (MODERATE/SENSITIVE) + pilihan ingat (sekali / chat ini /
             * selamanya). SENSITIVE tidak pernah menawarkan FOREVER (ditegakkan
             * di [Orchestrator.requestApproval]).
             */
            override suspend fun confirmStepWithGrant(request: ApprovalRequest): ApprovalDecision {
                val decision = CompletableDeferred<ApprovalDecision>()
                pendingStep = Pair(request, decision)
                return decision.await().also { pendingStep = null }
            }
        }
    }

    // v7.6 — ONE submit path for the Run button, the keyboard Send action,
    // the mic result, and the "Ringkas WA grup" chip. The optional callback
    // reports the outcome so a chip can add its own follow-up. The staged
    // file (v6 N-UI) is consumed here exactly as the old Run button did it.
    //
    // v8 BUG FIX — right after the blank/running guard: the text field is
    // cleared the instant the task is submitted, instead of waiting for
    // the agent's reply. The text was already captured in rawText, so
    // nothing is lost, and the chat-history recording below is untouched.
    fun submitTask(rawText: String, onDone: (suspend (Boolean) -> Unit)? = null) {
        if (rawText.isBlank() || running) return
        input = ""
        running = true
        result = null
        resultOk = null
        voiceOutput.stop() // v6 (F): a new task starts — stop any speech.
        // v6 (N-UI): the staged file is consumed now — its text is
        // prepended as a delimited DATA block (never instructions) and the
        // task still goes through orchestrator.submit(), so ActionGate and
        // every other gate still apply.
        val file = attachedFile
        attachedFile = null
        val submitText = if (file != null) {
            buildFileDataBlock(file) + "\n\n" + rawText
        } else {
            rawText
        }
        val userRecord = if (file != null) {
            "$rawText\n[File terlampir: ${file.fileName}]"
        } else {
            rawText
        }
        scope.launch {
            var ok = false
            try {
                // v6 (M-UI): record both turns; no-op when CHAT_HISTORY is OFF.
                app.memoryStore.addChatMessage("user", userRecord)
                val outcome = app.orchestrator.submit(submitText, hooks)
                result = outcome.message
                resultOk = outcome.success
                ok = outcome.success
                app.memoryStore.addChatMessage("assistant", outcome.message)
                // v6 (F): speak the assistant's short result (no-op when
                // VOICE_OUTPUT is OFF; never speaks other people's messages).
                voiceOutput.speak(outcome.message)
            } catch (e: Exception) {
                // submit() already converts AI failures into outcomes, but never
                // let a stray exception kill the app from this button.
                result = strings.capFix.homeErrorPrefix + (e.message ?: e.javaClass.simpleName)
                resultOk = false
            } finally {
                status = null
                running = false
                onDone?.invoke(ok)
            }
        }
    }

    // v7.6 — "Ringkas WA grup": there is NO direct WA-history reader in the
    // app, so the chip does the nearest real thing — it opens WhatsApp
    // through the planning loop (KnownApps → com.whatsapp), then records
    // the next step: open the group chat and ask the bubble to summarize
    // the visible page (SummarizePageAgent reads the screen). Nothing is
    // faked: if WhatsApp is missing, the launch outcome says so and no
    // guidance message is recorded.
    fun runWaGroupChip() {
        submitTask(strings.chips.chipWaOpen) { ok ->
            if (ok) {
                app.memoryStore.addChatMessage("assistant", strings.chips.chipWaNext)
            }
        }
    }

    // v8 — the four quick actions, shared by the greeting chips and the "+"
    // quick-actions sheet (same labels, same behaviour).
    val quickActions: List<Triple<ImageVector, String, () -> Unit>> = listOf(
        Triple(HomeChatIcon, strings.chips.chipWaGroup) { runWaGroupChip() },
        Triple(HomeTagIcon, strings.chips.chipPrice) { input = strings.chips.chipPricePrefill },
        Triple(HomeDocIcon, strings.chips.chipReport) { input = strings.chips.chipReportPrefill },
        Triple(HomeSearchIcon, strings.chips.chipSearch) { input = strings.chips.chipSearchPrefill },
    )

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // v8 — top bar: hamburger | model picker | new chat | avatar.
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onOpenDrawer) {
                Icon(
                    imageVector = HomeMenuIcon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            // Model picker: centred, shows the last engine actually used
            // (routing log) — or "Auto" when nothing has run yet.
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                TextButton(onClick = { showModelSheet = true }) {
                    Text(
                        lastEngineLabel,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        imageVector = HomeChevronDownIcon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            IconButton(onClick = {
                scope.launch { app.memoryStore.clearChat() }
                status = null
                result = null
                resultOk = null
            }) {
                Icon(
                    imageVector = HomeEditIcon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            // Avatar: signed in = first letter of the name/email on the
            // gradient; not signed in = logo, tap starts Google Sign-In.
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MobiusGradientBrush)
                    .clickable {
                        if (signedIn) {
                            onOpenSettings()
                        } else {
                            runCatching { context.startActivity(GoogleAuth.buildAuthIntent()) }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (signedIn) {
                    val initial = email.orEmpty().firstOrNull()
                        ?.uppercaseChar()?.toString().orEmpty()
                    Text(
                        initial,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color.White,
                    )
                } else {
                    Image(
                        painter = painterResource(id = R.drawable.ic_launcher_foreground),
                        contentDescription = "MOBIUS",
                        modifier = Modifier.size(36.dp).clip(CircleShape),
                    )
                }
            }
        }

        if (chatMessages.isEmpty()) {
            // v8 — greeting, ONLY when there is no chat yet: logo + greeting
            // + the four quick chips in one horizontal scrollable row.
            Column(
                Modifier.weight(1f).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(MobiusGradientBrush),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_launcher_foreground),
                        contentDescription = "MOBIUS",
                        modifier = Modifier.size(64.dp).clip(CircleShape),
                    )
                }
                Spacer(Modifier.size(16.dp))
                Text(
                    greeting,
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.size(24.dp))
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    quickActions.forEach { (icon, label, action) ->
                        QuickChip(icon, label, enabled = !running) { action() }
                    }
                }
            }
        } else {
            // v8 — active chat: history fills the space above the pill;
            // logo, greeting and chips are hidden. Top bar stays the same.
            if (chatHistoryOn) {
                Text(
                    strings.homeModel.chatHistoryTitle,
                    style = MaterialTheme.typography.titleMedium,
                )
                ChatHistoryView(
                    messages = chatMessages,
                    modifier = Modifier.weight(1f),
                    maxHeight = Dp.Unspecified,
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
        }

        // v8 — input pill, pinned at the bottom (never scrolls away):
        // "+" quick actions | text field | gradient mic button. Below it
        // the attached file, the attach button, the Run button and the
        // status/result text — behaviour unchanged from v7.9.
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(MobiusGradientBrush)
                        .clickable { showQuickActions = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = HomeAddIcon,
                        contentDescription = strings.homeModel.homeQuickActions,
                        tint = Color.White,
                        modifier = Modifier.size(24.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(strings.home.homePlaceholder) },
                    leadingIcon = {
                        Icon(
                            imageVector = HomeSparkleIcon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                        )
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = { if (input.isNotBlank()) submitTask(input) },
                    ),
                    shape = RoundedCornerShape(28.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                        cursorColor = MaterialTheme.colorScheme.primary,
                    ),
                )
                Box(
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(MobiusGradientBrush)
                        .clickable(enabled = !listening && !running) {
                            listening = true
                            voiceHint = null
                            scope.launch {
                                val heard = VoiceInput.listenOnce(context)
                                listening = false
                                if (heard.isNullOrBlank()) {
                                    voiceHint = strings.panel.inputVoiceHint
                                } else {
                                    input = heard
                                    submitTask(heard)
                                }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = HomeMicIcon,
                        contentDescription = strings.panel.inputMic,
                        tint = Color.White,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
            if (listening) {
                Text(strings.panel.inputListening, style = MaterialTheme.typography.bodySmall)
            }
            voiceHint?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            // v6 (N-UI): attach a file/photo via the system picker (no broad
            // storage permission). Hidden when LOCAL_FILES is OFF (v5: no attach).
            if (localFilesOn) {
                OutlinedButton(
                    onClick = { pickerLauncher.launch(FilePickerHelper.SUPPORTED_MIME_TYPES) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(strings.home.homeAttach) }
            }
            attachedFile?.let { file ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        strings.home.homeFilePrefix + file.fileName +
                            if (file.isImage) strings.home.homePhotoUnverified
                            else if (file.truncated) strings.home.homeTruncated
                            else "",
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(
                        onClick = { attachedFile = null },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    ) { Text(strings.home.homeRemove) }
                }
            }
            attachError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            GradientButton(
                enabled = input.isNotBlank() && !running,
                onClick = { submitTask(input) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (running) strings.home.homeWorking else strings.home.homeRun) }
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

    // v8 — model picker sheet: INFO + navigation only. Nothing here can be
    // picked: routing stays with AIRouter. The three rows show the LIVE
    // status of each link in the chain.
    if (showModelSheet) {
        ModalBottomSheet(
            onDismissRequest = { showModelSheet = false },
            sheetState = modelSheetState,
        ) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    strings.homeModel.homeModelTitle,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                )
                Text(
                    "${strings.homeModel.homeModelLastUsed}: $lastEngineLabel",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ModelChainRow(
                    label = "Gemini Nano",
                    status = nanoStatusLabel ?: strings.settings.setNanoNotChecked,
                )
                ModelChainRow(
                    label = "Gemini cloud (${AppConfig.CLOUD_MODEL})",
                    status = if (cloudAiAllowed) strings.home.capOn else strings.home.capOff,
                )
                ModelChainRow(
                    label = "OpenRouter ($openRouterModel)",
                    status = if (openRouterKey.isBlank()) strings.home.capOff else strings.home.capOn,
                )
                Text(
                    strings.homeModel.homeModelNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                GradientButton(
                    onClick = {
                        showModelSheet = false
                        onOpenSettings()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(strings.homeModel.homeModelOpenSettings) }
                Spacer(Modifier.size(16.dp))
            }
        }
    }

    // v8 — quick-actions sheet: the same four actions as the greeting
    // chips; the sheet closes right after one is tapped.
    if (showQuickActions) {
        ModalBottomSheet(
            onDismissRequest = { showQuickActions = false },
            sheetState = quickActionsSheetState,
        ) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    strings.homeModel.homeQuickActions,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                )
                quickActions.forEach { (icon, label, action) ->
                    QuickChip(
                        icon, label,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !running,
                    ) {
                        showQuickActions = false
                        action()
                    }
                }
                Spacer(Modifier.size(16.dp))
            }
        }
    }

    pendingConfirm?.let { (sender, draft, decision) ->
        AlertDialog(
            onDismissRequest = { decision.complete(false) },
            title = { Text(strings.dialogs.dlgSendTitle.format(sender)) },
            text = { Text(draft) },
            confirmButton = {
                GradientButton(onClick = { decision.complete(true) }) { Text(strings.dialogs.dlgSend) }
            },
            dismissButton = {
                TextButton(
                    onClick = { decision.complete(false) },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) { Text(strings.dialogs.dlgDontSend) }
            },
        )
    }

    pendingPlan?.let { (skillName, steps, decision) ->
        AlertDialog(
            onDismissRequest = { decision.complete(false) },
            title = { Text(strings.dialogs.dlgPlanTitle) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()).heightIn(max = 320.dp)) {
                    Text(skillName, style = MaterialTheme.typography.titleSmall)
                    steps.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = {
                GradientButton(onClick = { decision.complete(true) }) { Text(strings.dialogs.dlgPlanRun) }
            },
            dismissButton = {
                TextButton(
                    onClick = { decision.complete(false) },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) { Text(strings.dialogs.dlgPlanCancel) }
            },
        )
    }

    // (v8.1) Approval bertier + grant (menggantikan AlertDialog Boolean lama).
    pendingStep?.let { (request, decision) ->
        ApprovalDialog(
            request = request,
            onDecision = { choice ->
                pendingStep = null
                decision.complete(choice)
            },
        )
    }

    // v6 (O-ALERT): the file is NOT passed on until the user confirms.
    // Only the finding TYPES are named — never a matched value.
    pendingAttachConfirm?.let { (file, findings) ->
        AlertDialog(
            onDismissRequest = { pendingAttachConfirm = null },
            title = { Text(strings.dialogs.dlgAttachTitle) },
            text = {
                Text(strings.dialogs.dlgAttachBody.format(findings.joinToString(", ") { it.type }))
            },
            confirmButton = {
                GradientButton(onClick = {
                    attachedFile = file
                    pendingAttachConfirm = null
                }) { Text(strings.dialogs.dlgAttachKeep) }
            },
            dismissButton = {
                TextButton(
                    onClick = { pendingAttachConfirm = null },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) { Text(strings.dialogs.dlgAttachCancel) }
            },
        )
    }
}

/** One row of the model chain in the model sheet: name left, LIVE status right. */
@Composable
private fun ModelChainRow(label: String, status: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(status, style = MaterialTheme.typography.labelLarge)
    }
}

/** Copied verbatim from MainActivity.kt (v7.6 quick chip). */
@Composable
private fun QuickChip(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp),
        shape = RoundedCornerShape(16.dp),
    ) {
        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
    }
}

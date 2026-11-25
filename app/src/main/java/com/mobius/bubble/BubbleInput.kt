package com.mobius.bubble

import androidx.compose.foundation.layout.Arrangement
import com.mobius.core.ApprovalDecision
import com.mobius.core.ApprovalRequest
import com.mobius.ui.ApprovalDialog
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.mobius.core.AppConfig
import com.mobius.core.ChatMessage
import com.mobius.ui.ChatHistoryView
import com.mobius.ui.GradientButton
import com.mobius.ui.LocalStrings
import com.mobius.ui.MobiusGradientBrush

/** What the floating panel is currently showing. */
sealed interface PanelState {
    data object Hidden : PanelState
    data object Input : PanelState
    data class Confirm(val sender: String, val draft: String, val decide: (Boolean) -> Unit) : PanelState
    data class Result(val text: String) : PanelState
    /** (v5) Plan card: the skill's resolved steps, shown before anything runs. */
    data class Plan(val skillName: String, val steps: List<String>, val decide: (Boolean) -> Unit) : PanelState
    /** (v5) Irreversible-step approval (ActionGate). */
    data class StepConfirm(val description: String, val decide: (Boolean) -> Unit) : PanelState
    /**
     * (v8.1) Approval bertier + grant (ActionGate): badge tier + pilihan ingat
     * (sekali / chat ini / selamanya); SENSITIVE tidak menawarkan FOREVER.
     */
    data class StepConfirmGrant(val request: ApprovalRequest, val decide: (ApprovalDecision) -> Unit) : PanelState
}

/**
 * Text input sheet opened by tapping the bubble. Voice input is available through the Mic
 * button (Android SpeechRecognizer, free); if it hears nothing it falls back to typing.
 * The same panel also shows the send-confirmation step and long results.
 *
 * v6: the Input state also shows the shared chat history (M-UI, same list as
 * Home), the file/photo attach button (N-UI) and the auto-scroll toggle (G).
 * All three are hidden when their feature flag is OFF (v5 behavior).
 */
@Composable
fun BubblePanel(
    state: PanelState,
    onSubmit: (String) -> Unit,
    onClose: () -> Unit,
    /** Starts one voice listen; the consumer receives the recognized text, or null on failure. */
    onVoice: ((String?) -> Unit) -> Unit = {},
    /** v6 (M-UI): conversation from observeChatMessages(); shown only when [chatHistoryOn]. */
    messages: List<ChatMessage> = emptyList(),
    chatHistoryOn: Boolean = false,
    /** v6 (N-UI): shown only when LOCAL_FILES is ON; opens the picker via MainActivity. */
    localFilesOn: Boolean = false,
    onAttach: () -> Unit = {},
    /** v6 (G): shown only when AUTO_SCROLL is ON. */
    autoScrollOn: Boolean = false,
    autoScrolling: Boolean = false,
    onToggleAutoScroll: () -> Unit = {},
) {
    val strings = LocalStrings.current
    // v7.6 — the panel sheet is MORE TRANSPARENT than before so the app
    // behind shows through, but the sheet keeps a firm 1 dp outline and
    // the chat bubbles inside keep their own opaque fills + strokes
    // (ChatHistoryView) — transparency never costs bubble contrast.
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f),
        border = androidx.compose.foundation.BorderStroke(
            1.dp, MaterialTheme.colorScheme.outline,
        ),
        tonalElevation = 6.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = AppConfig.APP_NAME,
                    style = MaterialTheme.typography.titleMedium.copy(brush = MobiusGradientBrush),
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = onClose,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) { Text(if (state is PanelState.Confirm) strings.panel.panelCancel else strings.panel.panelClose) }
            }
            when (state) {
                is PanelState.Input -> InputBody(
                    onSubmit, onVoice, messages, chatHistoryOn,
                    localFilesOn, onAttach, autoScrollOn, autoScrolling, onToggleAutoScroll,
                )
                is PanelState.Confirm -> ConfirmBody(state)
                is PanelState.Plan -> PlanBody(state)
                is PanelState.StepConfirm -> StepConfirmBody(state)
                is PanelState.StepConfirmGrant -> ApprovalDialog(
                    request = state.request,
                    onDecision = state.decide,
                )
                is PanelState.Result -> Text(
                    text = state.text,
                    modifier = Modifier
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                )
                PanelState.Hidden -> Unit
            }
        }
    }
}

@Composable
private fun InputBody(
    onSubmit: (String) -> Unit,
    onVoice: ((String?) -> Unit) -> Unit,
    messages: List<ChatMessage> = emptyList(),
    chatHistoryOn: Boolean = false,
    localFilesOn: Boolean = false,
    onAttach: () -> Unit = {},
    autoScrollOn: Boolean = false,
    autoScrolling: Boolean = false,
    onToggleAutoScroll: () -> Unit = {},
) {
    val strings = LocalStrings.current
    var text by remember { mutableStateOf("") }
    var listening by remember { mutableStateOf(false) }
    var voiceHint by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    // v8.0 — the field clears SYNCHRONOUSLY the moment Send is hit: the
    // text is captured in a local first, then the state is emptied, then
    // onSubmit runs. The message still reaches the history through
    // onSubmit exactly as before; only the timing of the clear changed —
    // the field no longer waits for the agent's reply to empty.
    fun submitNow() {
        if (text.isBlank()) return
        val sent = text
        text = ""
        onSubmit(sent)
    }

    // v6 (M-UI): same conversation as Home (oldest first).
    if (chatHistoryOn) {
        ChatHistoryView(messages = messages)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier
                .weight(1f)
                .focusRequester(focus),
            placeholder = { Text(strings.panel.inputPlaceholder) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { submitNow() }),
            // v8.0 — WhatsApp-style pill input to match the new bubbles.
            shape = RoundedCornerShape(24.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                cursorColor = MaterialTheme.colorScheme.primary,
            ),
        )
        OutlinedButton(
            onClick = {
                if (listening) return@OutlinedButton
                listening = true
                voiceHint = null
                onVoice { heard ->
                    listening = false
                    if (heard.isNullOrBlank()) {
                        voiceHint = strings.panel.inputVoiceHint
                    } else {
                        onSubmit(heard)
                    }
                }
            },
            enabled = !listening,
            modifier = Modifier.padding(start = 8.dp),
        ) { Text(if (listening) "..." else strings.panel.inputMic) }
    }
    if (listening) {
        Text(strings.panel.inputListening, style = MaterialTheme.typography.bodySmall)
    }
    voiceHint?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    // v6 (N-UI): attach goes through MainActivity's picker (a Service cannot
    // receive ACTION_OPEN_DOCUMENT results); the file still flows through
    // extract -> scan -> alert -> submit in Home.
    if (localFilesOn) {
        OutlinedButton(
            onClick = onAttach,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(strings.panel.inputAttach) }
    }
    // v6 (G): starts the deterministic swipe loop; the panel closes so the
    // underlying app is visible, and Stop lives on the bubble itself.
    if (autoScrollOn) {
        OutlinedButton(
            onClick = onToggleAutoScroll,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (autoScrolling) strings.panel.inputAutoStop else strings.panel.inputAutoStart) }
    }
    GradientButton(
        onClick = { submitNow() },
        enabled = text.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
    ) { Text(strings.panel.inputRun) }
}

@Composable
private fun ConfirmBody(state: PanelState.Confirm) {
    val strings = LocalStrings.current
    Text(strings.panel.confirmTitle.format(state.sender), style = MaterialTheme.typography.bodyMedium)
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Text(
            text = state.draft,
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth()
                .heightIn(max = 160.dp)
                .verticalScroll(rememberScrollState()),
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { state.decide(false) }, modifier = Modifier.weight(1f)) { Text(strings.panel.confirmDontSend) }
        GradientButton(onClick = { state.decide(true) }, modifier = Modifier.weight(1f)) { Text(strings.panel.confirmSend) }
    }
}

/** (v5) Plan card: skill name + resolved steps, Jalankan / Batal. Nothing runs until Jalankan. */
@Composable
private fun PlanBody(state: PanelState.Plan) {
    val strings = LocalStrings.current
    Text(strings.panel.planTitle, style = MaterialTheme.typography.titleMedium)
    Text(state.skillName, style = MaterialTheme.typography.titleSmall)
    Column(
        modifier = Modifier
            .heightIn(max = 280.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        state.steps.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { state.decide(false) }, modifier = Modifier.weight(1f)) { Text(strings.panel.planCancel) }
        GradientButton(onClick = { state.decide(true) }, modifier = Modifier.weight(1f)) { Text(strings.panel.planRun) }
    }
}

/** (v5) One irreversible step (ActionGate): approve it, or the skill aborts. */
@Composable
private fun StepConfirmBody(state: PanelState.StepConfirm) {
    val strings = LocalStrings.current
    Text(strings.panel.stepTitle, style = MaterialTheme.typography.titleMedium)
    Text(state.description, style = MaterialTheme.typography.bodyMedium)
    Text(strings.panel.stepQuestion, style = MaterialTheme.typography.bodyMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { state.decide(false) }, modifier = Modifier.weight(1f)) { Text(strings.panel.stepCancel) }
        GradientButton(onClick = { state.decide(true) }, modifier = Modifier.weight(1f)) { Text(strings.panel.stepContinue) }
    }
}

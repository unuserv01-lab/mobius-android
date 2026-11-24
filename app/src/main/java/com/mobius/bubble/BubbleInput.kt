package com.mobius.bubble

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = AppConfig.APP_NAME,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onClose) { Text(if (state is PanelState.Confirm) "Cancel" else "Close") }
            }
            when (state) {
                is PanelState.Input -> InputBody(
                    onSubmit, onVoice, messages, chatHistoryOn,
                    localFilesOn, onAttach, autoScrollOn, autoScrolling, onToggleAutoScroll,
                )
                is PanelState.Confirm -> ConfirmBody(state)
                is PanelState.Plan -> PlanBody(state)
                is PanelState.StepConfirm -> StepConfirmBody(state)
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
    var text by remember { mutableStateOf("") }
    var listening by remember { mutableStateOf(false) }
    var voiceHint by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

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
            placeholder = { Text("e.g. Reply to the last WhatsApp message") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { if (text.isNotBlank()) onSubmit(text) }),
        )
        OutlinedButton(
            onClick = {
                if (listening) return@OutlinedButton
                listening = true
                voiceHint = null
                onVoice { heard ->
                    listening = false
                    if (heard.isNullOrBlank()) {
                        voiceHint = "Couldn't hear that — try typing."
                    } else {
                        onSubmit(heard)
                    }
                }
            },
            enabled = !listening,
            modifier = Modifier.padding(start = 8.dp),
        ) { Text(if (listening) "..." else "Mic") }
    }
    if (listening) {
        Text("Listening... speak now.", style = MaterialTheme.typography.bodySmall)
    }
    voiceHint?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    // v6 (N-UI): attach goes through MainActivity's picker (a Service cannot
    // receive ACTION_OPEN_DOCUMENT results); the file still flows through
    // extract -> scan -> alert -> submit in Home.
    if (localFilesOn) {
        OutlinedButton(
            onClick = onAttach,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Lampirkan file / foto") }
    }
    // v6 (G): starts the deterministic swipe loop; the panel closes so the
    // underlying app is visible, and Stop lives on the bubble itself.
    if (autoScrollOn) {
        OutlinedButton(
            onClick = onToggleAutoScroll,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (autoScrolling) "Stop auto-scroll" else "Mulai auto-scroll") }
    }
    Button(
        onClick = { onSubmit(text) },
        enabled = text.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Run") }
}

@Composable
private fun ConfirmBody(state: PanelState.Confirm) {
    Text("Send this reply to ${state.sender}?", style = MaterialTheme.typography.bodyMedium)
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
        OutlinedButton(onClick = { state.decide(false) }, modifier = Modifier.weight(1f)) { Text("Don't send") }
        Button(onClick = { state.decide(true) }, modifier = Modifier.weight(1f)) { Text("Send") }
    }
}

/** (v5) Plan card: skill name + resolved steps, Jalankan / Batal. Nothing runs until Jalankan. */
@Composable
private fun PlanBody(state: PanelState.Plan) {
    Text("Langkah yang akan dijalankan", style = MaterialTheme.typography.titleMedium)
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
        OutlinedButton(onClick = { state.decide(false) }, modifier = Modifier.weight(1f)) { Text("Batal") }
        Button(onClick = { state.decide(true) }, modifier = Modifier.weight(1f)) { Text("Jalankan") }
    }
}

/** (v5) One irreversible step (ActionGate): approve it, or the skill aborts. */
@Composable
private fun StepConfirmBody(state: PanelState.StepConfirm) {
    Text("Langkah tak terbalikkan", style = MaterialTheme.typography.titleMedium)
    Text(state.description, style = MaterialTheme.typography.bodyMedium)
    Text("Lanjutkan langkah ini?", style = MaterialTheme.typography.bodyMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { state.decide(false) }, modifier = Modifier.weight(1f)) { Text("Batalkan") }
        Button(onClick = { state.decide(true) }, modifier = Modifier.weight(1f)) { Text("Lanjutkan") }
    }
}

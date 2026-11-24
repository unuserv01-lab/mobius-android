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

/** What the floating panel is currently showing. */
sealed interface PanelState {
    data object Hidden : PanelState
    data object Input : PanelState
    data class Confirm(val sender: String, val draft: String, val decide: (Boolean) -> Unit) : PanelState
    data class Result(val text: String) : PanelState
}

/**
 * Text input sheet opened by tapping the bubble. Voice input is available through the Mic
 * button (Android SpeechRecognizer, free); if it hears nothing it falls back to typing.
 * The same panel also shows the send-confirmation step and long results.
 */
@Composable
fun BubblePanel(
    state: PanelState,
    onSubmit: (String) -> Unit,
    onClose: () -> Unit,
    /** Starts one voice listen; the consumer receives the recognized text, or null on failure. */
    onVoice: ((String?) -> Unit) -> Unit = {},
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
                is PanelState.Input -> InputBody(onSubmit, onVoice)
                is PanelState.Confirm -> ConfirmBody(state)
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
private fun InputBody(onSubmit: (String) -> Unit, onVoice: ((String?) -> Unit) -> Unit) {
    var text by remember { mutableStateOf("") }
    var listening by remember { mutableStateOf(false) }
    var voiceHint by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

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

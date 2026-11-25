package com.mobius.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.mobius.MobiusApp
import com.mobius.core.AppConfig
import kotlinx.coroutines.launch

/**
 * v7.2 — Settings section for the OpenRouter fallback model.
 * Key field is masked (PasswordVisualTransformation); the key is stored in the
 * app-private DataStore and never logged or shown in full anywhere.
 * The model field defaults to [AppConfig.DEFAULT_OPENROUTER_MODEL] and is
 * user-editable because free models rotate. Saving writes both fields.
 */
@Composable
fun OpenRouterSection(app: MobiusApp) {
    val strings = LocalStrings.current
    val pm = app.permissions
    val scope = rememberCoroutineScope()

    val savedKey by pm.openRouterApiKey.collectAsState(initial = "")
    val savedModel by pm.openRouterModel.collectAsState(initial = AppConfig.DEFAULT_OPENROUTER_MODEL)
    // v7.7 bug 2 — null = "not loaded yet". The cloud gate decides whether
    // the fallback can run at all; read it the same safe way.
    val cloudAllowed by pm.cloudAiAllowed.collectAsState(initial = null)

    // v7.7 bug 2 — the old code filled the drafts from collectAsState's
    // INITIAL placeholder ("") and then latched draftsLoaded=true, so the
    // real value arriving from disk a moment later was refused and the
    // field looked empty. A later Save then overwrote the real key with "".
    // Load once from the REAL disk value instead (first() suspends until
    // DataStore has actually read the file), and keep the drafts across
    // rotation with rememberSaveable.
    var keyDraft by rememberSaveable { mutableStateOf<String?>(null) }
    var modelDraft by rememberSaveable { mutableStateOf<String?>(null) }
    var savedStatus by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        keyDraft = pm.getOpenRouterApiKey()
        modelDraft = pm.getOpenRouterModel()
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            strings.setOrTitle,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.tertiary,
        )
        Text(strings.setOrNote, style = MaterialTheme.typography.bodySmall)
        Text(
            if (savedKey.isNotBlank()) strings.setOrKeySaved else strings.setOrKeyEmpty,
            style = MaterialTheme.typography.bodySmall,
            color = if (savedKey.isNotBlank()) {
                MaterialTheme.colorScheme.tertiary
            } else {
                MaterialTheme.colorScheme.error
            },
        )

        OutlinedTextField(
            value = keyDraft ?: "",
            onValueChange = { keyDraft = it },
            label = { Text(strings.setOrKeyLabel) },
            placeholder = { Text(strings.setOrKeyHint) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = modelDraft ?: "",
            onValueChange = { modelDraft = it },
            label = { Text(strings.setOrModelLabel) },
            placeholder = { Text(AppConfig.DEFAULT_OPENROUTER_MODEL) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        // v7.7 bug 2 — the fallback never runs while the cloud gate is OFF,
        // which looks exactly like "the key is gone". Say so plainly.
        if (cloudAllowed == false) {
            Text(strings.setOrGateOffWarn, style = MaterialTheme.typography.bodySmall)
        }

        GradientButton(
            onClick = {
                scope.launch {
                    val newKey = (keyDraft ?: "").trim()
                    val model = (modelDraft ?: "").trim()
                        .ifBlank { AppConfig.DEFAULT_OPENROUTER_MODEL }
                    // v7.7 bug 2 — NEVER overwrite a stored key with an empty
                    // draft (that is how the key was destroyed: field looked
                    // empty because of the load race, then Save wrote "").
                    if (newKey.isEmpty() && pm.getOpenRouterApiKey().isBlank()) {
                        savedStatus = strings.setOrKeyNeeded
                        return@launch
                    }
                    if (newKey.isNotEmpty()) {
                        pm.setOpenRouterSettings(newKey, model)
                    } else {
                        pm.setOpenRouterModel(model)
                    }
                    modelDraft = model
                    savedStatus = strings.setOrKeySaved
                }
            },
        ) { Text(strings.gwSave) }
        savedStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

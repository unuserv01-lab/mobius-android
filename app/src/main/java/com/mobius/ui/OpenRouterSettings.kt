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

    var keyDraft by remember { mutableStateOf("") }
    var modelDraft by remember { mutableStateOf("") }
    var draftsLoaded by remember { mutableStateOf(false) }
    var savedStatus by remember { mutableStateOf<String?>(null) }

    // Fill the drafts once from the stored values (do not overwrite while typing).
    LaunchedEffect(savedKey, savedModel) {
        if (!draftsLoaded) {
            keyDraft = savedKey
            modelDraft = savedModel
            draftsLoaded = true
        }
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
            value = keyDraft,
            onValueChange = { keyDraft = it },
            label = { Text(strings.setOrKeyLabel) },
            placeholder = { Text(strings.setOrKeyHint) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = modelDraft,
            onValueChange = { modelDraft = it },
            label = { Text(strings.setOrModelLabel) },
            placeholder = { Text(AppConfig.DEFAULT_OPENROUTER_MODEL) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        GradientButton(
            onClick = {
                scope.launch {
                    pm.setOpenRouterApiKey(keyDraft.trim())
                    val model = modelDraft.trim().ifBlank { AppConfig.DEFAULT_OPENROUTER_MODEL }
                    pm.setOpenRouterModel(model)
                    modelDraft = model
                    savedStatus = strings.setOrKeySaved
                }
            },
        ) { Text(strings.gwSave) }
        savedStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

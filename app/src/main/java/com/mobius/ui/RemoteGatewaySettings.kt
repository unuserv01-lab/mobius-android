package com.mobius.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.mobius.MobiusApp
import com.mobius.core.FeatureFlag
import com.mobius.core.RemoteGateway
import kotlinx.coroutines.launch

/**
 * v6 Workstream K part 2 — Settings section for the laptop bridge (remote gateway).
 * Functional only, no redesign. Wired in by the coordinator (this file does NOT edit
 * SettingsScreen.kt): call `RemoteGatewaySection(app)` from SettingsScreen.
 *
 * Fields: bridge address + token (password-style), both saved to the "remote_gateway"
 * DataStore. "Test connection" calls health(); "Kirim tugas tes" sends via
 * sendTaskAndWait() and shows the result — or the clear unreachable message when the
 * bridge cannot be reached (the phone then just keeps working locally).
 * When the REMOTE_GATEWAY flag is OFF, the test buttons are disabled and no remote
 * call is made at all.
 */
@Composable
fun RemoteGatewaySection(app: MobiusApp) {
    val strings = LocalStrings.current
    val gateway = remember { RemoteGateway(app, app.featureFlags) }
    val scope = rememberCoroutineScope()

    val gatewayEnabled by app.featureFlags.isEnabled(FeatureFlag.REMOTE_GATEWAY)
        .collectAsState(initial = true)
    val savedAddress by gateway.bridgeAddressFlow()
        .collectAsState(initial = RemoteGateway.DEFAULT_BRIDGE_ADDRESS)
    val savedToken by gateway.bridgeTokenFlow().collectAsState(initial = "")

    var addressDraft by remember { mutableStateOf("") }
    var tokenDraft by remember { mutableStateOf("") }
    var draftsLoaded by remember { mutableStateOf(false) }
    var healthStatus by remember { mutableStateOf<String?>(null) }
    var testTaskText by remember { mutableStateOf(strings.gateway.gwTaskDefault) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    // Fill the drafts once from the stored values (do not overwrite while typing).
    LaunchedEffect(savedAddress, savedToken) {
        if (!draftsLoaded) {
            addressDraft = savedAddress
            tokenDraft = savedToken
            draftsLoaded = true
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            strings.gateway.gwTitle,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.tertiary,
        )
        Text(strings.gateway.gwDesc, style = MaterialTheme.typography.bodySmall)
        if (!gatewayEnabled) {
            Text(
                strings.gateway.gwDisabled,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        OutlinedTextField(
            value = addressDraft,
            onValueChange = { addressDraft = it },
            label = { Text(strings.gateway.gwAddressLabel) },
            placeholder = { Text(RemoteGateway.DEFAULT_BRIDGE_ADDRESS) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(strings.gateway.gwAddressExample.format(RemoteGateway.DEFAULT_BRIDGE_ADDRESS), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        OutlinedTextField(
            value = tokenDraft,
            onValueChange = { tokenDraft = it },
            label = { Text(strings.gateway.gwTokenLabel) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(strings.gateway.gwTokenDesc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GradientButton(
                onClick = {
                    scope.launch {
                        gateway.saveSettings(addressDraft, tokenDraft)
                        healthStatus = strings.gateway.gwSaved
                    }
                },
            ) { Text(strings.gateway.gwSave) }
            OutlinedButton(
                enabled = gatewayEnabled && !busy,
                onClick = {
                    scope.launch {
                        busy = true
                        healthStatus = strings.gateway.gwTesting
                        gateway.saveSettings(addressDraft, tokenDraft)
                        healthStatus = if (gateway.health()) {
                            strings.gateway.gwReachable
                        } else {
                            strings.gateway.gwUnreachable
                        }
                        busy = false
                    }
                },
            ) { Text(strings.gateway.gwTest) }
        }
        healthStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }

        OutlinedTextField(
            value = testTaskText,
            onValueChange = { testTaskText = it },
            label = { Text(strings.gateway.gwTaskLabel) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        GradientButton(
            enabled = gatewayEnabled && !busy,
            onClick = {
                scope.launch {
                    busy = true
                    testResult = strings.gateway.gwSending
                    gateway.saveSettings(addressDraft, tokenDraft)
                    testResult = gateway.sendTaskAndWait(testTaskText)
                    busy = false
                }
            },
        ) { Text(strings.gateway.gwSendTask) }
        testResult?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

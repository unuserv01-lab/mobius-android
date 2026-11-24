package com.mobius.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mobius.MobiusApp
import com.mobius.ai.NanoStatus
import com.mobius.core.AppConfig
import kotlinx.coroutines.launch

private val TTL_CHOICES = listOf("1 hour" to 3_600L, "24 hours" to 86_400L, "7 days" to 604_800L)

/** Cloud-AI gate (default OFF), send confirmation, TTL, Gemini Nano status, memory wipe. */
@Composable
fun SettingsScreen(app: MobiusApp, onRerunSetup: () -> Unit) {
    val pm = app.permissions
    val scope = rememberCoroutineScope()
    val cloudAllowed by pm.cloudAiAllowed.collectAsState(initial = false)
    val confirmSend by pm.confirmBeforeSend.collectAsState(initial = true)
    val ttl by pm.memoryTtlSeconds.collectAsState(initial = AppConfig.DEFAULT_TTL_SECONDS)
    var nanoText by remember { mutableStateOf("not checked yet") }
    var nanoStatus by remember { mutableStateOf<NanoStatus?>(null) }

    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)

        // ---- Cloud AI gate ----
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Allow cloud AI", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Switch(checked = cloudAllowed, onCheckedChange = { v -> scope.launch { pm.setCloudAiAllowed(v) } })
            }
            Text(
                "OFF by default. Gemini Nano on your device is always tried first. When this is ON, " +
                    "${AppConfig.APP_NAME} may send the text of a request (and, for reply tasks, the message " +
                    "you are replying to) to Gemini in the cloud, but only if the task is heavy or Nano is " +
                    "unavailable. Every routing decision is listed in History. Your stored memory is never sent.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        // ---- Confirm before send ----
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Confirm before sending", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Switch(checked = confirmSend, onCheckedChange = { v -> scope.launch { pm.setConfirmBeforeSend(v) } })
            }
            Text(
                "Show the drafted reply and ask before anything is sent on your behalf.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        // ---- TTL ----
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Keep memory and logs for", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TTL_CHOICES.forEach { (label, seconds) ->
                    FilterChip(
                        selected = ttl == seconds,
                        onClick = { scope.launch { pm.setMemoryTtlSeconds(seconds) } },
                        label = { Text(label) },
                    )
                }
            }
            Text("Applies to new entries. Expired entries are deleted automatically.", style = MaterialTheme.typography.bodySmall)
        }

        // ---- Gemini Nano ----
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("On-device AI (Gemini Nano)", style = MaterialTheme.typography.titleMedium)
            Text("Status: $nanoText", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    scope.launch {
                        val s = app.onDeviceAI.status()
                        nanoStatus = s
                        nanoText = s.label
                    }
                }) { Text("Check") }
                if (nanoStatus == NanoStatus.DOWNLOADABLE) {
                    Button(onClick = {
                        scope.launch {
                            try {
                                app.onDeviceAI.download().collect { nanoText = it }
                            } catch (e: Exception) {
                                nanoText = "Download failed: ${e.message}"
                            }
                        }
                    }) { Text("Download Nano") }
                }
            }
            Text(
                "Needs a device with AICore support (for example recent Pixel or select Samsung phones). " +
                    "If unavailable, ${AppConfig.APP_NAME} falls back to cloud Gemini only when you allow it.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        // ---- v6 feature flags (Step 0) ----
        // One Switch per flag, all ON by default. OFF = v5 behavior exactly for that feature.
        FeatureFlagsSection(app)

        // ---- v6 UI wiring: watcher rules + remote gateway (own sections) ----
        WatcherRulesSection(app)
        RemoteGatewaySection(app)

        // ---- Data & setup ----
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Your data", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = { scope.launch { app.memoryStore.clearAll() } }) {
                Text("Delete all memory and logs")
            }
            OutlinedButton(
                onClick = { scope.launch { pm.setOnboardingDone(false); onRerunSetup() } },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Review permissions again") }
        }
    }
}

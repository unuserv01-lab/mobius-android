package com.mobius.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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

/** Cloud-AI gate (default OFF), send confirmation, TTL, Gemini Nano status, memory wipe. */
@Composable
fun SettingsScreen(app: MobiusApp, onRerunSetup: () -> Unit) {
    val strings = LocalStrings.current
    val pm = app.permissions
    val scope = rememberCoroutineScope()
    val cloudAllowed by pm.cloudAiAllowed.collectAsState(initial = false)
    val confirmSend by pm.confirmBeforeSend.collectAsState(initial = true)
    val ttl by pm.memoryTtlSeconds.collectAsState(initial = AppConfig.DEFAULT_TTL_SECONDS)
    var nanoText by remember(strings) { mutableStateOf(strings.setNanoNotChecked) }
    var nanoStatus by remember { mutableStateOf<NanoStatus?>(null) }
    // v7 — language: same DataStore setting the bubble Service reads; a change
    // recomposes both roots immediately (no restart, no reinstall).
    val languageManager = remember { LanguageManager(app.applicationContext) }
    val lang by languageManager.language.collectAsState(initial = AppLanguage.ID)
    val ttlChoices = listOf(
        strings.setTtl1h to 3_600L,
        strings.setTtl24h to 86_400L,
        strings.setTtl7d to 604_800L,
    )

    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(
            strings.setTitle,
            style = MaterialTheme.typography.headlineSmall.copy(brush = MobiusGradientBrush),
        )

        // ---- Language (v7) ----
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                strings.setLangTitle,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.tertiary,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = lang == AppLanguage.ID,
                    onClick = { scope.launch { languageManager.setLanguage(AppLanguage.ID) } },
                    label = { Text(strings.setLangId) },
                )
                FilterChip(
                    selected = lang == AppLanguage.EN,
                    onClick = { scope.launch { languageManager.setLanguage(AppLanguage.EN) } },
                    label = { Text(strings.setLangEn) },
                )
            }
            Text(strings.setLangDesc, style = MaterialTheme.typography.bodySmall)
        }

        // ---- Cloud AI gate ----
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    strings.setAllowCloud,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = cloudAllowed, onCheckedChange = { v -> scope.launch { pm.setCloudAiAllowed(v) } })
            }
            Text(strings.setAllowCloudDesc, style = MaterialTheme.typography.bodySmall)
        }

        // ---- Fallback model (OpenRouter, v7.2) ----
        OpenRouterSection(app)

        // ---- Confirm before send ----
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    strings.setConfirmSend,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = confirmSend, onCheckedChange = { v -> scope.launch { pm.setConfirmBeforeSend(v) } })
            }
            Text(strings.setConfirmSendDesc, style = MaterialTheme.typography.bodySmall)
        }

        // ---- TTL ----
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                strings.setTtlTitle,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.tertiary,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ttlChoices.forEach { (label, seconds) ->
                    FilterChip(
                        selected = ttl == seconds,
                        onClick = { scope.launch { pm.setMemoryTtlSeconds(seconds) } },
                        label = { Text(label) },
                    )
                }
            }
            Text(strings.setTtlApplies, style = MaterialTheme.typography.bodySmall)
        }

        // ---- Gemini Nano ----
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                strings.setNanoTitle,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.tertiary,
            )
            Text(strings.setNanoStatusPrefix + nanoText, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    scope.launch {
                        val s = app.onDeviceAI.status()
                        nanoStatus = s
                        nanoText = s.label
                    }
                }) { Text(strings.setNanoCheck) }
                if (nanoStatus == NanoStatus.DOWNLOADABLE) {
                    GradientButton(onClick = {
                        scope.launch {
                            try {
                                app.onDeviceAI.download().collect { nanoText = it }
                            } catch (e: Exception) {
                                nanoText = strings.setNanoDownloadFailed.format(e.message)
                            }
                        }
                    }) { Text(strings.setNanoDownload) }
                }
            }
            Text(strings.setNanoDesc, style = MaterialTheme.typography.bodySmall)
        }

        // ---- v6 feature flags (Step 0) ----
        // One Switch per flag, all ON by default. OFF = v5 behavior exactly for that feature.
        FeatureFlagsSection(app)

        // ---- v6 UI wiring: watcher rules + remote gateway (own sections) ----
        WatcherRulesSection(app)
        RemoteGatewaySection(app)

        // ---- Data & setup ----
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                strings.setDataTitle,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.tertiary,
            )
            OutlinedButton(onClick = { scope.launch { app.memoryStore.clearAll() } }) {
                Text(strings.setDataDelete)
            }
            OutlinedButton(
                onClick = { scope.launch { pm.setOnboardingDone(false); onRerunSetup() } },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(strings.setDataReview) }
        }
    }
}

package com.mobius.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
import com.mobius.bubble.AutoScrollSettings
import com.mobius.core.AppConfig
import kotlinx.coroutines.launch

/** Cloud-AI gate (default OFF), send confirmation, TTL, Gemini Nano status, memory wipe. */
@Composable
fun SettingsScreen(app: MobiusApp, onRerunSetup: () -> Unit, onBack: () -> Unit) {
    val strings = LocalStrings.current
    val pm = app.permissions
    val scope = rememberCoroutineScope()
    val cloudAllowed by pm.cloudAiAllowed.collectAsState(initial = false)
    val confirmSend by pm.confirmBeforeSend.collectAsState(initial = true)
    val ttl by pm.memoryTtlSeconds.collectAsState(initial = AppConfig.DEFAULT_TTL_SECONDS)
    var nanoText by remember(strings) { mutableStateOf(strings.settings.setNanoNotChecked) }
    var nanoStatus by remember { mutableStateOf<NanoStatus?>(null) }
    // v7 — language: same DataStore setting the bubble Service reads; a change
    // recomposes both roots immediately (no restart, no reinstall).
    val languageManager = remember { LanguageManager(app.applicationContext) }
    val lang by languageManager.language.collectAsState(initial = AppLanguage.ID)
    val ttlChoices = listOf(
        strings.settings.setTtl1h to 3_600L,
        strings.settings.setTtl24h to 86_400L,
        strings.settings.setTtl7d to 604_800L,
    )
    // v7.9-tambahan — registry screen is local state (MainActivity is owned by
    // another workstream): when true the whole list below is replaced by it.
    var showAppRegistry by remember { mutableStateOf(false) }
    // v8.1-hotfix bug 2 — Back from the registry sub-screen returns to
    // Settings (not HOME): this handler is inner to MainTabs' handler, so it
    // wins while the registry is open.
    BackHandler(enabled = showAppRegistry) { showAppRegistry = false }
    if (showAppRegistry) {
        AppRegistryScreen(app, onBack = { showAppRegistry = false })
        return
    }
    // v7.9-tambahan — interval auto-scroll (DataStore "auto_scroll", default 30 dtk).
    val autoScrollSettings = remember { AutoScrollSettings(app.applicationContext) }
    val intervalSeconds by autoScrollSettings.intervalSeconds
        .collectAsState(initial = AutoScrollSettings.DEFAULT_INTERVAL_SECONDS)
    val intervalChoices = listOf(5L, 10L, 15L, 30L, 60L, 120L)

    // v8.1-hotfix bug 2 — the back arrow stays fixed at the top; the
    // settings list scrolls below it. (The old gradient title is replaced
    // by the bar title so the heading is not shown twice.)
    Column(Modifier.fillMaxSize()) {
        BackTopBar(title = strings.settings.setTitle, onBack = onBack)
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
        // ---- Language (v7) ----
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                strings.settings.setLangTitle,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.tertiary,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = lang == AppLanguage.ID,
                    onClick = { scope.launch { languageManager.setLanguage(AppLanguage.ID) } },
                    label = { Text(strings.settings.setLangId) },
                )
                FilterChip(
                    selected = lang == AppLanguage.EN,
                    onClick = { scope.launch { languageManager.setLanguage(AppLanguage.EN) } },
                    label = { Text(strings.settings.setLangEn) },
                )
            }
            Text(strings.settings.setLangDesc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // ---- Cloud AI gate ----
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    strings.settings.setAllowCloud,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = cloudAllowed, onCheckedChange = { v -> scope.launch { pm.setCloudAiAllowed(v) } })
            }
            Text(strings.settings.setAllowCloudDesc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // ---- Fallback model (OpenRouter, v7.2) ----
        OpenRouterSection(app)

        // ---- Google account (v7.9-ui) ----
        GoogleAccountSection(app)

        // ---- Capabilities (v8 UI rework — moved here from Home) ----
        CapabilitiesSection(app)

        // ---- Persetujuan yang diingat (v8.1) ----
        // Daftar grant approval (sekali / chat ini / selamanya) + cabut per baris.
        ApprovalGrantsSection(app.orchestrator.approvalGrants)

        // ---- Interval auto-scroll (v7.9-tambahan) ----
        // Chips, same pattern as the TTL section. Applies on the NEXT start of
        // the loop (a running loop keeps its interval; stop + start again).
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Interval auto-scroll",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.tertiary,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                intervalChoices.take(3).forEach { seconds ->
                    FilterChip(
                        selected = intervalSeconds == seconds,
                        onClick = { scope.launch { autoScrollSettings.setIntervalSeconds(seconds) } },
                        label = { Text("$seconds dtk") },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                intervalChoices.drop(3).forEach { seconds ->
                    FilterChip(
                        selected = intervalSeconds == seconds,
                        onClick = { scope.launch { autoScrollSettings.setIntervalSeconds(seconds) } },
                        label = { Text("$seconds dtk") },
                    )
                }
            }
            Text(
                "Jeda antar scroll otomatis (5–120 detik, default 30). " +
                    "Berlaku saat auto-scroll dinyalakan lagi dari bubble.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ---- Aplikasi yang diizinkan (v7.9-tambahan) ----
        // Entry row only; the list itself lives in AppRegistryScreen (local nav above).
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Aplikasi yang diizinkan",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.tertiary,
            )
            Text(
                "MOBIUS hanya boleh membuka aplikasi yang diizinkan. " +
                    "Aplikasi yang sudah dikenal aktif secara default; sisanya mati sampai dinyalakan.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = { showAppRegistry = true },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Kelola aplikasi yang diizinkan") }
        }

        // ---- Confirm before send ----
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    strings.settings.setConfirmSend,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = confirmSend, onCheckedChange = { v -> scope.launch { pm.setConfirmBeforeSend(v) } })
            }
            Text(strings.settings.setConfirmSendDesc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // ---- TTL ----
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                strings.settings.setTtlTitle,
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
            Text(strings.settings.setTtlApplies, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // ---- Gemini Nano ----
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                strings.settings.setNanoTitle,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.tertiary,
            )
            Text(strings.settings.setNanoStatusPrefix + nanoText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    scope.launch {
                        val s = app.onDeviceAI.status()
                        nanoStatus = s
                        nanoText = s.label
                    }
                }) { Text(strings.settings.setNanoCheck) }
                if (nanoStatus == NanoStatus.DOWNLOADABLE) {
                    GradientButton(onClick = {
                        scope.launch {
                            try {
                                app.onDeviceAI.download().collect { nanoText = it }
                            } catch (e: Exception) {
                                nanoText = strings.settings.setNanoDownloadFailed.format(e.message)
                            }
                        }
                    }) { Text(strings.settings.setNanoDownload) }
                }
            }
            Text(strings.settings.setNanoDesc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                strings.settings.setDataTitle,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.tertiary,
            )
            OutlinedButton(onClick = { scope.launch { app.memoryStore.clearAll() } }) {
                Text(strings.settings.setDataDelete)
            }
            OutlinedButton(
                onClick = { scope.launch { pm.setOnboardingDone(false); onRerunSetup() } },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(strings.settings.setDataReview) }
        }
        }
    }
}

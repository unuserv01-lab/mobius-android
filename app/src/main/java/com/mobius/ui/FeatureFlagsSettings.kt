package com.mobius.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mobius.MobiusApp
import com.mobius.core.FeatureFlag
import kotlinx.coroutines.launch

/**
 * v6 Step 0 — one Switch per feature flag. All flags default ON.
 * OFF means "v5 behavior exactly": the new v6 behavior behind that flag is skipped.
 *
 * v7: labels/descriptions come from the strings table (ID + EN), so the
 * section follows the selected language like every other screen.
 */
private fun flagLabels(strings: MobiusStrings): List<Triple<FeatureFlag, String, String>> = listOf(
    Triple(FeatureFlag.PLANNING_LOOP, strings.flags.flagPlanningLoop, strings.flags.flagPlanningLoopDesc),
    Triple(FeatureFlag.ORCHESTRATION, strings.flags.flagOrchestration, strings.flags.flagOrchestrationDesc),
    Triple(FeatureFlag.CODE_AGENT, strings.flags.flagCodeAgent, strings.flags.flagCodeAgentDesc),
    Triple(FeatureFlag.WA_WATCHER, strings.flags.flagWaWatcher, strings.flags.flagWaWatcherDesc),
    Triple(FeatureFlag.PARALLEL_WORKERS, strings.flags.flagParallel, strings.flags.flagParallelDesc),
    Triple(FeatureFlag.VOICE_OUTPUT, strings.flags.flagVoice, strings.flags.flagVoiceDesc),
    Triple(FeatureFlag.AUTO_SCROLL, strings.flags.flagAutoScroll, strings.flags.flagAutoScrollDesc),
    Triple(FeatureFlag.TOKOPEDIA_SKILL, strings.flags.flagTokopedia, strings.flags.flagTokopediaDesc),
    Triple(FeatureFlag.MEMORY_V2, strings.flagsMore.flagMemory, strings.flagsMore.flagMemoryDesc),
    Triple(FeatureFlag.MULTI_ENGINE, strings.flagsMore.flagMultiEngine, strings.flagsMore.flagMultiEngineDesc),
    Triple(FeatureFlag.LAPTOP_BRIDGE, strings.flagsMore.flagBridge, strings.flagsMore.flagBridgeDesc),
    Triple(FeatureFlag.REMOTE_GATEWAY, strings.flagsMore.flagGateway, strings.flagsMore.flagGatewayDesc),
    Triple(FeatureFlag.NANO_TRIAGE, strings.flagsMore.flagTriage, strings.flagsMore.flagTriageDesc),
    Triple(FeatureFlag.CHAT_HISTORY, strings.flagsMore.flagChatHistory, strings.flagsMore.flagChatHistoryDesc),
    Triple(FeatureFlag.LOCAL_FILES, strings.flagsMore.flagLocalFiles, strings.flagsMore.flagLocalFilesDesc),
    Triple(FeatureFlag.SENSITIVE_ALERT, strings.flagsMore.flagSensitive, strings.flagsMore.flagSensitiveDesc),
)

@Composable
fun FeatureFlagsSection(app: MobiusApp) {
    val strings = LocalStrings.current
    val flags = app.featureFlags
    val scope = rememberCoroutineScope()

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            strings.flags.flagsTitle,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.tertiary,
        )
        Text(strings.flags.flagsDesc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        flagLabels(strings).forEach { (flag, label, description) ->
            val enabled by flags.isEnabled(flag).collectAsState(initial = true)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
                    Switch(
                        checked = enabled,
                        onCheckedChange = { v -> scope.launch { flags.setEnabled(flag, v) } },
                    )
                }
                Text(description, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(end = 56.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

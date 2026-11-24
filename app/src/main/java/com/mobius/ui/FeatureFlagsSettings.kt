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
    Triple(FeatureFlag.PLANNING_LOOP, strings.flagPlanningLoop, strings.flagPlanningLoopDesc),
    Triple(FeatureFlag.ORCHESTRATION, strings.flagOrchestration, strings.flagOrchestrationDesc),
    Triple(FeatureFlag.CODE_AGENT, strings.flagCodeAgent, strings.flagCodeAgentDesc),
    Triple(FeatureFlag.WA_WATCHER, strings.flagWaWatcher, strings.flagWaWatcherDesc),
    Triple(FeatureFlag.PARALLEL_WORKERS, strings.flagParallel, strings.flagParallelDesc),
    Triple(FeatureFlag.VOICE_OUTPUT, strings.flagVoice, strings.flagVoiceDesc),
    Triple(FeatureFlag.AUTO_SCROLL, strings.flagAutoScroll, strings.flagAutoScrollDesc),
    Triple(FeatureFlag.TOKOPEDIA_SKILL, strings.flagTokopedia, strings.flagTokopediaDesc),
    Triple(FeatureFlag.MEMORY_V2, strings.flagMemory, strings.flagMemoryDesc),
    Triple(FeatureFlag.MULTI_ENGINE, strings.flagMultiEngine, strings.flagMultiEngineDesc),
    Triple(FeatureFlag.LAPTOP_BRIDGE, strings.flagBridge, strings.flagBridgeDesc),
    Triple(FeatureFlag.REMOTE_GATEWAY, strings.flagGateway, strings.flagGatewayDesc),
    Triple(FeatureFlag.NANO_TRIAGE, strings.flagTriage, strings.flagTriageDesc),
    Triple(FeatureFlag.CHAT_HISTORY, strings.flagChatHistory, strings.flagChatHistoryDesc),
    Triple(FeatureFlag.LOCAL_FILES, strings.flagLocalFiles, strings.flagLocalFilesDesc),
    Triple(FeatureFlag.SENSITIVE_ALERT, strings.flagSensitive, strings.flagSensitiveDesc),
)

@Composable
fun FeatureFlagsSection(app: MobiusApp) {
    val strings = LocalStrings.current
    val flags = app.featureFlags
    val scope = rememberCoroutineScope()

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            strings.flagsTitle,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.tertiary,
        )
        Text(strings.flagsDesc, style = MaterialTheme.typography.bodySmall)
        flagLabels(strings).forEach { (flag, label, description) ->
            val enabled by flags.isEnabled(flag).collectAsState(initial = true)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Switch(
                        checked = enabled,
                        onCheckedChange = { v -> scope.launch { flags.setEnabled(flag, v) } },
                    )
                }
                Text(description, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(end = 56.dp))
            }
        }
    }
}

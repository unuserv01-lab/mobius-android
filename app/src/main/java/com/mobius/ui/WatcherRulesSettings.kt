package com.mobius.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import com.mobius.core.FeatureFlag
import com.mobius.notification.WatcherPendingDrafts
import com.mobius.notification.WatcherRules
import com.mobius.notification.watcherEngine
import kotlinx.coroutines.launch

/**
 * WORKSTREAM D (v6) — WA watcher standing rules, minimal functional UI only.
 *
 * Wired in by the coordinator (this file does NOT edit SettingsScreen.kt):
 *   WatcherRulesSection(app)
 *
 * Safety, stated in the UI so Tuan sees it where he creates the rules:
 * no rule exists until he creates one here, so nothing auto-replies by itself.
 * Creating a rule IS the standing approval for that exact reply text.
 */
@Composable
fun WatcherRulesSection(app: MobiusApp) {
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    val rulesStore = remember(app) { WatcherRules(app) }
    val rules by rulesStore.observeRules().collectAsState(initial = emptyList())
    val watcherOn by app.featureFlags.isEnabled(FeatureFlag.WA_WATCHER).collectAsState(initial = true)
    val pendingDrafts by WatcherPendingDrafts.drafts.collectAsState()

    var name by remember { mutableStateOf("") }
    var matchText by remember { mutableStateOf("") }
    var sender by remember { mutableStateOf("") }
    var replyTemplate by remember { mutableStateOf("") }
    var autoSend by remember { mutableStateOf(false) }
    var formError by remember { mutableStateOf<String?>(null) }
    var formNote by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            strings.watcher.watcherTitle,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.tertiary,
        )
        Text(strings.watcher.watcherDesc, style = MaterialTheme.typography.bodySmall)
        if (!watcherOn) {
            Text(
                strings.watcher.watcherOff,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        // ---- pending drafts waiting for approval (rules with auto-send OFF) ----
        if (pendingDrafts.isNotEmpty()) {
            Text(strings.watcher.watcherPendingTitle, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            pendingDrafts.forEach { draft ->
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        strings.watcher.watcherFrom.format(draft.sender, draft.ruleName),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(strings.watcher.watcherIncoming.format(draft.incomingText), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(strings.watcher.watcherReply.format(draft.replyText), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GradientButton(onClick = {
                            scope.launch {
                                val ok = app.watcherEngine().sendPending(draft.id)
                                formNote = if (ok) strings.watcherForm.watcherSent.format(draft.sender)
                                else strings.watcherForm.watcherSendFailed
                            }
                        }) { Text(strings.watcher.watcherSend) }
                        OutlinedButton(onClick = {
                            scope.launch { app.watcherEngine().discardPending(draft.id) }
                        }) { Text(strings.watcher.watcherDiscard) }
                    }
                }
            }
        }

        // ---- existing rules ----
        Text(strings.watcher.watcherRulesTitle, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        if (rules.isEmpty()) {
            Text(strings.watcher.watcherRulesEmpty, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        rules.forEach { rule ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(rule.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
                    Switch(
                        checked = rule.enabled,
                        onCheckedChange = { v -> scope.launch { rulesStore.setEnabled(rule.id, v) } },
                    )
                }
                Text(
                    strings.watcher.watcherMatchPrefix +
                        (if (rule.matchText.isNotBlank()) strings.watcher.watcherMatchText.format(rule.matchText) else "") +
                        (if (rule.matchText.isNotBlank() && rule.sender.isNotBlank()) " + " else "") +
                        (if (rule.sender.isNotBlank()) strings.watcher.watcherMatchSender.format(rule.sender) else ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(strings.watcher.watcherReplyIs.format(rule.replyTemplate), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        if (rule.autoSend) strings.watcher.watcherAutoOn else strings.watcher.watcherAutoOff,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = rule.autoSend,
                        onCheckedChange = { v -> scope.launch { rulesStore.setAutoSend(rule.id, v) } },
                    )
                    OutlinedButton(onClick = { scope.launch { rulesStore.deleteRule(rule.id) } }) {
                        Text(strings.watcher.watcherDelete)
                    }
                }
            }
        }

        // ---- add a rule ----
        Text(strings.watcherForm.watcherAddTitle, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        OutlinedTextField(
            value = name, onValueChange = { name = it },
            label = { Text(strings.watcherForm.watcherNameLabel) },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
        )
        OutlinedTextField(
            value = matchText, onValueChange = { matchText = it },
            label = { Text(strings.watcherForm.watcherMatchLabel) },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
        )
        OutlinedTextField(
            value = sender, onValueChange = { sender = it },
            label = { Text(strings.watcherForm.watcherSenderLabel) },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
        )
        OutlinedTextField(
            value = replyTemplate, onValueChange = { replyTemplate = it },
            label = { Text(strings.watcherForm.watcherReplyLabel) },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                strings.watcherForm.watcherAutoDesc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
            )
            Switch(checked = autoSend, onCheckedChange = { autoSend = it })
        }
        formError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        formNote?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        GradientButton(onClick = {
            scope.launch {
                val created = rulesStore.addRule(name, matchText, sender, replyTemplate, autoSend)
                if (created == null) {
                    formNote = null
                    formError = strings.watcherForm.watcherFormError
                } else {
                    formError = null
                    formNote = strings.watcherForm.watcherRuleCreated.format(created.name) +
                        (if (created.autoSend) strings.watcherForm.watcherRuleCreatedAuto else strings.watcherForm.watcherRuleCreatedDraft)
                    name = ""; matchText = ""; sender = ""; replyTemplate = ""; autoSend = false
                }
            }
        }) { Text(strings.watcherForm.watcherAddButton) }
    }
}

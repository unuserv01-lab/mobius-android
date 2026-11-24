package com.mobius.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
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
        Text("WA watcher — aturan balasan", style = MaterialTheme.typography.titleMedium)
        Text(
            "Watcher hanya jalan bila flag \"WA watcher\" ON dan ada aturan di bawah ini. " +
                "Tanpa aturan, tidak ada yang dibalas otomatis. Membuat aturan = persetujuan " +
                "berdiri untuk mengirim teks balasan itu persis apa adanya.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (!watcherOn) {
            Text(
                "Watcher sedang OFF (flag WA watcher dimatikan). Pesan WhatsApp hanya dicatat, tidak dibalas.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        // ---- pending drafts waiting for approval (rules with auto-send OFF) ----
        if (pendingDrafts.isNotEmpty()) {
            Text("Draf menunggu persetujuanmu", style = MaterialTheme.typography.titleSmall)
            pendingDrafts.forEach { draft ->
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Dari ${draft.sender} (aturan \"${draft.ruleName}\")", style = MaterialTheme.typography.bodyMedium)
                    Text("Pesan: ${draft.incomingText}", style = MaterialTheme.typography.bodySmall)
                    Text("Balasan: ${draft.replyText}", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            scope.launch {
                                val ok = app.watcherEngine().sendPending(draft.id)
                                formNote = if (ok) "Draf terkirim ke ${draft.sender}."
                                else "Gagal mengirim — aksi balas sudah kedaluwarsa, kirim manual dari WhatsApp."
                            }
                        }) { Text("Kirim") }
                        OutlinedButton(onClick = {
                            scope.launch { app.watcherEngine().discardPending(draft.id) }
                        }) { Text("Buang") }
                    }
                }
            }
        }

        // ---- existing rules ----
        Text("Aturan", style = MaterialTheme.typography.titleSmall)
        if (rules.isEmpty()) {
            Text("Belum ada aturan — watcher tidak membalas apa pun.", style = MaterialTheme.typography.bodySmall)
        }
        rules.forEach { rule ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(rule.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Switch(
                        checked = rule.enabled,
                        onCheckedChange = { v -> scope.launch { rulesStore.setEnabled(rule.id, v) } },
                    )
                }
                Text(
                    "Cocok: " +
                        (if (rule.matchText.isNotBlank()) "teks mengandung \"${rule.matchText}\"" else "") +
                        (if (rule.matchText.isNotBlank() && rule.sender.isNotBlank()) " + " else "") +
                        (if (rule.sender.isNotBlank()) "pengirim mengandung \"${rule.sender}\"" else ""),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text("Balas: \"${rule.replyTemplate}\"", style = MaterialTheme.typography.bodySmall)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        if (rule.autoSend) "Auto-send ON (langsung terkirim)" else "Auto-send OFF (draf ditahan)",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = rule.autoSend,
                        onCheckedChange = { v -> scope.launch { rulesStore.setAutoSend(rule.id, v) } },
                    )
                    OutlinedButton(onClick = { scope.launch { rulesStore.deleteRule(rule.id) } }) {
                        Text("Hapus")
                    }
                }
            }
        }

        // ---- add a rule ----
        Text("Tambah aturan", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = name, onValueChange = { name = it },
            label = { Text("Nama aturan (mis. \"Lagi sibuk\")") },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
        )
        OutlinedTextField(
            value = matchText, onValueChange = { matchText = it },
            label = { Text("Teks pesan mengandung… (mis. \"lagi di mana\")") },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
        )
        OutlinedTextField(
            value = sender, onValueChange = { sender = it },
            label = { Text("Pengirim mengandung… (opsional, mis. \"Budi\")") },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
        )
        OutlinedTextField(
            value = replyTemplate, onValueChange = { replyTemplate = it },
            label = { Text("Teks balasan persis (mis. \"Tuan sedang sibuk\")") },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Auto-send: ON = balasan langsung terkirim tanpa konfirmasi. " +
                    "OFF = draf ditahan, kamu yang tekan Kirim.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
            )
            Switch(checked = autoSend, onCheckedChange = { autoSend = it })
        }
        formError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        formNote?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Button(onClick = {
            scope.launch {
                val created = rulesStore.addRule(name, matchText, sender, replyTemplate, autoSend)
                if (created == null) {
                    formNote = null
                    formError = "Isi teks balasan, dan isi minimal satu: teks pesan atau pengirim."
                } else {
                    formError = null
                    formNote = "Aturan \"${created.name}\" dibuat" +
                        (if (created.autoSend) " — balasan akan langsung terkirim saat cocok." else " — balasan ditahan sebagai draf saat cocok.")
                    name = ""; matchText = ""; sender = ""; replyTemplate = ""; autoSend = false
                }
            }
        }) { Text("Tambah aturan") }
    }
}

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
 */
private val FLAG_LABELS: List<Triple<FeatureFlag, String, String>> = listOf(
    Triple(FeatureFlag.PLANNING_LOOP, "Planning loop", "Agent merencanakan langkah sendiri sebelum bertindak."),
    Triple(FeatureFlag.ORCHESTRATION, "Orchestration", "Orkestrasi multi-langkah antar agent dan skill."),
    Triple(FeatureFlag.CODE_AGENT, "Code agent", "Agent yang bisa membaca dan menulis kode."),
    Triple(FeatureFlag.WA_WATCHER, "WA watcher", "Memantau pesan WhatsApp masuk secara otomatis."),
    Triple(FeatureFlag.PARALLEL_WORKERS, "Parallel workers", "Beberapa worker berjalan bersamaan."),
    Triple(FeatureFlag.VOICE_OUTPUT, "Voice output", "Jawaban dibacakan dengan suara."),
    Triple(FeatureFlag.AUTO_SCROLL, "Auto scroll", "Layar di-scroll otomatis saat membaca konten panjang."),
    Triple(FeatureFlag.TOKOPEDIA_SKILL, "Tokopedia skill", "Skill pencarian harga di Tokopedia."),
    Triple(FeatureFlag.MEMORY_V2, "Memory v2", "Penyimpanan memori generasi baru."),
    Triple(FeatureFlag.MULTI_ENGINE, "Multi engine", "Beberapa engine AI dipakai bersamaan."),
    Triple(FeatureFlag.LAPTOP_BRIDGE, "Laptop bridge", "Terhubung ke laptop sebagai mesin tambahan."),
    Triple(FeatureFlag.REMOTE_GATEWAY, "Remote gateway", "Akses jarak jauh lewat gateway."),
    Triple(FeatureFlag.NANO_TRIAGE, "Nano triage", "Gemini Nano memilah tugas sebelum dikirim ke cloud."),
    Triple(FeatureFlag.CHAT_HISTORY, "Riwayat chat", "Percakapan Home + bubble tersimpan sampai kadaluarsa yang dipilih di Settings."),
    Triple(FeatureFlag.LOCAL_FILES, "File lokal", "Lampirkan file dari HP sebagai konteks tugas (pilih manual, tidak pernah scan storage)."),
    Triple(FeatureFlag.SENSITIVE_ALERT, "Peringatan data sensitif", "Pindai file/foto yang dilampirkan secara lokal sebelum dipakai; beri peringatan bila ada data sensitif."),
)

@Composable
fun FeatureFlagsSection(app: MobiusApp) {
    val flags = app.featureFlags
    val scope = rememberCoroutineScope()

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Fitur v6 (feature flags)", style = MaterialTheme.typography.titleMedium)
        Text(
            "Semua ON secara default. Matikan satu fitur untuk kembali ke perilaku v5 untuk fitur itu.",
            style = MaterialTheme.typography.bodySmall,
        )
        FLAG_LABELS.forEach { (flag, label, description) ->
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

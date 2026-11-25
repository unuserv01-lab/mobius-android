package com.mobius.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.mobius.core.ApprovalDecision
import com.mobius.core.ApprovalGrant
import com.mobius.core.ApprovalGrants
import com.mobius.core.ApprovalRequest
import com.mobius.core.GrantScope
import com.mobius.core.RiskTier
import kotlinx.coroutines.launch

/**
 * v8.1 — Kartu approval bertier (pola nanoMuse Sentinel: kartu approval +
 * "remember" — POLA SAJA, kode ditulis ulang sendiri).
 *
 * Satu dialog untuk SEMUA approval MOBIUS: langkah skill / planning loop
 * (lokal) maupun tugas laptop via bridge (lintas perangkat). Pola visual
 * mengikuti dialog yang sudah ada (GoogleApprovalDialog / dialog HomeScreen):
 * Material 3 AlertDialog di tema gelap, [GradientButton] untuk Setuju,
 * [TextButton] untuk Tolak. Dismiss (back / tap luar) = Tolak.
 *
 * WIRING (untuk koordinator — file ini saja tidak menampilkan dialog):
 *   HomeScreen pendingStep (dan BubbleService StepConfirm) ganti
 *   `confirmStep(description): Boolean` menjadi
 *   `confirmStepWithGrant(request): ApprovalDecision` dan tampilkan
 *   [ApprovalDialog] ini; keputusan + scope diteruskan ke
 *   Orchestrator.requestApproval (sudah ada). Lihat laporan v8.1.
 */

// ---------------------------------------------------------------------------
// Label + warna tier (pakai warna semantik yang sudah ada: sukses/hijau,
// peringatan/kuning, error/merah dari tema — tidak ada palet baru).
// ---------------------------------------------------------------------------

private fun tierLabel(tier: RiskTier, s: MobiusStrings): String = when (tier) {
    RiskTier.SAFE -> s.approval.apvTierSafe
    RiskTier.MODERATE -> s.approval.apvTierModerate
    RiskTier.SENSITIVE -> s.approval.apvTierSensitive
}

private fun tierColor(tier: RiskTier): Color = when (tier) {
    RiskTier.SAFE -> MobiusSuccess
    RiskTier.MODERATE -> MobiusWarning
    RiskTier.SENSITIVE -> Color(0xFFEF5350) // error tema (MainActivity MobiusColors)
}

private fun tierHint(tier: RiskTier, s: MobiusStrings): String = when (tier) {
    RiskTier.SAFE -> s.approval.apvHintSafe
    RiskTier.MODERATE -> s.approval.apvHintModerate
    RiskTier.SENSITIVE -> s.approval.apvHintSensitive
}

private fun scopeLabel(scope: GrantScope, s: MobiusStrings): String = when (scope) {
    GrantScope.ONCE -> s.approval.apvScopeOnce
    GrantScope.CHAT -> s.approval.apvScopeChat
    GrantScope.FOREVER -> s.approval.apvScopeForever
}

/**
 * Dialog approval bertier + pilihan grant.
 *
 * @param request permintaan approval (tier, deskripsi, asal local/laptop,
 *   daftar aksi yang ditahan untuk tugas laptop).
 * @param onDecision dipanggil tepat sekali dengan keputusan pengguna
 *   (approved + scope yang dipilih).
 */
@Composable
fun ApprovalDialog(
    request: ApprovalRequest,
    onDecision: (ApprovalDecision) -> Unit,
) {
    val s = LocalStrings.current
    var scope by remember { mutableStateOf(GrantScope.ONCE) }
    // SENSITIVE tidak pernah boleh FOREVER: bila state lama menyimpannya,
    // paksa turun ke CHAT sebelum dialog dipakai.
    if (request.tier == RiskTier.SENSITIVE && scope == GrantScope.FOREVER) {
        scope = GrantScope.CHAT
    }
    val originLabel = if (request.origin == "laptop") s.approval.apvFromLaptop else s.approval.apvOnPhone
    AlertDialog(
        onDismissRequest = { onDecision(ApprovalDecision(false, GrantScope.ONCE)) },
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(s.approval.apvTitle)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(tierColor(request.tier)),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        tierLabel(request.tier, s),
                        style = MaterialTheme.typography.labelLarge,
                        color = tierColor(request.tier),
                    )
                }
            }
        },
        text = {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(request.description, style = MaterialTheme.typography.bodyMedium)
                if (request.pendingActions.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        request.pendingActions.forEach { action ->
                            Text("• $action", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Text(
                    "${s.approval.apvTaskWord} $originLabel. ${tierHint(request.tier, s)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (request.tier != RiskTier.SAFE) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            s.approval.apvRememberTitle,
                            style = MaterialTheme.typography.titleSmall,
                        )
                        listOf(GrantScope.ONCE, GrantScope.CHAT, GrantScope.FOREVER).forEach { option ->
                            val disabled = option == GrantScope.FOREVER &&
                                request.tier == RiskTier.SENSITIVE
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                RadioButton(
                                    selected = scope == option,
                                    onClick = { if (!disabled) scope = option },
                                    enabled = !disabled,
                                )
                                Text(
                                    scopeLabel(option, s) +
                                        if (disabled) s.approval.apvScopeForeverUnavailable else "",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (disabled) {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            GradientButton(onClick = { onDecision(ApprovalDecision(true, scope)) }) {
                Text(s.approval.apvApprove)
            }
        },
        dismissButton = {
            TextButton(
                onClick = { onDecision(ApprovalDecision(false, GrantScope.ONCE)) },
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            ) { Text(s.approval.apvReject) }
        },
    )
}

/**
 * Kartu approval untuk tugas LAPTOP (hasil `awaiting_approval` bridge).
 * Membungkus [ApprovalDialog]: deskripsi + daftar aksi dari laptop,
 * tier = tier tertinggi, asal "laptop". Dipakai pemanggil
 * RemoteGateway.sendBrowserTaskAndWait (onApproval) — lihat laporan v8.1.
 */
@Composable
fun RemoteApprovalDialog(
    description: String,
    tier: RiskTier,
    pendingActions: List<String>,
    approvalId: String?,
    onDecision: (ApprovalDecision) -> Unit,
) {
    ApprovalDialog(
        request = ApprovalRequest(
            description = description,
            tier = tier,
            actionKey = null, // grant disimpan RemoteGateway per aksi, bukan di sini
            origin = "laptop",
            pendingActions = pendingActions,
            approvalId = approvalId,
        ),
        onDecision = onDecision,
    )
}

// ---------------------------------------------------------------------------
// Settings: daftar + cabut grants yang diingat (FOREVER).
// WIRING (untuk koordinator): panggil [ApprovalGrantsSection] dari
// SettingsScreen, mis. di bawah bagian "Aplikasi yang diizinkan":
//   ApprovalGrantsSection(app.orchestrator.approvalGrants)
// ---------------------------------------------------------------------------

/**
 * Bagian Settings "Persetujuan yang diingat": daftar grant FOREVER
 * (satu baris per aksi) + tombol cabut per baris + "Cabut semua".
 * Grant CHAT tidak terdaftar (hilang sendiri saat sesi berakhir) — hanya
 * jumlahnya yang ditampilkan. Tidak ada kredensial / isi pesan di sini:
 * baris hanya berisi kunci aksi ("tool:target").
 */
@Composable
fun ApprovalGrantsSection(grants: ApprovalGrants) {
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()
    val forever by grants.foreverGrantsFlow().collectAsState(initial = emptyList())
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            s.approval.apvGrantsTitle,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.tertiary,
        )
        Text(
            s.approval.apvGrantsDesc.format(grants.chatGrantCount()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (forever.isEmpty()) {
            Text(
                s.approval.apvGrantsEmpty,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            forever.forEach { grant ->
                GrantRow(grant = grant, onRevoke = {
                    scope.launch { grants.revoke(grant.actionKey) }
                })
            }
            Spacer(Modifier.height(4.dp))
            OutlinedButton(
                onClick = { scope.launch { grants.revokeAll() } },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(s.approval.apvGrantsRevokeAll) }
        }
    }
}

@Composable
private fun GrantRow(grant: ApprovalGrant, onRevoke: () -> Unit) {
    val s = LocalStrings.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.weight(1f)) {
            Text(grant.actionKey, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(
                s.approval.apvGrantsForever,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(
            onClick = onRevoke,
            colors = ButtonDefaults.textButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            ),
        ) { Text(s.approval.apvGrantsRevoke) }
    }
}

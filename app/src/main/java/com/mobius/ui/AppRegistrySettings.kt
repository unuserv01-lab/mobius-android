package com.mobius.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.mobius.MobiusApp
import com.mobius.core.AppRegistry
import com.mobius.core.InstalledApp
import kotlinx.coroutines.launch

/**
 * v7.9-tambahan — layar "Aplikasi yang diizinkan" (registry, default-deny).
 *
 * Shows every installed app that can be opened ([AppRegistry.scanInstalled]:
 * PackageManager, ACTION_MAIN + CATEGORY_LAUNCHER), one row each: launcher
 * icon + name + package + on/off toggle, plus a "Scan ulang" button.
 *
 *   - Default: apps MOBIUS already knows (KnownApps) are ON, everything else
 *     is OFF until the user turns it on. Toggling writes the explicit decision
 *     to the "app_registry" DataStore immediately.
 *   - Turning an app OFF makes every later "buka X" fail closed with the honest
 *     "belum diizinkan — aktifkan di Settings" message; turning it back ON
 *     restores launching at once (no restart).
 *   - The toggle does NOT override [ActionGate]: bank/fintech apps stay
 *     blocked by the gate even when their toggle here is ON — the footer says
 *     so, so an ON toggle on DANA/OVO is never a promise it will open.
 *
 * Navigation is local state in [SettingsScreen] (showAppRegistry boolean) —
 * MainActivity is owned by another workstream and is NOT touched. Strings are
 * hardcoded Indonesian for this screen only, the same precedent as
 * GoogleAccountSection (no MobiusStrings edit).
 *
 * LIMIT (honest): on Android 11+ the scan only lists the apps the system lets
 * this app see (see AppRegistry KDoc) — the list may be missing third-party
 * apps until a manifest <queries> intent entry is added by the coordinator.
 */
@Composable
fun AppRegistryScreen(app: MobiusApp, onBack: () -> Unit) {
    val registry = remember { AppRegistry(app.applicationContext) }
    val scope = rememberCoroutineScope()
    var apps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var allowed by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var scanning by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun load() {
        scanning = true
        error = null
        try {
            val scanned = registry.scanInstalled()
            val map = registry.allowedMap(scanned.map { it.packageName })
            apps = scanned
            allowed = map
        } catch (e: Exception) {
            error = "Gagal memindai aplikasi: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            scanning = false
        }
    }

    LaunchedEffect(Unit) { load() }

    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = onBack) { Text("Kembali") }
            Spacer(Modifier.weight(1f))
            OutlinedButton(
                onClick = { scope.launch { load() } },
                enabled = !scanning,
            ) { Text(if (scanning) "Memindai…" else "Scan ulang") }
        }

        Text(
            "Aplikasi yang diizinkan",
            style = MaterialTheme.typography.headlineSmall.copy(brush = MobiusGradientBrush),
        )
        Text(
            "MOBIUS hanya boleh membuka aplikasi yang diizinkan di sini. " +
                "Aplikasi yang sudah dikenal MOBIUS aktif secara default; sisanya mati " +
                "sampai kamu nyalakan (default-deny).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (error != null) {
            Text(
                error!!,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        if (apps.isEmpty() && !scanning) {
            Text(
                "Tidak ada aplikasi yang ditemukan. Di Android 11+, daftar ini hanya " +
                    "berisi aplikasi yang terlihat oleh sistem — aplikasi lain tetap " +
                    "mengikuti aturan default (dikenal = aktif, sisanya mati).",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.weight(1f),
        ) {
            items(apps, key = { it.packageName }) { item ->
                val on = allowed[item.packageName] ?: false
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    AppIcon(item.icon)
                    Column(Modifier.weight(1f)) {
                        Text(item.label, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            item.packageName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = on,
                        onCheckedChange = { v ->
                            allowed = allowed + (item.packageName to v)
                            scope.launch { registry.setAllowed(item.packageName, v) }
                        },
                    )
                }
            }
        }

        Text(
            "Aplikasi bank/fintech tetap tidak bisa dibuka walau tombolnya aktif — " +
                "itu batas keras keamanan (ActionGate), bukan pengaturan di sini.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Launcher icon as a Compose image (40dp), or an empty box when the icon could not load. */
@Composable
private fun AppIcon(icon: Drawable?) {
    val bitmap = remember(icon) { icon?.toBitmap() }
    if (bitmap != null) {
        Image(
            bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.size(40.dp),
        )
    } else {
        Box(Modifier.size(40.dp))
    }
}

/** Draws a Drawable into a Bitmap (falls back to 96px when it has no intrinsic size). */
private fun Drawable.toBitmap(): Bitmap? = try {
    val w = if (intrinsicWidth > 0) intrinsicWidth else 96
    val h = if (intrinsicHeight > 0) intrinsicHeight else 96
    Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bmp ->
        val canvas = Canvas(bmp)
        setBounds(0, 0, w, h)
        draw(canvas)
    }
} catch (e: Exception) {
    null
}

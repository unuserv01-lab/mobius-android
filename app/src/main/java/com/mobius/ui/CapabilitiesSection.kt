package com.mobius.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mobius.MobiusApp
import com.mobius.core.PermissionStatus

/**
 * v8 UI rework — the Capabilities card moved from Home to Settings.
 * CapabilityRow + CapabilityRowA11y are copied verbatim from MainActivity.kt
 * (same bodies, same 5 rows, same 3-state accessibility row); only the
 * package/imports changed. rememberPermissionStatus lives in
 * OnboardingScreen.kt, same package (public top-level), so no import needed.
 */
@Composable
fun CapabilitiesSection(app: MobiusApp) {
    val strings = LocalStrings.current
    val perms by rememberPermissionStatus(app.permissions)
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(strings.home.homeCapabilities, style = MaterialTheme.typography.titleMedium)
            CapabilityRow(strings.home.capBubble, perms.overlay)
            // v7.7 bug 3 — three states: OFF / ON-but-service-not-running / live.
            CapabilityRowA11y(app, perms)
            CapabilityRow(strings.home.capNotifications, perms.notificationListener)
            CapabilityRow(strings.home.capUsage, perms.usageStats)
            CapabilityRow(strings.home.capMicrophone, perms.microphone)
        }
    }
}

@Composable
private fun CapabilityRow(label: String, on: Boolean) {
    val strings = LocalStrings.current
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(
            if (on) strings.home.capOn else strings.home.capOff,
            color = if (on) MobiusSuccess else MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

/**
 * v7.7 bug 3 — accessibility is a THREE-state capability. The toggle can be
 * ON while the service itself is not running (after an APK update, or killed
 * by MIUI) — showing plain "on" for that state is what misled Tuan. The
 * mismatch state shows what to do and a button straight to system Settings.
 * The row refreshes on ON_RESUME (rememberPermissionStatus), i.e. right
 * after the user comes back from Settings.
 */
@Composable
private fun CapabilityRowA11y(app: MobiusApp, perms: PermissionStatus) {
    val strings = LocalStrings.current
    val context = LocalContext.current
    val mismatch = perms.accessibility && !perms.accessibilityLive
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            Text(
                strings.home.capAccessibility,
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                when {
                    !perms.accessibility -> strings.home.capOff
                    perms.accessibilityLive -> strings.home.capOn
                    else -> strings.capFix.capNeedsFix
                },
                color = when {
                    !perms.accessibility -> MaterialTheme.colorScheme.error
                    perms.accessibilityLive -> MobiusSuccess
                    else -> MobiusWarning
                },
                style = MaterialTheme.typography.labelLarge,
            )
        }
        if (mismatch) {
            Text(
                strings.capFix.capA11yNotRunning,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = { context.startActivity(app.permissions.accessibilitySettingsIntent()) },
            ) { Text(strings.onboardingActions.obOpenSettings) }
        }
    }
}

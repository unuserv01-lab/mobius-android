package com.mobius.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.mobius.MobiusApp
import com.mobius.core.AppConfig
import com.mobius.core.PermissionManager
import com.mobius.core.PermissionStatus
import kotlinx.coroutines.launch

/** Re-reads the permission state every time the user comes back from a system Settings screen. */
@Composable
fun rememberPermissionStatus(pm: PermissionManager): State<PermissionStatus> {
    val state = remember { mutableStateOf(pm.status()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) state.value = pm.status()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return state
}

private data class PermissionItem(
    val title: String,
    val why: String,
    val ifDenied: String,
    val granted: Boolean,
    val actionLabel: String,
    val onAction: () -> Unit,
)

/**
 * Permission onboarding (P8): explain each capability in plain language, say what happens if it is
 * denied, then send the user to the right system screen. "Continue" is always available because
 * every feature degrades gracefully.
 */
@Composable
fun OnboardingScreen(app: MobiusApp, onDone: () -> Unit) {
    val strings = LocalStrings.current
    val context = LocalContext.current
    val pm = app.permissions
    val status by rememberPermissionStatus(pm)
    val scope = rememberCoroutineScope()

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun open(intent: Intent) = context.startActivity(intent)

    val permissionItems = listOf(
        PermissionItem(
            strings.onboarding.obDrawTitle,
            strings.onboarding.obDrawWhy,
            strings.onboarding.obDrawDenied,
            status.overlay, strings.onboardingActions.obOpenSettings,
        ) { open(pm.overlaySettingsIntent()) },
        PermissionItem(
            strings.onboarding.obAccTitle,
            strings.onboarding.obAccWhy,
            strings.onboarding.obAccDenied,
            status.accessibility, strings.onboardingActions.obOpenSettings,
        ) { open(pm.accessibilitySettingsIntent()) },
        PermissionItem(
            strings.onboarding.obNotifTitle,
            strings.onboarding.obNotifWhy,
            strings.onboarding.obNotifDenied,
            status.notificationListener, strings.onboardingActions.obOpenSettings,
        ) { open(pm.notificationListenerSettingsIntent()) },
        PermissionItem(
            strings.onboarding.obUsageTitle,
            strings.onboarding.obUsageWhy,
            strings.onboarding.obUsageDenied,
            status.usageStats, strings.onboardingActions.obOpenSettings,
        ) { open(pm.usageAccessSettingsIntent()) },
        PermissionItem(
            strings.onboarding.obStatusTitle,
            strings.onboarding.obStatusWhy,
            strings.onboarding.obStatusDenied,
            status.postNotifications, strings.onboardingActions.obAllow,
        ) {
            if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        },
        PermissionItem(
            strings.onboarding.obMicTitle,
            strings.onboarding.obMicWhy,
            strings.onboarding.obMicDenied,
            status.microphone, strings.onboardingActions.obAllow,
        ) { micLauncher.launch(Manifest.permission.RECORD_AUDIO) },
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                AppConfig.APP_NAME,
                style = MaterialTheme.typography.headlineMedium.copy(brush = MobiusGradientBrush),
            )
            Text(
                AppConfig.TAGLINE,
                style = MaterialTheme.typography.bodyMedium.copy(brush = MobiusGradientBrush),
            )
            Text(
                strings.onboarding.obIntro,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        items(permissionItems) { item -> PermissionCard(item) }
        item {
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(strings.onboardingActions.obApkTitle, style = MaterialTheme.typography.titleSmall)
                    Text(strings.onboardingActions.obApkBody, style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { open(pm.appDetailsIntent()) }) { Text(strings.onboardingActions.obApkButton) }
                }
            }
        }
        item {
            GradientButton(
                onClick = { scope.launch { pm.setOnboardingDone(true); onDone() } },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(strings.onboardingActions.obContinue) }
        }
    }
}

@Composable
private fun PermissionCard(item: PermissionItem) {
    val strings = LocalStrings.current
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(
                    if (item.granted) strings.onboardingActions.obGranted else strings.onboardingActions.obNotGranted,
                    color = if (item.granted) MobiusSuccess else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Text(item.why, style = MaterialTheme.typography.bodySmall)
            if (!item.granted) {
                Text(strings.onboardingActions.obSkipPrefix + item.ifDenied, style = MaterialTheme.typography.bodySmall)
                GradientButton(onClick = item.onAction) { Text(item.actionLabel) }
            }
        }
    }
}

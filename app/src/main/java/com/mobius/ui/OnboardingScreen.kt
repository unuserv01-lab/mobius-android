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
import androidx.compose.material3.Button
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
    val context = LocalContext.current
    val pm = app.permissions
    val status by rememberPermissionStatus(pm)
    val scope = rememberCoroutineScope()

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun open(intent: Intent) = context.startActivity(intent)

    val permissionItems = listOf(
        PermissionItem(
            "Draw over other apps",
            "Shows the floating bubble on top of other apps so you can call ${AppConfig.APP_NAME} from anywhere.",
            "The bubble is hidden. You can still use ${AppConfig.APP_NAME} from the app itself.",
            status.overlay, "Open settings",
        ) { open(pm.overlaySettingsIntent()) },
        PermissionItem(
            "Accessibility service",
            "Lets ${AppConfig.APP_NAME} read the screen and tap or type in other apps, only when you ask it to run a task.",
            "Cross-app tasks are disabled. AI chat still works.",
            status.accessibility, "Open settings",
        ) { open(pm.accessibilitySettingsIntent()) },
        PermissionItem(
            "Notification access",
            "Lets ${AppConfig.APP_NAME} read incoming notifications (for example the last WhatsApp message). They stay in memory for 10 minutes and are never saved or uploaded by themselves.",
            "Tasks that need a notification (like replying to WhatsApp) are disabled.",
            status.notificationListener, "Open settings",
        ) { open(pm.notificationListenerSettingsIntent()) },
        PermissionItem(
            "Usage access",
            "Lets ${AppConfig.APP_NAME} know which app is in front, to understand context.",
            "Context falls back to the time and notifications only.",
            status.usageStats, "Open settings",
        ) { open(pm.usageAccessSettingsIntent()) },
        PermissionItem(
            "Notifications",
            "Shows the small status notice while ${AppConfig.APP_NAME} is running.",
            "No status notification (the assistant still works).",
            status.postNotifications, "Allow",
        ) {
            if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        },
        PermissionItem(
            "Microphone",
            "Lets you speak a request into the bubble instead of typing (Android speech recognition, on-device where available).",
            "Voice input is unavailable; typing still works.",
            status.microphone, "Allow",
        ) { micLauncher.launch(Manifest.permission.RECORD_AUDIO) },
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(AppConfig.APP_NAME, style = MaterialTheme.typography.headlineMedium)
            Text(AppConfig.TAGLINE, style = MaterialTheme.typography.bodyMedium)
            Text(
                "Everything below is opt-in. You stay in control and can revoke any of it at any time.",
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        items(permissionItems) { item -> PermissionCard(item) }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Installed from an APK file?", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "On Android 13+, Accessibility and Notification access can be greyed out for apps " +
                            "installed outside the Play Store. Open App info, tap the three-dot menu and choose " +
                            "\"Allow restricted settings\", then try again.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(onClick = { open(pm.appDetailsIntent()) }) { Text("Open App info") }
                }
            }
        }
        item {
            Button(
                onClick = { scope.launch { pm.setOnboardingDone(true); onDone() } },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Continue") }
        }
    }
}

@Composable
private fun PermissionCard(item: PermissionItem) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(
                    if (item.granted) "Granted" else "Not granted",
                    color = if (item.granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Text(item.why, style = MaterialTheme.typography.bodySmall)
            if (!item.granted) {
                Text("If you skip: ${item.ifDenied}", style = MaterialTheme.typography.bodySmall)
                Button(onClick = item.onAction) { Text(item.actionLabel) }
            }
        }
    }
}

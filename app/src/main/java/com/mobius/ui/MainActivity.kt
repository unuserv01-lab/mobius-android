package com.mobius.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mobius.MobiusApp
import com.mobius.bubble.BubbleService
import com.mobius.core.AppConfig
import com.mobius.core.TaskHooks
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch

private val MobiusColors: ColorScheme = darkColorScheme(
    primary = Color(0xFF7C9CFF),
    onPrimary = Color(0xFF0B1030),
    background = Color(0xFF0F1115),
    surface = Color(0xFF151922),
    surfaceVariant = Color(0xFF1E2430),
    onBackground = Color(0xFFE6E8EE),
    onSurface = Color(0xFFE6E8EE),
    onSurfaceVariant = Color(0xFFB8BECC),
    tertiary = Color(0xFFFFB86B),
)

/** Shared by the app screens and the overlay windows. */
@Composable
fun MobiusTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = MobiusColors, content = content)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MobiusTheme { MobiusRoot() } }
    }
}

@Composable
private fun MobiusRoot() {
    val app = LocalContext.current.applicationContext as MobiusApp
    val onboardingDone by app.permissions.onboardingDone.collectAsState(initial = null)
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        when (onboardingDone) {
            null -> Unit // still reading preferences
            false -> OnboardingScreen(app) { }
            true -> MainTabs(app)
        }
    }
}

@Composable
private fun MainTabs(app: MobiusApp) {
    var tab by remember { mutableStateOf(0) }
    val labels = listOf("Home", "History", "Settings")
    Scaffold(
        bottomBar = {
            NavigationBar {
                labels.forEachIndexed { index, label ->
                    NavigationBarItem(
                        selected = tab == index,
                        onClick = { tab = index },
                        icon = { Text(listOf("⌂", "⧖", "⚙")[index]) },
                        label = { Text(label) },
                    )
                }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (tab) {
                0 -> HomeScreen(app)
                1 -> HistoryScreen(app)
                else -> SettingsScreen(app, onRerunSetup = { })
            }
        }
    }
}

/**
 * Launcher-side entry point. It uses the SAME Orchestrator as the bubble, so MOBIUS works even when
 * the overlay permission is denied ("app works from launcher only").
 */
@Composable
private fun HomeScreen(app: MobiusApp) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val perms by rememberPermissionStatus(app.permissions)
    val bubbleOn by app.permissions.bubbleEnabled.collectAsState(initial = false)

    var input by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }
    var pendingConfirm by remember { mutableStateOf<Triple<String, String, CompletableDeferred<Boolean>>?>(null) }

    val hooks = remember {
        object : TaskHooks {
            override fun status(message: String) { status = message }
            override suspend fun confirmSend(sender: String, draft: String): Boolean {
                val decision = CompletableDeferred<Boolean>()
                pendingConfirm = Triple(sender, draft, decision)
                return decision.await().also { pendingConfirm = null }
            }
        }
    }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(AppConfig.APP_NAME, style = MaterialTheme.typography.headlineMedium)
        Text(AppConfig.TAGLINE, style = MaterialTheme.typography.bodyMedium)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Floating bubble", style = MaterialTheme.typography.titleMedium)
                if (!perms.overlay) {
                    Text(
                        "Draw-over-apps is not granted, so the bubble is hidden. You can still run tasks below.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (bubbleOn && BubbleService.running) {
                    OutlinedButton(onClick = {
                        BubbleService.stop(context)
                        scope.launch { app.permissions.setBubbleEnabled(false) }
                    }) { Text("Stop bubble") }
                } else {
                    Button(onClick = {
                        scope.launch { app.permissions.setBubbleEnabled(true) }
                        BubbleService.start(context)
                    }) { Text("Start bubble") }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Ask ${AppConfig.APP_NAME}", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("e.g. Reply to the last WhatsApp message") },
                )
                Button(
                    enabled = input.isNotBlank() && !running,
                    onClick = {
                        running = true
                        result = null
                        val text = input
                        scope.launch {
                            try {
                                val outcome = app.orchestrator.submit(text, hooks)
                                result = outcome.message
                            } catch (e: Exception) {
                                // submit() already converts AI failures into outcomes, but never
                                // let a stray exception kill the app from this button.
                                result = "Something went wrong: ${e.message ?: e.javaClass.simpleName}"
                            } finally {
                                status = null
                                running = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (running) "Working..." else "Run") }
                status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                result?.let { Text(it) }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Capabilities", style = MaterialTheme.typography.titleMedium)
                CapabilityRow("Bubble overlay", perms.overlay)
                CapabilityRow("Cross-app actions (Accessibility)", perms.accessibility)
                CapabilityRow("Notifications (WhatsApp reading)", perms.notificationListener)
                CapabilityRow("Foreground-app context (Usage access)", perms.usageStats)
                CapabilityRow("Microphone (voice input)", perms.microphone)
            }
        }
    }

    pendingConfirm?.let { (sender, draft, decision) ->
        AlertDialog(
            onDismissRequest = { decision.complete(false) },
            title = { Text("Send this reply to $sender?") },
            text = { Text(draft) },
            confirmButton = { TextButton(onClick = { decision.complete(true) }) { Text("Send") } },
            dismissButton = { TextButton(onClick = { decision.complete(false) }) { Text("Don't send") } },
        )
    }
}

@Composable
private fun CapabilityRow(label: String, on: Boolean) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(
            if (on) "on" else "off",
            color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

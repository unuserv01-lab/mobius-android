package com.mobius.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mobius.MobiusApp
import kotlinx.coroutines.launch

/**
 * v8 — fullscreen chat history (read-only, no input here).
 * The conversation itself lives in Home / the bubble; this screen only
 * shows it and offers a fresh start.
 */
@Composable
fun ChatHistoryScreen(app: MobiusApp, onBack: () -> Unit) {
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    val chatMessages by app.memoryStore.observeChatMessages().collectAsState(initial = emptyList())

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // v8.1-hotfix bug 2 — back arrow at the top (replaces the old title:
        // the bar already shows it, so it is not repeated below).
        BackTopBar(title = strings.homeModel.chatHistoryTitle, onBack = onBack)
        if (chatMessages.isEmpty()) {
            Text(
                strings.panel.chatEmpty,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
        } else {
            ChatHistoryView(
                messages = chatMessages,
                modifier = Modifier.weight(1f),
            )
        }
        GradientButton(
            onClick = { scope.launch { app.memoryStore.clearChat() } },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(strings.drawer.drawerNewChat)
        }
    }
}

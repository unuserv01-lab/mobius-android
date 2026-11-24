package com.mobius.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mobius.core.ChatMessage

/**
 * v6 workstream M-UI — ONE shared message list for Home and the bubble.
 *
 * Fed by `app.memoryStore.observeChatMessages()` (oldest first, createdAt
 * ASC); user turns on the right-ish "Anda" card, assistant turns on the
 * "MOBIUS" card. The list auto-scrolls to the newest message. It shows
 * ONLY what [messages] contains — recording happens at the submit/outcome
 * points in HomeScreen and BubbleService, and is a no-op in MemoryStore
 * when the CHAT_HISTORY flag is OFF, so callers show this view only when
 * the flag is ON. Rows expire only via the memory TTL setting; nothing
 * here ever deletes history early.
 */
@Composable
fun ChatHistoryView(messages: List<ChatMessage>, modifier: Modifier = Modifier) {
    if (messages.isEmpty()) {
        Text(
            "Belum ada percakapan.",
            style = MaterialTheme.typography.bodySmall,
            modifier = modifier,
        )
        return
    }
    val scrollState = rememberScrollState()
    LaunchedEffect(messages.size) {
        scrollState.scrollTo(scrollState.maxValue)
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 320.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        messages.forEach { msg ->
            val isUser = msg.role == "user"
            Surface(
                shape = MaterialTheme.shapes.small,
                color = if (isUser) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(8.dp)) {
                    Text(
                        if (isUser) "Anda" else "MOBIUS",
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Text(msg.text, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

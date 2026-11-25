package com.mobius.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.mobius.R
import com.mobius.core.ChatMessage

/**
 * v6 workstream M-UI — ONE shared message list for Home and the bubble.
 *
 * Fed by `app.memoryStore.observeChatMessages()` (oldest first, createdAt
 * ASC); user turns on the right, assistant turns on the left. The list
 * auto-scrolls to the newest message. It shows ONLY what [messages]
 * contains — recording happens at the submit/outcome points in HomeScreen
 * and BubbleService, and is a no-op in MemoryStore when the CHAT_HISTORY
 * flag is OFF, so callers show this view only when the flag is ON. Rows
 * expire only via the memory TTL setting; nothing here ever deletes
 * history early.
 *
 * v7.6 — mockup bubbles (Tuan's order): the two sides must be instantly
 * distinguishable by alignment AND background AND avatar. User = right,
 * logo-gradient bubble, white text, thin white stroke. Agent = left,
 * MOBIUS loop avatar + dark card with a firm glow stroke. The borders
 * stay fully opaque even when the bubble panel behind them is
 * semi-transparent — contrast never drops.
 */
@Composable
fun ChatHistoryView(messages: List<ChatMessage>, modifier: Modifier = Modifier) {
    val strings = LocalStrings.current
    if (messages.isEmpty()) {
        Text(
            strings.chatEmpty,
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
            if (msg.role == "user") {
                // User: right-aligned, gradient bubble, white text.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Box(
                        modifier = Modifier
                            .widthIn(max = 280.dp)
                            .background(
                                brush = MobiusGradientBrush,
                                shape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp),
                            )
                            .border(
                                width = 1.dp,
                                color = Color.White.copy(alpha = 0.35f),
                                shape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp),
                            )
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Column {
                            Text(
                                strings.chatYou,
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.85f),
                            )
                            Text(
                                msg.text,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White,
                            )
                        }
                    }
                }
            } else {
                // Agent: left-aligned, loop avatar + dark card, firm glow stroke.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Start,
                    verticalAlignment = Alignment.Top,
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(MobiusGradientBrush),
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_launcher_foreground),
                            contentDescription = "MOBIUS",
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        shape = RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            MobiusGlow.copy(alpha = 0.7f),
                        ),
                        modifier = Modifier.widthIn(max = 280.dp),
                    ) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Text(
                                "MOBIUS",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                            Text(
                                msg.text,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }
    }
}

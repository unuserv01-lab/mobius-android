package com.mobius.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
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
 *
 * v8.0 — WhatsApp-style theme ("Thema WQ"): same data flow and roles,
 * only the look changed. User = right, gradient bubble with a small
 * bottom-right corner + a real triangle tail at the bottom-right.
 * Agent = left, loop avatar + "MOBIUS" label + surfaceVariant card with
 * glow stroke, small bottom-left corner + a real triangle tail at the
 * bottom-left. Bubbles are at most ~80% of the available width, with
 * 8 dp between them and 12 dp of side padding.
 */
@Composable
fun ChatHistoryView(
    messages: List<ChatMessage>,
    modifier: Modifier = Modifier,
    // v8 — Home passes Dp.Unspecified so the list stretches to fill the
    // space above the input pill; the bubble panel keeps the 320 dp cap.
    maxHeight: Dp = 320.dp,
) {
    val strings = LocalStrings.current
    if (messages.isEmpty()) {
        Text(
            strings.panel.chatEmpty,
            style = MaterialTheme.typography.bodySmall,
            modifier = modifier,
        )
        return
    }
    val scrollState = rememberScrollState()
    LaunchedEffect(messages.size) {
        scrollState.scrollTo(scrollState.maxValue)
    }
    // BoxWithConstraints so the ~80% cap follows the real width in BOTH
    // callers (Home's full-width list and the narrower bubble panel).
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val maxBubbleWidth = maxWidth * 0.8f
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                .verticalScroll(scrollState)
                // Bottom 8 dp leaves room for the last bubble's tail, which
                // pokes 7 dp below its bubble (see BubbleTail).
                .padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            messages.forEach { msg ->
                if (msg.role == "user") {
                    // User: right-aligned, gradient bubble, white text.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        Box {
                            Box(
                                modifier = Modifier
                                    .widthIn(max = maxBubbleWidth)
                                    .background(
                                        brush = MobiusGradientBrush,
                                        shape = UserBubbleShape,
                                    )
                                    .border(
                                        width = 1.dp,
                                        color = Color.White.copy(alpha = 0.35f),
                                        shape = UserBubbleShape,
                                    )
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                            ) {
                                Column {
                                    Text(
                                        strings.panel.chatYou,
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
                            // Tail: gradient ends in #7C4DFF at the
                            // bottom-right, so a solid tail in that color
                            // blends into the bubble.
                            BubbleTail(
                                fill = Color(0xFF7C4DFF),
                                stroke = Color.White.copy(alpha = 0.35f),
                                agentSide = false,
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .offset(y = 7.dp),
                            )
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
                        Box {
                            Surface(
                                shape = AgentBubbleShape,
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    MobiusGlow.copy(alpha = 0.7f),
                                ),
                                modifier = Modifier.widthIn(max = maxBubbleWidth),
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
                            BubbleTail(
                                fill = MaterialTheme.colorScheme.surfaceVariant,
                                stroke = MobiusGlow.copy(alpha = 0.7f),
                                agentSide = true,
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .offset(y = 7.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** User bubble: every corner round except the small bottom-right (tail side). */
private val UserBubbleShape = RoundedCornerShape(
    topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 4.dp,
)

/** Agent bubble: every corner round except the small bottom-left (tail side). */
private val AgentBubbleShape = RoundedCornerShape(
    topStart = 16.dp, topEnd = 16.dp, bottomStart = 4.dp, bottomEnd = 16.dp,
)

/**
 * v8.0 — WhatsApp-style tail: a real small triangle drawn under the
 * bubble's bottom corner (user: bottom-right, agent: bottom-left). It
 * overlaps the bubble by 1 dp so there is no seam, and its two visible
 * edges carry the same 1 dp stroke as the bubble itself. The tail is
 * positioned with an offset, so it does not change the bubble's measured
 * size — it draws into the 8 dp gap below the bubble.
 */
@Composable
private fun BubbleTail(
    fill: Color,
    stroke: Color,
    agentSide: Boolean,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.size(12.dp, 8.dp)) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            if (agentSide) {
                // Left tail: right angle at the top-right, point at bottom-left.
                moveTo(0f, 0f)
                lineTo(w, 0f)
                lineTo(0f, h)
            } else {
                // Right tail: right angle at the top-left, point at bottom-right.
                moveTo(0f, 0f)
                lineTo(w, 0f)
                lineTo(w, h)
            }
            close()
        }
        drawPath(path, fill)
        // Stroke only the two visible edges (hypotenuse + outer vertical
        // edge); the top edge sits inside the bubble.
        val strokeWidth = 1.dp.toPx()
        if (agentSide) {
            drawLine(stroke, Offset(w, 0f), Offset(0f, h), strokeWidth)
            drawLine(stroke, Offset(0f, 0f), Offset(0f, h), strokeWidth)
        } else {
            drawLine(stroke, Offset(0f, 0f), Offset(w, h), strokeWidth)
            drawLine(stroke, Offset(w, 0f), Offset(w, h), strokeWidth)
        }
    }
}

package com.mobius.bubble

import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobius.R
import com.mobius.ui.LocalStrings
import com.mobius.ui.MobiusGlow
import com.mobius.ui.MobiusGradientBrush
import kotlin.math.abs

/**
 * The floating bubble: the ENTRY POINT only (P7). It shows the product mark and, below it, a short
 * status chip while a task runs. Touch handling (drag + tap) lives in [DragTouchListener].
 *
 * v6 (G): while the auto-scroll loop runs, a visible Stop button sits under
 * the bubble — tapping it stops the loop. The bubble itself is a separate
 * overlay window from the panel, so the Stop button stays reachable while
 * the user reads another app.
 *
 * v7 — bubble follows the LOGO's color DNA (Tuan's order): the actual logo
 * image as the mark (no text glyph), on the purple-blue gradient with the
 * accent glow. A progress ring spins over the logo while a task runs.
 * v7 — unread-reply badge: a small accent circle ABOVE the chip with the
 * count of agent replies that arrived while the panel was closed ("9+"
 * over 9). Tapping the bubble/badge opens the panel and BubbleService
 * resets the count to 0.
 */
@Composable
fun BubbleView(
    status: String?,
    working: Boolean,
    autoScrolling: Boolean = false,
    onStopAutoScroll: () -> Unit = {},
    unreadCount: Int = 0,
) {
    val strings = LocalStrings.current
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(4.dp)) {
        if (unreadCount > 0) {
            Row(modifier = Modifier.width(56.dp), horizontalArrangement = Arrangement.End) {
                Box(
                    modifier = Modifier
                        .sizeIn(minWidth = 28.dp, minHeight = 28.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.tertiary),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (unreadCount > 9) "9+" else unreadCount.toString(),
                        color = MaterialTheme.colorScheme.onTertiary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
        }
        Box(
            modifier = Modifier
                .size(56.dp)
                .shadow(12.dp, CircleShape, spotColor = MobiusGlow)
                .clip(CircleShape)
                .background(MobiusGradientBrush),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_launcher_foreground),
                contentDescription = "MOBIUS",
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape),
            )
            if (working) {
                CircularProgressIndicator(
                    color = Color.White,
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(56.dp),
                )
            }
        }
        if (status != null) {
            Spacer(Modifier.height(4.dp))
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.93f),
                modifier = Modifier.widthIn(max = 220.dp),
            ) {
                Text(
                    text = status,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 12.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(8.dp),
                )
            }
        }
        if (autoScrolling) {
            Spacer(Modifier.height(4.dp))
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.error.copy(alpha = 0.9f),
                modifier = Modifier
                    .widthIn(max = 220.dp)
                    .clickable(onClick = onStopAutoScroll),
            ) {
                Text(
                    text = strings.inputAutoStop,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/**
 * Drag-to-move + tap-to-open for the overlay window. Uses raw screen coordinates, because the
 * window itself moves while dragging (window-local coordinates would jitter).
 */
class DragTouchListener(
    private val params: WindowManager.LayoutParams,
    private val windowManager: WindowManager,
    private val slopPx: Int,
    private val onTap: () -> Unit,
) : View.OnTouchListener {
    private var startX = 0
    private var startY = 0
    private var touchX = 0f
    private var touchY = 0f
    private var moved = false

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = params.x
                startY = params.y
                touchX = event.rawX
                touchY = event.rawY
                moved = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (event.rawX - touchX).toInt()
                val dy = (event.rawY - touchY).toInt()
                if (abs(dx) > slopPx || abs(dy) > slopPx) moved = true
                if (moved) {
                    params.x = startX + dx
                    params.y = startY + dy
                    windowManager.updateViewLayout(v, params)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!moved) {
                    v.performClick()
                    onTap()
                }
                return true
            }
        }
        return false
    }
}

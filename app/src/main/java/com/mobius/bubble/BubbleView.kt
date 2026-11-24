package com.mobius.bubble

import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

/**
 * The floating bubble: the ENTRY POINT only (P7). It shows the product mark and, below it, a short
 * status chip while a task runs. Touch handling (drag + tap) lives in [DragTouchListener].
 *
 * v6 (G): while the auto-scroll loop runs, a visible Stop button sits under
 * the bubble — tapping it stops the loop. The bubble itself is a separate
 * overlay window from the panel, so the Stop button stays reachable while
 * the user reads another app.
 */
@Composable
fun BubbleView(
    status: String?,
    working: Boolean,
    autoScrolling: Boolean = false,
    onStopAutoScroll: () -> Unit = {},
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(4.dp)) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(Brush.radialGradient(listOf(Color(0xFF7C9CFF), Color(0xFF3D5AFE)))),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (working) "..." else "∞", // infinity sign
                color = Color.White,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        if (status != null) {
            Spacer(Modifier.height(4.dp))
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color(0xEE1B1F2A),
                modifier = Modifier.widthIn(max = 220.dp),
            ) {
                Text(
                    text = status,
                    color = Color.White,
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
                color = Color(0xEE7C1F1F),
                modifier = Modifier
                    .widthIn(max = 220.dp)
                    .clickable(onClick = onStopAutoScroll),
            ) {
                Text(
                    text = "Stop auto-scroll",
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

package com.mobius.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mobius.MobiusApp
import com.mobius.ai.AIRouter
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val timeFormat = SimpleDateFormat("MMM d, HH:mm:ss", Locale.getDefault())
private fun fmt(ms: Long) = timeFormat.format(Date(ms))

/**
 * v8 — moved verbatim from HistoryScreen (deleted): past tasks + stored
 * memory (with delete) and the AI routing log. Every cloud call and every
 * blocked cloud call shows up here.
 */
@Composable
fun LogScreen(app: MobiusApp, onBack: () -> Unit) {
    val strings = LocalStrings.current
    val memory by app.memoryStore.observeMemory().collectAsState(initial = emptyList())
    val routing by app.memoryStore.observeRoutingLog().collectAsState(initial = emptyList())
    val trail by app.memoryStore.observeTaskTrail().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    // Show only live data: purge anything that expired while this screen was closed.
    LaunchedEffect(Unit) { app.memoryStore.purgeExpired() }

    // v8.1-hotfix bug 2 — the back arrow stays fixed at the top; the log
    // list scrolls below it.
    Column(Modifier.fillMaxSize()) {
        BackTopBar(title = strings.drawer.drawerLog, onBack = onBack)
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
        // (v5) Skill task step trail: latest tasks first, steps marked ✓/✗ + duration,
        // plus the plan decision and every ActionGate decision.
        item {
            Text(
                strings.history.histTrailTitle,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        val trailGroups = trail.groupBy { it.taskId }.entries.take(5)
        if (trailGroups.isEmpty()) {
            item { Text(strings.history.histTrailEmpty, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        for ((taskId, rows) in trailGroups) {
            item {
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        val head = rows.first()
                        Text(
                            "${head.skillName} — \"${head.userText.take(40)}\"",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            "task $taskId - ${fmt(head.createdAt)}",
                            style = MaterialTheme.typography.labelSmall,
                        )
                        for (row in rows.sortedBy { it.id }) {
                            when (row.tool) {
                                "plan" -> Text(
                                    if (row.success) strings.history.histPlanApproved else strings.history.histPlanCancelled,
                                    color = if (row.success) MobiusSuccess
                                        else MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                "gate" -> Text(
                                    "GATE ${row.gateDecision}: ${row.message}",
                                    color = if (row.success) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                else -> Text(
                                    "${if (row.success) "✓" else "✗"} ${row.stepIndex + 1}. ${row.tool} — " +
                                        "${row.message.take(120)} (${row.durationMs}ms)",
                                    color = if (row.success) MobiusSuccess
                                        else MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            Text(
                strings.history.histRoutingTitle,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
        if (routing.isEmpty()) {
            item { Text(strings.history.histRoutingEmpty, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        items(routing, key = { "log-${it.id}" }) { entry ->
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(16.dp)) {
                    val color = when (entry.model) {
                        AIRouter.MODEL_CLOUD -> MaterialTheme.colorScheme.secondary
                        AIRouter.MODEL_BLOCKED -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.primary
                    }
                    Text(entry.model, color = color, style = MaterialTheme.typography.titleSmall)
                    Text(entry.reason, style = MaterialTheme.typography.bodyMedium)
                    Text("task ${entry.taskId} - ${fmt(entry.createdAt)}", style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        item {
            Text(
                strings.history.histMemoryTitle,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
        if (memory.isEmpty()) {
            item { Text(strings.history.histMemoryEmpty, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        items(memory, key = { "mem-${it.id}" }) { entry ->
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(vertical = 12.dp)) {
                        Text(entry.content, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${entry.type} - ${fmt(entry.createdAt)} - expires ${fmt(entry.expiresAt)}",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    TextButton(onClick = { scope.launch { app.memoryStore.delete(entry.id) } }) {
                        Text(strings.history.histDelete)
                    }
                }
            }
        }
        }
    }
}

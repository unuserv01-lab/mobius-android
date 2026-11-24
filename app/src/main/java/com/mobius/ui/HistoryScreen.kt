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
 * Past tasks + stored memory (with delete) and the AI routing log. Every cloud call and every
 * blocked cloud call shows up here.
 */
@Composable
fun HistoryScreen(app: MobiusApp) {
    val memory by app.memoryStore.observeMemory().collectAsState(initial = emptyList())
    val routing by app.memoryStore.observeRoutingLog().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    // Show only live data: purge anything that expired while this screen was closed.
    LaunchedEffect(Unit) { app.memoryStore.purgeExpired() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { Text("AI routing log", style = MaterialTheme.typography.titleLarge) }
        if (routing.isEmpty()) {
            item { Text("No AI calls yet.", style = MaterialTheme.typography.bodySmall) }
        }
        items(routing, key = { "log-${it.id}" }) { entry ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    val color = when (entry.model) {
                        AIRouter.MODEL_CLOUD -> MaterialTheme.colorScheme.tertiary
                        AIRouter.MODEL_BLOCKED -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.primary
                    }
                    Text(entry.model, color = color, style = MaterialTheme.typography.titleSmall)
                    Text(entry.reason, style = MaterialTheme.typography.bodyMedium)
                    Text("task ${entry.taskId} - ${fmt(entry.createdAt)}", style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        item { Text("Past tasks and memory", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 16.dp)) }
        if (memory.isEmpty()) {
            item { Text("Nothing stored.", style = MaterialTheme.typography.bodySmall) }
        }
        items(memory, key = { "mem-${it.id}" }) { entry ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(vertical = 12.dp)) {
                        Text(entry.content, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${entry.type} - ${fmt(entry.createdAt)} - expires ${fmt(entry.expiresAt)}",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    TextButton(onClick = { scope.launch { app.memoryStore.delete(entry.id) } }) { Text("Delete") }
                }
            }
        }
    }
}

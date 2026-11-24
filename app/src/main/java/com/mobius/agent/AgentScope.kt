package com.mobius.agent

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/**
 * Lifecycle of one subagent: start -> run -> cancel -> cleanup (P2, P6).
 * The coroutine scope exists only for one task. [runAndDestroy] ALWAYS tears everything down in
 * `finally`, whether the task succeeded, failed or was cancelled by the caller.
 */
class AgentScope(val taskId: String) {
    private val job = SupervisorJob()
    val coroutineScope = CoroutineScope(job + Dispatchers.Default + CoroutineName("agent-$taskId"))
    val temp = TempStore()

    @Volatile
    var alive: Boolean = true
        private set

    suspend fun <T> runAndDestroy(block: suspend CoroutineScope.() -> T): T =
        try {
            coroutineScope.async { block() }.await()
        } finally {
            destroy()
        }

    /** Cancels every child coroutine and clears temp data. Safe to call more than once. */
    fun destroy() {
        if (!alive) return
        alive = false
        job.cancel()
        temp.clear()
    }
}

package com.mobius.core

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.InputStream

/** Result of one command run: stdout/stderr are capped at [CommandRunner.MAX_OUTPUT_CHARS]. */
data class CommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    /** True when the process was killed for exceeding [CommandRunner.TIMEOUT_MS]. */
    val timedOut: Boolean,
)

/**
 * v6 CodeAgent — runs a script the agent just wrote, nothing else.
 *
 * COMMAND ALLOWLIST (the whole list — there is nothing else):
 *   - `sh <scoped-script-path>` and nothing else. Exactly one argument, which must be
 *     a `.sh` file inside the scoped directory ([ScopedFileStore]).
 *
 * Enforcement, in [validate] (pure, no I/O — also used by [ActionGate.checkCommand]):
 *   - The command must be exactly "sh"; any other command is DENIED (that is how
 *     destructive commands — rm, dd, su, sudo — and network commands — curl, wget,
 *     nc, ssh — are refused: they are simply not on the list).
 *   - No shell is ever involved: the process is started with ProcessBuilder's argv
 *     list, so shell metacharacters (`; & | > < $ \`` quotes, newlines) and
 *     pipes/redirects have no shell to interpret them — and they are rejected outright
 *     in the command and in every argument anyway.
 *   - The script argument must be a relative `.sh` path inside the scoped directory;
 *     absolute paths and ".." traversal are rejected here and again canonically by
 *     [ScopedFileStore.resolve] at run time.
 *   - The script's own text is scanned for banned whole words (network tools and
 *     destructive/privilege commands: curl, wget, nc, ncat, telnet, ssh, ftp, su,
 *     sudo, rm, dd, mkfs). This is a guardrail, not a sandbox: the script still runs
 *     as the app's own UID inside the app sandbox, so it cannot touch other apps'
 *     data or gain root — but it must not even try.
 *
 * Runtime: [TIMEOUT_MS] (30 s) wall clock, then the process is killed
 * ([Process.destroyForcibly]) and [CommandResult.timedOut] is true. Stdout/stderr are
 * captured separately and capped, so a runaway script cannot fill memory.
 *
 * v8.1 — EXECUTION COORDINATOR (pattern only, from OpenMinis' serial execution
 * coordinator — GPLv3, no code copied): all command runs go through ONE
 * process-wide FIFO ([queueMutex]). The Tool layer builds one CommandRunner
 * per task, so a per-instance lock would NOT serialize across parallel
 * workers/tasks — the shared companion mutex does. A queued caller simply
 * waits for the running command (at most [TIMEOUT_MS]); there is no
 * preemption and no priority, by design — commands here are short scripts,
 * not long jobs. Validation, allowlist and output caps are unchanged.
 */
class CommandRunner(private val store: ScopedFileStore) {

    /**
     * Runs [command] with [args] after [validate]; never throws.
     * Serialized through the shared FIFO: one command runs at a time.
     */
    suspend fun run(command: String, args: List<String>): CommandResult =
        queueMutex.withLock { runInternal(command, args) }

    private suspend fun runInternal(command: String, args: List<String>): CommandResult {
        validate(command, args)?.let { reason ->
            return CommandResult(-1, "", "Command denied: $reason", false)
        }
        val scriptArg = args[0]
        val scriptFile = store.resolve(scriptArg)
            ?: return CommandResult(-1, "", "Command denied: script '$scriptArg' is outside the scoped directory.", false)
        if (!scriptFile.isFile) {
            return CommandResult(-1, "", "Script '$scriptArg' does not exist in the scoped directory.", false)
        }
        val scriptText = try {
            scriptFile.readText()
        } catch (e: Exception) {
            return CommandResult(-1, "", "Could not read script '$scriptArg': ${e.message}", false)
        }
        findBannedWord(scriptText)?.let { word ->
            return CommandResult(
                -1, "", "Command denied: script contains banned command '$word' (network/destructive commands are not allowed).", false,
            )
        }

        return withContext(Dispatchers.IO) {
            val process = try {
                ProcessBuilder("sh", scriptFile.absolutePath)
                    .directory(store.baseDir)
                    .redirectErrorStream(false)
                    .start()
            } catch (e: Exception) {
                return@withContext CommandResult(-1, "", "Failed to start command: ${e.message}", false)
            }
            // Drain both streams concurrently so a chatty script cannot block on a full pipe.
            val outDeferred = async { readCapped(process.inputStream) }
            val errDeferred = async { readCapped(process.errorStream) }
            // Poll for exit (blocking waitFor would ignore the timeout); kill on timeout.
            val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
            var timedOut = false
            while (process.isAlive) {
                if (SystemClock.elapsedRealtime() >= deadline) {
                    timedOut = true
                    process.destroyForcibly()
                    break
                }
                delay(100)
            }
            val exitCode = try {
                process.waitFor()
            } catch (e: Exception) {
                -1
            }
            CommandResult(
                exitCode = if (timedOut) -1 else exitCode,
                stdout = runCatching { outDeferred.await() }.getOrDefault(""),
                stderr = runCatching { errDeferred.await() }.getOrDefault(""),
                timedOut = timedOut,
            )
        }
    }

    /** Reads at most [MAX_OUTPUT_CHARS] characters; stops at EOF. */
    private fun readCapped(stream: InputStream): String {
        val reader = stream.bufferedReader()
        val sb = StringBuilder()
        val buf = CharArray(8192)
        try {
            while (sb.length < MAX_OUTPUT_CHARS) {
                val n = reader.read(buf, 0, minOf(buf.size, MAX_OUTPUT_CHARS - sb.length))
                if (n <= 0) break
                sb.append(buf, 0, n)
            }
        } catch (e: Exception) {
            // Stream closed by a killed process: return what was captured.
        }
        return sb.toString()
    }

    companion object {
        /**
         * v8.1 — the single FIFO for ALL command runs in this process. Lives in
         * the companion (not per instance) because the Tool layer builds a fresh
         * CommandRunner per task; an instance lock would let two tasks' scripts
         * run concurrently. One command at a time, first-come-first-served.
         */
        private val queueMutex = Mutex()

        /** Wall-clock timeout per command run; the process is killed past this. */
        const val TIMEOUT_MS = 30_000L

        /** Per-stream output cap (characters). */
        const val MAX_OUTPUT_CHARS = 50_000

        /**
         * Combined output bound (characters, stdout + stderr): 2 x 50.000 = 100.000,
         * i.e. ~100 KB — the context-flood cap. Enforced by construction through
         * the per-stream cap above; kept as a named constant so callers (Tool,
         * CodeAgent) can quote one number.
         */
        const val MAX_TOTAL_OUTPUT_CHARS = MAX_OUTPUT_CHARS * 2

        /** The complete command allowlist. Only "sh" is on it. */
        val ALLOWED_COMMANDS: Set<String> = setOf("sh")

        /** Whole words rejected anywhere in a script's text (network / destructive / privilege). */
        val BANNED_SCRIPT_WORDS: Set<String> = setOf(
            "curl", "wget", "nc", "ncat", "telnet", "ssh", "ftp",
            "su", "sudo", "rm", "dd", "mkfs",
        )

        /** Characters that have no business in a command or an argument (no shell involved). */
        private val META_CHARS: Set<Char> = setOf(';', '&', '|', '>', '<', '$', '`', '\n', '\r', '\\', '"', '\'')

        private val BANNED_WORD_REGEX =
            Regex("\\b(${BANNED_SCRIPT_WORDS.joinToString("|")})\\b", RegexOption.IGNORE_CASE)

        /**
         * Pure validation of a command + args (no I/O). Returns a denial reason,
         * or null when the invocation is allowed. Used by [run] and by
         * [ActionGate.checkCommand] so the gate and the runner can never disagree.
         */
        fun validate(command: String, args: List<String>): String? {
            if (command.isBlank()) return "empty command"
            if (command !in ALLOWED_COMMANDS) {
                return "command '$command' is not allowed (only ${ALLOWED_COMMANDS.joinToString(", ")})."
            }
            if (command.any { it in META_CHARS || it.isWhitespace() }) {
                return "command '$command' contains shell metacharacters or whitespace."
            }
            if (args.size != 1) {
                return "sh takes exactly one argument: a scoped .sh script path (got ${args.size})."
            }
            val scriptArg = args[0]
            if (scriptArg.any { it in META_CHARS }) {
                return "argument '$scriptArg' contains shell metacharacters."
            }
            if (!scriptArg.endsWith(".sh")) {
                return "argument '$scriptArg' is not a .sh script."
            }
            if (!ScopedFileStore.isRelativePathSafe(scriptArg)) {
                return "script path '$scriptArg' is outside the scoped directory (mobius-code)."
            }
            return null
        }

        /** First banned whole word found in [scriptText], or null when the text is clean. */
        fun findBannedWord(scriptText: String): String? =
            BANNED_WORD_REGEX.find(scriptText)?.value
    }
}

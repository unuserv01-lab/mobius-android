package com.mobius.core

import android.accessibilityservice.AccessibilityService
import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.AlarmClock
import com.mobius.accessibility.ActionExecutor
import com.mobius.accessibility.ScreenReader
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/**
 * LAYER 1 — TOOLS (closed primitives).
 *
 * The complete set of things a MOBIUS worker may DO on the phone. Every tool is a small,
 * deterministic Kotlin primitive over the AccessibilityService: no LLM calls inside tools,
 * no app-specific knowledge, no hidden state. A tool returns a [ToolResult] and NEVER throws
 * (P8: callers degrade gracefully instead of crashing).
 *
 * Community code CANNOT add tools: new capabilities arrive only as LAYER 2 skills (declarative
 * JSON recipes that compose these primitives). Adding a primitive means editing this file and
 * the closed implementation below.
 */
/**
 * (v8.1) The result of one tool call — now with verification attached.
 * `success` alone only means "the action was dispatched / the call returned
 * data". Whether the intended EFFECT actually happened is [verification]:
 * only [VerificationStatus.VERIFIED] counts as confirmed. [SkillRunner]'s
 * done-gate ([OutcomeGate]) refuses to report DONE while any mutating step
 * is not VERIFIED — that is what stops the 26 Nov 2026 false-success bug
 * ("Alarm berhasil diatur" with no alarm set) from recurring.
 *
 * [evidence] is the short read-back proof shown to the user/audit trail
 * (e.g. "07:00 terlihat di daftar alarm", "exit code 0", "id pesan Gmail").
 * [notPossible] marks a DETERMINISTIC blocker (bad input, missing app,
 * unsupported Android version): retrying the same call cannot help.
 */
data class ToolResult(
    val success: Boolean,
    val message: String = "",
    /** Structured data the caller may store, e.g. {"0": "Rp12.000", "1": "Rp15.500", "count": "2"}. */
    val data: Map<String, String> = emptyMap(),
    val verification: VerificationStatus = VerificationStatus.UNVERIFIED,
    val evidence: String? = null,
    val notPossible: Boolean = false,
)

interface Tool {
    /** Opens an app through its launcher intent. */
    suspend fun launchApp(packageName: String): ToolResult

    /** Taps the first node whose visible text matches (exact or contains). */
    suspend fun tapText(packageName: String, text: String, exact: Boolean = true): ToolResult

    /** Types [text] into the first editable field on screen (replaces its content). */
    suspend fun type(packageName: String, text: String): ToolResult

    /**
     * Presses Enter/Search on the focused text field (submits a search or a form).
     * Implemented with AccessibilityNodeInfo.ACTION_IME_ENTER (API 31+); on older Android
     * there is no supported way to send Enter from an accessibility service, so it fails
     * honestly instead of pretending success.
     */
    suspend fun submit(packageName: String): ToolResult

    /** Visible texts on screen, as indexed data entries plus "count". */
    suspend fun readScreen(packageName: String, limit: Int = 200): ToolResult

    /** Checks whether [text] is visible on screen right now. */
    suspend fun findText(packageName: String, text: String, exact: Boolean = true): ToolResult

    /**
     * Extracts up to [max] distinct matches of regex [pattern] from the visible screen text.
     * Generic text mining (e.g. prices "Rp[0-9.,]+"); the pattern comes from the skill, not the tool.
     */
    suspend fun extract(packageName: String, pattern: String, max: Int = 5): ToolResult

    /** Scrolls the first scrollable node on screen forward. */
    suspend fun scroll(packageName: String): ToolResult

    /** Directional swipe ("up" | "down" | "left" | "right") across the middle of the screen. */
    suspend fun swipe(direction: String, durationMs: Long = 300): ToolResult

    /** System Back. */
    suspend fun back(): ToolResult

    /** System Home. */
    suspend fun home(): ToolResult

    /** Sleeps [ms] milliseconds (screens load asynchronously). */
    suspend fun waitMs(ms: Long): ToolResult

    /** Waits up to [timeoutMs] for [text] to appear on screen. */
    suspend fun waitForText(packageName: String, text: String, timeoutMs: Long): ToolResult

    // --- v8.1 alarm skill (Layer 1, closed primitive) ---
    // Sets an alarm through the SYSTEM clock app via AlarmClock.ACTION_SET_ALARM
    // (the public intent; the Clock app owns the alarm, MOBIUS never touches
    // AlarmManager directly) and then READS BACK the alarm list to verify the
    // alarm is really there. Firing the intent alone is never reported as
    // success: if the time is not seen in the alarm list the result is
    // success=true + verification=UNVERIFIED ("belum terverifikasi") and the
    // done-gate turns the run into PARTIAL — the words "Alarm berhasil
    // diatur" are never produced without read-back evidence. Never retried
    // (the skill sets maxRetries=0): a retry could create a duplicate alarm.
    // Needs Manifest.permission.SET_ALARM (normal permission, auto-granted at
    // install — see AndroidManifest.xml, v8.1). The Clock app must be allowed
    // in the app registry (com.android.deskclock is a KnownApps default-ON).
    suspend fun setAlarm(packageName: String, hour: Int, minute: Int, message: String): ToolResult

    // --- v6 CodeAgent (Layer 1, closed primitives — see ScopedFileStore / CommandRunner) ---
    // All three are gated by the CODE_AGENT feature flag AND by ActionGate; when the flag
    // is OFF they deny with a clear message and no code runs at all (v5 behavior).

    /**
     * Reads a text file inside the scoped directory (filesDir/mobius-code).
     * [path] is relative; absolute paths, ".." traversal and symlinks out are rejected.
     */
    suspend fun readFileScoped(path: String): ToolResult

    /**
     * Writes [content] to a text file inside the scoped directory.
     * Overwriting an existing file needs user approval (ActionGate NEEDS_APPROVAL) and
     * is refused without it — callers should use a fresh name per write.
     */
    suspend fun writeFileScoped(path: String, content: String): ToolResult

    /**
     * Runs a command. Allowlist: only `sh` with exactly one argument, a scoped `.sh`
     * script path, no extra args. No shell is involved (argv list, not a shell line),
     * so metacharacters / pipes / redirects in [args] are rejected outright.
     */
    suspend fun runCommand(command: String, args: List<String> = emptyList()): ToolResult
}

/**
 * The closed implementation: every primitive delegates to [ScreenReader] / [ActionExecutor],
 * which are themselves thin wrappers over the AccessibilityService. All failures become
 * ToolResult(false, ...) — nothing here throws.
 */
class AccessibilityTools(
    private val reader: ScreenReader,
    private val executor: ActionExecutor,
    @Suppress("unused") private val service: AccessibilityService,
) : Tool {

    override suspend fun launchApp(packageName: String): ToolResult = resultOf(
        okMessage = "Opened $packageName.",
        failMessage = "Could not open $packageName.",
        // v7.8: the executor only returns true after observing the package in
        // the foreground — the read-back IS the verification.
        okVerification = VerificationStatus.VERIFIED,
        okEvidence = "$packageName terlihat di foreground.",
    ) { executor.launchApp(packageName) }

    /**
     * (v8.1) Tap + read-back: snapshot layar sebelum tap, tunggu layar stabil,
     * bandingkan. Tap yang tidak mengubah apa pun = NO_EFFECT + success=false
     * (BUKAN sukses — dulu tap yang "terkirim" tapi tak berefek dilaporkan
     * "Tapped ..." seolah berhasil).
     */
    override suspend fun tapText(packageName: String, text: String, exact: Boolean): ToolResult = try {
        val before = ScreenSnapshot.take(reader, packageName)
        if (!executor.tapByText(packageName, text, exact)) {
            // Target tidak ada DI LAYAR SAAT INI — bisa karena layar belum
            // termuat, jadi ini FAILED biasa (bukan notPossible): retry /
            // observe ulang masih masuk akal.
            ToolResult(false, "Could not find \"$text\" on screen.", verification = VerificationStatus.FAILED)
        } else {
            Verification.verifyScreenChanged(
                reader, packageName, before,
                okMessage = "Tapped \"$text\".",
                noEffectMessage = "Mengetuk \"$text\" tidak mengubah layar (tombol mungkin tidak merespons) — bukan sukses.",
            )
        }
    } catch (e: Exception) {
        ToolResult(false, e.message ?: e.javaClass.simpleName, verification = VerificationStatus.FAILED)
    }

    /**
     * (v8.1) Type + read-back (pola Mika UnseenField): baca-balik isi kolom
     * sesudah setText dan bandingkan dengan teks yang diminta. Tidak berubah
     * = NO_EFFECT; berubah tapi isinya lain (mis. auto-format) = UNVERIFIED
     * + gagal jujur, bukan "Typed text." palsu.
     */
    override suspend fun type(packageName: String, text: String): ToolResult = try {
        val field = reader.findEditable(packageName)
            ?: return ToolResult(false, "No text field on screen to type into.", verification = VerificationStatus.FAILED)
        val beforeText = field.text?.toString().orEmpty()
        if (!executor.setText(field, text)) {
            return ToolResult(false, "The text field did not accept the text.", verification = VerificationStatus.FAILED)
        }
        // Re-find: the node object can go stale after the edit.
        val afterText = reader.findEditable(packageName)?.text?.toString().orEmpty()
        when {
            afterText == text || (text.isNotEmpty() && afterText.contains(text)) ->
                ToolResult(true, "Typed text.", verification = VerificationStatus.VERIFIED, evidence = "kolom berisi \"$afterText\"")
            afterText == beforeText ->
                ToolResult(false, "Mengetik tidak mengubah kolom (teks tetap \"$beforeText\") — bukan sukses.", verification = VerificationStatus.NO_EFFECT)
            else ->
                ToolResult(false, "Kolom berubah tapi isinya tidak sesuai (\"$afterText\", bukan \"$text\").", verification = VerificationStatus.UNVERIFIED)
        }
    } catch (e: Exception) {
        ToolResult(false, e.message ?: e.javaClass.simpleName, verification = VerificationStatus.FAILED)
    }

    /**
     * (v8.1) Submit + read-back: Enter terkirim lalu layar harus berubah
     * (navigasi hasil, keyboard turun, teks baru). Tidak ada perubahan =
     * NO_EFFECT + gagal jujur.
     */
    override suspend fun submit(packageName: String): ToolResult = try {
        val before = ScreenSnapshot.take(reader, packageName)
        if (!executor.pressEnter(packageName)) {
            ToolResult(
                false,
                "Could not submit: no focused text field, or the field did not accept Enter " +
                    "(needs Android 12+).",
                verification = VerificationStatus.FAILED,
                notPossible = true,
            )
        } else {
            Verification.verifyScreenChanged(
                reader, packageName, before,
                okMessage = "Submitted.",
                noEffectMessage = "Enter terkirim tapi layar tidak berubah (pencarian mungkin tidak jalan) — bukan sukses.",
            )
        }
    } catch (e: Exception) {
        ToolResult(false, e.message ?: e.javaClass.simpleName, verification = VerificationStatus.FAILED)
    }

    override suspend fun readScreen(packageName: String, limit: Int): ToolResult = try {
        val texts = reader.readScreen(packageName, limit)
        val data = LinkedHashMap<String, String>()
        data["count"] = texts.size.toString()
        texts.forEachIndexed { i, t -> data[i.toString()] = t }
        ToolResult(
            success = texts.isNotEmpty(),
            message = if (texts.isEmpty()) "No text found on screen for $packageName."
                else texts.take(10).joinToString(" | ").take(500),
            data = data,
            // Read-only: the observation IS the evidence.
            verification = if (texts.isNotEmpty()) VerificationStatus.VERIFIED else VerificationStatus.FAILED,
        )
    } catch (e: Exception) {
        ToolResult(false, e.message ?: e.javaClass.simpleName, verification = VerificationStatus.FAILED)
    }

    override suspend fun findText(packageName: String, text: String, exact: Boolean): ToolResult = try {
        val node = if (exact) reader.findByText(packageName, text) else reader.findByTextContains(packageName, text)
        if (node != null) ToolResult(true, "Found \"$text\".", verification = VerificationStatus.VERIFIED)
        else ToolResult(false, "\"$text\" is not on screen.", verification = VerificationStatus.FAILED)
    } catch (e: Exception) {
        ToolResult(false, e.message ?: e.javaClass.simpleName, verification = VerificationStatus.FAILED)
    }

    override suspend fun extract(packageName: String, pattern: String, max: Int): ToolResult = try {
        val regex = Regex(pattern)
        val texts = reader.readScreen(packageName, 200)
        val matches = regex.findAll(texts.joinToString("\n")).map { it.value }.distinct().take(max).toList()
        val data = LinkedHashMap<String, String>()
        data["count"] = matches.size.toString()
        matches.forEachIndexed { i, m -> data[i.toString()] = m }
        ToolResult(
            success = matches.isNotEmpty(),
            message = if (matches.isEmpty()) "No match for '$pattern' on screen."
                else matches.joinToString(", "),
            data = data,
            verification = if (matches.isNotEmpty()) VerificationStatus.VERIFIED else VerificationStatus.FAILED,
        )
    } catch (e: Exception) {
        ToolResult(false, "Invalid pattern '$pattern': ${e.message}", verification = VerificationStatus.FAILED)
    }

    /**
     * (v8.1) Scroll/swipe/back/home + read-back: layar (atau aplikasi
     * foreground) harus berubah. Di ujung daftar, scroll yang tidak mengubah
     * apa pun = NO_EFFECT + gagal jujur — skill yang memang mengharapkan
     * ujung daftar harus memakai onFail="skip" untuk itu.
     */
    override suspend fun scroll(packageName: String): ToolResult = try {
        val before = ScreenSnapshot.take(reader, packageName)
        if (!executor.scrollForwardOnScreen(packageName)) {
            ToolResult(false, "Nothing scrollable on screen.", verification = VerificationStatus.FAILED)
        } else {
            Verification.verifyScreenChanged(
                reader, packageName, before,
                okMessage = "Scrolled.",
                noEffectMessage = "Scroll tidak mengubah layar (mungkin sudah di ujung) — bukan sukses.",
            )
        }
    } catch (e: Exception) {
        ToolResult(false, e.message ?: e.javaClass.simpleName, verification = VerificationStatus.FAILED)
    }

    override suspend fun swipe(direction: String, durationMs: Long): ToolResult = try {
        val pkg = reader.currentPackage().orEmpty()
        val before = ScreenSnapshot.take(reader, pkg)
        if (!executor.swipeDirection(direction, durationMs)) {
            ToolResult(false, "Swipe $direction was not accepted.", verification = VerificationStatus.FAILED)
        } else {
            Verification.verifyScreenChanged(
                reader, pkg, before,
                okMessage = "Swiped $direction.",
                noEffectMessage = "Swipe $direction tidak mengubah layar — bukan sukses.",
                afterPkg = reader.currentPackage().takeIf { !it.isNullOrBlank() } ?: pkg,
            )
        }
    } catch (e: Exception) {
        ToolResult(false, e.message ?: e.javaClass.simpleName, verification = VerificationStatus.FAILED)
    }

    override suspend fun back(): ToolResult = try {
        val pkg = reader.currentPackage().orEmpty()
        val before = ScreenSnapshot.take(reader, pkg)
        if (!executor.back()) {
            ToolResult(false, "Back was not accepted.", verification = VerificationStatus.FAILED)
        } else {
            Verification.verifyScreenChanged(
                reader, pkg, before,
                okMessage = "Back.",
                noEffectMessage = "Back tidak mengubah layar — bukan sukses.",
                afterPkg = reader.currentPackage().takeIf { !it.isNullOrBlank() } ?: pkg,
            )
        }
    } catch (e: Exception) {
        ToolResult(false, e.message ?: e.javaClass.simpleName, verification = VerificationStatus.FAILED)
    }

    override suspend fun home(): ToolResult = try {
        val pkg = reader.currentPackage().orEmpty()
        val before = ScreenSnapshot.take(reader, pkg)
        if (!executor.home()) {
            ToolResult(false, "Home was not accepted.", verification = VerificationStatus.FAILED)
        } else {
            Verification.verifyScreenChanged(
                reader, pkg, before,
                okMessage = "Home.",
                noEffectMessage = "Home tidak mengubah layar (mungkin sudah di Home) — bukan sukses.",
                afterPkg = reader.currentPackage().takeIf { !it.isNullOrBlank() } ?: pkg,
            )
        }
    } catch (e: Exception) {
        ToolResult(false, e.message ?: e.javaClass.simpleName, verification = VerificationStatus.FAILED)
    }

    override suspend fun waitMs(ms: Long): ToolResult {
        delay(ms.coerceIn(0, 30_000))
        // Pure wait, no state change expected — the wait itself is the effect.
        return ToolResult(true, "Waited ${ms}ms.", verification = VerificationStatus.VERIFIED)
    }

    override suspend fun waitForText(packageName: String, text: String, timeoutMs: Long): ToolResult = try {
        val found = executor.waitFor(timeoutMs) { reader.findByTextContains(packageName, text) }
        if (found != null) ToolResult(true, "Found \"$text\".", verification = VerificationStatus.VERIFIED)
        else ToolResult(false, "\"$text\" did not appear within ${timeoutMs}ms.", verification = VerificationStatus.FAILED)
    } catch (e: Exception) {
        ToolResult(false, e.message ?: e.javaClass.simpleName, verification = VerificationStatus.FAILED)
    }

    // --- v6 CodeAgent ---
    // The scoped store lives under the app-private filesDir, reached through
    // service.applicationContext — the constructor is NOT changed (Orchestrator.buildTool
    // is owned by another workstream). Flag OFF = deny, no code runs at all.

    /** Lazily built from the service's application context; no new constructor params. */
    private val scopedStore: ScopedFileStore by lazy { ScopedFileStore(service.applicationContext) }
    private val commandRunner: CommandRunner by lazy { CommandRunner(scopedStore) }
    private val featureFlags: FeatureFlags by lazy { FeatureFlags(service.applicationContext) }

    /** True unless the CODE_AGENT flag was explicitly turned OFF (default ON; read failure = ON). */
    private suspend fun codeAgentEnabled(): Boolean = try {
        featureFlags.isEnabled(FeatureFlag.CODE_AGENT).first()
    } catch (e: Exception) {
        true
    }

    private fun codeAgentOffResult(): ToolResult =
        ToolResult(false, "Code agent is turned off (feature flag CODE_AGENT). Turn it on in Settings to run code tasks.", verification = VerificationStatus.FAILED, notPossible = true)

    override suspend fun readFileScoped(path: String): ToolResult = try {
        if (!codeAgentEnabled()) return codeAgentOffResult()
        val content = scopedStore.read(path)
            ?: return ToolResult(false, "Could not read '$path': not found, or outside the scoped directory (mobius-code).", verification = VerificationStatus.FAILED)
        // Read-only: the content read IS the evidence.
        ToolResult(true, content.take(500), mapOf("path" to path, "content" to content), verification = VerificationStatus.VERIFIED)
    } catch (e: Exception) {
        ToolResult(false, e.message ?: e.javaClass.simpleName, verification = VerificationStatus.FAILED)
    }

    override suspend fun writeFileScoped(path: String, content: String): ToolResult = try {
        if (!codeAgentEnabled()) return codeAgentOffResult()
        val gate = ActionGate.checkFileWrite(path, scopedStore.exists(path))
        when (gate.decision) {
            GateDecision.DENIED ->
                return ToolResult(false, "Ditolak ActionGate: ${gate.reason}", verification = VerificationStatus.FAILED, notPossible = true)
            GateDecision.NEEDS_APPROVAL ->
                // No approval hook exists at the Tool layer: fail closed. The caller
                // (CodeAgent) writes under a fresh name per run, so this is not hit
                // in normal operation; a skill step hitting it must go through the
                // SkillRunner confirmStep hook instead.
                return ToolResult(false, "Butuh persetujuan: ${gate.reason} Tulis ke nama file baru, atau minta persetujuan dulu.", verification = VerificationStatus.FAILED, notPossible = true)
            GateDecision.ALLOW -> Unit
        }
        if (!scopedStore.write(path, content)) {
            return ToolResult(false, "Could not write '$path': unsafe path, or file too large (max ${ScopedFileStore.MAX_FILE_CHARS} chars).", verification = VerificationStatus.FAILED, notPossible = true)
        }
        // (v8.1) Read-back: tulis lalu baca lagi, bandingkan isinya.
        val readBack = scopedStore.read(path)
        if (readBack == content) {
            ToolResult(true, "Wrote $path (${content.length} chars).", mapOf("path" to path), verification = VerificationStatus.VERIFIED, evidence = "isi file dibaca-balik, cocok")
        } else {
            ToolResult(false, "File '$path' ditulis tapi baca-balik tidak cocok — belum terverifikasi.", verification = VerificationStatus.UNVERIFIED)
        }
    } catch (e: Exception) {
        ToolResult(false, e.message ?: e.javaClass.simpleName, verification = VerificationStatus.FAILED)
    }

    override suspend fun runCommand(command: String, args: List<String>): ToolResult = try {
        if (!codeAgentEnabled()) return codeAgentOffResult()
        val gate = ActionGate.checkCommand(command, args)
        if (gate.decision == GateDecision.DENIED) {
            return ToolResult(false, "Ditolak ActionGate: ${gate.reason}", verification = VerificationStatus.FAILED, notPossible = true)
        }
        val result = commandRunner.run(command, args)
        val data = mapOf(
            "exitCode" to result.exitCode.toString(),
            "stdout" to result.stdout,
            "stderr" to result.stderr,
            "timedOut" to result.timedOut.toString(),
        )
        when {
            result.timedOut -> ToolResult(false, "Command timed out after ${CommandRunner.TIMEOUT_MS / 1000}s and was killed.", data, verification = VerificationStatus.FAILED)
            // Exit code 0 IS the effect evidence for a command run.
            result.exitCode == 0 -> ToolResult(true, result.stdout.ifBlank { "(no output)" }.take(2000), data, verification = VerificationStatus.VERIFIED, evidence = "exit code 0")
            else -> ToolResult(
                false,
                "Command exited with code ${result.exitCode}." +
                    (if (result.stderr.isNotBlank()) " ${result.stderr.take(500)}" else ""),
                data,
                verification = VerificationStatus.FAILED,
            )
        }
    } catch (e: Exception) {
        ToolResult(false, e.message ?: e.javaClass.simpleName, verification = VerificationStatus.FAILED)
    }

    // --- v8.1 alarm skill ---
    //
    // Flow: (1) validasi jam; (2) cek registry Jam (last enforcement, sama
    // seperti launchApp — SkillRunner sudah mengecek lebih dulu supaya
    // penolakannya membawa pesan "belum diizinkan"); (3) kirim
    // AlarmClock.ACTION_SET_ALARM dengan EXTRA_SKIP_UI=true — aplikasi Jam
    // sistem yang memasang alarmnya, bukan MOBIUS; (4) VERIFIKASI: buka daftar
    // alarm (ACTION_SHOW_ALARMS) dan baca layar — jam harus terlihat di
    // daftar sebagai teks ("07:00" / "7:00", sesuai format 24-jam/12-jam
    // perangkat). Tidak terlihat -> success=true + UNVERIFIED + pesan
    // "belum terverifikasi" (bukan gagal mutlak: intent terkirim dan alarm
    // mungkin saja terpasang, tapi TIDAK BOLEH diklaim) — done-gate
    // menurunkannya ke PARTIAL. Kata "berhasil" tidak pernah muncul tanpa
    // bukti read-back.
    //
    // Batasan jujur: verifikasi ini membaca TEKS LAYAR daftar alarm. Bila
    // aplikasi Jam di HP tidak menampilkan jam alarm sebagai teks yang bisa
    // dibaca Accessibility (atau memakai package Jam lain), hasilnya
    // UNVERIFIED walau alarm sebenarnya terpasang — itu harga kejujuran,
    // dan pesan kepada pengguna selalu menyuruh cek aplikasi Jam.
    // Android TIDAK menyediakan API publik untuk membaca daftar alarm
    // (content provider deskclock tidak stabil antar OEM), jadi read-back
    // layar adalah satu-satunya verifikasi yang tersedia.
    override suspend fun setAlarm(packageName: String, hour: Int, minute: Int, message: String): ToolResult {
        if (hour !in 0..23 || minute !in 0..59) {
            return ToolResult(
                false,
                "Jam tidak valid: $hour:$minute (jam 0–23, menit 0–59).",
                verification = VerificationStatus.FAILED,
                notPossible = true,
            )
        }
        val ctx = service.applicationContext
        val allowed = runCatching { AppRegistry(ctx).isAllowed(packageName) }.getOrDefault(false)
        if (!allowed) {
            return ToolResult(
                false,
                "Aplikasi Jam ($packageName) belum diizinkan — aktifkan di Settings > Aplikasi yang diizinkan.",
                verification = VerificationStatus.FAILED,
                notPossible = true,
            )
        }
        val label = message.trim()
        val setIntent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            if (label.isNotEmpty()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        }
        try {
            ctx.startActivity(setIntent)
        } catch (e: SecurityException) {
            // Izin SET_ALARM (normal permission, manifest v8.1) belum aktif —
            // terjadi bila APK lama di-update tanpa reinstall penuh.
            return ToolResult(
                false,
                "Tidak bisa memasang alarm: izin SET_ALARM belum aktif — install ulang APK v8.1.",
                verification = VerificationStatus.FAILED,
                notPossible = true,
            )
        } catch (e: ActivityNotFoundException) {
            return ToolResult(
                false,
                "Tidak ada aplikasi Jam di HP ini yang bisa memasang alarm.",
                verification = VerificationStatus.FAILED,
                notPossible = true,
            )
        } catch (e: Exception) {
            return ToolResult(false, e.message ?: e.javaClass.simpleName, verification = VerificationStatus.FAILED)
        }
        // --- Read-back: buka daftar alarm, cari jamnya sebagai teks. ---
        val time24 = "%02d:%02d".format(hour, minute)
        val time12 = "%d:%02d".format(if (hour % 12 == 0) 12 else hour % 12, minute)
        val candidates = listOf(time24, time12)
        try {
            val showIntent = Intent(AlarmClock.ACTION_SHOW_ALARMS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(showIntent)
        } catch (e: Exception) {
            // Daftar alarm tidak bisa dibuka — intent SET sudah terkirim, tapi
            // verifikasi tidak memungkinkan: laporkan belum terverifikasi.
            return ToolResult(
                true,
                "Permintaan alarm $time24 sudah dikirim ke aplikasi Jam, tapi daftar alarm tidak bisa dibuka untuk verifikasi — belum terverifikasi, cek aplikasi Jam.",
                verification = VerificationStatus.UNVERIFIED,
            )
        }
        // Tunggu aplikasi Jam muncul di foreground (maks 4 dtk), lalu baca layar.
        val shown = Verification.waitForForeground(reader, packageName, 4_000)
        val foregroundPkg = reader.currentPackage() ?: packageName
        val texts = if (shown || foregroundPkg == packageName) {
            // Beri waktu daftar alarm me-render, lalu baca.
            val stable = Verification.waitForStableScreen(reader, foregroundPkg, 3_000)
            stable.texts
        } else {
            // Aplikasi Jam tidak muncul — baca apa pun yang ada di layar.
            ScreenSnapshot.take(reader, foregroundPkg).texts
        }
        val hit = candidates.firstOrNull { want -> texts.any { it.contains(want) } }
        return if (hit != null) {
            ToolResult(
                true,
                "Alarm $time24 sudah terpasang (terverifikasi di daftar alarm).",
                mapOf("hour" to hour.toString(), "minute" to minute.toString(), "label" to label, "time" to time24),
                verification = VerificationStatus.VERIFIED,
                evidence = "\"$hit\" terlihat di daftar alarm",
            )
        } else {
            ToolResult(
                true,
                "Permintaan alarm $time24 sudah dikirim ke aplikasi Jam, tapi $time24 tidak terlihat di daftar alarm — belum terverifikasi, cek aplikasi Jam.",
                mapOf("hour" to hour.toString(), "minute" to minute.toString(), "label" to label, "time" to time24),
                verification = VerificationStatus.UNVERIFIED,
            )
        }
    }

    private suspend fun resultOf(
        okMessage: String,
        failMessage: String,
        okVerification: VerificationStatus = VerificationStatus.UNVERIFIED,
        okEvidence: String? = null,
        block: suspend () -> Boolean,
    ): ToolResult = try {
        if (block()) ToolResult(true, okMessage, verification = okVerification, evidence = okEvidence)
        else ToolResult(false, failMessage, verification = VerificationStatus.FAILED)
    } catch (e: Exception) {
        ToolResult(false, e.message ?: e.javaClass.simpleName, verification = VerificationStatus.FAILED)
    }
}

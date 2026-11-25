package com.mobius.core

import com.mobius.accessibility.ScreenReader
import kotlinx.coroutines.delay
import android.os.SystemClock

/**
 * v8.1 — VERIFIKASI PASCA-AKSI + DONE-GATE.
 *
 * Obat bug false-success 26 Nov 2026 (MOBIUS bilang "Alarm berhasil diatur"
 * padahal tidak ada alarm yang terpasang): mulai v8.1 tidak ada lagi klaim
 * sukses tanpa bukti. Setiap aksi pengubah keadaan WAJIB dibaca-balik dari
 * layar/keadaan, dan hanya run yang lolos [OutcomeGate] sebagai [RunOutcome.DONE]
 * yang boleh dilaporkan sukses.
 *
 * Pola diadopsi dari (lihat breakdown-kompetitor, Bagian 3 Prioritas 1):
 *   - Mika (Apache 2.0) — ActionEffect per aksi + OutcomeJudge
 *     (Done / Partial / NotPossible / Unknown) + LearnGate.done.
 *   - Nanobrowser (Apache 2.0) — verification debt: done(success) ditolak
 *     selama ada utang verifikasi; harus observe ulang atau done("partial").
 * Keduanya Apache 2.0, kompatibel dengan lisensi MOBIUS (juga Apache 2.0).
 * Ini ADOPSI POLA yang ditulis ulang sendiri dari nol — TIDAK ADA kode yang
 * disalin dari repo Mika/Nanobrowser (dan TIDAK ADA dari repo GPL nanoMuse /
 * OpenMinis — pola saja, sesuai Bagian 4 breakdown).
 *
 * Tiga outcome terpisah per aksi (ala Nanobrowser receipt):
 *   1. dispatched — perintah terkirim (performAction / startActivity tidak throw);
 *   2. reacted — layar/keadaan berubah (read-back);
 *   3. expected state reached — keadaan yang diharapkan teramati
 *      (teks muncul, app di foreground, alarm ada di daftar).
 * [ToolResult.success] hanya berarti (1)+(2); (3) dinyatakan oleh
 * [ToolResult.verification]. Hanya [VerificationStatus.VERIFIED] yang
 * melunasi utang verifikasi di [OutcomeGate].
 */
enum class VerificationStatus {
    /** Read-back mengonfirmasi efek yang diharapkan (teks/app/alarm teramati). */
    VERIFIED,
    /** Aksi terkirim tapi efeknya TIDAK BISA dikonfirmasi (best-effort saja).
     *  Bukan sukses — [OutcomeGate] menurunkannya ke Partial, bukan Done. */
    UNVERIFIED,
    /** Aksi terkirim tapi layar/keadaan TERBUKTI tidak berubah — BUKAN sukses. */
    NO_EFFECT,
    /** Aksinya sendiri gagal (node tidak ada, exception, timeout). */
    FAILED,
}

/** Klasifikasi akhir satu run skill (pola Mika OutcomeJudge). */
enum class RunOutcome {
    /** Semua langkah berhasil DAN semua aksi mutasi terverifikasi. Satu-satunya
     *  status yang boleh dilaporkan sukses ke pengguna. */
    DONE,
    /** Ada progres terverifikasi, tapi ada utang verifikasi atau langkah yang
     *  dilewati/dihentikan — laporkan jujur "partial / belum terverifikasi". */
    PARTIAL,
    /** Tujuan TIDAK BISA dilakukan di perangkat/keadaan ini (ditolak gate atau
     *  registry, aplikasi tidak ada, fitur tidak didukung). Jangan retry buta. */
    NOT_POSSIBLE,
    /** Hasil tidak jelas (timeout, exception tak terduga, gagal tanpa progres
     *  terverifikasi dan tanpa alasan deterministik). Observe ulang / coba lagi. */
    UNKNOWN,
}

/**
 * Snapshot kecil keadaan layar untuk read-back (bandingkan sebelum vs sesudah).
 * Teks dinormalisasi (trim, buang yang kosong) dan dibandingkan sebagai SET
 * supaya refresh daftar yang mengubah urutan tidak dianggap perubahan palsu.
 * Jumlah node + status keyboard + package foreground ikut dibandingkan, karena
 * ada perubahan (dialog hilang, keyboard turun, pindah aplikasi) yang tidak
 * mengubah satu pun teks.
 */
data class ScreenSnapshot(
    val texts: Set<String>,
    val nodeCount: Int,
    val keyboardVisible: Boolean,
    val foregroundPackage: String?,
) {
    companion object {
        fun take(reader: ScreenReader, pkg: String): ScreenSnapshot {
            val raw = reader.readScreen(pkg, 200)
            return ScreenSnapshot(
                texts = raw.map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
                nodeCount = raw.size,
                keyboardVisible = runCatching { reader.isKeyboardVisible() }.getOrDefault(false),
                foregroundPackage = runCatching { reader.currentPackage() }.getOrNull(),
            )
        }
    }

    /** True bila ada perbedaan yang berarti dengan [other]. */
    fun differsFrom(other: ScreenSnapshot): Boolean =
        texts != other.texts || nodeCount != other.nodeCount ||
            keyboardVisible != other.keyboardVisible ||
            foregroundPackage != other.foregroundPackage
}

/**
 * Helper tunggu/observasi deterministik — KODE yang menunggu, bukan model
 * (pola Mika BatchRunner expect / wait_stable).
 *
 * Semua fungsi ini membaca layar lewat [ScreenReader] dan mengembalikan
 * Boolean / snapshot — tidak pernah melempar ke pemanggil (gagal baca =
 * false / snapshot kosong, supaya verifikasi gagal jujur, bukan crash).
 */
object Verification {

    /**
     * Tools yang MENGUBAH keadaan (di luar HP atau di akun pengguna). Hanya
     * langkah-langkah inilah yang menciptakan UTANG VERIFIKASI bila hasilnya
     * bukan VERIFIED — tool baca (readScreen/findText/extract/waitForText dan
     * semua pencarian Google) adalah observasi: keberhasilannya adalah
     * buktinya sendiri. Harus tetap sinkron dengan nama dispatch di
     * [SkillRunner.callTool] dan [Tool].
     */
    val MUTATING_TOOLS: Set<String> = setOf(
        "launchApp", "tapText", "type", "submit", "scroll", "swipe", "back", "home",
        "gmailSend", "calendarCreate", "browserNavigate",
        "writeFileScoped", "runCommand", "setAlarm",
    )

    /** Tunggu sampai [text] muncul di layar (contains, case-insensitive). */
    suspend fun waitForText(
        reader: ScreenReader,
        pkg: String,
        text: String,
        timeoutMs: Long,
    ): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs.coerceAtLeast(0)
        while (true) {
            val found = runCatching { reader.findByTextContains(pkg, text) }.getOrNull()
            if (found != null) return true
            if (SystemClock.elapsedRealtime() >= deadline) return false
            delay(200)
        }
    }

    /** Tunggu sampai [text] HILANG dari layar (untuk expect "teks hilang"). */
    suspend fun waitForTextGone(
        reader: ScreenReader,
        pkg: String,
        text: String,
        timeoutMs: Long,
    ): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs.coerceAtLeast(0)
        while (true) {
            val found = runCatching { reader.findByTextContains(pkg, text) }.getOrNull()
            if (found == null) return true
            if (SystemClock.elapsedRealtime() >= deadline) return false
            delay(200)
        }
    }

    /** Tunggu sampai [pkg] menjadi aplikasi foreground. */
    suspend fun waitForForeground(
        reader: ScreenReader,
        pkg: String,
        timeoutMs: Long,
    ): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs.coerceAtLeast(0)
        while (true) {
            if (runCatching { reader.currentPackage() }.getOrNull() == pkg) return true
            if (SystemClock.elapsedRealtime() >= deadline) return false
            delay(200)
        }
    }

    /**
     * Tunggu sampai layar STABIL: dua snapshot berurutan (jarak [stableMs])
     * identik, atau [timeoutMs] habis. Mengembalikan snapshot TERAKHIR yang
     * terlihat (stabil atau tidak) supaya pemanggil tetap bisa membandingkan —
     * timeout di sini BUKAN kegagalan, hanya "layar masih bergerak".
     */
    suspend fun waitForStableScreen(
        reader: ScreenReader,
        pkg: String,
        timeoutMs: Long = 3_000,
        stableMs: Long = 400,
        pollMs: Long = 200,
    ): ScreenSnapshot {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs.coerceAtLeast(0)
        var previous = ScreenSnapshot.take(reader, pkg)
        while (true) {
            delay(pollMs)
            val current = ScreenSnapshot.take(reader, pkg)
            if (current == previous) {
                // Satu snapshot yang sama dua kali berturut-turut dalam
                // interval poll belum cukup — pastikan diam selama stableMs.
                delay(stableMs)
                val confirm = ScreenSnapshot.take(reader, pkg)
                if (confirm == current) return confirm
                previous = confirm
            } else {
                previous = current
            }
            if (SystemClock.elapsedRealtime() >= deadline) return previous
        }
    }

    /**
     * Read-back generik "layar berubah sesudah aksi": tunggu layar stabil lalu
     * bandingkan dengan [before]. Berubah -> VERIFIED; identik -> NO_EFFECT
     * (dan success=false: klik tanpa perubahan BUKAN sukses).
     *
     * [afterPkg]: package tempat layar dibaca sesudah aksi. Defaultnya [pkg],
     * tapi aksi seperti back/home bisa PINDAH aplikasi — pemanggil boleh
     * mengisi package foreground yang teramati sesudah aksi supaya
     * perbandingannya tidak membaca layar yang salah.
     */
    suspend fun verifyScreenChanged(
        reader: ScreenReader,
        pkg: String,
        before: ScreenSnapshot,
        okMessage: String,
        noEffectMessage: String,
        afterPkg: String? = null,
    ): ToolResult {
        val after = waitForStableScreen(reader, afterPkg ?: pkg)
        return if (after.differsFrom(before)) {
            ToolResult(true, okMessage, verification = VerificationStatus.VERIFIED)
        } else {
            ToolResult(
                success = false,
                message = noEffectMessage,
                verification = VerificationStatus.NO_EFFECT,
            )
        }
    }

    /** Daftar langkah mutasi yang masih berutang verifikasi (untuk pesan jujur). */
    fun describeDebt(steps: List<StepResult>): String =
        steps.filter { it.tool in MUTATING_TOOLS && it.success && it.verification != VerificationStatus.VERIFIED }
            .joinToString(", ") { "${it.tool} (${it.verification.name.lowercase()})" }
}

/**
 * Done-gate (pola Nanobrowser completion.ts + Mika OutcomeJudge): mengklasifikasi
 * satu run skill menjadi DONE / PARTIAL / NOT_POSSIBLE / UNKNOWN.
 *
 * Aturan, sesuai urutan:
 *  1. Ditolak gate/registry -> NOT_POSSIBLE (deterministik, jangan retry).
 *  2. Timeout skill -> UNKNOWN.
 *  3. Ada langkah gagal:
 *     - gagal karena alasan deterministik (notPossible) TANPA progres
 *       terverifikasi -> NOT_POSSIBLE;
 *     - gagal TAPI ada progres terverifikasi -> PARTIAL;
 *     - gagal tanpa progres dan tanpa alasan deterministik -> UNKNOWN.
 *  4. Semua langkah "berhasil" tapi ada utang verifikasi (aksi mutasi yang
 *     tidak VERIFIED) -> PARTIAL — inilah yang dulu lolos sebagai sukses palsu.
 *  5. Ada langkah dilewati/dihentikan (onFail skip/stop) -> PARTIAL.
 *  6. Selain itu -> DONE. Hanya DONE yang boleh dilaporkan sukses.
 */
object OutcomeGate {
    fun classify(
        failed: Boolean,
        partial: Boolean,
        blocked: Boolean,
        timedOut: Boolean,
        hadNotPossibleFailure: Boolean,
        verifiedProgress: Boolean,
        hasDebt: Boolean,
    ): RunOutcome = when {
        blocked -> RunOutcome.NOT_POSSIBLE
        timedOut -> RunOutcome.UNKNOWN
        failed && hadNotPossibleFailure && !verifiedProgress -> RunOutcome.NOT_POSSIBLE
        failed && verifiedProgress -> RunOutcome.PARTIAL
        failed -> RunOutcome.UNKNOWN
        hasDebt -> RunOutcome.PARTIAL
        partial -> RunOutcome.PARTIAL
        else -> RunOutcome.DONE
    }
}

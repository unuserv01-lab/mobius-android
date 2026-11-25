package com.mobius.agent

/**
 * v8.1 — EXECUTION GUARDS (anti-loop + anti-waste).
 *
 * Pola diadaptasi dari Nanobrowser `src/agent/guards.ts` (Apache License 2.0 —
 * MOBIUS juga Apache 2.0, jadi kompatibel) dan ditulis ulang dari nol dalam
 * Kotlin; TIDAK ada kode Nanobrowser yang disalin. Pola antrean eksekusi
 * perintah di [com.mobius.core.CommandRunner] mengambil ide dari koordinator
 * eksekusi OpenMinis (GPLv3 — POLA SAJA, tidak ada kode yang disalin; lihat
 * Bagian 4 breakdown kompetitor soal batasan lisensi).
 *
 * Satu instance = satu run (satu [PlanningAgent.execute]). Counter milik run,
 * bukan global: run baru mulai dari nol, sama seperti Nanobrowser ("counters
 * belong to the run"). Orchestration tidak perlu diubah — tiap job membangun
 * PlanningAgent baru, jadi tiap job mendapat guard state yang segar.
 *
 * Tiga guard, semuanya ADITIF di atas kontrol loop yang sudah ada (cap 15
 * langkah, timeout 5 menit, abort setelah 3 gagal beruntun — semuanya TETAP
 * dan TIDAK disentuh):
 *
 *  1. REPEAT — tanda tangan tiap tool call (tool + arg + outcome) dicatat
 *     dalam jendela 6 call terakhir. Tanda tangan yang sama 3x -> nudge ke
 *     model lewat history ("coba cara lain, jangan ulangi"); ke-4x-nya
 *     setelah nudge -> stop. Kunci repeat memakai outcome juga (tool+arg+
 *     sukses/gagal yang sama), mengikuti Nanobrowser "same call same
 *     outcome": panggilan sama yang hasilnya BERUBAH bukan loop.
 *  2. NO-EFFECT — untuk tool pengubah layar (tap/type/submit/scroll/swipe/
 *     launch/back/home), sidik layar sebelum vs sesudah dibandingkan. 2x
 *     berturut-turut tanpa perubahan -> nudge; 3x -> stop. Ini obat loop
 *     "klik buta" (mis. mengetuk tombol yang tidak merespons). Tool baca
 *     (readScreen/findText/extract/waitForText) dan wait() tidak dihitung:
 *     mereka memang tidak mengubah layar. Bila layar tidak terbaca di salah
 *     satu sisi, guard diam (tidak bisa menilai) — bukan no-effect.
 *  3. ERROR — 5 error tool-call berturut-turut -> stop. Di dalam
 *     PlanningAgent, abort 3-gagal-beruntun yang sudah ada akan memicu
 *     LEBIH DULU; threshold 5 di sini adalah backstop untuk pemanggil lain
 *     di masa depan yang tidak punya aturan 3-strike, dan untuk error yang
 *     tidak dihitung sebagai "failure" oleh loop.
 *
 * Verdict: [GuardVerdict.Allow] (lanjut), [GuardVerdict.Nudge] (lanjut,
 * tapi model diberi peringatan di history langkah berikut), atau
 * [GuardVerdict.Stop] (loop harus berhenti dengan alasan yang diberikan).
 * Bila nudge dan stop terpicu bersamaan, stop menang; bila dua nudge
 * terpicu bersamaan, pesannya digabung.
 */
class ExecutionGuards {

    /** One logged tool call: signature (tool + args + outcome) + raw args for the trail. */
    data class GuardedCall(val tool: String, val args: Map<String, String>, val success: Boolean)

    sealed class GuardVerdict {
        /** Nothing suspicious — carry on. */
        object Allow : GuardVerdict()

        /** Suspicious pattern — carry on, but warn the model in the next prompt. */
        data class Nudge(val message: String) : GuardVerdict()

        /** Loop confirmed — stop the run; [reason] is user-facing (Indonesian). */
        data class Stop(val reason: String) : GuardVerdict()
    }

    /** Recent calls, oldest first; never longer than [REPEAT_WINDOW]. */
    private val recent = ArrayDeque<GuardedCall>()

    /** Repeat keys already nudged once — a second nudge for the same key never fires; the next repeat stops. */
    private val nudgedRepeatKeys = mutableSetOf<String>()

    /** Consecutive mutating calls whose screen fingerprint did not change. */
    private var noEffectStreak = 0

    /** True once the current no-effect streak has been nudged (reset when an effect is seen). */
    private var noEffectNudged = false

    /** Consecutive failed tool calls (any tool). */
    private var consecutiveErrors = 0

    /**
     * Records one tool call and returns the verdict. Call AFTER the tool ran
     * and the screen was re-observed, so [screenBefore]/[screenAfter] are the
     * summaries bracketing this call. Both screens may be null (e.g. the
     * "code" step has no screen) — then only repeat + error guards apply.
     */
    fun record(
        tool: String,
        args: Map<String, String>,
        success: Boolean,
        screenBefore: String?,
        screenAfter: String?,
    ): GuardVerdict {
        val call = GuardedCall(tool, args, success)
        recent += call
        while (recent.size > REPEAT_WINDOW) recent.removeFirst()

        var nudge: String? = null
        var stop: String? = null

        // --- 1. Repeat: same signature (tool + normalized args + outcome) in the window. ---
        val key = signatureKey(tool, args, success)
        val repeats = recent.count { signatureKey(it.tool, it.args, it.success) == key }
        if (repeats >= REPEAT_STOP_AFTER && key in nudgedRepeatKeys) {
            stop = "Planning dihentikan: tool $tool dengan argumen yang sama dipanggil " +
                "$repeats kali berturut-turut tanpa hasil berbeda (guard anti-loop)."
        } else if (repeats >= REPEAT_NUDGE_AFTER && key !in nudgedRepeatKeys) {
            nudgedRepeatKeys += key
            nudge = "GUARD: you just called $tool(${describeArgs(args)}) with the same outcome " +
                "$repeats times in a row. It is not working — try a different tool or different " +
                "arguments, or report done with what you found so far instead of repeating it."
        }

        // --- 2. No-effect: a successful mutating call that changed nothing on screen. ---
        if (tool in MUTATING_TOOLS) {
            val before = fingerprint(screenBefore)
            val after = fingerprint(screenAfter)
            if (success && before != null && after != null) {
                if (before == after) {
                    noEffectStreak++
                    if (noEffectStreak >= NO_EFFECT_STOP_AFTER) {
                        stop = "Planning dihentikan: $NO_EFFECT_STOP_AFTER aksi berturut-turut " +
                            "tidak mengubah layar sama sekali (guard anti-loop)."
                    } else if (noEffectStreak >= NO_EFFECT_NUDGE_AFTER && !noEffectNudged) {
                        noEffectNudged = true
                        val n = "GUARD: your last $noEffectStreak $tool actions changed nothing on " +
                            "screen — the screen is identical before and after. Do NOT tap/type the " +
                            "same thing again. Read the screen first (readScreen/findText) and pick " +
                            "a different target, or report done/partial."
                        nudge = if (nudge == null) n else "$nudge $n"
                    }
                } else {
                    noEffectStreak = 0
                    noEffectNudged = false
                }
            }
            // Unreadable screen on either side: cannot judge — streak untouched.
        }

        // --- 3. Errors: consecutive failed tool calls. ---
        consecutiveErrors = if (success) 0 else consecutiveErrors + 1
        if (consecutiveErrors >= ERROR_STOP_AFTER) {
            stop = "Planning dihentikan: $ERROR_STOP_AFTER error berturut-turut (guard anti-loop)."
        }

        return when {
            stop != null -> GuardVerdict.Stop(stop)
            nudge != null -> GuardVerdict.Nudge(nudge)
            else -> GuardVerdict.Allow
        }
    }

    /** Recent calls (oldest first) — for the task trail / debugging. */
    fun recentCalls(): List<GuardedCall> = recent.toList()

    /** Current no-effect streak (0 = last mutating call had a visible effect). */
    fun noEffectStreak(): Int = noEffectStreak

    /** Current consecutive-error count. */
    fun consecutiveErrors(): Int = consecutiveErrors

    /** Clears all per-run state (a fresh run starts clean). */
    fun reset() {
        recent.clear()
        nudgedRepeatKeys.clear()
        noEffectStreak = 0
        noEffectNudged = false
        consecutiveErrors = 0
    }

    /**
     * Repeat key: tool + args sorted by key with values trimmed + outcome.
     * Two calls are "the same" only when all three match.
     */
    private fun signatureKey(tool: String, args: Map<String, String>, success: Boolean): String =
        buildString {
            append(tool).append('|')
            args.entries.sortedBy { it.key }.joinTo(this, ",") { (k, v) -> "$k=${v.trim()}" }
            append('|').append(if (success) "ok" else "fail")
        }

    private fun describeArgs(args: Map<String, String>): String =
        args.entries.joinToString(", ") { (k, v) -> "$k=$v" }

    /**
     * Screen fingerprint, or null when the screen cannot be judged.
     * Whitespace is collapsed so a reflowed-but-identical tree still matches;
     * the package prefix ("App <pkg> — ...") is kept, so switching apps
     * always counts as an effect.
     */
    private fun fingerprint(screen: String?): String? {
        val s = screen?.trim().orEmpty()
        if (s.isEmpty()) return null
        if ("unreadable" in s || "failed" in s || "No app in foreground" in s) return null
        return s.split(Regex("\\s+")).joinToString(" ")
    }

    companion object {
        /** How many recent calls the repeat detector looks at (Nanobrowser REPEAT_WINDOW). */
        const val REPEAT_WINDOW = 6

        /** Same signature this many times -> nudge the model once. */
        const val REPEAT_NUDGE_AFTER = 3

        /** Same signature again after the nudge -> stop the run. */
        const val REPEAT_STOP_AFTER = 4

        /** Mutating calls with no screen change this many times in a row -> nudge. */
        const val NO_EFFECT_NUDGE_AFTER = 2

        /** ...and this many times -> stop. */
        const val NO_EFFECT_STOP_AFTER = 3

        /** Consecutive failed tool calls -> stop. */
        const val ERROR_STOP_AFTER = 5

        /** Tools expected to change the screen; read-only tools and wait() are excluded. */
        val MUTATING_TOOLS: Set<String> = setOf(
            "launchApp", "tapText", "type", "submit", "scroll", "swipe", "back", "home",
        )
    }
}

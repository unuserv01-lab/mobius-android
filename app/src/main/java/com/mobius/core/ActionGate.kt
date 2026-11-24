package com.mobius.core

/**
 * ActionGate (v5) — the Sentinel-style gate for skill steps.
 *
 * Two rules, checked by [SkillRunner] BEFORE every step:
 *   1. DENIED — the step targets a sensitive app (banking/fintech). No approval can override
 *      this; the skill stops immediately.
 *   2. NEEDS_APPROVAL — the step is marked irreversible (risk="irreversible" in the skill JSON).
 *      The user is asked via TaskHooks.confirmStep; approval runs the step, rejection aborts
 *      the skill.
 *
 * Read-only steps (readScreen, findText, extract, waitForText) are never irreversible by
 * themselves — only a skill author marking a step risk="irreversible" triggers approval.
 * Every decision is recorded in the audit trail (see [GateDecisionRecord]).
 */
enum class GateDecision { ALLOW, NEEDS_APPROVAL, DENIED }

data class GateResult(val decision: GateDecision, val reason: String)

/** One gate decision, kept for the audit trail (History screen). */
data class GateDecisionRecord(
    val stepIndex: Int,
    val tool: String,
    val packageName: String?,
    val decision: GateDecision,
    /** For NEEDS_APPROVAL steps: "approved" or "rejected". Null otherwise. */
    val resolution: String? = null,
    val reason: String,
)

/**
 * Pure gate, no Android state. The sensitive-app list is ONE editable constant below:
 * package name -> display label. If an app is not on this list it is NOT blocked — keep the
 * list current when a bank/e-wallet changes its package name.
 *
 * Package names verified against Google Play listings, 24 Nov 2026 (OCTO, SeaBank, Livin,
 * BRImo, BNI, DANA, OVO, GoPay, LinkAja, Jenius, Blu, BCA). Note Jenius is com.btpn.dc,
 * not com.btpn.jenius; OCTO Mobile IS CIMB Niaga's app (one entry covers both names).
 */
object ActionGate {

    /** The only risk value that triggers approval. Any other value is treated as no risk. */
    const val RISK_IRREVERSIBLE = "irreversible"

    val SENSITIVE_APPS: Map<String, String> = mapOf(
        "com.bca" to "BCA mobile",
        "id.co.bri.brimo" to "BRImo",
        "id.bmri.livin" to "Livin' by Mandiri",
        "src.com.bni" to "BNI Mobile",
        "id.co.cimbniaga.mobile.android" to "OCTO Mobile (CIMB Niaga)",
        "id.dana" to "DANA",
        "ovo.id" to "OVO",
        "com.gojek.gopay" to "GoPay",
        "com.telkom.mwallet" to "LinkAja",
        "id.co.bankbkemobile.digitalbank" to "SeaBank",
        "com.btpn.dc" to "Jenius",
        "com.bcadigital.blu" to "Blu (BCA Digital)",
    )

    /**
     * DENIED wins over NEEDS_APPROVAL: a step into a sensitive app is refused even if it is
     * also marked irreversible. Steps with no package (submit, swipe, back, home, wait) can
     * never be DENIED — they can only be NEEDS_APPROVAL via risk.
     */
    fun check(toolName: String, packageName: String?, risk: String?): GateResult {
        val label = packageName?.let { SENSITIVE_APPS[it] }
        if (label != null) {
            return GateResult(
                GateDecision.DENIED,
                "Aplikasi sensitif: $label ($packageName) — aksi ke aplikasi ini ditolak.",
            )
        }
        if (risk == RISK_IRREVERSIBLE) {
            return GateResult(
                GateDecision.NEEDS_APPROVAL,
                "Langkah tak terbalikkan: $toolName — butuh persetujuanmu sebelum dijalankan.",
            )
        }
        return GateResult(GateDecision.ALLOW, "")
    }

    // --- v6 CodeAgent (additive; check() above is untouched) ---

    /**
     * Gate for a file write through the code tools.
     *
     *   - DENIED — [path] is not a safe relative path inside the scoped directory
     *     (absolute, ".." traversal, NUL, backslash). No approval can override this;
     *     the write never happens. The canonical/symlink check happens again in
     *     [ScopedFileStore.resolve] at write time.
     *   - NEEDS_APPROVAL — the file already exists inside the scope, so writing would
     *     OVERWRITE (destructive). The caller must ask the user via the existing
     *     TaskHooks.confirmStep path; without a hook the Tool layer fails closed
     *     (see AccessibilityTools.writeFileScoped). [CodeAgent] avoids this by
     *     writing every script under a fresh, unique name.
     *   - ALLOW — a new file inside the scoped directory.
     */
    fun checkFileWrite(path: String, alreadyExists: Boolean = false): GateResult {
        if (!ScopedFileStore.isRelativePathSafe(path)) {
            return GateResult(
                GateDecision.DENIED,
                "File write ditolak: path '$path' keluar dari direktori scoped (mobius-code) atau tidak valid.",
            )
        }
        if (alreadyExists) {
            return GateResult(
                GateDecision.NEEDS_APPROVAL,
                "Menimpa file yang sudah ada di mobius-code: $path — butuh persetujuanmu sebelum dijalankan.",
            )
        }
        return GateResult(GateDecision.ALLOW, "")
    }

    /**
     * Gate for a command run through the code tools. Delegates to
     * [CommandRunner.validate], so the gate and the runner share one allowlist and
     * can never disagree.
     *
     *   - DENIED — the command is not on the allowlist (only "sh" is), the argument
     *     is not exactly one scoped `.sh` path, or command/args contain shell
     *     metacharacters. Destructive commands (rm, dd, su, sudo) and network
     *     commands (curl, wget, nc, ssh) are DENIED here because they are not
     *     allowlisted — no approval path exists for them.
     *   - ALLOW — `sh <scoped .sh script>` with clean arguments. (The script text
     *     itself is scanned for banned words by [CommandRunner] at run time.)
     *   - NEEDS_APPROVAL is never returned for commands: running an allowlisted
     *     script is the CodeAgent's normal operation, and anything outside the
     *     allowlist is denied outright rather than approved into existence.
     */
    fun checkCommand(command: String, args: List<String> = emptyList()): GateResult {
        val denial = CommandRunner.validate(command, args)
            ?: return GateResult(GateDecision.ALLOW, "")
        return GateResult(GateDecision.DENIED, "Perintah ditolak: $denial")
    }
}

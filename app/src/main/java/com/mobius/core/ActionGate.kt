package com.mobius.core

/**
 * ActionGate (v5, bertier v8.1) — the Sentinel-style gate for skill steps.
 *
 * v8.1: dari biner (ALLOW / NEEDS_APPROVAL / DENIED) menjadi TIER RISIKO
 * (pola nanoMuse Sentinel — POLA SAJA, kode ditulis ulang sendiri):
 *   - SAFE: read-only, jalan otomatis.
 *   - MODERATE: mengubah state tapi reversibel — approval pertama kali,
 *     boleh diingat (sekali / chat ini / selamanya, lihat [ApprovalGrants]).
 *   - SENSITIVE: irreversible / uang / kirim / hapus / tulis Google —
 *     SELALU approval, TIDAK BOLEH diingat selamanya (maksimal "chat ini").
 *
 * Tiga aturan lama tetap berlaku, kini dengan tier:
 *   1. DENIED — the step targets a sensitive app (banking/fintech). No approval can override
 *      this; the skill stops immediately.
 *   2. NEEDS_APPROVAL (SENSITIVE) — the step is marked irreversible (risk="irreversible"
 *      in the skill JSON). The user is asked via TaskHooks.confirmStep; approval runs the step,
 *      rejection aborts the skill.
 *   3. (v7.9) NEEDS_APPROVAL (SENSITIVE) — the step is a Google WRITE (sending an email,
 *      creating a calendar event). This is mandatory and does NOT depend on the skill JSON
 *      marking the step risk="irreversible": a Google write can never run without the user
 *      approving it first. Read-only Google steps (search Gmail/Calendar/Drive, read a Doc) are
 *      never gated by this rule.
 *
 *   4. (v7.9-tambahan) DENIED — the step opens an app the user has not allowed
 *      in the app registry ([checkRegistry], allowlist, default-deny). Checked
 *      AFTER rule 1: a sensitive app is denied by rule 1 even when its registry
 *      toggle is ON — the registry can never override the sensitive list.
 *
 *   5. (v8.1) NEEDS_APPROVAL (MODERATE) — the step mutates state reversibly
 *      (tap, type, launchApp, tulis file BARU). Approval pertama kali; bila
 *      pengguna memilih "ingat", langkah yang sama (tool + target) jalan
 *      otomatis berikutnya (lihat [ApprovalGrants]).
 *
 *   6. (v8.1) NEEDS_APPROVAL (SENSITIVE) — `submit` (kirim) SELALU sensitif,
 *      walau skill JSON tidak menandainya risk="irreversible". Sebelum v8.1
 *      langkah submit tanpa flag lolos sebagai ALLOW — itu celah fail-open
 *      yang ditutup di sini.
 *
 * Read-only steps (readScreen, findText, extract, waitForText) are never irreversible by
 * themselves — only a skill author marking a step risk="irreversible" triggers approval.
 * Every decision is recorded in the audit trail (see [GateDecisionRecord]).
 */
enum class GateDecision { ALLOW, NEEDS_APPROVAL, DENIED }

/** Hasil gate v8.1: keputusan + tier + kunci grant (null bila tidak bisa diingat). */
data class GateResult(
    val decision: GateDecision,
    val reason: String,
    /** Tier risiko aksi ini. DENIED tidak punya tier bermakna (SAFE sebagai default). */
    val tier: RiskTier = RiskTier.SAFE,
    /** Kunci untuk lookup grant, mis. "tapText:com.shopee.id". Null untuk
     * SAFE (tidak perlu diingat) dan DENIED (tidak pernah jalan). */
    val actionKey: String? = null,
)

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

    /**
     * (v7.9) Google WRITE tools — anything that creates or sends data in the user's Google
     * account. These ALWAYS need approval (rule 3 in the class KDoc), even when the skill
     * JSON forgets risk="irreversible". The tool names must match the dispatch names in
     * [SkillRunner.callTool] exactly. Read-only Google tools (gmailSearch, gmailRead,
     * calendarList, driveSearch, docsRead) are NOT in this set and are never gated here.
     */
    val GOOGLE_WRITE_TOOLS: Set<String> = setOf("gmailSend", "calendarCreate")

    /** True when [toolName] is a Google write (sending email / creating a calendar event). */
    fun isGoogleWrite(toolName: String): Boolean = toolName in GOOGLE_WRITE_TOOLS

    // --- v8.1: tier risiko ---
    //
    // Tool names MUST match the dispatch names in [SkillRunner.callTool] /
    // [PlanningAgent] exactly. A tool not listed anywhere below is SAFE
    // (unknown tools fail later in callTool with "Unknown tool" — the gate
    // never invents a restriction for a tool it does not know).

    /** Mengubah state tapi reversibel: ketuk, ketik, buka aplikasi, geser.
     * Approval pertama kali; boleh diingat. */
    val MODERATE_TOOLS: Set<String> = setOf(
        "tapText", "type", "launchApp", "swipe",
        // Browser di laptop (sisi ekstensi): klik/ketik di halaman. Submit
        // TIDAK di sini — submit selalu SENSITIVE (lihat SENSITIVE_TOOLS).
        "clickText", "typeText", "whatsappOpenChat",
    )

    /** Irreversible / uang / kirim / hapus. SELALU approval; tidak boleh
     * diingat selamanya (maksimal "chat ini"). */
    val SENSITIVE_TOOLS: Set<String> = setOf(
        "submit", // kirim (tombol Kirim di aplikasi apa pun)
    ) + GOOGLE_WRITE_TOOLS

    /** Read-only (jalan otomatis). Daftar eksplisit agar klasifikasi bisa
     * diaudit; tool yang tidak dikenal juga SAFE (lihat catatan di atas). */
    val SAFE_TOOLS: Set<String> = setOf(
        "readScreen", "findText", "extract", "wait", "waitForText",
        "back", "home", "scroll", "browserNavigate", "navigate",
        "gmailSearch", "gmailRead", "calendarList", "driveSearch", "docsRead",
        "readFileScoped",
        // runCommand: allowlist "sh <scoped .sh>" + scan kata terlarang di
        // CommandRunner sudah membatasi ke skrip scoped; di luar allowlist =
        // DENIED (checkCommand). Tidak naik tier — skrip CodeAgent ditulis
        // baru tiap run, approval per skrip hanya menambah gesekan.
        "runCommand",
    )

    /**
     * Kunci grant untuk satu aksi: tool + target (package / path / host).
     * Sengaja KASAR (bukan isi argumen lengkap): grant "tapText di Shopee"
     * harus berlaku untuk ketukan berikutnya di Shopee, bukan hanya untuk
     * teks yang persis sama. TIDAK berisi isi pesan / teks yang diketik.
     */
    fun actionKey(toolName: String, packageName: String?, extra: String? = null): String {
        val target = extra?.takeIf { it.isNotBlank() }
            ?: packageName?.takeIf { it.isNotBlank() }
            ?: "any"
        return "$toolName:$target"
    }

    /**
     * Klasifikasi tier murni dari (tool, package, risk) — tanpa Android state.
     * Urutan: DENIED (aplikasi sensitif) > SENSITIVE (Google write / risk
     * irreversible / tool sensitif) > MODERATE (tool moderat / tulis file
     * baru, lihat [checkFileWrite]) > SAFE.
     */
    fun classify(toolName: String, packageName: String?, risk: String?): GateResult {
        val label = packageName?.let { SENSITIVE_APPS[it] }
        if (label != null) {
            return GateResult(
                GateDecision.DENIED,
                "Aplikasi sensitif: $label ($packageName) — aksi ke aplikasi ini ditolak.",
            )
        }
        if (toolName in GOOGLE_WRITE_TOOLS) {
            return GateResult(
                GateDecision.NEEDS_APPROVAL,
                "Aksi tulis Google: $toolName — menulis ke akun Google (kirim email / buat acara) " +
                    "selalu butuh persetujuanmu sebelum dijalankan.",
                RiskTier.SENSITIVE,
                actionKey(toolName, packageName),
            )
        }
        if (risk == RISK_IRREVERSIBLE || toolName in SENSITIVE_TOOLS) {
            return GateResult(
                GateDecision.NEEDS_APPROVAL,
                "Langkah tak terbalikkan: $toolName — butuh persetujuanmu sebelum dijalankan.",
                RiskTier.SENSITIVE,
                actionKey(toolName, packageName),
            )
        }
        if (toolName in MODERATE_TOOLS) {
            return GateResult(
                GateDecision.NEEDS_APPROVAL,
                "Langkah mengubah tampilan/aplikasi: $toolName — persetujuan pertama kali; " +
                    "kamu bisa memilih untuk mengingatnya.",
                RiskTier.MODERATE,
                actionKey(toolName, packageName),
            )
        }
        return GateResult(GateDecision.ALLOW, "", RiskTier.SAFE, null)
    }

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
     * (v7.9) A Google write is NEEDS_APPROVAL even with no risk flag at all: writing to
     * the user's Google account always asks first, and the JSON cannot opt out of it.
     * (v8.1) Delegates to [classify] — same decisions as before, plus tier +
     * actionKey, plus MODERATE for reversible mutations and SENSITIVE for
     * `submit` without a risk flag.
     */
    fun check(toolName: String, packageName: String?, risk: String?): GateResult =
        classify(toolName, packageName, risk)

    // --- v7.9-tambahan: registry aplikasi yang diizinkan (AppRegistry) ---
    //
    // The user's allowlist decides "may this app be OPENED". It is a SEPARATE
    // decision from [check] and it can NEVER override it:
    //   - Callers run [check] FIRST. A sensitive app (bank/fintech) is DENIED
    //     there even when its registry toggle is ON — the sensitive list is
    //     the hard limit, the registry only narrows further.
    //   - Only when [check] does not deny does the caller run [checkRegistry]
    //     with the value it already read from AppRegistry (this object stays
    //     pure and context-free; the DataStore read lives with the caller).
    //   - DENIED here means "not allowed by the user (yet)" — the message
    //     says exactly that and where to turn it on, so the failure is
    //     honest instead of a generic "could not open".
    fun checkRegistry(packageName: String, allowed: Boolean, displayName: String? = null): GateResult {
        if (allowed) return GateResult(GateDecision.ALLOW, "")
        val label = displayName ?: packageName
        return GateResult(
            GateDecision.DENIED,
            "Aplikasi $label ($packageName) belum diizinkan — aktifkan di Settings > Aplikasi yang diizinkan.",
        )
    }

    // --- v6 CodeAgent (additive; check() above is untouched) ---

    /**
     * Gate for a file write through the code tools.
     *
     *   - DENIED — [path] is not a safe relative path inside the scoped directory
     *     (absolute, ".." traversal, NUL, backslash). No approval can override this;
     *     the write never happens. The canonical/symlink check happens again in
     *     [ScopedFileStore.resolve] at write time.
     *   - NEEDS_APPROVAL (SENSITIVE) — the file already exists inside the scope, so writing would
     *     OVERWRITE (destructive). The caller must ask the user via the existing
     *     TaskHooks.confirmStep path; without a hook the Tool layer fails closed
     *     (see AccessibilityTools.writeFileScoped). [CodeAgent] avoids this by
     *     writing every script under a fresh, unique name.
     *   - ALLOW (SAFE) — a new file inside the scoped directory. (v8.1: DITAHAN
     *     sebagai SAFE — menaikkan file baru ke MODERATE akan memecahkan
     *     CodeAgent, karena Tool.writeFileScoped tidak punya approval hook dan
     *     fail closed pada NEEDS_APPROVAL; tiap skrip CodeAgent ditulis dengan
     *     nama baru. Menaikkan tier file butuh hook di Tool layer dulu —
     *     dicatat sebagai follow-up di laporan, bukan diimplementasikan di sini.)
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
                RiskTier.SENSITIVE,
                actionKey("writeFileScoped", null, "mobius-code/$path"),
            )
        }
        return GateResult(GateDecision.ALLOW, "", RiskTier.SAFE, null)
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

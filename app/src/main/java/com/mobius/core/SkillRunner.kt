package com.mobius.core

import android.os.SystemClock
import com.mobius.accessibility.MobiusAccessibilityService
import com.mobius.ai.GoogleApi
import com.mobius.ai.GoogleAuth
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * LAYER 2 — the deterministic Runner.
 *
 * Executes one skill step by step, in order, with no LLM calls and no branching beyond the
 * skill's own `when` conditions. The runner is a pure interpreter:
 *   - resolves $params / $vars in step args (a missing reference fails the step, loudly);
 *   - calls exactly one Layer 1 tool per step;
 *   - evaluates `when` (see [Skill] for the condition language);
 *   - honors `onFail` (fail | skip | stop) and `maxRetries`;
 *   - enforces the skill's `maxSteps` and wall-clock `timeoutMs`;
 *   - (v5) asks the [ActionGate] before EVERY step: a step into a sensitive app is DENIED
 *     (skill stops at once); a step marked risk="irreversible" pauses for the user's
 *     approval via [onConfirmStep] — no hook means deny (fail closed). Every gate decision
 *     is collected into the outcome for the audit trail.
 *   - (v7.9) dispatches the Google API tools (gmailSearch/gmailRead/gmailSend,
 *     calendarList/calendarCreate, driveSearch/docsRead) to [com.mobius.ai.GoogleApi]
 *     (network calls, no screen involved). Google writes are forced to NEEDS_APPROVAL
 *     by the gate itself (ActionGate.GOOGLE_WRITE_TOOLS), independent of the skill JSON.
 *   - (v7.9-tambahan) dispatches browserNavigate to the laptop bridge via
 *     [RemoteGateway]: POST /task with text = {"type":"browser_navigate","url":...}
 *     (bridge README, takeover v9). Success is reported ONLY when the bridge
 *     accepts the task (HTTP 202 + taskId); unreachable bridge / missing token /
 *     gateway OFF all fail honestly. browser_navigate is a read-only action, so
 *     it needs no approval (same as the bridge contract).
 *   - (v7.9-tambahan) checks the app registry ([AppRegistry]) before every
 *     launchApp step, AFTER the ActionGate: a sensitive app is already DENIED
 *     by the gate (which can never be overridden), and an app the user has not
 *     allowed is DENIED here with the honest "not allowed yet" message. The
 *     last enforcement still sits in ActionExecutor.launchApp itself, so any
 *     path that forgets this check (planning loop, reply agent) still cannot
 *     open a disallowed app — it just reports the generic failure there.
 *   - (v8.1) DONE-GATE: after the last step, [OutcomeGate] classifies the run
 *     as Done / Partial / NotPossible / Unknown (see [Verification.kt]).
 *     ONLY Done sets [SkillOutcome.success]=true. A run whose mutating steps
 *     were dispatched but never VERIFIED (read-back) is PARTIAL, never Done —
 *     the runner reports "partial / belum terverifikasi" instead of the false
 *     success that caused the 26 Nov 2026 alarm bug. Verification debt is
 *     listed in the message so the user (and the LLM summarizer) sees exactly
 *     which step is unconfirmed. Gate/registry denials classify as
 *     NOT_POSSIBLE; timeouts and unclear failures as UNKNOWN.
 *   - (v8.1) dispatches the alarm tool (setAlarm) to [Tool.setAlarm]:
 *     system AlarmClock intent + read-back of the alarm list. The registry
 *     check above also applies to setAlarm (it opens the Clock app).
 *
 * The LLM never runs here. Its only jobs stay where they were: picking the skill + filling
 * params ([TaskRouter]) and summarizing the structured result ([AIRouter], only when the skill
 * sets "summarize": true). Same input + same screen state = same step sequence: deterministic.
 *
 * @param remoteGateway a [RemoteGateway] to use for browserNavigate. Null by default:
 *   the runner then builds one lazily from [MobiusAccessibilityService.instance]
 *   (the only Context reachable from here — MobiusApp owns the composition root
 *   and this file may not touch it). When the accessibility service is not bound
 *   there is no Context to read the bridge settings from, so browserNavigate
 *   fails honestly instead of pretending to work.
 */
class SkillRunner(private val remoteGateway: RemoteGateway? = null) {

    suspend fun run(
        skill: Skill,
        params: Map<String, String>,
        tool: Tool?,
        onStatus: (String) -> Unit = {},
        gate: ActionGate = ActionGate,
        onConfirmStep: (suspend (String) -> Boolean)? = null,
        /**
         * (v8.1-integrasi) Hook approval bertier: bila ada, dipakai DAN tier +
         * actionKey asli dari [GateResult] diteruskan ke [Orchestrator.requestApproval]
         * (grant MODERATE bisa diingat). Bila null, fallback ke [onConfirmStep]
         * Boolean (semua NEEDS_APPROVAL diperlakukan SENSITIVE, fail-closed).
         */
        onConfirmStepWithGrant: (suspend (ApprovalRequest) -> ApprovalDecision)? = null,
    ): SkillOutcome {
        if (skill.steps.size > skill.maxSteps) {
            return SkillOutcome(
                success = false,
                partial = false,
                message = "Skill '${skill.id}' has ${skill.steps.size} steps but maxSteps is ${skill.maxSteps}.",
                steps = emptyList(),
                vars = params,
                outcome = RunOutcome.NOT_POSSIBLE,
            )
        }

        val vars = LinkedHashMap<String, String>()
        for (p in skill.params) {
            params[p.name]?.let { vars[p.name] = it } ?: p.default?.let { vars[p.name] = it }
        }

        val deadline = SystemClock.elapsedRealtime() + skill.timeoutMs
        val results = mutableListOf<StepResult>()
        val gateDecisions = mutableListOf<GateDecisionRecord>()
        var last: ToolResult? = null
        var failed = false
        var partial = false
        var failMessage = ""
        // (v8.1) Done-gate inputs — see OutcomeGate.classify.
        var blocked = false
        var timedOut = false
        var hadNotPossibleFailure = false

        for ((index, step) in skill.steps.withIndex()) {
            if (SystemClock.elapsedRealtime() > deadline) {
                failed = true
                timedOut = true
                failMessage = "Skill timed out after ${skill.timeoutMs}ms at step ${index + 1}."
                results += StepResult(index, step.tool, false, failMessage, 0, VerificationStatus.FAILED)
                break
            }
            if (step.whenExpr != null && !evalWhen(step.whenExpr, vars, last)) {
                // (v8.1) Skipped by its own `when` = no action taken, so no
                // verification debt: VERIFIED here means "nothing to verify",
                // not "an effect was confirmed". Without this, a skipped
                // mutating step would wrongly downgrade the run to PARTIAL.
                results += StepResult(index, step.tool, true, "skipped (when false)", 0, VerificationStatus.VERIFIED)
                continue
            }

            onStatus("Step ${index + 1}/${skill.steps.size}: ${step.tool}...")

            // Resolve $params/$vars ONCE, before the gate (the gate needs the resolved package).
            var resolveError: String? = null
            val resolved: Map<String, String>? = try {
                step.args.mapValues { (_, v) -> resolveRefs(v, vars) }
            } catch (e: Exception) {
                resolveError = e.message ?: e.javaClass.simpleName
                null
            }

            var result: ToolResult? = null
            var attempt = 0
            val started = SystemClock.elapsedRealtime()
            if (resolved == null) {
                // Missing $ref: fails the step exactly as before (no tool call, no gate check).
                result = ToolResult(false, resolveError ?: "missing parameter")
            } else {
                // --- ActionGate (v5): every step is checked BEFORE it runs. ---
                val gateResult = gate.check(step.tool, resolved["package"], step.risk)
                when (gateResult.decision) {
                    GateDecision.DENIED -> {
                        gateDecisions += GateDecisionRecord(
                            index, step.tool, resolved["package"],
                            GateDecision.DENIED, null, gateResult.reason,
                        )
                        val msg = "Ditolak ActionGate: ${gateResult.reason}"
                        results += StepResult(index, step.tool, false, msg, 0, VerificationStatus.FAILED)
                        failed = true
                        blocked = true
                        failMessage = "Langkah ${index + 1} (${step.tool}) ditolak: ${gateResult.reason}"
                        break
                    }
                    GateDecision.NEEDS_APPROVAL -> {
                        // (v7.9) A Google write shows WHAT is about to happen (recipient +
                        // subject + body excerpt, or title + time + attendees), so the user
                        // approves the actual action, not just a tool name.
                        val desc = if (gate.isGoogleWrite(step.tool)) describeGoogleWrite(step.tool, resolved)
                        else "Langkah ${index + 1}: ${step.tool} (${describeArgs(resolved)}) — aksi tak terbalikkan."
                        // No approval hook means deny: never run an irreversible step blind.
                        // (v8.1-integrasi) Grant hook wins when present: tier + actionKey
                        // asli diteruskan ke requestApproval (grant MODERATE bisa diingat);
                        // hook Boolean lama hanya fallback (semua diperlakukan SENSITIVE).
                        val ok = if (onConfirmStepWithGrant != null) {
                            onConfirmStepWithGrant.invoke(
                                ApprovalRequest(desc, gateResult.tier, gateResult.actionKey, "local"),
                            ).approved
                        } else {
                            onConfirmStep?.invoke(desc) ?: false
                        }
                        if (!ok) {
                            gateDecisions += GateDecisionRecord(
                                index, step.tool, resolved["package"],
                                GateDecision.NEEDS_APPROVAL, "rejected", gateResult.reason,
                            )
                            val msg = "Ditolak pengguna: ${gateResult.reason}"
                            results += StepResult(index, step.tool, false, msg, 0, VerificationStatus.FAILED)
                            failed = true
                            blocked = true
                            failMessage = "Dibatalkan pengguna pada langkah ${index + 1} (${step.tool}): ${gateResult.reason}"
                            break
                        }
                        gateDecisions += GateDecisionRecord(
                            index, step.tool, resolved["package"],
                            GateDecision.NEEDS_APPROVAL, "approved", gateResult.reason,
                        )
                    }
                    GateDecision.ALLOW -> Unit
                }
                // --- (v7.9-tambahan) Registry aplikasi: launchApp hanya boleh
                // membuka aplikasi yang diizinkan user. Cek di sini supaya
                // penolakannya membawa pesan jujur ("belum diizinkan —
                // aktifkan di Settings"), dan di ActionExecutor.launchApp
                // sebagai penegakan terakhir untuk semua jalur. Gate sensitif
                // di atas sudah menang lebih dulu: aplikasi sensitif tetap
                // DENIED walau registry-nya ON. Tanpa Context (layanan
                // aksesibilitas mati) cek ini dilewati — langkah itu gagal
                // jujur di callTool karena tidak ada Tool layar.
                // (v8.1) setAlarm ikut dicek: tool itu membuka aplikasi Jam
                // (intent SET + read-back daftar alarm), jadi registry yang
                // sama berlaku; penegakan terakhir ada di Tool.setAlarm.
                if (step.tool == "launchApp" || step.tool == "setAlarm") {
                    val pkg = resolved["package"].orEmpty()
                    val ctx = MobiusAccessibilityService.instance?.applicationContext
                    if (ctx != null && pkg.isNotBlank()) {
                        val registry = AppRegistry(ctx)
                        val allowed = runCatching { registry.isAllowed(pkg) }.getOrDefault(false)
                        if (!allowed) {
                            val gateResult = gate.checkRegistry(pkg, false, registry.displayName(pkg))
                            gateDecisions += GateDecisionRecord(
                                index, step.tool, pkg,
                                GateDecision.DENIED, null, gateResult.reason,
                            )
                            val msg = "Ditolak ActionGate: ${gateResult.reason}"
                            results += StepResult(index, step.tool, false, msg, 0, VerificationStatus.FAILED)
                            failed = true
                            blocked = true
                            failMessage = "Langkah ${index + 1} (${step.tool}) ditolak: ${gateResult.reason}"
                            break
                        }
                    }
                }
                while (true) {
                    result = try {
                        callTool(tool, step.tool, resolved)
                    } catch (e: Exception) {
                        ToolResult(false, e.message ?: e.javaClass.simpleName)
                    }
                    if (result.success || attempt >= step.maxRetries) break
                    attempt++
                    delay(300)
                }
            }
            val duration = SystemClock.elapsedRealtime() - started
            val r = result
            results += StepResult(index, step.tool, r.success, r.message, duration, r.verification)
            if (r.notPossible) hadNotPossibleFailure = true

            if (r.success) {
                last = r
                step.storeAs?.let { key ->
                    vars[key] = r.message
                    r.data.forEach { (k, v) -> vars["$key.$k"] = v }
                }
            } else {
                last = r
                when (step.onFail) {
                    "skip" -> partial = true
                    "stop" -> {
                        partial = true
                        failMessage = "Stopped at step ${index + 1} (${step.tool}): ${r.message}"
                        break
                    }
                    else -> {
                        failed = true
                        failMessage = "Step ${index + 1} (${step.tool}) failed: ${r.message}"
                        break
                    }
                }
            }
        }

        // --- (v8.1) DONE-GATE ---
        // Only DONE may be reported as success. Verification debt (a mutating
        // step that "succeeded" without read-back proof) downgrades the run to
        // PARTIAL even when nothing failed — this is the Nanobrowser rule
        // (done(success) refused while debt is open) applied to MOBIUS.
        val mutatingResults = results.filter { it.tool in Verification.MUTATING_TOOLS }
        val verifiedProgress = mutatingResults.any { it.success && it.verification == VerificationStatus.VERIFIED }
        val debtSteps = mutatingResults.filter { it.success && it.verification != VerificationStatus.VERIFIED }
        val outcome = OutcomeGate.classify(
            failed = failed,
            partial = partial,
            blocked = blocked,
            timedOut = timedOut,
            hadNotPossibleFailure = hadNotPossibleFailure,
            verifiedProgress = verifiedProgress,
            hasDebt = debtSteps.isNotEmpty(),
        )
        val lastOk = results.lastOrNull { it.success }
        val debtDetail = Verification.describeDebt(results).takeIf { it.isNotEmpty() }
        val message = when (outcome) {
            RunOutcome.DONE ->
                lastOk?.message?.takeIf { it.isNotBlank() } ?: "Done: ${skill.name}."
            RunOutcome.PARTIAL -> {
                val base = if (failMessage.isNotEmpty()) "Partially done — $failMessage"
                else "Partially done (some steps skipped): ${lastOk?.message ?: skill.name}."
                if (debtDetail != null) "$base Belum terverifikasi: $debtDetail."
                else base
            }
            RunOutcome.NOT_POSSIBLE ->
                "Tidak bisa dilakukan: $failMessage"
            RunOutcome.UNKNOWN ->
                "Belum jelas hasilnya: ${failMessage.ifBlank { "tidak ada langkah yang terverifikasi" }} — cek layar, lalu coba lagi."
        }
        // (v8.1) `partial` keeps its old meaning (a step was skipped/stopped)
        // for existing readers; `success` is now strictly outcome == DONE.
        return SkillOutcome(outcome == RunOutcome.DONE, partial, message, results, vars, gateDecisions, outcome)
    }

    /** "package=com.shopee.id, text=sepatu" — for the approval dialog and the plan card. */
    private fun describeArgs(args: Map<String, String>): String =
        args.entries.joinToString(", ") { (k, v) -> "$k=$v" }

    private suspend fun callTool(tool: Tool?, name: String, args: Map<String, String>): ToolResult {
        // (v7.9-verifikasi) Tool layar boleh null: langkah Google API tidak
        // memakainya sama sekali. Langkah layar/file tanpa Tool gagal jujur
        // di langkah itu, bukan crash — Orchestrator sudah menolak skill
        // yang butuh layar sebelum sampai ke sini, jadi ini pertahanan kedua.
        if (name in GOOGLE_API_TOOLS) {
            return when (name) {
                "gmailSearch" -> googleSearchGmail(args["query"].orEmpty(), args["maxResults"]?.toIntOrNull() ?: 5)
                "gmailRead" -> googleReadGmail(args.require("messageId"))
                "gmailSend" -> googleSendGmail(args.require("to"), args.require("subject"), args.require("body"))
                "calendarList" -> googleListCalendar(args)
                "calendarCreate" -> googleCreateCalendar(args)
                "driveSearch" -> googleSearchDrive(args)
                "docsRead" -> googleReadDoc(args.require("docId"))
                // (v7.9-tambahan) "Buka di laptop": POST /task ke bridge, tanpa Tool layar.
                "browserNavigate" -> openOnLaptop(args.require("url"))
                // Unreachable (name sudah dicek di GOOGLE_API_TOOLS), tapi
                // when-expression butuh else.
                else -> ToolResult(false, "Unknown tool: $name")
            }
        }
        val tool = tool ?: return ToolResult(
            false,
            "Langkah \"$name\" butuh layanan aksesibilitas MOBIUS (layar), yang sedang tidak aktif.",
        )
        return when (name) {
        "launchApp" -> tool.launchApp(args.require("package"))
        "tapText" -> tool.tapText(args.require("package"), args.require("text"), args["exact"]?.toBooleanStrictOrNull() ?: true)
        "type" -> tool.type(args.require("package"), args.require("text"))
        "submit" -> tool.submit(args.require("package"))
        "readScreen" -> tool.readScreen(args.require("package"), args["limit"]?.toIntOrNull() ?: 200)
        "findText" -> tool.findText(args.require("package"), args.require("text"), args["exact"]?.toBooleanStrictOrNull() ?: true)
        "extract" -> tool.extract(args.require("package"), args.require("pattern"), args["max"]?.toIntOrNull() ?: 5)
        "scroll" -> tool.scroll(args.require("package"))
        "swipe" -> tool.swipe(args.require("direction"), args["durationMs"]?.toLongOrNull() ?: 300)
        "back" -> tool.back()
        "home" -> tool.home()
        "wait" -> tool.waitMs(args["ms"]?.toLongOrNull() ?: 1_000)
        "waitForText" -> tool.waitForText(args.require("package"), args.require("text"), args["timeoutMs"]?.toLongOrNull() ?: 5_000)
        // (v8.1) Alarm: jam diisi dari $params (string, dilonggarkan — "7" atau
        // "7 pagi" -> 7); angka yang tidak bisa dibaca gagal jujur sebagai
        // notPossible, bukan ditebak. maxRetries skill ini 0: jangan pernah
        // retry setAlarm (bisa jadi alarm ganda).
        "setAlarm" -> tool.setAlarm(
            args.require("package"),
            parseClockNumber(args["hour"]) ?: return ToolResult(
                false,
                "Jam alarm tidak jelas: \"${args["hour"]}\" — sebutkan jamnya, mis. \"pasang alarm jam 7 pagi\".",
                verification = VerificationStatus.FAILED,
                notPossible = true,
            ),
            parseClockNumber(args["minute"]) ?: 0,
            args["message"].orEmpty(),
        )
        // v6 CodeAgent — the gate (flag + ActionGate) is enforced inside the Tool
        // implementation; "args" for runCommand is one comma-separated string in JSON
        // (e.g. "args": "script.sh"), because skill args are strings.
        "readFileScoped" -> tool.readFileScoped(args.require("path"))
        "writeFileScoped" -> tool.writeFileScoped(args.require("path"), args.require("content"))
        "runCommand" -> tool.runCommand(
            args.require("command"),
            args["args"]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList(),
        )
        // v7.9 — Google API tools + browserNavigate ditangani di cabang awal
        // di atas (tanpa Tool layar); bila sampai ke sini, nama-nama itu
        // sudah tertangani.
        else -> ToolResult(false, "Unknown tool: $name")
        }
    }

    // --- v7.9-tambahan — "Buka di laptop" (bridge browser_navigate) ---
    //
    // Sends POST /task with text = {"type":"browser_navigate","url":"..."} via
    // [RemoteGateway.submitTask] (the bridge README takeover contract, v9:
    // the extension detects the JSON "type" field and navigates deterministically,
    // no LLM, no skill match on the laptop side). browser_navigate is a READ
    // action, so no approval is needed — the same rule as the bridge contract.
    // Success is reported ONLY when the bridge ACCEPTS the task (HTTP 202 +
    // taskId). Acceptance means "queued for the extension", not "the tab is
    // already on screen" — the extension polls every ~3s (panel open) or up to
    // ~60s (service-worker alarm), so the tab appears a few seconds later.
    // No login/credential is ever added to this flow: the bridge token lives in
    // the RemoteGateway DataStore (entered by Tuan in Settings) and is only
    // ever sent to the bridge itself, never logged.
    //
    // Honest failures, in order: no usable Context to read the bridge settings
    // (accessibility service not bound) -> gateway flag OFF -> token missing ->
    // bridge unreachable / token rejected (submitTask returns null for both;
    // the message says to check address + token rather than guessing which).

    /** Injected gateway, or one built from the live accessibility service (see class KDoc). */
    private fun gateway(): RemoteGateway? =
        remoteGateway ?: MobiusAccessibilityService.instance?.applicationContext?.let { RemoteGateway(it) }

    private suspend fun openOnLaptop(urlRaw: String): ToolResult {
        val url = normalizeUrl(urlRaw)
            ?: return ToolResult(
                false,
                "Tidak ada alamat situs yang jelas dari \"$urlRaw\" — sebutkan alamat lengkap, " +
                    "mis. \"buka example.com di laptop\".",
            )
        val gw = gateway()
            ?: return ToolResult(
                false,
                "Tidak bisa membaca pengaturan bridge: layanan aksesibilitas MOBIUS belum jalan. " +
                    "Nyalakan dulu, lalu coba lagi.",
            )
        if (!gw.isGatewayEnabled()) return ToolResult(false, RemoteGateway.MSG_GATEWAY_OFF)
        if (gw.getBridgeToken().isBlank()) {
            return ToolResult(
                false,
                "Token bridge belum diisi — isi alamat + token laptop di Settings > Remote gateway dulu.",
            )
        }
        val taskText = JSONObject().apply {
            put("type", "browser_navigate")
            put("url", url)
        }.toString()
        val taskId = gw.submitTask(taskText)
            ?: return ToolResult(
                false,
                "Bridge tidak terjangkau atau token ditolak — periksa alamat/token di Settings > Remote gateway.",
                verification = VerificationStatus.FAILED,
            )
        // (v8.1) Diterima bridge (HTTP 202 + taskId) BUKAN berarti tab sudah
        // terbuka — ekstensi baru polling sesudahnya. Jadi ini UNVERIFIED:
        // done-gate menurunkannya ke PARTIAL ("belum terverifikasi") sampai
        // ada observasi dari sisi laptop.
        return ToolResult(
            true,
            "Sudah dibuka di laptop: $url",
            mapOf("taskId" to taskId, "url" to url),
            verification = VerificationStatus.UNVERIFIED,
            evidence = "bridge menerima task $taskId (tab belum terlihat)",
        )
    }

    /** (v8.1) Angka jam/menit dari param skill (string): ambil bilangan bulat
     *  pertama ("7" -> 7, "7 pagi" -> 7). Null bila tidak ada angka sama sekali
     *  — pemanggil gagal jujur, bukan menebak jam. */
    private fun parseClockNumber(raw: String?): Int? =
        raw?.let { Regex("\\d+").find(it)?.value?.toIntOrNull() }

    /**
     * Picks the first domain-like token out of the raw param (the LLM usually
     * fills a clean URL; the keyword heuristic may leave verbs around it, e.g.
     * "buka example.com" -> "example.com"). A bare scheme-less domain gets
     * "https://". Returns null when no token looks like a URL at all.
     */
    private fun normalizeUrl(raw: String): String? {
        val tokens = raw.trim().split(Regex("\\s+"))
            .map { it.trim(' ', ',', ';', '"', '\'', '(', ')', '.') }
            .filter { it.isNotBlank() }
        val candidate = tokens.firstOrNull { looksLikeUrl(it) } ?: return null
        return if (candidate.startsWith("http://") || candidate.startsWith("https://")) candidate
        else "https://$candidate"
    }

    private fun looksLikeUrl(token: String): Boolean {
        val t = token.removePrefix("http://").removePrefix("https://")
        if (t.isBlank() || ' ' in t) return false
        if (t == "localhost" || t.startsWith("localhost:") || t.startsWith("localhost/")) return true
        // domain.tld[/path][:port] — at least one dot with a 2+ letter TLD.
        return Regex("^[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)+(:\\d+)?(/\\S*)?$").matches(t)
    }

    // --- v7.9 — Google API tools (via ai/GoogleApi.kt) ---
    //
    // The GoogleApi is built lazily against the GoogleAuth contract (ai/GoogleAuth.kt,
    // owned by another v7.9 builder): tokenProvider = GoogleAuth.getAccessToken().
    // Every helper catches failures and returns ToolResult(false, honest reason) — a
    // Google call NEVER throws out of here, and never claims success it did not get.
    // Data layout (same shape as readScreen/extract): data["count"], data["<i>"] is the
    // item id (so a later step can use $storeAs.0, e.g. docsRead docId=$files.0), and
    // data["<i>.<field>"] holds the per-item fields.

    private val googleApi: GoogleApi by lazy { GoogleApi(tokenProvider = { GoogleAuth.getAccessToken() }) }

    private suspend fun googleSearchGmail(query: String, maxResults: Int): ToolResult = try {
        val messages = googleApi.searchGmail(query.trim(), maxResults)
        if (messages.isEmpty()) {
            return ToolResult(
                false,
                if (query.isBlank()) "Tidak ada email di Gmail."
                else "Tidak ada email untuk \"$query\".",
            )
        }
        val data = LinkedHashMap<String, String>()
        data["count"] = messages.size.toString()
        messages.forEachIndexed { i, m ->
            data[i.toString()] = m.id
            data["$i.from"] = m.from
            data["$i.subject"] = m.subject
            data["$i.date"] = m.date
            data["$i.snippet"] = m.snippet
        }
        val lines = messages.mapIndexed { i, m ->
            "${i + 1}. ${m.subject.ifBlank { "(tanpa subjek)" }} — ${m.from} (${m.date})"
        }
        ToolResult(true, lines.joinToString("\n").take(1500), data, verification = VerificationStatus.VERIFIED)
    } catch (e: Exception) {
        ToolResult(false, e.message ?: e.javaClass.simpleName, verification = VerificationStatus.FAILED)
    }

    private suspend fun googleReadGmail(messageId: String): ToolResult = try {
        val m = googleApi.readGmail(messageId)
        val data = mapOf(
            "id" to m.id, "from" to m.from, "subject" to m.subject,
            "date" to m.date, "body" to m.body,
        )
        val header = "Dari: ${m.from}\nSubjek: ${m.subject}\nTanggal: ${m.date}\n\n"
        ToolResult(true, (header + m.body).take(2000), data, verification = VerificationStatus.VERIFIED)
    } catch (e: Exception) {
        ToolResult(false, e.message ?: e.javaClass.simpleName, verification = VerificationStatus.FAILED)
    }

    private suspend fun googleSendGmail(to: String, subject: String, body: String): ToolResult = try {
        val id = googleApi.sendGmail(to.trim(), subject, body)
        // Server mengembalikan id pesan = bukti pengiriman (bukan klaim).
        ToolResult(true, "Email terkirim ke $to (subjek: ${subject.ifBlank { "(tanpa subjek)" }}).", mapOf("id" to id), verification = VerificationStatus.VERIFIED, evidence = "id pesan Gmail $id")
    } catch (e: Exception) {
        ToolResult(false, e.message ?: e.javaClass.simpleName, verification = VerificationStatus.FAILED)
    }

    private suspend fun googleListCalendar(args: Map<String, String>): ToolResult = try {
        val events = if (args.containsKey("day")) {
            googleApi.listCalendarDay(args["day"].orEmpty())
        } else {
            googleApi.listCalendarEvents(
                args.require("timeMin"),
                args.require("timeMax"),
                args["maxResults"]?.toIntOrNull() ?: 20,
            )
        }
        if (events.isEmpty()) return ToolResult(false, "Tidak ada acara pada waktu itu.")
        val data = LinkedHashMap<String, String>()
        data["count"] = events.size.toString()
        events.forEachIndexed { i, e ->
            data[i.toString()] = e.id
            data["$i.title"] = e.title
            data["$i.start"] = e.start
            data["$i.end"] = e.end
            data["$i.attendees"] = e.attendees.joinToString(", ")
        }
        val lines = events.mapIndexed { i, e ->
            "${i + 1}. ${e.title} — ${shortTime(e.start)} s.d. ${shortTime(e.end)}"
        }
        ToolResult(true, lines.joinToString("\n").take(1500), data, verification = VerificationStatus.VERIFIED)
    } catch (e: Exception) {
        ToolResult(false, e.message ?: e.javaClass.simpleName, verification = VerificationStatus.FAILED)
    }

    private suspend fun googleCreateCalendar(args: Map<String, String>): ToolResult = try {
        val attendees = args["attendees"].orEmpty().split(",")
            .map { it.trim() }.filter { it.isNotEmpty() }
        val event = googleApi.createCalendarEvent(
            title = args.require("title"),
            start = args.require("start"),
            end = args["end"].orEmpty(),
            description = args["description"]?.trim()?.takeIf { it.isNotEmpty() },
            attendees = attendees,
        )
        val data = mapOf(
            "id" to event.id, "title" to event.title,
            "start" to event.start, "end" to event.end,
        )
        val who = if (event.attendees.isEmpty()) "" else " (peserta: ${event.attendees.joinToString(", ")})"
        ToolResult(
            true,
            "Acara dibuat: ${event.title}, ${shortTime(event.start)} s.d. ${shortTime(event.end)}.$who",
            data,
            // Server mengembalikan id acara = bukti pembuatan.
            verification = VerificationStatus.VERIFIED,
            evidence = "id acara ${event.id}",
        )
    } catch (e: Exception) {
        ToolResult(false, e.message ?: e.javaClass.simpleName, verification = VerificationStatus.FAILED)
    }

    private suspend fun googleSearchDrive(args: Map<String, String>): ToolResult = try {
        val files = googleApi.searchDrive(
            query = args.require("query"),
            maxResults = args["maxResults"]?.toIntOrNull() ?: 10,
            mimeType = args["mimeType"]?.takeIf { it.isNotBlank() },
        )
        if (files.isEmpty()) {
            return ToolResult(false, "Tidak ada file di Drive untuk \"${args["query"]}\".")
        }
        val data = LinkedHashMap<String, String>()
        data["count"] = files.size.toString()
        files.forEachIndexed { i, f ->
            data[i.toString()] = f.id
            data["$i.name"] = f.name
            data["$i.mimeType"] = f.mimeType
            data["$i.modifiedTime"] = f.modifiedTime
        }
        val lines = files.mapIndexed { i, f ->
            "${i + 1}. ${f.name} (${shortMime(f.mimeType)})"
        }
        ToolResult(true, lines.joinToString("\n").take(1500), data, verification = VerificationStatus.VERIFIED)
    } catch (e: Exception) {
        ToolResult(false, e.message ?: e.javaClass.simpleName, verification = VerificationStatus.FAILED)
    }

    private suspend fun googleReadDoc(docId: String): ToolResult = try {
        val doc = googleApi.readDoc(docId)
        if (doc.text.isBlank()) {
            return ToolResult(false, "Dokumen \"${doc.title.ifBlank { docId }}\" kosong atau tidak bisa dibaca (bukan Google Doc?).")
        }
        val data = mapOf("id" to doc.id, "title" to doc.title, "text" to doc.text.take(8000))
        ToolResult(true, "Dokumen: ${doc.title}\n\n${doc.text}".take(2000), data, verification = VerificationStatus.VERIFIED)
    } catch (e: Exception) {
        ToolResult(false, e.message ?: e.javaClass.simpleName, verification = VerificationStatus.FAILED)
    }

    /** "2026-11-27T10:00:00+07:00" -> "10:00"; a plain date passes through unchanged. */
    private fun shortTime(iso: String): String =
        if ('T' in iso && iso.length >= 16) iso.substring(11, 16) else iso

    /** "application/vnd.google-apps.document" -> "Google Doc"; anything else: last segment. */
    private fun shortMime(mime: String): String = when (mime) {
        "application/vnd.google-apps.document" -> "Google Doc"
        "application/vnd.google-apps.spreadsheet" -> "Spreadsheet"
        "application/vnd.google-apps.presentation" -> "Presentasi"
        "application/vnd.google-apps.folder" -> "Folder"
        else -> mime.substringAfterLast('.', mime).ifBlank { mime }
    }

    /** Approval text for a Google write: the user approves the actual action, not a tool name. */
    private fun describeGoogleWrite(toolName: String, args: Map<String, String>): String = when (toolName) {
        "gmailSend" -> "Kirim email — ke: ${args["to"].orEmpty()} | subjek: ${args["subject"].orEmpty()} | " +
            "isi: ${args["body"].orEmpty().take(200)}"
        "calendarCreate" -> "Buat acara — ${args["title"].orEmpty()} | " +
            "${args["start"].orEmpty()} s.d. ${args["end"].orEmpty()}" +
            (args["attendees"].orEmpty().takeIf { it.isNotBlank() }?.let { " | peserta: $it" } ?: "")
        else -> "Aksi tulis Google: $toolName (${describeArgs(args)})"
    }

    private fun Map<String, String>.require(key: String): String =
        this[key] ?: throw IllegalArgumentException("missing argument '$key'")

    /** Replaces $name with vars[name]; a missing reference fails the step. */
    private fun resolveRefs(value: String, vars: Map<String, String>): String {
        var missing: String? = null
        val out = REF.replace(value) { m ->
            vars[m.groupValues[1]] ?: run { missing = m.groupValues[1]; m.value }
        }
        if (missing != null) throw IllegalArgumentException("missing parameter/var '\$$missing'")
        return out
    }

    /** "a && b": every clause must hold. */
    private fun evalWhen(expr: String, vars: Map<String, String>, last: ToolResult?): Boolean =
        expr.split("&&").all { part -> evalAtom(part.trim(), vars, last) }

    private fun evalAtom(atom: String, vars: Map<String, String>, last: ToolResult?): Boolean {
        val negated = atom.startsWith("!")
        val core = if (negated) atom.substring(1).trim() else atom
        val value = when {
            core == "last.success" -> last?.success == true
            core == "last.failed" -> last?.success == false
            core.startsWith("vars.") -> {
                val rest = core.removePrefix("vars.")
                when {
                    rest.contains(" contains ") -> {
                        val (k, v) = rest.split(" contains ", limit = 2)
                        vars[k.trim()]?.contains(unquote(v.trim())) == true
                    }
                    rest.contains("==") -> {
                        val (k, v) = rest.split("==", limit = 2)
                        vars[k.trim()] == unquote(v.trim())
                    }
                    rest.contains("!=") -> {
                        val (k, v) = rest.split("!=", limit = 2)
                        vars[k.trim()] != unquote(v.trim())
                    }
                    else -> !vars[rest].isNullOrBlank()
                }
            }
            else -> false
        }
        return if (negated) !value else value
    }

    private fun unquote(s: String): String = s.removeSurrounding("\"").removeSurrounding("'")

    companion object {
        val REF = Regex("\\\$([A-Za-z0-9_.]+)")

        /**
         * (v7.9-verifikasi) Tools that run over the NETWORK and never touch the
         * screen — Google API tools plus (v7.9-tambahan) the laptop-bridge tool
         * browserNavigate. A skill whose steps are ALL in this set may run
         * without the accessibility service: Orchestrator uses this set to
         * decide that (see Orchestrator.runSkill). The name is historical —
         * it is really "network tools, no screen needed" now. Must stay
         * exactly in sync with the dispatch names in [callTool].
         */
        val GOOGLE_API_TOOLS: Set<String> = setOf(
            "gmailSearch", "gmailRead", "gmailSend",
            "calendarList", "calendarCreate",
            "driveSearch", "docsRead",
            "browserNavigate",
        )
    }
}

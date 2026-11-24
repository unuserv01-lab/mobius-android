package com.mobius.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.core.app.NotificationCompat
import com.mobius.MobiusApp
import com.mobius.R
import com.mobius.core.ActionGate
import com.mobius.core.AppConfig
import com.mobius.core.FeatureFlag
import com.mobius.core.FeatureFlags
import com.mobius.core.GateDecision
import com.mobius.core.GateDecisionRecord
import com.mobius.core.MemoryStore
import com.mobius.core.PermissionManager
import com.mobius.core.StepResult
import com.mobius.ui.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/**
 * WORKSTREAM D (v6) — WA watcher engine (standing rules, NOT one-shot).
 *
 * Called by [NotificationListener] for every captured notification. Flow per message:
 *   1. Flag WA_WATCHER OFF -> do nothing (v5 behavior: notifications are only cached).
 *   2. Non-WhatsApp package -> ignore.
 *   3. No enabled rule matches -> ignore (silently: a non-match is not an event worth
 *      logging, otherwise every WA message would spam the trail).
 *   4. Match -> draft = the rule's replyTemplate VERBATIM.
 *   5. ActionGate.check("wa_reply", package, risk=null) — DENIED aborts, always.
 *   6. Rule autoSend ON -> send immediately via RemoteInput (creating the rule was the
 *      standing approval for that exact text).
 *      Rule autoSend OFF -> hold a [PendingDraft] for the user to approve in the app
 *      (confirm-before-send), unless the global confirm-before-send setting is OFF,
 *      in which case the draft is sent the same way.
 *   7. Every match, draft, send, denial and failure is written to the v5 task step
 *      trail via [MemoryStore.recordTrail] (best-effort, never crashes the listener).
 *
 * WHY NO AI PERSONALISATION: the standing approval covers the EXACT template text the
 * user typed when creating the rule. Letting AIRouter rewrite it would send words Tuan
 * never approved. The template is therefore used verbatim; AIRouter is intentionally
 * not called here. If Tuan later wants AI-drafted watcher replies, that needs its own
 * approval model (draft -> user approves the wording -> rule stores the approved text).
 *
 * REPLY PATH — RemoteInput on the notification's own reply action (NOT accessibility
 * tap): this is the standard direct-reply mechanism, it works with the screen OFF and
 * without opening WhatsApp. No new permission is needed for it.
 */
class WatcherEngine(
    context: Context,
    private val rules: WatcherRules,
    private val flags: FeatureFlags,
    private val memory: MemoryStore,
    private val permissions: PermissionManager,
) {
    private val appContext = context.applicationContext

    /** One incoming WhatsApp notification, matched against the standing rules. */
    suspend fun onNotification(captured: CapturedNotification, notification: Notification) {
        // 1) Feature flag gates ALL new behavior.
        if (!flags.isEnabled(FeatureFlag.WA_WATCHER).first()) return
        // 2) WhatsApp only (regular + Business).
        if (captured.packageName !in AppConfig.WHATSAPP_PACKAGES) return

        val rule = rules.currentRules().firstOrNull { WatcherRules.matches(it, captured.sender, captured.text) }
            ?: return // 3) No match -> nothing to do, nothing to log.

        val taskId = "watcher-${rule.id.take(8)}-${System.currentTimeMillis()}"
        val userText = "${captured.sender}: ${captured.text}".take(200)
        val steps = mutableListOf<StepResult>()
        val gates = mutableListOf<GateDecisionRecord>()
        val startedAt = System.currentTimeMillis()

        steps += StepResult(
            0, "match", true,
            "Aturan \"${rule.name}\" cocok untuk pesan dari ${captured.sender}.",
            System.currentTimeMillis() - startedAt,
        )

        // 4) Draft = template verbatim.
        val draft = rule.replyTemplate.trim()
        steps += StepResult(1, "draft", true, "Draf balasan: \"$draft\"", 0)

        // 5) ActionGate guards every auto-send. WhatsApp is not a sensitive app, so this
        //    is ALLOW in practice — but the check stays, so a future list change still blocks.
        val gate = ActionGate.check("wa_reply", captured.packageName, risk = null)
        gates += GateDecisionRecord(1, "wa_reply", captured.packageName, gate.decision, null, gate.reason)
        if (gate.decision == GateDecision.DENIED) {
            steps += StepResult(2, "gate", false, "Ditolak ActionGate: ${gate.reason}", 0)
            persist(taskId, userText, steps, gates)
            Log.w(TAG, "Watcher send denied by ActionGate for ${captured.sender}: ${gate.reason}")
            return
        }

        // 6) Confirm-before-send, unless this rule was created with auto-send enabled.
        val needsApproval = !rule.autoSend && permissions.confirmBeforeSend.first()
        if (needsApproval) {
            val pending = PendingDraft(
                id = "draft-${System.currentTimeMillis()}",
                ruleId = rule.id,
                ruleName = rule.name,
                packageName = captured.packageName,
                sender = captured.sender,
                incomingText = captured.text,
                replyText = draft,
                replyAction = notification.replyAction(),
                taskId = taskId,
            )
            WatcherPendingDrafts.add(pending)
            steps += StepResult(
                2, "hold", true,
                "Draf ditahan menunggu persetujuanmu (aturan \"${rule.name}\" auto-send OFF).",
                0,
            )
            persist(taskId, userText, steps, gates)
            notifyDraftReady(pending)
            Log.i(TAG, "Watcher held a draft for ${captured.sender} (rule \"${rule.name}\")")
            return
        }

        // 6b) Auto-send (standing approval) or global confirm-before-send OFF.
        val sent = sendViaRemoteInput(notification, draft)
        steps += StepResult(
            2, "send", sent,
            if (sent) "Balasan terkirim via RemoteInput ke ${captured.sender}."
            else "GAGAL mengirim: notifikasi WhatsApp tidak punya aksi balas (RemoteInput). " +
                "Tidak ada pesan yang terkirim; coba lagi dari aplikasi.",
            0,
        )
        persist(taskId, userText, steps, gates)
        if (!sent) Log.w(TAG, "Watcher could not send reply to ${captured.sender}: no RemoteInput action")
    }

    /**
     * Sends a held draft after the user taps "Kirim" in the app. The gate is checked again
     * at send time (a rule may have been edited/disabled since the draft was held).
     * Returns true when the RemoteInput intent was delivered to the system.
     */
    suspend fun sendPending(draftId: String): Boolean {
        val draft = WatcherPendingDrafts.remove(draftId) ?: return false
        val rule = rules.currentRules().firstOrNull { it.id == draft.ruleId }
        if (rule == null || !rule.enabled) {
            persist(
                draft.taskId, "${draft.sender}: ${draft.incomingText}".take(200),
                listOf(StepResult(3, "send", false, "Draf dibatalkan: aturan sudah dihapus/dinonaktifkan.", 0)),
                emptyList(),
            )
            return false
        }
        val gate = ActionGate.check("wa_reply", draft.packageName, risk = null)
        if (gate.decision == GateDecision.DENIED) {
            persist(
                draft.taskId, "${draft.sender}: ${draft.incomingText}".take(200),
                listOf(StepResult(3, "gate", false, "Ditolak ActionGate: ${gate.reason}", 0)),
                listOf(GateDecisionRecord(3, "wa_reply", draft.packageName, gate.decision, null, gate.reason)),
            )
            return false
        }
        val action = draft.replyAction
        val sent = action != null && sendViaAction(action, draft.replyText)
        persist(
            draft.taskId, "${draft.sender}: ${draft.incomingText}".take(200),
            listOf(
                StepResult(
                    3, "send", sent,
                    if (sent) "Draf disetujui dan terkirim via RemoteInput ke ${draft.sender}."
                    else "GAGAL mengirim draf: aksi balas sudah kedaluwarsa (notifikasi lama).",
                    0,
                )
            ),
            emptyList(),
        )
        return sent
    }

    /** Discards a held draft without sending anything. */
    fun discardPending(draftId: String) {
        WatcherPendingDrafts.remove(draftId)
    }

    // ------------------------------------------------------------------ RemoteInput send

    /** Finds the notification's direct-reply action and delivers [replyText] through it. */
    private fun sendViaRemoteInput(notification: Notification, replyText: String): Boolean {
        val action = notification.replyAction() ?: return false
        return sendViaAction(action, replyText)
    }

    private fun sendViaAction(action: Notification.Action, replyText: String): Boolean {
        val remoteInputs = action.remoteInputs ?: return false
        return try {
            val results = Bundle()
            for (ri in remoteInputs) results.putCharSequence(ri.resultKey, replyText)
            val intent = Intent()
            RemoteInput.addResultsToIntent(remoteInputs, intent, results)
            // Delivers straight to WhatsApp's reply receiver — works with the screen OFF,
            // no UI interaction, no accessibility tap.
            action.actionIntent.send(appContext, 0, intent)
            true
        } catch (e: PendingIntent.CanceledException) {
            Log.w(TAG, "Reply PendingIntent was cancelled (notification gone)", e)
            false
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send reply via RemoteInput", e)
            false
        }
    }

    // ------------------------------------------------------------------ trail + notify

    /** Best-effort audit trail (v5 TaskTrailEntry): a failure here must never break the watcher. */
    private suspend fun persist(
        taskId: String,
        userText: String,
        steps: List<StepResult>,
        gates: List<GateDecisionRecord>,
    ) {
        try {
            val ttl = permissions.memoryTtlSeconds.first()
            memory.recordTrail(taskId, SKILL_NAME, userText, steps, gates, planDecision = null, ttl)
        } catch (e: Exception) {
            Log.w(TAG, "Could not write watcher trail", e)
        }
    }

    /** Tells the user a draft is waiting (only if the app may post notifications). */
    private fun notifyDraftReady(draft: PendingDraft) {
        try {
            if (!permissions.status().postNotifications) return
            val nm = appContext.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "WA watcher", NotificationManager.IMPORTANCE_DEFAULT)
            )
            val openApp = PendingIntent.getActivity(
                appContext, 0,
                Intent(appContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = NotificationCompat.Builder(appContext, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Balasan WhatsApp siap untuk ${draft.sender}")
                .setContentText("Aturan \"${draft.ruleName}\": \"${draft.replyText}\" — buka MOBIUS untuk kirim.")
                .setContentIntent(openApp)
                .setAutoCancel(true)
                .build()
            nm.notify(draft.id.hashCode(), n)
        } catch (e: Exception) {
            Log.w(TAG, "Could not post draft-ready notification", e)
        }
    }

    companion object {
        const val TAG = "WatcherEngine"
        const val SKILL_NAME = "wa-watcher"
        const val CHANNEL_ID = "mobius_watcher"
    }
}

/** The reply action of a notification: the first action carrying RemoteInput(s). */
private fun Notification.replyAction(): Notification.Action? =
    actions?.firstOrNull { it.remoteInputs?.isNotEmpty() == true }

/** A draft held for user approval (rule auto-send OFF). In-memory only: a process restart drops it. */
data class PendingDraft(
    val id: String,
    val ruleId: String,
    val ruleName: String,
    val packageName: String,
    val sender: String,
    val incomingText: String,
    val replyText: String,
    /** The original notification's reply action; null when the notification had none. */
    val replyAction: Notification.Action?,
    val taskId: String,
)

/**
 * Shared in-memory holder for held drafts. The listener-side engine and the Settings UI
 * each build their own WatcherEngine, so the drafts live here — not on an instance —
 * so both sides see the same list. Deliberately not persisted: a draft's reply action
 * (a live PendingIntent) cannot survive a process restart anyway.
 */
object WatcherPendingDrafts {
    private val _drafts = MutableStateFlow<List<PendingDraft>>(emptyList())
    val drafts: StateFlow<List<PendingDraft>> = _drafts.asStateFlow()

    fun add(draft: PendingDraft) {
        _drafts.value = _drafts.value + draft
    }

    /** Removes and returns the draft, or null when it is already gone. */
    fun remove(id: String): PendingDraft? {
        val found = _drafts.value.firstOrNull { it.id == id } ?: return null
        _drafts.value = _drafts.value.filter { it.id != id }
        return found
    }
}

/** Convenience accessor for the listener wiring (keeps the engine construction in one place). */
fun MobiusApp.watcherEngine(): WatcherEngine =
    WatcherEngine(this, WatcherRules(this), featureFlags, memoryStore, permissions)

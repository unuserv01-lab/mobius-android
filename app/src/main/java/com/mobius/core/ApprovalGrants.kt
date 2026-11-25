package com.mobius.core

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.approvalGrantsDataStore: DataStore<Preferences> by preferencesDataStore(name = "approval_grants")

/**
 * v8.1 — Approval bertier + grants yang diingat (Prioritas 3 breakdown kompetitor).
 *
 * POLA dari nanoMuse Sentinel (GPL-3.0-or-later): tier risiko + "remember"
 * sekali / chat ini / selamanya + kartu approval lintas perangkat. POLA SAJA —
 * semua kode di file ini ditulis ulang sendiri dari nol untuk MOBIUS
 * (Apache 2.0); TIDAK ADA kode nanoMuse yang disalin.
 *
 * Aturan yang TIDAK BOLEH dilanggar (Bagian 4 breakdown):
 *   - Scope SELAMANYA hanya untuk tier MODERATE. TIDAK PERNAH untuk SENSITIVE.
 *   - SENSITIVE (uang / kirim / hapus / tulis Google) SELALU minta approval;
 *     maksimal diingat "chat ini" — itupun hanya bila pemanggil memintanya.
 *   - Grants milik PERANGKAT ini saja (on-device). Tidak disinkronkan,
 *     tidak dikirim ke bridge / laptop / cloud. Laptop hanya MINTA approval;
 *     HP yang memutuskan (lihat RemoteGateway).
 *
 * Penyimpanan (tanpa mekanisme baru):
 *   - FOREVER: DataStore file "approval_grants" (pola yang sama seperti
 *     PermissionManager "mobius_prefs" / RemoteGateway "remote_gateway",
 *     hanya nama file berbeda — satu delegate per file, jadi tidak ada
 *     bentrok delegate ganda untuk "mobius_prefs").
 *   - CHAT: hanya di memori (map di instance ini), kunci = session id chat
 *     yang dibuat Orchestrator per proses. Mati bersama proses / saat
 *     [endChatSession] dipanggil — tidak pernah tersimpan ke disk, jadi
 *     tidak ada grant "chat ini" yang bocor ke sesi berikutnya.
 *   - ONCE: tidak disimpan sama sekali — berlaku untuk satu keputusan saja.
 */

/** Tingkat risiko satu aksi (lihat [ActionGate.classify]). */
enum class RiskTier {
    /** Read-only: baca layar, cari teks, navigasi. Jalan otomatis, tanpa dialog. */
    SAFE,

    /** Mengubah state tapi reversibel (ketuk, ketik, buka aplikasi, tulis file
     * baru). Approval pertama kali; boleh diingat (sekali / chat ini / selamanya). */
    MODERATE,

    /** Irreversible / uang / kirim / hapus / tulis Google. SELALU approval.
     * TIDAK BOLEH diingat selamanya; maksimal "chat ini". */
    SENSITIVE,
}

/** Berapa lama satu persetujuan diingat. */
enum class GrantScope {
    /** Berlaku untuk satu keputusan ini saja; tidak disimpan. */
    ONCE,

    /** Berlaku sampai akhir sesi chat ini (di memori saja, lihat [ApprovalGrants]). */
    CHAT,

    /** Berlaku selamanya sampai dicabut di Settings. HANYA untuk MODERATE. */
    FOREVER,
}

/** Satu persetujuan yang diingat. */
data class ApprovalGrant(
    /** Kunci aksi, mis. "tapText:com.shopee.id" atau "browser:clickText:example.com".
     * Dibuat [ActionGate.actionKey] / [RemoteGateway]; TIDAK berisi isi pesan,
     * kredensial, atau data pribadi — hanya nama tool + target. */
    val actionKey: String,
    val tier: RiskTier,
    val scope: GrantScope,
    /** Untuk CHAT: session id saat grant diberikan. Untuk FOREVER: "". */
    val chatSessionId: String = "",
    val grantedAtMillis: Long = 0L,
)

/** Keputusan satu permintaan approval (jawaban dialog). */
data class ApprovalDecision(
    val approved: Boolean,
    /** Scope yang dipilih pengguna di dialog. Hanya bermakna bila approved.
     * Pemanggil WAJIB memvalidasi via [ApprovalGrants.isScopeAllowed]. */
    val grant: GrantScope = GrantScope.ONCE,
)

/** Satu permintaan approval yang diteruskan ke UI (dialog / kartu). */
data class ApprovalRequest(
    /** Kalimat yang dibaca pengguna, mis. "Kirim email ke ...". */
    val description: String,
    val tier: RiskTier,
    /** Kunci untuk lookup grant; null = aksi ini tidak bisa diingat. */
    val actionKey: String?,
    /** "local" (tugas di HP) atau "laptop" (tugas via bridge, minta dari laptop). */
    val origin: String,
    /** Untuk asal laptop: daftar aksi yang ditahan (deskripsi per aksi). */
    val pendingActions: List<String> = emptyList(),
    /** Untuk asal laptop: id yang mengikat permintaan ini dengan tugas
     * ulang yang disetujui (lihat protokol approvalId di bridge/README.md). */
    val approvalId: String? = null,
)

class ApprovalGrants(context: Context) {
    private val appContext = context.applicationContext
    private val store: DataStore<Preferences> get() = appContext.approvalGrantsDataStore

    /** Grant CHAT (di memori saja): actionKey -> grant. */
    private val chatGrants = LinkedHashMap<String, ApprovalGrant>()

    /** Satu baris per grant FOREVER: "actionKey|TIER|grantedAtMillis". */
    private val KEY_FOREVER = stringSetPreferencesKey("grants_forever_v81")

    /** Benar bila scope ini diizinkan untuk tier ini. Aturan keras v8.1. */
    fun isScopeAllowed(tier: RiskTier, scope: GrantScope): Boolean = when (scope) {
        GrantScope.ONCE -> true
        GrantScope.CHAT -> true // CHAT diizinkan untuk MODERATE dan SENSITIVE.
        GrantScope.FOREVER -> tier == RiskTier.MODERATE // SELAMANYA HANYA MODERATE.
    }

    /**
     * Benar bila aksi ini sudah diizinkan oleh grant yang masih berlaku:
     * FOREVER (persisten, tier apa pun yang tersimpan — hanya MODERATE yang
     * bisa tersimpan) atau CHAT dengan session id yang sama. ONCE tidak
     * pernah tersimpan, jadi tidak pernah cocok di sini. SENSITIVE tidak
     * pernah punya grant FOREVER, jadi hanya CHAT yang bisa mencocokkannya.
     */
    suspend fun isGranted(actionKey: String, chatSessionId: String): Boolean {
        val chat = synchronized(chatGrants) { chatGrants[actionKey] }
        if (chat != null && chat.chatSessionId == chatSessionId) return true
        return foreverGrants().any { it.actionKey == actionKey }
    }

    /** Simpan grant. ONCE tidak disimpan. FOREVER untuk SENSITIVE DITOLAK
     * (fail closed, return false) — pemanggil harus memvalidasi dulu, ini
     * pertahanan kedua. CHAT disimpan di memori dengan session id ini. */
    suspend fun grant(grant: ApprovalGrant, chatSessionId: String): Boolean {
        if (!isScopeAllowed(grant.tier, grant.scope)) return false
        return when (grant.scope) {
            GrantScope.ONCE -> true // tidak disimpan; keputusan sekali itu sudah jalan.
            GrantScope.CHAT -> {
                synchronized(chatGrants) {
                    chatGrants[grant.actionKey] = grant.copy(chatSessionId = chatSessionId)
                }
                true
            }
            GrantScope.FOREVER -> {
                val now = System.currentTimeMillis()
                store.edit { prefs ->
                    val current = prefs[KEY_FOREVER].orEmpty().toMutableSet()
                    current.removeAll { it.substringBefore("|") == grant.actionKey }
                    current += "${grant.actionKey}|${grant.tier}|$now"
                    prefs[KEY_FOREVER] = current
                }
                true
            }
        }
    }

    /** Daftar grant FOREVER (untuk Settings). CHAT tidak ditampilkan — ia
     * hilang sendiri saat sesi berakhir; [chatGrantCount] menyebut jumlahnya. */
    fun foreverGrantsFlow(): Flow<List<ApprovalGrant>> = store.data.map { prefs ->
        prefs[KEY_FOREVER].orEmpty().mapNotNull { parse(it) }.sortedBy { it.actionKey }
    }

    suspend fun foreverGrants(): List<ApprovalGrant> = foreverGrantsFlow().first()

    /** Jumlah grant CHAT yang masih hidup di memori (untuk Settings). */
    fun chatGrantCount(): Int = synchronized(chatGrants) { chatGrants.size }

    /** Cabut satu grant FOREVER (dan CHAT dengan kunci yang sama, bila ada). */
    suspend fun revoke(actionKey: String) {
        synchronized(chatGrants) { chatGrants.remove(actionKey) }
        store.edit { prefs ->
            prefs[KEY_FOREVER] = prefs[KEY_FOREVER].orEmpty()
                .filter { it.substringBefore("|") != actionKey }.toSet()
        }
    }

    /** Cabut SEMUA grant (FOREVER + CHAT). Dipakai tombol "Cabut semua" di Settings. */
    suspend fun revokeAll() {
        synchronized(chatGrants) { chatGrants.clear() }
        store.edit { prefs -> prefs[KEY_FOREVER] = emptySet() }
    }

    /** Akhir sesi chat: semua grant CHAT hangus. Dipanggil Orchestrator saat
     * pengguna mulai chat baru (dan otomatis hilang saat proses mati). */
    fun endChatSession() {
        synchronized(chatGrants) { chatGrants.clear() }
    }

    private fun parse(raw: String): ApprovalGrant? {
        val parts = raw.split("|")
        if (parts.size != 3) return null
        val tier = runCatching { RiskTier.valueOf(parts[1]) }.getOrNull() ?: return null
        // Pertahanan: baris FOREVER untuk SENSITIVE tidak pernah ditulis oleh
        // grant() — bila ada (mis. data lama/rusak), abaikan.
        if (tier != RiskTier.MODERATE) return null
        return ApprovalGrant(
            actionKey = parts[0],
            tier = tier,
            scope = GrantScope.FOREVER,
            grantedAtMillis = parts[2].toLongOrNull() ?: 0L,
        )
    }
}

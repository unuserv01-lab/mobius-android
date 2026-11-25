package com.mobius.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.googleAuthDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "google_auth_prefs")

/**
 * v7.9-auth — encrypted storage for Google OAuth tokens.
 *
 * EncryptedSharedPreferences is NOT available (it would need a new Gradle
 * dependency), so the encryption is done by hand on top of the Android
 * Keystore:
 *
 * - One AES-256 key lives in the Keystore under [KEY_ALIAS]
 *   (AES/GCM/NoPadding, randomised IV per encryption, 128-bit GCM tag).
 *   The key never leaves the Keystore and is never written to a file.
 * - The token JSON (access + refresh + expiry + account email) is encrypted
 *   with that key; only the Base64 ciphertext (IV || ciphertext+tag) is
 *   stored, in an app-private DataStore file. The plaintext token NEVER
 *   touches disk, is NEVER logged, and NEVER enters the repo.
 * - The DataStore file is "google_auth_prefs" — deliberately NOT
 *   "mobius_prefs": [com.mobius.core.PermissionManager] already owns a
 *   `preferencesDataStore("mobius_prefs")` delegate, and two DataStore
 *   instances on the same file are unsupported (each keeps its own cached
 *   snapshot and can clobber the other's keys on write). One file, one
 *   owner — the token store gets its own file.
 *
 * The pending PKCE verifier + state (single-use randoms for the login flow
 * in progress) live in the same file as plaintext. That is safe: the
 * verifier alone is useless without the authorization code it belongs to,
 * both values are deleted the moment the code is exchanged, and the file
 * itself is app-private.
 */
object GoogleTokenStore {

    private object Keys {
        val TOKENS_ENC = stringPreferencesKey("google_tokens_enc")
        val OAUTH_STATE = stringPreferencesKey("google_oauth_state")
        val OAUTH_VERIFIER = stringPreferencesKey("google_oauth_verifier")
    }

    /** Refresh this far before the real expiry so a slow call never races the clock. */
    const val EXPIRY_SKEW_MS = 60_000L

    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "mobius_google_tokens"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_IV_BYTES = 12
    private const val GCM_TAG_BITS = 128
    private const val TAG = "GoogleTokenStore"

    // In-memory cache: the only writer is this object, so the cache cannot go
    // stale. It also lets the synchronous [GoogleAuth.isSignedIn] /
    // [GoogleAuth.accountEmail] answer without a blocking disk read once any
    // suspend load has happened.
    private val cacheLock = Mutex()
    @Volatile
    private var cached: StoredTokens? = null
    @Volatile
    private var cacheLoaded = false

    // ----- contract API (names/signatures are frozen — other builders code against them) -----

    suspend fun saveTokens(
        accessToken: String,
        refreshToken: String?,
        expiresAtMillis: Long,
        email: String,
    ) {
        val tokens = StoredTokens(accessToken, refreshToken, expiresAtMillis, email)
        val json = JSONObject().apply {
            put("access_token", accessToken)
            if (refreshToken != null) put("refresh_token", refreshToken)
            put("expires_at", expiresAtMillis)
            put("email", email)
        }.toString()
        val encrypted = encrypt(json)
            ?: throw IllegalStateException("Token encryption failed (Keystore unavailable)")
        // NOTE: the encrypted blob is written; the plaintext `json` is never
        // stored or logged.
        appDataStore.edit { it[Keys.TOKENS_ENC] = encrypted }
        cacheLock.withLock {
            cached = tokens
            cacheLoaded = true
        }
    }

    suspend fun loadTokens(): StoredTokens? {
        cacheLock.withLock {
            if (cacheLoaded) return cached
        }
        val encrypted = appDataStore.data.map { it[Keys.TOKENS_ENC] }.first() ?: run {
            cacheLock.withLock { cacheLoaded = true }
            return null
        }
        val json = decrypt(encrypted)
        if (json == null) {
            // Wrong key, corrupted blob, or the Keystore key was invalidated
            // (e.g. lock screen removed): the stored tokens are unrecoverable.
            // Drop them so the user gets a clean re-login instead of a
            // permanent "signed in but broken" state.
            Log.w(TAG, "Stored tokens undecryptable — clearing")
            clear()
            return null
        }
        val tokens = try {
            val obj = JSONObject(json)
            StoredTokens(
                accessToken = obj.getString("access_token"),
                refreshToken = obj.optString("refresh_token").takeIf { it.isNotEmpty() },
                expiresAtMillis = obj.getLong("expires_at"),
                email = obj.optString("email"),
            )
        } catch (e: Exception) {
            Log.w(TAG, "Stored tokens unreadable — clearing")
            clear()
            return null
        }
        cacheLock.withLock {
            cached = tokens
            cacheLoaded = true
        }
        return tokens
    }

    suspend fun clear() {
        appDataStore.edit { it.remove(Keys.TOKENS_ENC) }
        cacheLock.withLock {
            cached = null
            cacheLoaded = true
        }
    }

    // ----- synchronous cache peek (for GoogleAuth.isSignedIn / accountEmail) -----

    /**
     * Tokens already loaded by a previous suspend call, or null if nothing has
     * been loaded yet in this process. Never touches disk; the caller falls
     * back to a blocking [loadTokens] when this is null.
     */
    fun cachedTokens(): StoredTokens? = if (cacheLoaded) cached else null

    // ----- pending PKCE login (state + code_verifier, single-use) -----

    suspend fun savePendingAuth(state: String, codeVerifier: String) {
        appDataStore.edit {
            it[Keys.OAUTH_STATE] = state
            it[Keys.OAUTH_VERIFIER] = codeVerifier
        }
    }

    suspend fun loadPendingAuth(): PendingAuth? {
        val prefs = appDataStore.data.first()
        val state = prefs[Keys.OAUTH_STATE] ?: return null
        val verifier = prefs[Keys.OAUTH_VERIFIER] ?: return null
        return PendingAuth(state, verifier)
    }

    suspend fun clearPendingAuth() {
        appDataStore.edit {
            it.remove(Keys.OAUTH_STATE)
            it.remove(Keys.OAUTH_VERIFIER)
        }
    }

    // ----- Keystore AES-GCM -----

    /** Base64(IV || ciphertext+tag), or null when encryption is impossible. */
    private fun encrypt(plainText: String): String? = try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv // 12 random bytes, fresh per encryption
        val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(iv.size + cipherText.size)
        iv.copyInto(combined)
        cipherText.copyInto(combined, iv.size)
        Base64.encodeToString(combined, Base64.NO_WRAP)
    } catch (e: Exception) {
        Log.w(TAG, "Encrypt failed: ${e.javaClass.simpleName}")
        null
    }

    /** Inverse of [encrypt]; null when the blob cannot be decrypted. */
    private fun decrypt(encoded: String): String? = try {
        val combined = Base64.decode(encoded, Base64.DEFAULT)
        if (combined.size <= GCM_IV_BYTES) return null
        val iv = combined.copyOfRange(0, GCM_IV_BYTES)
        val cipherText = combined.copyOfRange(GCM_IV_BYTES, combined.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.doFinal(cipherText).toString(Charsets.UTF_8)
    } catch (e: Exception) {
        // Includes KeyPermanentlyInvalidatedException (key invalidated when
        // the lock screen changed) and AEADBadTagException (tampered blob).
        // The caller clears the unrecoverable tokens; never log the blob.
        if (isKeyInvalidated(e)) deleteKey()
        Log.w(TAG, "Decrypt failed: ${e.javaClass.simpleName}")
        null
    }

    private fun getOrCreateKey(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (store.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey?.let { return it }
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            // No setUserAuthenticationRequired: unlocking the phone must not
            // be needed for a background token refresh.
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
            .apply { init(spec) }
            .generateKey()
    }

    private fun isKeyInvalidated(e: Exception): Boolean =
        e.javaClass.name == "android.security.keystore.KeyPermanentlyInvalidatedException"

    private fun deleteKey() {
        runCatching {
            KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }.deleteEntry(KEY_ALIAS)
        }
    }

    // ----- small types -----

    /** The PKCE values for the login currently in flight (or null if none). */
    data class PendingAuth(val state: String, val codeVerifier: String)

    // Set once per process from the Application context; GoogleAuth calls
    // [init] lazily on first use so no Application-class edit is needed.
    // MainActivity.onCreate calls GoogleAuth.init(this) on the normal
    // path; any other entry point (e.g. a Service started after reboot)
    // must do the same before touching GoogleAuth, or the calls below
    // fail closed instead of crashing.
    // (v7.9-verifikasi: ini dulunya `companion object` di dalam `object`,
    // yang tidak legal di Kotlin — companion hanya boleh di class.
    // Membernya dipindah langsung ke badan object; panggilannya
    // `GoogleTokenStore.init(...)` / `.isInitialized()` tidak berubah.)
    private lateinit var appContext: Context

    internal fun init(context: Context) {
        if (!::appContext.isInitialized) appContext = context.applicationContext
    }

    internal fun isInitialized(): Boolean = ::appContext.isInitialized

    private val appDataStore: DataStore<Preferences>
        get() {
            check(isInitialized()) {
                "GoogleAuth.init(context) must be called before using GoogleTokenStore"
            }
            return appContext.googleAuthDataStore
        }
}

/** Contract type (frozen): the tokens for one signed-in Google account. */
data class StoredTokens(
    val accessToken: String,
    val refreshToken: String?,
    val expiresAtMillis: Long,
    val email: String,
) {
    /** True when the access token is expired (or about to be, within the skew). */
    fun isAccessExpired(nowMillis: Long = System.currentTimeMillis()): Boolean =
        nowMillis >= expiresAtMillis - GoogleTokenStore.EXPIRY_SKEW_MS

    /** True when this record can still produce a working access token. */
    fun hasUsableToken(nowMillis: Long = System.currentTimeMillis()): Boolean =
        accessToken.isNotBlank() && (!isAccessExpired(nowMillis) || !refreshToken.isNullOrBlank())
}

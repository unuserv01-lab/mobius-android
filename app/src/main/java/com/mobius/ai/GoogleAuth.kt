package com.mobius.ai

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * v7.9-auth — Google Sign-In (OAuth 2.0 + PKCE), MANUAL.
 *
 * No new Gradle dependency: there is no `play-services-auth`, no
 * `firebase-auth`, and no `androidx.browser` in this project, so the flow is
 * built by hand on HttpURLConnection + org.json (same pattern as
 * [OpenRouterAI]):
 *
 * 1. [buildAuthIntent] builds the Google authorization URL (PKCE S256,
 *    `access_type=offline` so Google returns a refresh token,
 *    `prompt=consent` so a re-login re-issues one) and returns an
 *    ACTION_VIEW intent for the system browser.
 * 2. The browser returns to the app via the custom-scheme deep link
 *    `com.mobius:/oauth2redirect?code=...&state=...` (registered in
 *    AndroidManifest.xml). MainActivity forwards it to [handleRedirect].
 * 3. [handleRedirect] checks `state` (CSRF), exchanges the code for tokens
 *    at oauth2.googleapis.com/token with the PKCE `code_verifier` and NO
 *    client_secret (installed apps must not carry one), fetches the account
 *    email from the Gmail profile endpoint, and stores everything encrypted
 *    via [GoogleTokenStore].
 * 4. [getAccessToken] hands out a working access token, refreshing it
 *    silently when it is expired (or about to expire). Callers (GoogleApi)
 *    just send it as `Authorization: Bearer <token>`.
 *
 * Tokens are NEVER logged and NEVER leave the phone except as a Bearer
 * header to Google APIs.
 *
 * ### CLIENT_ID — SETUP REQUIRED (Tuan)
 *
 * The Android OAuth client ID is a PUBLIC identifier (installed-app client
 * IDs are not secrets — the security comes from PKCE + the registered
 * redirect URI + the package/SHA-1 binding), so it lives here as a plain
 * constant. It is currently EMPTY on purpose: the project's
 * `app/google-services.json` contains NO `oauth_client` entry at all
 * (both `oauth_client` arrays are `[]`), so there is no client ID to read
 * from it. Before the first login Tuan must:
 *
 * 1. Google Cloud Console → project gen-lang-client-0936734513 → APIs &
 *    Services → Credentials → Create Credentials → OAuth client ID →
 *    Application type **Android**, package name `com.mobius`, SHA-1
 *    `C1:2C:18:FE:F2:94:4E:F7:6B:BD:81:E5:BC:2D:51:0A:0E:1D:A4:2A`
 *    (SHA-1 of the permanent signing certificate of the v7.8 APK).
 * 2. Copy the issued client ID (`...apps.googleusercontent.com`) into
 *    [CLIENT_ID] below, and re-download `google-services.json` so the
 *    `oauth_client` entry is recorded there too.
 * 3. On the OAuth consent screen: add the scopes in [SCOPES] (Gmail,
 *    Calendar, Drive, Docs) and add Tuan's Google account as a test user
 *    while the app is in "Testing" publishing status — otherwise Google
 *    refuses the login with `access_blocked`.
 *
 * Until [CLIENT_ID] is filled, [buildAuthIntent] throws IllegalStateException
 * with these instructions instead of opening a broken login page.
 *
 * ### init
 *
 * Call [init] once with any Context (MainActivity.onCreate does it) before
 * any other call. Calls made before init fail closed: [getAccessToken]
 * returns null, [isSignedIn]/[accountEmail] return false/null,
 * [buildAuthIntent] throws.
 */
object GoogleAuth {

    // ----- configuration (public identifiers only — no secrets here) -----

    /**
     * Android OAuth client ID (`...apps.googleusercontent.com`).
     * EMPTY until the setup above is done — see the class KDoc.
     */
    const val CLIENT_ID = "1035973413407-qv2q555k7qlbb1o16u41mnd3pmh48eor.apps.googleusercontent.com"

    /** Custom scheme for the OAuth redirect; must match AndroidManifest.xml. */
    const val REDIRECT_SCHEME = "com.mobius"
    const val REDIRECT_URI = "com.mobius:/oauth2redirect"

    const val AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth"
    const val TOKEN_URL = "https://oauth2.googleapis.com/token"
    const val GMAIL_PROFILE_URL = "https://gmail.googleapis.com/gmail/v1/users/me/profile"

    /** Scopes the GoogleApi workstream needs (Gmail + Calendar + Drive + Docs). */
    val SCOPES: List<String> = listOf(
        "https://www.googleapis.com/auth/gmail.readonly",
        "https://www.googleapis.com/auth/gmail.send",
        "https://www.googleapis.com/auth/calendar.readonly",
        "https://www.googleapis.com/auth/calendar.events",
        "https://www.googleapis.com/auth/drive.readonly",
        "https://www.googleapis.com/auth/drive.file",
        "https://www.googleapis.com/auth/docs.readonly",
    )

    private const val TAG = "GoogleAuth"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 30_000

    // Serialises refreshes: two parallel getAccessToken() calls must not both
    // refresh and race the stored tokens.
    private val refreshMutex = Mutex()

    // In-memory copy of the pending PKCE values (fast path); the same values
    // are also persisted in GoogleTokenStore so the login survives the app
    // process being killed while the browser is in front.
    @Volatile
    private var pendingState: String? = null
    @Volatile
    private var pendingVerifier: String? = null

    // ----- setup -----

    fun init(context: Context) {
        GoogleTokenStore.init(context)
    }

    // ----- contract API (names/signatures are frozen — other builders code against them) -----

    /**
     * Builds the intent that starts the Google sign-in in the system browser.
     * The caller (an Activity) just does `startActivity(GoogleAuth.buildAuthIntent())`.
     * Throws IllegalStateException when [CLIENT_ID] is not configured yet.
     */
    fun buildAuthIntent(): Intent {
        check(GoogleTokenStore.isInitialized()) {
            "GoogleAuth.init(context) must be called before buildAuthIntent()"
        }
        check(CLIENT_ID.isNotBlank()) {
            "Google OAuth client ID belum dikonfigurasi: buat OAuth client ID Android " +
                "(package com.mobius) di Google Cloud Console proyek " +
                "gen-lang-client-0936734513, lalu isi GoogleAuth.CLIENT_ID. " +
                "Lihat KDoc GoogleAuth untuk langkah lengkapnya."
        }
        val state = randomBase64Url(32)
        val verifier = randomBase64Url(32) // 43 chars: valid PKCE code_verifier
        val challenge = codeChallengeS256(verifier)
        pendingState = state
        pendingVerifier = verifier
        // Persisted (not just in-memory): Android may kill our process while
        // the browser is in front; the redirect must still find the verifier.
        runBlocking { GoogleTokenStore.savePendingAuth(state, verifier) }
        val uri = Uri.Builder()
            .scheme("https")
            .authority("accounts.google.com")
            .path("/o/oauth2/v2/auth")
            .appendQueryParameter("client_id", CLIENT_ID)
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("scope", SCOPES.joinToString(" "))
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("state", state)
            .appendQueryParameter("access_type", "offline")
            .appendQueryParameter("prompt", "consent")
            .appendQueryParameter("include_granted_scopes", "true")
            .build()
        // NOTE: the URL contains code_challenge + state (public, single-use)
        // but never a token, code, or verifier — safe to put in an intent.
        return Intent(Intent.ACTION_VIEW, uri)
    }

    /**
     * Handles the deep-link redirect from the browser. Returns true only when
     * the code was exchanged and tokens were stored. False = not our redirect,
     * user denied access, state mismatch, or the exchange failed (the user can
     * simply tap sign-in again).
     */
    suspend fun handleRedirect(uri: Uri): Boolean {
        if (uri.scheme != REDIRECT_SCHEME || uri.path != "/oauth2redirect") return false
        uri.getQueryParameter("error")?.let {
            // User denied access on the consent screen, or Google refused
            // (e.g. access_blocked while the app is in Testing). Never log
            // error_description: it can echo back request details.
            Log.w(TAG, "OAuth redirect error: $it")
            clearPending()
            return false
        }
        val code = uri.getQueryParameter("code") ?: run {
            Log.w(TAG, "OAuth redirect without code")
            return false
        }
        val returnedState = uri.getQueryParameter("state")
        val (state, verifier) = pendingAuth() ?: run {
            Log.w(TAG, "OAuth redirect with no pending login")
            return false
        }
        if (returnedState == null || returnedState != state) {
            Log.w(TAG, "OAuth state mismatch — possible CSRF, rejecting")
            clearPending()
            return false
        }
        return try {
            val tokenJson = withContext(Dispatchers.IO) {
                postForm(
                    TOKEN_URL,
                    mapOf(
                        "grant_type" to "authorization_code",
                        "code" to code,
                        "redirect_uri" to REDIRECT_URI,
                        "client_id" to CLIENT_ID,
                        "code_verifier" to verifier,
                    ),
                )
            }
            val accessToken = tokenJson.getString("access_token")
            val expiresInSec = tokenJson.optLong("expires_in", 3600)
            // Google returns a refresh token only on the first consent (or
            // when prompt=consent forces it, which we do). If it is absent,
            // keep the previous one so a re-login never loses offline access.
            val newRefresh = tokenJson.optString("refresh_token").takeIf { it.isNotEmpty() }
            val oldRefresh = GoogleTokenStore.loadTokens()?.refreshToken
            val email = fetchAccountEmail(accessToken)
            GoogleTokenStore.saveTokens(
                accessToken = accessToken,
                refreshToken = newRefresh ?: oldRefresh,
                expiresAtMillis = System.currentTimeMillis() + expiresInSec * 1000,
                email = email,
            )
            clearPending()
            Log.i(TAG, "Google sign-in succeeded")
            true
        } catch (e: Exception) {
            // Exchange failed (network, bad code, revoked client). The user
            // just retries; the pending values are dropped so a stale code
            // can never be replayed.
            Log.w(TAG, "Token exchange failed: ${e.javaClass.simpleName}: ${safeMessage(e)}")
            clearPending()
            false
        }
    }

    /**
     * Returns a working access token, refreshing it first when expired.
     * Null = not signed in, or the refresh failed (tokens were cleared, so
     * the user must sign in again). Never returns an expired token.
     */
    suspend fun getAccessToken(): String? {
        if (!GoogleTokenStore.isInitialized()) {
            Log.w(TAG, "getAccessToken before init — call GoogleAuth.init(context) first")
            return null
        }
        val tokens = GoogleTokenStore.loadTokens() ?: return null
        if (!tokens.isAccessExpired()) return tokens.accessToken
        if (tokens.refreshToken.isNullOrBlank()) {
            GoogleTokenStore.clear()
            return null
        }
        return refreshMutex.withLock {
            // Another caller may have refreshed while we waited: re-read.
            val fresh = GoogleTokenStore.loadTokens() ?: return@withLock null
            if (!fresh.isAccessExpired()) return@withLock fresh.accessToken
            val newRefresh = fresh.refreshToken ?: run {
                GoogleTokenStore.clear()
                return@withLock null
            }
            try {
                val tokenJson = withContext(Dispatchers.IO) {
                    postForm(
                        TOKEN_URL,
                        mapOf(
                            "grant_type" to "refresh_token",
                            "refresh_token" to newRefresh,
                            "client_id" to CLIENT_ID,
                        ),
                    )
                }
                val accessToken = tokenJson.getString("access_token")
                val expiresInSec = tokenJson.optLong("expires_in", 3600)
                GoogleTokenStore.saveTokens(
                    accessToken = accessToken,
                    // Google usually returns no new refresh token: keep the old one.
                    refreshToken = tokenJson.optString("refresh_token")
                        .takeIf { it.isNotEmpty() } ?: newRefresh,
                    expiresAtMillis = System.currentTimeMillis() + expiresInSec * 1000,
                    email = fresh.email,
                )
                accessToken
            } catch (e: Exception) {
                // Refresh token revoked/expired or network down: staying
                // "signed in" would be a lie, so clear everything.
                Log.w(TAG, "Token refresh failed: ${e.javaClass.simpleName}: ${safeMessage(e)}")
                GoogleTokenStore.clear()
                null
            }
        }
    }

    /**
     * Synchronous: true when tokens exist that can still yield a working
     * access token (valid access token, or an expired one with a refresh
     * token). Reads the in-memory cache when available; otherwise one
     * blocking DataStore read (fast — a few ms — on first use per process).
     */
    fun isSignedIn(): Boolean {
        if (!GoogleTokenStore.isInitialized()) return false
        GoogleTokenStore.cachedTokens()?.let { return it.hasUsableToken() }
        return runBlocking { GoogleTokenStore.loadTokens() }?.hasUsableToken() ?: false
    }

    /** Synchronous: the signed-in account's email, or null when not signed in. */
    fun accountEmail(): String? {
        if (!GoogleTokenStore.isInitialized()) return null
        GoogleTokenStore.cachedTokens()?.email?.takeIf { it.isNotEmpty() }?.let { return it }
        return runBlocking { GoogleTokenStore.loadTokens() }?.email?.takeIf { it.isNotEmpty() }
    }

    /** Signs out: deletes the tokens (and any pending login) from the phone. */
    suspend fun signOut() {
        if (!GoogleTokenStore.isInitialized()) return
        GoogleTokenStore.clear()
        clearPending()
        Log.i(TAG, "Signed out — tokens cleared")
    }

    // ----- internals -----

    private suspend fun pendingAuth(): Pair<String, String>? {
        pendingState?.let { state ->
            pendingVerifier?.let { verifier -> return state to verifier }
        }
        val stored = GoogleTokenStore.loadPendingAuth() ?: return null
        pendingState = stored.state
        pendingVerifier = stored.codeVerifier
        return stored.state to stored.codeVerifier
    }

    private suspend fun clearPending() {
        pendingState = null
        pendingVerifier = null
        runCatching { GoogleTokenStore.clearPendingAuth() }
    }

    /** The Gmail profile endpoint returns the account email for the token. */
    private suspend fun fetchAccountEmail(accessToken: String): String = try {
        val json = withContext(Dispatchers.IO) { getJson(GMAIL_PROFILE_URL, accessToken) }
        json.optString("emailAddress")
    } catch (e: Exception) {
        // Tokens are still valid; the email is display-only. Keep "" so the
        // sign-in succeeds and accountEmail() reports null.
        Log.w(TAG, "Gmail profile lookup failed: ${e.javaClass.simpleName}")
        ""
    }

    /** POST application/x-www-form-urlencoded; throws on any non-200 status. */
    private fun postForm(url: String, params: Map<String, String>): JSONObject {
        val body = params.entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("Accept", "application/json")
        }
        try {
            conn.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body) }
            val code = conn.responseCode
            val raw = readBody(conn)
            if (code != HttpURLConnection.HTTP_OK) {
                // Never log the body: error responses can echo request data.
                throw GoogleAuthException("Token endpoint HTTP $code")
            }
            return JSONObject(raw)
        } finally {
            conn.disconnect()
        }
    }

    /** GET with Bearer auth; throws on any non-200 status. */
    private fun getJson(url: String, accessToken: String): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $accessToken")
        }
        try {
            val code = conn.responseCode
            val raw = readBody(conn)
            if (code != HttpURLConnection.HTTP_OK) {
                throw GoogleAuthException("Google API HTTP $code")
            }
            return JSONObject(raw)
        } finally {
            conn.disconnect()
        }
    }

    private fun readBody(conn: HttpURLConnection): String {
        val stream = if (conn.responseCode < HttpURLConnection.HTTP_BAD_REQUEST) {
            conn.inputStream
        } else {
            conn.errorStream
        } ?: return ""
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    private fun randomBase64Url(bytes: Int): String {
        val buf = ByteArray(bytes)
        SecureRandom().nextBytes(buf)
        return Base64.encodeToString(buf, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun codeChallengeS256(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    /**
     * Exception messages must never carry secrets: our own messages contain
     * only HTTP statuses, but a wrapped cause could in theory echo a token,
     * so only the exception class + our own message are ever logged.
     */
    private fun safeMessage(e: Exception): String =
        if (e is GoogleAuthException) e.message ?: e.javaClass.simpleName else e.javaClass.simpleName
}

/** Thrown when a Google OAuth/API call fails; the message holds only a status, never a secret. */
class GoogleAuthException(message: String) : Exception(message)

package com.mobius.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.mobius.MobiusApp
import com.mobius.ai.GoogleAuth
import kotlinx.coroutines.launch

/**
 * v7.9-ui — Settings section for the Google account (Gmail / Calendar /
 * Docs / Drive). Follows the OpenRouterSection pattern: title in tertiary,
 * status line (green when active, error-red when not), one primary action,
 * short note. No token or credential is ever shown or logged here.
 *
 * Flow: "Masuk dengan Google" launches [GoogleAuth.buildAuthIntent]
 * (ACTION_VIEW to the Google sign-in page, opened in the browser). The
 * sign-in itself happens outside the app, so the section re-reads the auth
 * state on every ON_RESUME — same pattern as rememberPermissionStatus in
 * OnboardingScreen.kt. "Keluar" ALWAYS asks first via
 * [GoogleApprovalDialog]; only after confirmation does it call
 * [GoogleAuth.signOut].
 *
 * Strings: kept local to this file (ID + EN, same LanguageManager source
 * as SettingsScreen) instead of MobiusStrings.kt, because this file is the
 * exclusive write scope of the v7.9-ui builder — the coordinator may move
 * them into MobiusStrings.kt later if desired. Default language is
 * Bahasa Indonesia (Tuan's language).
 */
@Composable
fun GoogleAccountSection(app: MobiusApp) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Same language source as SettingsScreen (v7): the DataStore setting,
    // not the system locale — so the card follows the app language.
    val languageManager = remember { LanguageManager(app.applicationContext) }
    val lang by languageManager.language.collectAsState(initial = AppLanguage.ID)
    val s = if (lang == AppLanguage.EN) GoogleAccountStringsEn else GoogleAccountStringsId

    var signedIn by remember { mutableStateOf(GoogleAuth.isSignedIn()) }
    var email by remember { mutableStateOf(GoogleAuth.accountEmail()) }
    var showLogoutDialog by remember { mutableStateOf(false) }
    var signingOut by remember { mutableStateOf(false) }
    var statusNote by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        signedIn = GoogleAuth.isSignedIn()
        email = GoogleAuth.accountEmail()
    }

    // Login happens in the browser: the user leaves the app and comes back,
    // so re-read the auth state on every resume.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            s.title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.tertiary,
        )
        Text(s.desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = when {
                !signedIn -> s.notSignedIn
                email != null -> s.signedInAs.format(email)
                else -> s.signedIn
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (signedIn) {
                MaterialTheme.colorScheme.tertiary
            } else {
                MaterialTheme.colorScheme.error
            },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (signedIn) {
                OutlinedButton(
                    onClick = { showLogoutDialog = true },
                    enabled = !signingOut,
                ) { Text(if (signingOut) s.loggingOut else s.logout) }
            } else {
                GradientButton(onClick = {
                    context.startActivity(GoogleAuth.buildAuthIntent())
                }) { Text(s.login) }
            }
        }
        statusNote?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }

    if (showLogoutDialog) {
        GoogleApprovalDialog(
            title = s.logoutTitle,
            details = s.logoutDetails.format(email ?: ""),
            confirmLabel = s.logoutConfirm,
            cancelLabel = s.logoutCancel,
            onConfirm = {
                showLogoutDialog = false
                signingOut = true
                scope.launch {
                    try {
                        GoogleAuth.signOut()
                    } finally {
                        refresh()
                        signingOut = false
                        statusNote = s.loggedOut
                    }
                }
            },
            onCancel = { showLogoutDialog = false },
        )
    }
}

/** Static strings for this section only (see the KDoc on [GoogleAccountSection]). */
private data class GoogleAccountStrings(
    val title: String,
    val desc: String,
    val signedInAs: String,
    val signedIn: String,
    val notSignedIn: String,
    val login: String,
    val logout: String,
    val loggingOut: String,
    val logoutTitle: String,
    val logoutDetails: String,
    val logoutConfirm: String,
    val logoutCancel: String,
    val loggedOut: String,
)

private val GoogleAccountStringsId = GoogleAccountStrings(
    title = "Akun Google",
    desc = "Masuk dengan akun Google supaya MOBIUS bisa membantu lewat Gmail, Kalender, Docs, dan Drive — hanya untuk tugas yang kamu minta. Izin yang diberikan: membaca email, mengirim email, membaca dan membuat acara kalender, serta membaca file di Drive/Docs.",
    signedInAs = "Masuk sebagai %s",
    signedIn = "Sudah masuk",
    notSignedIn = "Belum masuk — tugas Google belum aktif",
    login = "Masuk dengan Google",
    logout = "Keluar",
    loggingOut = "Keluar…",
    logoutTitle = "Keluar dari Akun Google?",
    logoutDetails = "Akun %s akan diputus dari MOBIUS. Tugas Gmail, Kalender, Docs, dan Drive tidak bisa jalan sampai kamu masuk lagi.",
    logoutConfirm = "Ya, keluar",
    logoutCancel = "Batal",
    loggedOut = "Sudah keluar dari Akun Google.",
)

private val GoogleAccountStringsEn = GoogleAccountStrings(
    title = "Google account",
    desc = "Sign in with Google so MOBIUS can help via Gmail, Calendar, Docs, and Drive — only for tasks you ask for. Permissions granted: read email, send email, read and create calendar events, and read files in Drive/Docs.",
    signedInAs = "Signed in as %s",
    signedIn = "Signed in",
    notSignedIn = "Not signed in — Google tasks are inactive",
    login = "Sign in with Google",
    logout = "Sign out",
    loggingOut = "Signing out…",
    logoutTitle = "Sign out of your Google account?",
    logoutDetails = "%s will be disconnected from MOBIUS. Gmail, Calendar, Docs, and Drive tasks won't work until you sign in again.",
    logoutConfirm = "Yes, sign out",
    logoutCancel = "Cancel",
    loggedOut = "Signed out of your Google account.",
)

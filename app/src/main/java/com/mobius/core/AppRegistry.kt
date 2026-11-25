package com.mobius.core

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

private val Context.appRegistryDataStore: DataStore<Preferences> by preferencesDataStore(name = "app_registry")

/** One launchable app found by the scan: package, display label, launcher icon (may be null). */
data class InstalledApp(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
)

/**
 * v7.9-tambahan — registry aplikasi yang diizinkan (allowlist milik user, default-deny).
 *
 *   - [scanInstalled] lists every installed app that can be opened (PackageManager,
 *     ACTION_MAIN + CATEGORY_LAUNCHER; apps without a launcher activity are excluded).
 *   - [isAllowed] is the check every launch must pass: a package is allowed when the
 *     user explicitly turned it ON, or when it is a [KnownApps] package that the user
 *     never turned OFF; everything else is OFF by default (default-deny).
 *   - The registry decides "may this app be OPENED". It never overrides [ActionGate]:
 *     a sensitive app (bank/fintech) stays DENIED by the gate even when its registry
 *     toggle is ON — the gate is the hard limit, the registry is the user's allowlist.
 *
 * Storage: its OWN DataStore file ("app_registry"), two string sets of explicit
 * user decisions — [KEY_ALLOWED] (turned ON) and [KEY_DENIED] (turned OFF). Nothing
 * is stored for apps the user never touched, so the KnownApps default stays live:
 * if KnownApps grows later, the new known app is ON without a migration.
 *
 * LIMIT (honest, device-side): on Android 11+ the scan only sees the apps the
 * system lets this app see. The manifest only declares <queries> for WhatsApp /
 * Shopee / Tokopedia, so the scan list on Android 11+ is system apps + those
 * declared packages (+ MOBIUS itself, which is excluded below) — third-party
 * apps outside the manifest declarations may be missing from the LIST, even
 * though the allow/deny CHECK itself ([isAllowed]) works for any package name.
 * A full list needs a manifest <queries><intent> entry for ACTION_MAIN +
 * CATEGORY_LAUNCHER (or QUERY_ALL_PACKAGES) — that file is owned by another
 * workstream, so it is NOT added here; the coordinator decides.
 */
class AppRegistry(context: Context) {
    private val appContext = context.applicationContext
    private val store: DataStore<Preferences> get() = appContext.appRegistryDataStore

    /**
     * Scans installed apps that can be opened. Runs on Dispatchers.IO (icon loading
     * touches the PackageManager). Sorted by label, MOBIUS itself excluded (opening
     * itself is meaningless), duplicates collapsed by package.
     */
    @Suppress("DEPRECATION") // queryIntentActivities(Intent, Int) is deprecated on API 33+ but still works.
    suspend fun scanInstalled(): List<InstalledApp> = withContext(Dispatchers.IO) {
        val pm = appContext.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val infos = pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        val seen = LinkedHashSet<String>()
        val out = ArrayList<InstalledApp>()
        for (info in infos) {
            val pkg = info.activityInfo?.packageName ?: continue
            if (pkg == appContext.packageName) continue
            if (!seen.add(pkg)) continue
            // Only apps that can actually be opened (have a launch intent).
            if (pm.getLaunchIntentForPackage(pkg) == null) continue
            val label = info.loadLabel(pm)?.toString()?.takeIf { it.isNotBlank() } ?: pkg
            val icon = runCatching { info.loadIcon(pm) }.getOrNull()
            out += InstalledApp(pkg, label, icon)
        }
        out.sortedBy { it.label.lowercase() }
    }

    /** Packages MOBIUS already knows ([KnownApps]) — ON by default unless the user turned them OFF. */
    val defaultAllowedPackages: Set<String> get() = KnownApps.PACKAGES.values.toSet()

    /** Display name for a package ("YouTube"), or the raw package name when unknown. */
    fun displayName(packageName: String): String =
        KnownApps.displayName(packageName) ?: packageName

    /** One-shot allow check (suspend — reads DataStore once). */
    suspend fun isAllowed(packageName: String): Boolean {
        val prefs = store.data.first()
        return isAllowed(prefs, packageName)
    }

    /** Flow version for UI that watches one package. */
    fun isAllowedFlow(packageName: String): Flow<Boolean> =
        store.data.map { isAllowed(it, packageName) }

    /** One-shot map for a whole scanned list (one DataStore read, not one per app). */
    suspend fun allowedMap(packageNames: List<String>): Map<String, Boolean> {
        val prefs = store.data.first()
        return packageNames.associateWith { isAllowed(prefs, it) }
    }

    /** Records the user's explicit decision for one package (toggle in Settings). */
    suspend fun setAllowed(packageName: String, allowed: Boolean) {
        store.edit { prefs ->
            val allow = (prefs[KEY_ALLOWED] ?: emptySet()).toMutableSet()
            val deny = (prefs[KEY_DENIED] ?: emptySet()).toMutableSet()
            if (allowed) {
                allow += packageName
                deny -= packageName
            } else {
                deny += packageName
                allow -= packageName
            }
            prefs[KEY_ALLOWED] = allow
            prefs[KEY_DENIED] = deny
        }
    }

    private fun isAllowed(prefs: Preferences, packageName: String): Boolean {
        val denied = prefs[KEY_DENIED] ?: emptySet()
        if (packageName in denied) return false
        val allowed = prefs[KEY_ALLOWED] ?: emptySet()
        if (packageName in allowed) return true
        return packageName in defaultAllowedPackages
    }

    companion object {
        private val KEY_ALLOWED = stringSetPreferencesKey("allowed_overrides")
        private val KEY_DENIED = stringSetPreferencesKey("denied_overrides")
    }
}

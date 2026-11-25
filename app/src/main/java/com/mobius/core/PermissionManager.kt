package com.mobius.core

import android.Manifest
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mobius.accessibility.MobiusAccessibilityService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.mobiusDataStore: DataStore<Preferences> by preferencesDataStore(name = "mobius_prefs")

/** Snapshot of every opt-in capability (spec section 7). */
data class PermissionStatus(
    val overlay: Boolean,
    val accessibility: Boolean,
    /**
     * v7.7 bug 3 — LIVE accessibility service (bound right now), not just the
     * toggle. The toggle can be ON while the service is dead (after an APK
     * update, or killed by MIUI), so [accessibility] alone must never be
     * shown as "the service works".
     */
    val accessibilityLive: Boolean,
    val notificationListener: Boolean,
    val usageStats: Boolean,
    val postNotifications: Boolean,
    val microphone: Boolean,
)

/**
 * v7.7 bug 3 — the REAL accessibility state. The toggle and the live service
 * are two different things; the UI and the agents must both use this.
 */
enum class AccessibilityState {
    /** Toggle OFF in Settings. */
    OFF,
    /** Toggle ON, but the service is not bound/running right now. */
    ENABLED_NOT_RUNNING,
    /** Toggle ON and the service is bound — cross-app actions can really run. */
    LIVE,
}

/**
 * Permission gate + user preferences (DataStore).
 * Every cross-app capability is opt-in; callers check [status] and degrade gracefully (P8).
 * The "Allow cloud AI" flag is NOT an Android permission; it is a separate gate, default OFF.
 */
class PermissionManager(context: Context) {
    private val appContext = context.applicationContext
    private val store: DataStore<Preferences> get() = appContext.mobiusDataStore

    private object Keys {
        val CLOUD_AI = booleanPreferencesKey("cloud_ai_allowed")
        val CONFIRM_SEND = booleanPreferencesKey("confirm_before_send")
        val TTL_SECONDS = longPreferencesKey("memory_ttl_seconds")
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        val BUBBLE_ENABLED = booleanPreferencesKey("bubble_enabled")
        // v7.2 — OpenRouter fallback: key + model. The key is stored on the phone
        // only, in the same app-private DataStore; it is NEVER logged or shown
        // in full anywhere in the app.
        val OPENROUTER_KEY = stringPreferencesKey("openrouter_api_key")
        val OPENROUTER_MODEL = stringPreferencesKey("openrouter_model")
    }

    // ----- preferences -----
    val cloudAiAllowed: Flow<Boolean> = store.data.map { it[Keys.CLOUD_AI] ?: false }
    val confirmBeforeSend: Flow<Boolean> = store.data.map { it[Keys.CONFIRM_SEND] ?: true }
    val memoryTtlSeconds: Flow<Long> = store.data.map { it[Keys.TTL_SECONDS] ?: AppConfig.DEFAULT_TTL_SECONDS }
    val onboardingDone: Flow<Boolean> = store.data.map { it[Keys.ONBOARDING_DONE] ?: false }
    val bubbleEnabled: Flow<Boolean> = store.data.map { it[Keys.BUBBLE_ENABLED] ?: false }
    // v7.2 — OpenRouter fallback settings (blank key = fallback inactive).
    val openRouterApiKey: Flow<String> = store.data.map { it[Keys.OPENROUTER_KEY] ?: "" }
    val openRouterModel: Flow<String> =
        store.data.map { it[Keys.OPENROUTER_MODEL] ?: AppConfig.DEFAULT_OPENROUTER_MODEL }

    suspend fun isCloudAiAllowed(): Boolean = cloudAiAllowed.first()

    suspend fun getOpenRouterApiKey(): String = openRouterApiKey.first()
    suspend fun getOpenRouterModel(): String = openRouterModel.first()

    suspend fun setCloudAiAllowed(value: Boolean) = store.edit { it[Keys.CLOUD_AI] = value }
    suspend fun setConfirmBeforeSend(value: Boolean) = store.edit { it[Keys.CONFIRM_SEND] = value }
    suspend fun setMemoryTtlSeconds(value: Long) = store.edit { it[Keys.TTL_SECONDS] = value }
    suspend fun setOnboardingDone(value: Boolean) = store.edit { it[Keys.ONBOARDING_DONE] = value }
    suspend fun setBubbleEnabled(value: Boolean) = store.edit { it[Keys.BUBBLE_ENABLED] = value }
    suspend fun setOpenRouterApiKey(value: String) = store.edit { it[Keys.OPENROUTER_KEY] = value }
    suspend fun setOpenRouterModel(value: String) = store.edit { it[Keys.OPENROUTER_MODEL] = value }

    /** v7.7 bug 2 — one atomic edit for key + model (two separate edits could leave only one saved). */
    suspend fun setOpenRouterSettings(key: String, model: String) = store.edit {
        it[Keys.OPENROUTER_KEY] = key
        it[Keys.OPENROUTER_MODEL] = model
    }

    // ----- Android permission state -----
    fun status(): PermissionStatus = PermissionStatus(
        overlay = Settings.canDrawOverlays(appContext),
        accessibility = isAccessibilityEnabled(),
        accessibilityLive = MobiusAccessibilityService.instance != null,
        notificationListener = NotificationManagerCompat.getEnabledListenerPackages(appContext)
            .contains(appContext.packageName),
        usageStats = isUsageAccessGranted(),
        postNotifications = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED,
        microphone = ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED,
    )

    /**
     * v7.7 bug 3 — the single source of truth for "is accessibility usable".
     * Toggle (Settings.Secure) AND live binding (service instance) must agree;
     * anything else is a mismatch the UI and the agents must report honestly.
     */
    fun accessibilityState(): AccessibilityState {
        if (!isAccessibilityEnabled()) return AccessibilityState.OFF
        return if (MobiusAccessibilityService.instance != null) AccessibilityState.LIVE
        else AccessibilityState.ENABLED_NOT_RUNNING
    }

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            appContext.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val wantedClass = MobiusAccessibilityService::class.java.name
        return enabled.split(':')
            .mapNotNull { ComponentName.unflattenFromString(it) }
            .any { it.packageName == appContext.packageName && it.className == wantedClass }
    }

    private fun isUsageAccessGranted(): Boolean {
        val appOps = appContext.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), appContext.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), appContext.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    // ----- Settings screens the user must visit (these cannot be granted programmatically) -----
    fun overlaySettingsIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${appContext.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun accessibilitySettingsIntent(): Intent =
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun notificationListenerSettingsIntent(): Intent =
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun usageAccessSettingsIntent(): Intent =
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** App info page: where "Allow restricted settings" lives for sideloaded APKs (Android 13+). */
    fun appDetailsIntent(): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${appContext.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

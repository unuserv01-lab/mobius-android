package com.mobius.core

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.featureFlagsDataStore: DataStore<Preferences> by preferencesDataStore(name = "feature_flags")

/**
 * v6 Step 0 — single source of truth for feature flags.
 *
 * Every v6 feature is built behind one of these flags, default ON. A flag OFF must mean
 * "v5 behavior exactly": flags only gate NEW behavior that other v6 workstreams add.
 * This class itself changes no existing behavior — it only stores and reads flags.
 *
 * Backed by its OWN DataStore file ("feature_flags"), separate from PermissionManager's
 * "mobius_prefs" store, so flag state never interferes with permission preferences.
 *
 * Other workstreams call:
 *   app.featureFlags.isEnabled(FeatureFlag.PLANNING_LOOP)  // Flow<Boolean>, default true
 *   app.featureFlags.setEnabled(FeatureFlag.PLANNING_LOOP, false)  // suspend
 * For a one-shot read inside a suspend function: `isEnabled(flag).first()`.
 */
enum class FeatureFlag {
    PLANNING_LOOP,
    ORCHESTRATION,
    CODE_AGENT,
    WA_WATCHER,
    PARALLEL_WORKERS,
    VOICE_OUTPUT,
    AUTO_SCROLL,
    TOKOPEDIA_SKILL,
    MEMORY_V2,
    MULTI_ENGINE,
    LAPTOP_BRIDGE,
    REMOTE_GATEWAY,
    NANO_TRIAGE,
    CHAT_HISTORY,
    LOCAL_FILES,
    SENSITIVE_ALERT,
}

class FeatureFlags(context: Context) {
    private val appContext = context.applicationContext
    private val store: DataStore<Preferences> get() = appContext.featureFlagsDataStore

    /** True when the flag is enabled; defaults to TRUE for every flag when nothing is stored. */
    fun isEnabled(flag: FeatureFlag): Flow<Boolean> =
        store.data.map { it[booleanPreferencesKey(flag.name)] ?: true }

    suspend fun setEnabled(flag: FeatureFlag, enabled: Boolean) {
        store.edit { it[booleanPreferencesKey(flag.name)] = enabled }
    }
}

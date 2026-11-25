package com.mobius

import android.app.Application
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.mobius.agent.AgentFactory
import com.mobius.ai.AIRouter
import com.mobius.ai.CloudAI
import com.mobius.ai.OnDeviceAI
import com.mobius.ai.OpenRouterAI
import com.mobius.core.ContextManager
import com.mobius.core.FeatureFlags
import com.mobius.core.MemoryStore
import com.mobius.core.Orchestrator
import com.mobius.core.PermissionManager
import com.mobius.core.SkillRegistry
import com.mobius.core.SkillRunner
import com.mobius.core.TaskRouter
import com.mobius.core.TtlCleanupWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * Application object = composition root of the local core. All core components live here as
 * lazy singletons so the activity, the bubble service and the WorkManager job share them.
 */
class MobiusApp : Application() {

    val memoryStore by lazy { MemoryStore(this) }
    val permissions by lazy { PermissionManager(this) }
    val featureFlags by lazy { FeatureFlags(this) }
    val onDeviceAI by lazy { OnDeviceAI() }
    val aiRouter by lazy { AIRouter(onDeviceAI, CloudAI(), permissions, memoryStore, OpenRouterAI()) }
    val skillRegistry by lazy { SkillRegistry(this, memoryStore) }
    val skillRunner by lazy { SkillRunner() }
    val orchestrator by lazy {
        Orchestrator(
            context = this,
            permissions = permissions,
            memory = memoryStore,
            contextManager = ContextManager(this, memoryStore, permissions),
            taskRouter = TaskRouter(aiRouter, skillRegistry),
            agentFactory = AgentFactory(),
            aiRouter = aiRouter,
            skillRegistry = skillRegistry,
            skillRunner = skillRunner,
        )
    }

    override fun onCreate() {
        super.onCreate()
        installAppCheck()
        scheduleTtlCleanup()
        // Purge anything that expired while the app was closed.
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            runCatching { memoryStore.purgeExpired() }
            // (v8.1) Persist skill registry metadata (enabled/useCount) ke SQLite.
            // Registry tetap jalan penuh tanpa DB; sync gagal = metadata in-memory saja.
            runCatching { skillRegistry.syncMetadataToDb() }
        }
    }

    /**
     * App Check is required by Firebase AI Logic. The hackathon accepts debug tokens:
     * run the app once, copy the debug token printed in Logcat (tag "DebugAppCheckProvider"),
     * and register it in Firebase console > App Check > Apps > Manage debug tokens.
     */
    private fun installAppCheck() {
        try {
            FirebaseApp.initializeApp(this)
            FirebaseAppCheck.getInstance()
                .installAppCheckProviderFactory(DebugAppCheckProviderFactory.getInstance())
        } catch (e: Exception) {
            Log.e(TAG, "Firebase/App Check init failed. Is app/google-services.json present?", e)
        }
    }

    /** P6: periodic TTL cleanup (WorkManager's minimum period is 15 minutes). */
    private fun scheduleTtlCleanup() {
        val request = PeriodicWorkRequestBuilder<TtlCleanupWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(this)
            .enqueueUniquePeriodicWork("mobius_ttl_cleanup", ExistingPeriodicWorkPolicy.KEEP, request)
    }

    private companion object {
        const val TAG = "MobiusApp"
    }
}

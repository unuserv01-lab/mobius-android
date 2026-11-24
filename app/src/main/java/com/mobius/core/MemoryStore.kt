package com.mobius.core

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.mobius.MobiusApp
import com.mobius.notification.NotificationCache
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/** Allowed memory types (spec section 8). */
object MemoryType {
    const val FACT = "fact"
    const val PREFERENCE = "preference"
    const val TASK_RESULT = "task_result"
}

/** One thing the CORE decided to remember. Subagents can never create these directly (P5). */
@Entity(tableName = "memory")
data class MemoryEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    val content: String,
    /** "task:<id>/agent:<name>" */
    val source: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "ttl_seconds") val ttlSeconds: Long,
    @ColumnInfo(name = "expires_at") val expiresAt: Long,
)

/** One AI routing decision (which model, why). Visible in History. */
@Entity(tableName = "routing_log")
data class RoutingLogEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: String,
    /** "on-device", "cloud" or "blocked". */
    val model: String,
    val reason: String,
    val createdAt: Long,
    val expiresAt: Long,
)

/**
 * One row of a skill task's audit trail (v5): a step result, a gate decision, or the plan
 * decision. Rows expire with the task record (same TTL). `tool` is the tool name, "gate" for
 * a gate row, "plan" for the plan row (stepIndex -1). `gateDecision` is null for plain steps.
 */
@Entity(tableName = "task_trail")
data class TaskTrailEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: String,
    val skillName: String,
    val userText: String,
    val stepIndex: Int,
    val tool: String,
    val success: Boolean,
    val message: String,
    val durationMs: Long,
    val gateDecision: String?,
    val createdAt: Long,
    val expiresAt: Long,
)

/**
 * One stored user preference (v6 Memory V2): a like/dislike or a per-app note.
 * Keyed by a free-form key (e.g. "like:music", "app:com.whatsapp:note"); upsert by key.
 * Preferences have NO expiry — they live until the user clears all data.
 */
@Entity(tableName = "preferences")
data class PreferenceEntry(
    @PrimaryKey val key: String,
    val value: String,
    val updatedAt: Long,
)

/**
 * One progress update of a multi-step task (v6 Memory V2). Append-only: every call to
 * [MemoryStore.recordProgress] adds a row, so the full timeline survives across sessions.
 * Callers that want the CURRENT status of a job take the latest row per `jobLabel`.
 * Progress rows have NO expiry — a task's progress is still there when the user returns.
 */
@Entity(tableName = "progress")
data class ProgressEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: String,
    val jobLabel: String,
    val status: String,
    val detail: String,
    val updatedAt: Long,
)

/**
 * One chat message (v6 Memory V2, CHAT_HISTORY flag): a "user" or "assistant" turn from
 * Home / bubble. Retention follows Tuan's rule — a row expires ONLY via the memory TTL
 * setting (1h/24h/7d, `permissions.memoryTtlSeconds`); nothing deletes chat history
 * before its expiry. Expired rows are removed by [MemoryStore.purgeExpired].
 */
@Entity(tableName = "chat_message")
data class ChatMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** "user" or "assistant". */
    val role: String,
    val text: String,
    val createdAt: Long,
    val expiresAt: Long,
)

@Dao
interface MemoryDao {
    @Insert
    suspend fun insertMemory(entry: MemoryEntry): Long

    @Query("SELECT * FROM memory ORDER BY created_at DESC")
    fun observeMemory(): Flow<List<MemoryEntry>>

    @Query("SELECT * FROM memory WHERE type = :type ORDER BY created_at DESC LIMIT :limit")
    suspend fun recent(type: String, limit: Int): List<MemoryEntry>

    @Query("DELETE FROM memory WHERE id = :id")
    suspend fun deleteMemory(id: Long)

    @Query("DELETE FROM memory WHERE expires_at <= :now")
    suspend fun purgeMemory(now: Long): Int

    @Query("DELETE FROM memory")
    suspend fun clearMemory()

    @Insert
    suspend fun insertLog(entry: RoutingLogEntry): Long

    @Query("SELECT * FROM routing_log ORDER BY createdAt DESC LIMIT 200")
    fun observeLog(): Flow<List<RoutingLogEntry>>

    @Query("DELETE FROM routing_log WHERE expiresAt <= :now")
    suspend fun purgeLog(now: Long): Int

    @Query("DELETE FROM routing_log")
    suspend fun clearLog()

    @Insert
    suspend fun insertTrail(entry: TaskTrailEntry): Long

    @Query("SELECT * FROM task_trail ORDER BY createdAt DESC LIMIT 300")
    fun observeTrail(): Flow<List<TaskTrailEntry>>

    @Query("DELETE FROM task_trail WHERE expiresAt <= :now")
    suspend fun purgeTrail(now: Long): Int

    @Query("DELETE FROM task_trail")
    suspend fun clearTrail()

    // ----- v6 Memory V2: preferences (upsert by key, no expiry) -----
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPreference(entry: PreferenceEntry)

    @Query("SELECT value FROM preferences WHERE `key` = :key")
    suspend fun preferenceValue(key: String): String?

    @Query("DELETE FROM preferences")
    suspend fun clearPreferences()

    // ----- v6 Memory V2: multi-step task progress (append-only timeline) -----
    @Insert
    suspend fun insertProgress(entry: ProgressEntry): Long

    @Query("SELECT * FROM progress WHERE taskId = :taskId ORDER BY updatedAt ASC")
    fun observeProgress(taskId: String): Flow<List<ProgressEntry>>

    @Query("DELETE FROM progress")
    suspend fun clearProgress()

    // ----- v6 Memory V2: chat history (TTL expiry, same setting as memory) -----
    @Insert
    suspend fun insertChat(entry: ChatMessage): Long

    @Query("SELECT * FROM chat_message ORDER BY createdAt ASC")
    fun observeChatMessages(): Flow<List<ChatMessage>>

    @Query("DELETE FROM chat_message WHERE expiresAt <= :now")
    suspend fun purgeChat(now: Long): Int

    @Query("DELETE FROM chat_message")
    suspend fun clearChat()

    // ----- v6 Memory V2: full memory list for relevance scoring -----
    @Query("SELECT * FROM memory ORDER BY created_at DESC")
    suspend fun allMemory(): List<MemoryEntry>
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // New table only; existing memory + routing_log rows are untouched.
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `task_trail` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`taskId` TEXT NOT NULL, `skillName` TEXT NOT NULL, `userText` TEXT NOT NULL, " +
                "`stepIndex` INTEGER NOT NULL, `tool` TEXT NOT NULL, `success` INTEGER NOT NULL, " +
                "`message` TEXT NOT NULL, `durationMs` INTEGER NOT NULL, `gateDecision` TEXT, " +
                "`createdAt` INTEGER NOT NULL, `expiresAt` INTEGER NOT NULL)"
        )
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // New tables only (v6 Memory V2); existing memory + routing_log + task_trail
        // rows are untouched — no DROP, no ALTER, no data migration.
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `preferences` (" +
                "`key` TEXT NOT NULL, `value` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`key`))"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `progress` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`taskId` TEXT NOT NULL, `jobLabel` TEXT NOT NULL, `status` TEXT NOT NULL, " +
                "`detail` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `chat_message` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`role` TEXT NOT NULL, `text` TEXT NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, `expiresAt` INTEGER NOT NULL)"
        )
    }
}

@Database(
    entities = [
        MemoryEntry::class, RoutingLogEntry::class, TaskTrailEntry::class,
        PreferenceEntry::class, ProgressEntry::class, ChatMessage::class,
    ],
    version = 3, exportSchema = false,
)
abstract class MobiusDatabase : RoomDatabase() {
    abstract fun dao(): MemoryDao
}

/**
 * Core-owned memory (Room). Only core components (Orchestrator, AIRouter's routing log, the TTL
 * worker, the UI) hold a reference to this class. It is deliberately NOT part of AgentTools, so a
 * SubAgent has no way to write memory: it can only REPORT a result and the Orchestrator decides.
 * Nothing in this store is ever sent to the cloud.
 *
 * v6 Memory V2: [featureFlags] is optional — when null, flags are read from the app's
 * [FeatureFlags] DataStore via the given [Context] (same single source of truth the
 * Settings UI writes). Existing construction `MemoryStore(context)` keeps working unchanged;
 * callers may pass `app.featureFlags` explicitly (e.g. tests). A flag that cannot be read
 * behaves as ON (the default for every flag), so a DataStore failure never silently
 * drops chat history or relevance.
 */
class MemoryStore(context: Context, featureFlags: FeatureFlags? = null) {
    private val appContext = context.applicationContext
    private val flags: FeatureFlags = featureFlags ?: FeatureFlags(appContext)
    // Own PermissionManager wrapper over the same "mobius_prefs" DataStore, only to read
    // the memory TTL setting for chat expiry. MobiusApp's instance is untouched.
    private val permissions: PermissionManager by lazy { PermissionManager(appContext) }

    private val dao: MemoryDao = Room.databaseBuilder(
        appContext, MobiusDatabase::class.java, "mobius.db"
    ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().dao()

    /** Flag read that fails open to the flag's default (ON). */
    private suspend fun isFlagEnabled(flag: FeatureFlag): Boolean =
        runCatching { flags.isEnabled(flag).first() }.getOrDefault(true)

    /** Current memory TTL setting (1h/24h/7d); falls back to the app default on read failure. */
    private suspend fun currentMemoryTtlSeconds(): Long =
        runCatching { permissions.memoryTtlSeconds.first() }
            .getOrDefault(AppConfig.DEFAULT_TTL_SECONDS)

    fun observeMemory(): Flow<List<MemoryEntry>> = dao.observeMemory()
    fun observeRoutingLog(): Flow<List<RoutingLogEntry>> = dao.observeLog()
    fun observeTaskTrail(): Flow<List<TaskTrailEntry>> = dao.observeTrail()

    /** All stored memories, newest first — used by ContextManager relevance scoring. */
    suspend fun allMemories(): List<MemoryEntry> = dao.allMemory()

    // ----- v6 Memory V2: preferences -----
    suspend fun setPreference(key: String, value: String) {
        dao.upsertPreference(
            PreferenceEntry(key = key, value = value, updatedAt = System.currentTimeMillis())
        )
    }

    suspend fun getPreference(key: String): String? = dao.preferenceValue(key)

    // ----- v6 Memory V2: multi-step task progress -----
    suspend fun recordProgress(taskId: String, jobLabel: String, status: String, detail: String = "") {
        dao.insertProgress(
            ProgressEntry(
                taskId = taskId, jobLabel = jobLabel, status = status, detail = detail,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    fun observeProgress(taskId: String): Flow<List<ProgressEntry>> = dao.observeProgress(taskId)

    // ----- v6 Memory V2: chat history -----
    /**
     * Stores one chat turn. `expiresAt = createdAt + current memory TTL`, so the row lives
     * exactly as long as the TTL chosen in Settings — never deleted before its expiry.
     * No-op (nothing stored) when the CHAT_HISTORY flag is OFF.
     */
    suspend fun addChatMessage(role: String, text: String) {
        if (!isFlagEnabled(FeatureFlag.CHAT_HISTORY)) return
        val now = System.currentTimeMillis()
        val ttl = currentMemoryTtlSeconds()
        dao.insertChat(
            ChatMessage(role = role, text = text, createdAt = now, expiresAt = now + ttl * 1000)
        )
    }

    fun observeChatMessages(): Flow<List<ChatMessage>> = dao.observeChatMessages()

    suspend fun remember(type: String, content: String, source: String, ttlSeconds: Long): Long {
        val now = System.currentTimeMillis()
        return dao.insertMemory(
            MemoryEntry(
                type = type, content = content, source = source,
                createdAt = now, ttlSeconds = ttlSeconds, expiresAt = now + ttlSeconds * 1000
            )
        )
    }

    suspend fun logRouting(taskId: String, model: String, reason: String, ttlSeconds: Long) {
        val now = System.currentTimeMillis()
        dao.insertLog(
            RoutingLogEntry(
                taskId = taskId, model = model, reason = reason,
                createdAt = now, expiresAt = now + ttlSeconds * 1000
            )
        )
    }

    suspend fun recentTaskSummaries(limit: Int = 3): List<String> =
        dao.recent(MemoryType.TASK_RESULT, limit).map { it.content }

    /**
     * Persists one skill task's audit trail (v5): the plan decision, every step result and
     * every gate decision. One row per item, all sharing the task's TTL.
     */
    suspend fun recordTrail(
        taskId: String,
        skillName: String,
        userText: String,
        steps: List<StepResult>,
        gateDecisions: List<GateDecisionRecord>,
        planDecision: String?,
        ttlSeconds: Long,
    ) {
        val now = System.currentTimeMillis()
        val expiresAt = now + ttlSeconds * 1000
        planDecision?.let {
            dao.insertTrail(
                TaskTrailEntry(
                    taskId = taskId, skillName = skillName, userText = userText,
                    stepIndex = -1, tool = "plan", success = it == "confirmed",
                    message = if (it == "confirmed") "Rencana disetujui, eksekusi dimulai."
                        else "Rencana dibatalkan pengguna.",
                    durationMs = 0, gateDecision = "plan-$it",
                    createdAt = now, expiresAt = expiresAt,
                )
            )
        }
        for (step in steps) {
            dao.insertTrail(
                TaskTrailEntry(
                    taskId = taskId, skillName = skillName, userText = userText,
                    stepIndex = step.index, tool = step.tool, success = step.success,
                    message = step.message, durationMs = step.durationMs, gateDecision = null,
                    createdAt = now, expiresAt = expiresAt,
                )
            )
        }
        for (g in gateDecisions) {
            dao.insertTrail(
                TaskTrailEntry(
                    taskId = taskId, skillName = skillName, userText = userText,
                    stepIndex = g.stepIndex, tool = "gate",
                    success = g.decision != GateDecision.DENIED && g.resolution != "rejected",
                    message = g.reason, durationMs = 0,
                    gateDecision = g.decision.name + (g.resolution?.let { r -> "/$r" } ?: ""),
                    createdAt = now, expiresAt = expiresAt,
                )
            )
        }
    }

    suspend fun delete(id: Long) = dao.deleteMemory(id)

    /**
     * Deletes expired memory + routing-log + trail + CHAT rows. Returns how many rows
     * were removed. Preferences and progress have no expiry and are never purged here —
     * they disappear only via [clearAll] (user-initiated wipe in Settings).
     */
    suspend fun purgeExpired(): Int {
        val now = System.currentTimeMillis()
        return dao.purgeMemory(now) + dao.purgeLog(now) + dao.purgeTrail(now) + dao.purgeChat(now)
    }

    suspend fun clearAll() {
        dao.clearMemory()
        dao.clearLog()
        dao.clearTrail()
        dao.clearChat()
        dao.clearProgress()
        dao.clearPreferences()
    }
}

/** WorkManager periodic job (P6): deletes expired memory/log rows and stale cached notifications. */
class TtlCleanupWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as MobiusApp
        app.memoryStore.purgeExpired()
        NotificationCache.prune()
        return Result.success()
    }
}

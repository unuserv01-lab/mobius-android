package com.mobius.core

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.mobius.MobiusApp
import com.mobius.notification.NotificationCache
import kotlinx.coroutines.flow.Flow

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
}

@Database(entities = [MemoryEntry::class, RoutingLogEntry::class], version = 1, exportSchema = false)
abstract class MobiusDatabase : RoomDatabase() {
    abstract fun dao(): MemoryDao
}

/**
 * Core-owned memory (Room). Only core components (Orchestrator, AIRouter's routing log, the TTL
 * worker, the UI) hold a reference to this class. It is deliberately NOT part of AgentTools, so a
 * SubAgent has no way to write memory: it can only REPORT a result and the Orchestrator decides.
 * Nothing in this store is ever sent to the cloud.
 */
class MemoryStore(context: Context) {
    private val dao: MemoryDao = Room.databaseBuilder(
        context.applicationContext, MobiusDatabase::class.java, "mobius.db"
    ).build().dao()

    fun observeMemory(): Flow<List<MemoryEntry>> = dao.observeMemory()
    fun observeRoutingLog(): Flow<List<RoutingLogEntry>> = dao.observeLog()

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

    suspend fun delete(id: Long) = dao.deleteMemory(id)

    /** Deletes expired memory + routing-log rows. Returns how many rows were removed. */
    suspend fun purgeExpired(): Int {
        val now = System.currentTimeMillis()
        return dao.purgeMemory(now) + dao.purgeLog(now)
    }

    suspend fun clearAll() {
        dao.clearMemory()
        dao.clearLog()
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

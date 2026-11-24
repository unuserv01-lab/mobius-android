package com.mobius.agent

import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory scratch space for ONE subagent, bound by TTL (P6). Never touches disk.
 * It is cleared when the owning [AgentScope] is destroyed, and expired values are never returned
 * even if the task is still running.
 */
class TempStore(private val defaultTtlMs: Long = 5 * 60_000L) {
    private data class Item(val value: Any, val expiresAt: Long)

    private val items = ConcurrentHashMap<String, Item>()

    fun put(key: String, value: Any, ttlMs: Long = defaultTtlMs) {
        items[key] = Item(value, System.currentTimeMillis() + ttlMs)
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> get(key: String): T? {
        val item = items[key] ?: return null
        if (item.expiresAt <= System.currentTimeMillis()) {
            items.remove(key)
            return null
        }
        return item.value as? T
    }

    fun purgeExpired() {
        val now = System.currentTimeMillis()
        items.entries.removeIf { it.value.expiresAt <= now }
    }

    fun size(): Int = items.size

    fun clear() = items.clear()
}

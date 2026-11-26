package io.nekohasekai.sfa.vfs.relay

/**
 * Serializes every Share read-modify-write. Callers must pass targeted
 * transforms: never write back a snapshot captured before a network request,
 * or a concurrent Revoke could be resurrected.
 */
class VfsShareRepository(
    private val store: VfsShareStore,
) {
    private val lock = Any()

    fun load(): List<VfsShareRecord> = synchronized(lock) { store.load() }

    fun add(record: VfsShareRecord) {
        synchronized(lock) {
            val all = store.load().toMutableList()
            all += record
            store.save(all)
        }
    }

    /** Returns the updated record, or null when absent/transform aborted. */
    fun update(
        shareId: String,
        transform: (VfsShareRecord) -> VfsShareRecord?,
    ): VfsShareRecord? =
        synchronized(lock) {
            val all = store.load().toMutableList()
            val index = all.indexOfFirst { it.shareId == shareId }
            if (index < 0) return@synchronized null
            val updated = transform(all[index]) ?: return@synchronized null
            all[index] = updated
            store.save(all)
            updated
        }

    fun updateAll(transform: (VfsShareRecord) -> VfsShareRecord): List<VfsShareRecord> =
        synchronized(lock) {
            val updated = store.load().map(transform)
            store.save(updated)
            updated
        }

    fun replaceAll(records: List<VfsShareRecord>) {
        synchronized(lock) { store.save(records) }
    }

    fun remove(shareId: String) {
        synchronized(lock) {
            store.save(store.load().filterNot { it.shareId == shareId })
        }
    }

    /** Only touches lastUsedAt on the latest record; never revives a revoked Share. */
    fun markLastUsed(
        shareId: String,
        now: Long,
        throttleMs: Long,
    ): VfsShareRecord? =
        synchronized(lock) {
            val all = store.load().toMutableList()
            val index = all.indexOfFirst { it.shareId == shareId }
            if (index < 0) return@synchronized null
            val current = all[index]
            if (current.revokedAt != null || current.revokingAt != null) return@synchronized null
            if (current.lastUsedAt != null && now - current.lastUsedAt < throttleMs) return@synchronized null
            val updated = current.copy(lastUsedAt = now)
            all[index] = updated
            store.save(all)
            updated
        }

    /**
     * Persists a pending Share before registration and removes it if the
     * registration block throws, so a failed Create does not accumulate.
     */
    suspend fun <T> addPendingAndRollback(
        record: VfsShareRecord,
        block: suspend () -> T,
    ): T {
        add(record)
        return try {
            block()
        } catch (error: Throwable) {
            remove(record.shareId)
            throw error
        }
    }
}

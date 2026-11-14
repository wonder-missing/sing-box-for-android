package io.nekohasekai.sfa.vfs

/** One denial counter per server; process-scoped like the confirmation itself. */
class VfsCooldownStore(
    private val factory: () -> VfsCooldown = { VfsCooldown() },
) {
    private val byServer = mutableMapOf<String, VfsCooldown>()
    private val lock = Any()

    fun forServer(server: String): VfsCooldown =
        synchronized(lock) {
            byServer.getOrPut(server, factory)
        }

    fun reset(server: String) {
        synchronized(lock) {
            byServer.remove(server)
        }
    }
}

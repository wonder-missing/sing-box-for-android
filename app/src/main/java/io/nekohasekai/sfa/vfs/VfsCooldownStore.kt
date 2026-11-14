package io.nekohasekai.sfa.vfs

/** One denial counter per issuer; process-scoped like the confirmation itself. */
class VfsCooldownStore(
    private val factory: () -> VfsCooldown = { VfsCooldown() },
) {
    private val byIssuer = mutableMapOf<String, VfsCooldown>()
    private val lock = Any()

    fun forIssuer(issuer: String): VfsCooldown =
        synchronized(lock) {
            byIssuer.getOrPut(issuer, factory)
        }

    fun reset(issuer: String) {
        synchronized(lock) {
            byIssuer.remove(issuer)
        }
    }
}

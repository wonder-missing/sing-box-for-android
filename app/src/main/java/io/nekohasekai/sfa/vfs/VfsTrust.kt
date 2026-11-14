package io.nekohasekai.sfa.vfs

data class VfsTrust(
    val issuer: String,
    val scope: Set<VfsScope>,
    val lastUsedAt: Long,
    val expiresAt: Long,
    val processScoped: Boolean,
)

/**
 * Trust is keyed by issuer plus the exact scope set that was confirmed. A
 * stored trust may cover a subset request; any wider request needs a new
 * confirmation. Process-scoped trust dies with the app process.
 */
class VfsTrustStore(
    private val now: () -> Long = System::currentTimeMillis,
    private val inactivityMs: Long = 15L * 24L * 60L * 60L * 1000L,
) {
    private val persistent = LinkedHashMap<String, VfsTrust>()
    private val process = LinkedHashMap<String, VfsTrust>()

    fun find(
        issuer: String,
        requested: Set<VfsScope>,
    ): VfsTrust? {
        if (requested.isEmpty()) return null
        val normalized = VfsIssuer.normalize(issuer) ?: return null
        return (process.values + persistent.values).firstOrNull { trust ->
            trust.issuer == normalized && trust.scope.containsAll(requested) && isActive(trust)
        }
    }

    fun grant(trust: VfsTrust) {
        require(trust.scope.isNotEmpty()) { "trust scope must not be empty" }
        val normalized = VfsIssuer.normalize(trust.issuer) ?: return
        val stored = trust.copy(issuer = normalized)
        val target = if (stored.processScoped) process else persistent
        target[key(stored)] = stored
    }

    fun touch(
        issuer: String,
        scope: Set<VfsScope>,
    ) {
        val trust = find(issuer, scope) ?: return
        val updated = trust.copy(lastUsedAt = now(), expiresAt = expiryFromNow())
        val target = if (updated.processScoped) process else persistent
        target[key(updated)] = updated
    }

    fun revoke(issuer: String) {
        val normalized = VfsIssuer.normalize(issuer) ?: return
        persistent.values.removeIf { it.issuer == normalized }
        process.values.removeIf { it.issuer == normalized }
    }

    fun list(): List<VfsTrust> =
        (process.values + persistent.values)
            .filter(::isActive)
            .sortedByDescending { it.lastUsedAt }

    fun clearProcessScope() {
        process.clear()
    }

    fun expiryFromNow(): Long = now() + inactivityMs

    private fun isActive(trust: VfsTrust): Boolean = trust.processScoped || trust.expiresAt >= now()

    private fun key(trust: VfsTrust): String =
        trust.issuer + "|" + trust.scope.map { it.wire }.sorted().joinToString(",")
}

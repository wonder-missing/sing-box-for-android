package io.nekohasekai.sfa.vfs

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

data class VfsTrust(
    val issuer: String,
    val scope: Set<VfsScope>,
    val lastUsedAt: Long,
    val expiresAt: Long,
    val processScoped: Boolean,
)

interface VfsTrustPersistence {
    fun load(): List<VfsTrust>

    fun save(trusts: List<VfsTrust>)
}

object NoopVfsTrustPersistence : VfsTrustPersistence {
    override fun load(): List<VfsTrust> = emptyList()

    override fun save(trusts: List<VfsTrust>) = Unit
}

class FileVfsTrustPersistence(
    private val file: File,
) : VfsTrustPersistence {
    private val json = Json { ignoreUnknownKeys = true }

    override fun load(): List<VfsTrust> {
        if (!file.isFile) return emptyList()
        val parsed =
            runCatching {
                json.decodeFromString(VfsTrustFile.serializer(), file.readText(Charsets.UTF_8))
            }.getOrNull() ?: return emptyList()
        if (parsed.version != VfsTrustFile.VERSION) return emptyList()
        return parsed.trusts.mapNotNull { entry ->
            val issuer = VfsIssuer.normalize(entry.issuer) ?: return@mapNotNull null
            val scope = entry.scope.mapNotNull(VfsScope::fromWire).toSet()
            if (scope.isEmpty()) return@mapNotNull null
            VfsTrust(
                issuer = issuer,
                scope = scope,
                lastUsedAt = entry.lastUsedAt,
                expiresAt = entry.expiresAt,
                processScoped = false,
            )
        }
    }

    override fun save(trusts: List<VfsTrust>) {
        val parent = file.parentFile ?: return
        if (!parent.isDirectory && !parent.mkdirs()) return
        val entries =
            trusts
                .filterNot { it.processScoped }
                .map { trust ->
                    VfsTrustEntry(
                        issuer = trust.issuer,
                        scope = trust.scope.map { it.wire }.sorted(),
                        lastUsedAt = trust.lastUsedAt,
                        expiresAt = trust.expiresAt,
                    )
                }
        val tmp = File(parent, file.name + ".tmp")
        tmp.writeText(
            json.encodeToString(
                VfsTrustFile.serializer(),
                VfsTrustFile(trusts = entries),
            ),
            Charsets.UTF_8,
        )
        runCatching {
            tmp.setReadable(false, false)
            tmp.setReadable(true, true)
            tmp.setWritable(false, false)
            tmp.setWritable(true, true)
        }
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText(Charsets.UTF_8), Charsets.UTF_8)
            tmp.delete()
        }
    }
}

@Serializable
private data class VfsTrustFile(
    val version: Int = 1,
    val trusts: List<VfsTrustEntry> = emptyList(),
) {
    companion object {
        const val VERSION: Int = 1
    }
}

@Serializable
private data class VfsTrustEntry(
    val issuer: String,
    val scope: List<String>,
    val lastUsedAt: Long,
    val expiresAt: Long,
)

/**
 * Trust is keyed by issuer plus the exact scope set that was confirmed. A
 * stored trust may cover a subset request; any wider request needs a new
 * confirmation. Process-scoped trust dies with the app process.
 */
class VfsTrustStore(
    private val now: () -> Long = System::currentTimeMillis,
    private val inactivityMs: Long = 15L * 24L * 60L * 60L * 1000L,
    private val persistence: VfsTrustPersistence = NoopVfsTrustPersistence,
) {
    private val persistent = LinkedHashMap<String, VfsTrust>()
    private val process = LinkedHashMap<String, VfsTrust>()

    init {
        runCatching {
            persistence.load().forEach { trust ->
                if (trust.processScoped) return@forEach
                val issuer = VfsIssuer.normalize(trust.issuer) ?: return@forEach
                if (trust.scope.isEmpty()) return@forEach
                val stored = trust.copy(issuer = issuer)
                persistent[key(stored)] = stored
            }
        }
    }

    @Synchronized
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

    @Synchronized
    fun grant(trust: VfsTrust) {
        require(trust.scope.isNotEmpty()) { "trust scope must not be empty" }
        val normalized = VfsIssuer.normalize(trust.issuer) ?: return
        val stored = trust.copy(issuer = normalized)
        val target = if (stored.processScoped) process else persistent
        target[key(stored)] = stored
        if (!stored.processScoped) savePersistent()
    }

    @Synchronized
    fun touch(
        issuer: String,
        scope: Set<VfsScope>,
    ) {
        val trust = find(issuer, scope) ?: return
        val updated = trust.copy(lastUsedAt = now(), expiresAt = expiryFromNow())
        val target = if (updated.processScoped) process else persistent
        target[key(updated)] = updated
        if (!updated.processScoped) savePersistent()
    }

    @Synchronized
    fun revoke(issuer: String) {
        val normalized = VfsIssuer.normalize(issuer) ?: return
        persistent.keys.filter { persistent[it]?.issuer == normalized }.forEach { persistent.remove(it) }
        process.keys.filter { process[it]?.issuer == normalized }.forEach { process.remove(it) }
        savePersistent()
    }

    @Synchronized
    fun revoke(
        issuer: String,
        scope: Set<VfsScope>,
    ) {
        val normalized = VfsIssuer.normalize(issuer) ?: return
        val exactKey =
            key(
                VfsTrust(
                    issuer = normalized,
                    scope = scope,
                    lastUsedAt = 0L,
                    expiresAt = 0L,
                    processScoped = false,
                ),
            )
        persistent.remove(exactKey)
        process.remove(exactKey)
        savePersistent()
    }

    @Synchronized
    fun list(): List<VfsTrust> =
        (process.values + persistent.values)
            .filter(::isActive)
            .sortedByDescending { it.lastUsedAt }

    @Synchronized
    fun clearProcessScope() {
        process.clear()
    }

    fun expiryFromNow(): Long = now() + inactivityMs

    private fun isActive(trust: VfsTrust): Boolean = trust.processScoped || trust.expiresAt >= now()

    private fun key(trust: VfsTrust): String =
        trust.issuer + "|" + trust.scope.map { it.wire }.sorted().joinToString(",")

    private fun savePersistent() {
        runCatching { persistence.save(persistent.values.toList()) }
    }
}

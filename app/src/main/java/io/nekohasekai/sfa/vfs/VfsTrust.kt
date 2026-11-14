package io.nekohasekai.sfa.vfs

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

data class VfsTrust(
    val server: String,
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
            val server = VfsServerBase.normalize(entry.server) ?: return@mapNotNull null
            val scope = entry.scope.mapNotNull(VfsScope::fromWire).toSet()
            if (scope.isEmpty()) return@mapNotNull null
            VfsTrust(
                server = server,
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
                        server = trust.server,
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
    @SerialName("issuer")
    val server: String,
    val scope: List<String>,
    val lastUsedAt: Long,
    val expiresAt: Long,
)

/**
 * Trust is keyed by server plus the exact scope set that was confirmed. A
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
                val server = VfsServerBase.normalize(trust.server) ?: return@forEach
                if (trust.scope.isEmpty()) return@forEach
                val stored = trust.copy(server = server)
                persistent[key(stored)] = stored
            }
        }
    }

    @Synchronized
    fun find(
        server: String,
        requested: Set<VfsScope>,
    ): VfsTrust? {
        if (requested.isEmpty()) return null
        val normalized = VfsServerBase.normalize(server) ?: return null
        return (process.values + persistent.values).firstOrNull { trust ->
            trust.server == normalized && trust.scope.containsAll(requested) && isActive(trust)
        }
    }

    @Synchronized
    fun grant(trust: VfsTrust) {
        require(trust.scope.isNotEmpty()) { "trust scope must not be empty" }
        val normalized = VfsServerBase.normalize(trust.server) ?: return
        val stored = trust.copy(server = normalized)
        val target = if (stored.processScoped) process else persistent
        target[key(stored)] = stored
        if (!stored.processScoped) savePersistent()
    }

    @Synchronized
    fun touch(
        server: String,
        scope: Set<VfsScope>,
    ) {
        val trust = find(server, scope) ?: return
        val updated = trust.copy(lastUsedAt = now(), expiresAt = expiryFromNow())
        val target = if (updated.processScoped) process else persistent
        target[key(updated)] = updated
        if (!updated.processScoped) savePersistent()
    }

    @Synchronized
    fun revoke(server: String) {
        val normalized = VfsServerBase.normalize(server) ?: return
        persistent.keys.filter { persistent[it]?.server == normalized }.forEach { persistent.remove(it) }
        process.keys.filter { process[it]?.server == normalized }.forEach { process.remove(it) }
        savePersistent()
    }

    @Synchronized
    fun revoke(
        server: String,
        scope: Set<VfsScope>,
    ) {
        val normalized = VfsServerBase.normalize(server) ?: return
        val exactKey =
            key(
                VfsTrust(
                    server = normalized,
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
        trust.server + "|" + trust.scope.map { it.wire }.sorted().joinToString(",")

    private fun savePersistent() {
        runCatching { persistence.save(persistent.values.toList()) }
    }
}

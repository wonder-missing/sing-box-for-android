package io.nekohasekai.sfa.vfs

import io.nekohasekai.sfa.agent.mcp.McpTokenCipher
import io.nekohasekai.sfa.agent.mcp.PlaintextMcpTokenCipher
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

interface VfsResumeTokenStore {
    fun get(
        issuer: String,
        linkId: String,
    ): String?

    fun put(
        issuer: String,
        linkId: String,
        token: String,
    )

    fun remove(
        issuer: String,
        linkId: String,
    )
}

class InMemoryVfsResumeTokenStore : VfsResumeTokenStore {
    private val tokens = LinkedHashMap<String, String>()

    override fun get(
        issuer: String,
        linkId: String,
    ): String? = tokens[key(issuer, linkId)]

    override fun put(
        issuer: String,
        linkId: String,
        token: String,
    ) {
        tokens[key(issuer, linkId)] = token
    }

    override fun remove(
        issuer: String,
        linkId: String,
    ) {
        tokens.remove(key(issuer, linkId))
    }

    private fun key(
        issuer: String,
        linkId: String,
    ): String = "$issuer|$linkId"
}

/**
 * Encrypted at rest with the same cipher as MCP tokens. A missing or corrupt
 * file simply means no resume; it must never clear a value in memory.
 */
class FileVfsResumeTokenStore(
    private val file: File,
    private val cipher: McpTokenCipher = PlaintextMcpTokenCipher,
) : VfsResumeTokenStore {
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    override fun get(
        issuer: String,
        linkId: String,
    ): String? {
        val blob = load()[key(issuer, linkId)] ?: return null
        return cipher.decrypt(key(issuer, linkId), blob)
    }

    @Synchronized
    override fun put(
        issuer: String,
        linkId: String,
        token: String,
    ) {
        val next = load().toMutableMap()
        next[key(issuer, linkId)] = cipher.encrypt(key(issuer, linkId), token)
        save(next)
    }

    @Synchronized
    override fun remove(
        issuer: String,
        linkId: String,
    ) {
        val next = load().toMutableMap()
        if (next.remove(key(issuer, linkId)) != null) save(next)
    }

    private fun load(): Map<String, String> {
        if (!file.isFile) return emptyMap()
        val parsed =
            runCatching {
                json.decodeFromString(VfsResumeFile.serializer(), file.readText(Charsets.UTF_8))
            }.getOrNull() ?: return emptyMap()
        if (parsed.version != VfsResumeFile.VERSION) return emptyMap()
        return parsed.tokens
    }

    private fun save(tokens: Map<String, String>) {
        val parent = file.parentFile ?: throw IllegalStateException("Resume token path has no parent.")
        if (!parent.isDirectory && !parent.mkdirs()) {
            throw IllegalStateException("Could not create the resume token directory.")
        }
        val tmp = File(parent, file.name + ".tmp")
        tmp.writeText(
            json.encodeToString(
                VfsResumeFile.serializer(),
                VfsResumeFile(tokens = tokens),
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

    private fun key(
        issuer: String,
        linkId: String,
    ): String = "$issuer|$linkId"
}

@Serializable
private data class VfsResumeFile(
    val version: Int = 1,
    val tokens: Map<String, String> = emptyMap(),
) {
    companion object {
        const val VERSION: Int = 1
    }
}

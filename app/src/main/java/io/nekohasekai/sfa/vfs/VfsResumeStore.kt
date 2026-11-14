package io.nekohasekai.sfa.vfs

import io.nekohasekai.sfa.agent.mcp.McpTokenCipher
import io.nekohasekai.sfa.agent.mcp.PlaintextMcpTokenCipher
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

interface VfsResumeTokenStore {
    fun get(
        server: String,
        linkId: String,
    ): String?

    fun put(
        server: String,
        linkId: String,
        token: String,
    )

    fun remove(
        server: String,
        linkId: String,
    )
}

class InMemoryVfsResumeTokenStore : VfsResumeTokenStore {
    private val tokens = LinkedHashMap<String, String>()

    override fun get(
        server: String,
        linkId: String,
    ): String? = tokens[key(server, linkId)]

    override fun put(
        server: String,
        linkId: String,
        token: String,
    ) {
        tokens[key(server, linkId)] = token
    }

    override fun remove(
        server: String,
        linkId: String,
    ) {
        tokens.remove(key(server, linkId))
    }

    private fun key(
        server: String,
        linkId: String,
    ): String = "$server|$linkId"
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
        server: String,
        linkId: String,
    ): String? {
        val blob = load()[key(server, linkId)] ?: return null
        return cipher.decrypt(key(server, linkId), blob)
    }

    @Synchronized
    override fun put(
        server: String,
        linkId: String,
        token: String,
    ) {
        val next = load().toMutableMap()
        next[key(server, linkId)] = cipher.encrypt(key(server, linkId), token)
        save(next)
    }

    @Synchronized
    override fun remove(
        server: String,
        linkId: String,
    ) {
        val next = load().toMutableMap()
        if (next.remove(key(server, linkId)) != null) save(next)
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
        server: String,
        linkId: String,
    ): String = "$server|$linkId"
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

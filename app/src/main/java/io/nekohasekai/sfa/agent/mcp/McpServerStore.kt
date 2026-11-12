package io.nekohasekai.sfa.agent.mcp

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.URI
import java.util.Locale

enum class McpConfigError {
    NAME_BLANK,
    NAME_LENGTH,
    URL,
    URL_USERINFO,
    URL_HOST,
    URL_LENGTH,
    URL_QUERY_SECRET,
    TOKEN,
    LIMIT,
}

@Serializable
data class McpServerConfig(
    val id: String,
    val name: String,
    val url: String,
    val token: String = "",
    val enabled: Boolean = false,
) {
    override fun toString(): String =
        "McpServerConfig(id=$id, name=$name, url=$url, enabled=$enabled, hasToken=${token.isNotBlank()})"
}

interface McpServerRepository {
    fun load(): List<McpServerConfig>

    fun save(servers: List<McpServerConfig>)
}

object McpServerValidator {
    const val MAX_SERVERS = 8
    const val MAX_NAME = 32
    const val MAX_URL = 300
    const val MAX_TOKEN = 512

    private val secretQuery = Regex("(?i)(?:^|&)(?:token|access_token|api_key|key|secret)=")

    fun validate(
        name: String,
        url: String,
        token: String,
        existingCount: Int,
    ): McpConfigError? {
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) return McpConfigError.NAME_BLANK
        if (trimmedName.length > MAX_NAME) return McpConfigError.NAME_LENGTH
        val trimmedUrl = url.trim()
        if (trimmedUrl.length > MAX_URL) return McpConfigError.URL_LENGTH
        if (trimmedUrl.any { it.isWhitespace() }) return McpConfigError.URL
        val uri =
            try {
                URI(trimmedUrl)
            } catch (_: Exception) {
                return McpConfigError.URL
            }
        val scheme = uri.scheme?.lowercase(Locale.US)
        if (scheme != "http" && scheme != "https") return McpConfigError.URL
        if (!uri.userInfo.isNullOrBlank()) return McpConfigError.URL_USERINFO
        if (uri.host.isNullOrBlank()) return McpConfigError.URL_HOST
        if (uri.rawQuery?.let { secretQuery.containsMatchIn(it) } == true) return McpConfigError.URL_QUERY_SECRET
        if (token.length > MAX_TOKEN || token.any { it == '\n' || it == '\r' }) return McpConfigError.TOKEN
        if (existingCount >= MAX_SERVERS) return McpConfigError.LIMIT
        return null
    }

    fun allocateId(
        name: String,
        used: Set<String>,
    ): String {
        val base = slug(name, 24)
        if (base !in used) return base
        var n = 2
        while (true) {
            val candidate = base.take(20).trimEnd('_') + "_$n"
            if (candidate !in used) return candidate
            n += 1
        }
    }

    fun slug(
        raw: String,
        max: Int,
    ): String {
        val cleaned =
            buildString {
                for (ch in raw.lowercase(Locale.US)) {
                    if (ch in 'a'..'z' || ch in '0'..'9') {
                        append(ch)
                    } else if (isNotEmpty() && last() != '_') {
                        append('_')
                    }
                }
            }.trim('_').take(max).trim('_')
        return cleaned.ifEmpty { "server" }
    }
}

class FileMcpServerRepository(
    private val file: File,
) : McpServerRepository {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    override fun load(): List<McpServerConfig> {
        if (!file.isFile) return emptyList()
        return runCatching {
            json
                .decodeFromString(McpServerFile.serializer(), file.readText(Charsets.UTF_8))
                .servers
                .filter { server ->
                    server.id.isNotBlank() &&
                        server.name.isNotBlank() &&
                        (server.url.startsWith("http://") || server.url.startsWith("https://"))
                }.take(McpServerValidator.MAX_SERVERS)
        }.getOrDefault(emptyList())
    }

    override fun save(servers: List<McpServerConfig>) {
        val parent = file.parentFile ?: throw McpException("Could not save MCP servers.")
        if (!parent.isDirectory && !parent.mkdirs()) throw McpException("Could not save MCP servers.")
        val tmp = File(parent, file.name + ".tmp")
        tmp.writeText(
            json.encodeToString(McpServerFile.serializer(), McpServerFile(servers)),
            Charsets.UTF_8,
        )
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText(Charsets.UTF_8), Charsets.UTF_8)
            tmp.delete()
        }
    }
}

@Serializable
private data class McpServerFile(
    val servers: List<McpServerConfig> = emptyList(),
)

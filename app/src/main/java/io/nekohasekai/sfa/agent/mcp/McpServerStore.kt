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
    NAMESPACE,
    NAMESPACE_DUPLICATE,
    NOT_FOUND,
}

@Serializable
data class McpServerConfig(
    val id: String,
    val name: String,
    val url: String,
    val token: String = "",
    val enabled: Boolean = false,
    /** Optional VFS server base; derived from [url] when blank. */
    val serverBase: String = "",
    /** Stable prefix for local tool names; old configs fall back to [id]. */
    val namespace: String = "",
) {
    val toolPrefix: String
        get() = namespace.ifBlank { id }

    override fun toString(): String =
        "McpServerConfig(id=$id, name=$name, url=$url, enabled=$enabled, namespace=$namespace, hasToken=${token.isNotBlank()}, hasServerBase=${serverBase.isNotBlank()})"
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
    const val MAX_NAMESPACE = 16

    private val namespacePattern = Regex("^[a-z][a-z0-9_]{0,15}$")
    private val secretQuery = Regex("(?i)(?:^|&)(?:token|access_token|api_key|key|secret)=")

    fun validate(
        name: String,
        url: String,
        token: String,
        existingCount: Int,
        namespace: String = "",
        existingNamespaces: Set<String> = emptySet(),
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
        val trimmedNamespace = namespace.trim()
        if (trimmedNamespace.isNotEmpty()) {
            if (!namespacePattern.matches(trimmedNamespace)) return McpConfigError.NAMESPACE
            if (existingNamespaces.any { it.equals(trimmedNamespace, ignoreCase = true) }) {
                return McpConfigError.NAMESPACE_DUPLICATE
            }
        }
        if (existingCount >= MAX_SERVERS) return McpConfigError.LIMIT
        return null
    }

    fun allocateNamespace(
        name: String,
        used: Set<String>,
    ): String {
        val raw = slug(name, MAX_NAMESPACE)
        val base =
            if (raw.firstOrNull()?.isLetter() == true) {
                raw
            } else {
                "s$raw".take(MAX_NAMESPACE).trimEnd('_')
            }
        if (used.none { it.equals(base, ignoreCase = true) }) return base
        var n = 2
        while (true) {
            val suffix = "_$n"
            val candidate = base.take(MAX_NAMESPACE - suffix.length).trimEnd('_') + suffix
            if (used.none { it.equals(candidate, ignoreCase = true) }) return candidate
            n += 1
        }
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
    private val cipher: McpTokenCipher = PlaintextMcpTokenCipher,
) : McpServerRepository {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    override fun load(): List<McpServerConfig> {
        if (!file.isFile) return emptyList()
        val parsed =
            runCatching {
                json.decodeFromString(McpServerFile.serializer(), file.readText(Charsets.UTF_8))
            }.getOrNull() ?: return emptyList()
        val servers =
            parsed.servers
                .filter { server ->
                    server.id.isNotBlank() &&
                        server.name.isNotBlank() &&
                        (server.url.startsWith("http://") || server.url.startsWith("https://"))
                }.take(McpServerValidator.MAX_SERVERS)
                .map { server ->
                    val token =
                        when {
                            server.token.isBlank() -> ""
                            parsed.tokensEncrypted && cipher.supported ->
                                cipher.decrypt(server.id, server.token).orEmpty()
                            parsed.tokensEncrypted -> ""
                            else -> server.token
                        }
                    server.copy(token = token)
                }
        if (!parsed.tokensEncrypted && cipher.supported && servers.any { it.token.isNotBlank() }) {
            runCatching { save(servers) }
        }
        return servers
    }

    override fun save(servers: List<McpServerConfig>) {
        val parent = file.parentFile ?: throw McpException("Could not save MCP servers.")
        if (!parent.isDirectory && !parent.mkdirs()) throw McpException("Could not save MCP servers.")
        val stored =
            servers.map { server ->
                server.copy(
                    token =
                        if (server.token.isBlank()) {
                            ""
                        } else {
                            cipher.encrypt(server.id, server.token)
                        },
                )
            }
        val encrypted = cipher.supported && stored.any { it.token.isNotBlank() }
        val tmp = File(parent, file.name + ".tmp")
        tmp.writeText(
            json.encodeToString(
                McpServerFile.serializer(),
                McpServerFile(servers = stored, tokensEncrypted = encrypted),
            ),
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
    val tokensEncrypted: Boolean = false,
)

package io.nekohasekai.sfa.vfs

import java.net.URI
import java.util.Locale

/**
 * Canonical server-base matching. A Link server is the MCP server base URL,
 * never the grant path. The app stores an optional serverBase; when it is
 * absent, the base is derived from the configured MCP URL by removing the
 * known grant path.
 */
object VfsServerBase {
    // TODO(.72): remove this fallback once portal-knock always supplies serverBase.
    private val GRANT_PATH = Regex("/knocks/mcp/hk_[A-Za-z0-9]+/?$")

    fun normalize(raw: String): String? {
        val uri = parse(raw) ?: return null
        return canonical(uri, uri.rawPath.orEmpty())
    }

    /**
     * Prefer an explicitly configured serverBase. Otherwise treat the MCP URL
     * as a grant URL and strip `/knocks/mcp/{hk_...}` before canonicalizing.
     */
    fun resolve(
        configuredServerBase: String?,
        mcpUrl: String,
    ): String? {
        val explicit = configuredServerBase?.trim().orEmpty()
        if (explicit.isNotEmpty()) return normalize(explicit)
        val uri = parse(mcpUrl) ?: return null
        val basePath = uri.rawPath.orEmpty().replace(GRANT_PATH, "")
        return canonical(uri, basePath)
    }

    fun matches(
        linkServer: String,
        configuredServerBase: String?,
        mcpUrl: String,
    ): Boolean {
        val expected = resolve(configuredServerBase, mcpUrl) ?: return false
        val actual = normalize(linkServer) ?: return false
        return actual == expected
    }

    /** The fixed provider endpoint under the matched server base. */
    fun providerUrl(serverBase: String): String? {
        val base = normalize(serverBase) ?: return null
        val wsBase =
            when {
                base.startsWith("https://") -> "wss://" + base.removePrefix("https://")
                base.startsWith("http://") -> "ws://" + base.removePrefix("http://")
                else -> return null
            }
        return "$wsBase/vfs/provider"
    }

    private fun parse(raw: String): URI? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return null
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase(Locale.US) ?: return null
        if (scheme != "http" && scheme != "https") return null
        if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
        if (uri.host.isNullOrBlank()) return null
        if (uri.port != -1 && uri.port !in 1..65535) return null
        return uri
    }

    private fun canonical(
        uri: URI,
        rawPath: String,
    ): String {
        val scheme = uri.scheme.lowercase(Locale.US)
        val host = uri.host.lowercase(Locale.US)
        val defaultPort = (scheme == "http" && uri.port == 80) || (scheme == "https" && uri.port == 443)
        val authority = if (uri.port == -1 || defaultPort) host else "$host:${uri.port}"
        val path = rawPath.trimEnd('/')
        return "$scheme://$authority$path"
    }
}

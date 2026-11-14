package io.nekohasekai.sfa.vfs

import java.net.URI
import java.util.Locale

/**
 * Canonical issuer matching. A Link issuer is the server base URL, never the
 * MCP grant path. The app stores an optional issuerBase; when it is absent, the
 * base is derived from the configured MCP URL by removing the known grant path.
 */
object VfsIssuer {
    private val GRANT_PATH = Regex("/knocks/mcp/hk_[A-Za-z0-9]+/?$")

    fun normalize(raw: String): String? {
        val uri = parse(raw) ?: return null
        return canonical(uri, uri.rawPath.orEmpty())
    }

    /**
     * Prefer an explicitly configured issuerBase. Otherwise treat the MCP URL
     * as a grant URL and strip `/knocks/mcp/{hk_...}` before canonicalizing.
     */
    fun resolve(
        configuredIssuerBase: String?,
        mcpUrl: String,
    ): String? {
        val explicit = configuredIssuerBase?.trim().orEmpty()
        if (explicit.isNotEmpty()) return normalize(explicit)
        val uri = parse(mcpUrl) ?: return null
        val basePath = uri.rawPath.orEmpty().replace(GRANT_PATH, "")
        return canonical(uri, basePath)
    }

    fun matches(
        linkIssuer: String,
        configuredIssuerBase: String?,
        mcpUrl: String,
    ): Boolean {
        val expected = resolve(configuredIssuerBase, mcpUrl) ?: return false
        val actual = normalize(linkIssuer) ?: return false
        return actual == expected
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

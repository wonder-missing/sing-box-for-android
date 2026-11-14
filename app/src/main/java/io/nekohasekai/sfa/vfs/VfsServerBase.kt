package io.nekohasekai.sfa.vfs

import java.net.URI
import java.util.Locale

/**
 * Canonical server-base matching. A Link server is the MCP server base URL,
 * never the grant path. An explicitly configured serverBase must match exactly;
 * otherwise the configured MCP URL must sit under the Link server. This keeps
 * the app from knowing any particular server's grant-path shape.
 */
object VfsServerBase {
    fun normalize(raw: String): String? {
        val uri = parse(raw) ?: return null
        return canonical(uri, uri.rawPath.orEmpty())
    }

    fun matches(
        linkServer: String,
        configuredServerBase: String?,
        mcpUrl: String,
    ): Boolean {
        val actual = normalize(linkServer) ?: return false
        val explicit = configuredServerBase?.trim().orEmpty()
        if (explicit.isNotEmpty()) return normalize(explicit) == actual
        val configured = parse(mcpUrl) ?: return false
        val configuredUrl = canonical(configured, configured.rawPath.orEmpty())
        return configuredUrl == actual || configuredUrl.startsWith("$actual/")
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

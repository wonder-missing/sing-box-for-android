package io.nekohasekai.sfa.vfs

import java.net.URI

/**
 * Accepts a ws:// or wss:// endpoint. A bare host or a trailing slash gains
 * `/provider`, the vfsd provider path. Credentials in the URL are rejected;
 * the token travels in the Authorization header.
 */
fun normalizeVirtualDirectoryUrl(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase() ?: return null
    if (scheme != "ws" && scheme != "wss") return null
    if (uri.host.isNullOrBlank()) return null
    if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
    val authority = uri.rawAuthority ?: return null
    val path = uri.rawPath.orEmpty()
    val finalPath = if (path.isEmpty() || path == "/") "/provider" else path
    return "$scheme://$authority$finalPath"
}

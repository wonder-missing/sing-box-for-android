package io.nekohasekai.sfa.utils

import io.nekohasekai.sfa.database.TypedProfile
import java.net.HttpURLConnection
import java.net.URL
import java.util.Date

/**
 * Reads the subscription endpoint's Last-Modified header without going through
 * the libbox HTTP client, whose Java binding only exposes the response body.
 *
 * Call from an IO thread. A failure is reported as [Result.fetched] = false so
 * callers can keep a previously known timestamp instead of clearing it.
 */
object RemoteProfileMetadata {
    data class Result(
        val lastModified: Date?,
        val fetched: Boolean,
    )

    fun fetch(url: String, userAgent: String): Result {
        val head =
            runCatching { request(url, "HEAD", userAgent) }.getOrNull()
                ?: return Result(null, false)
        try {
            val code = runCatching { head.responseCode }.getOrNull()
            if (code != null && code in 200..299) {
                val lastModified = parseLastModified(head)
                if (lastModified != null) {
                    return Result(lastModified, true)
                }
            }
        } finally {
            head.disconnect()
        }
        return fetchWithGet(url, userAgent)
    }

    private fun fetchWithGet(url: String, userAgent: String): Result {
        val get =
            runCatching { request(url, "GET", userAgent) }.getOrNull()
                ?: return Result(null, false)
        try {
            get.setRequestProperty("Range", "bytes=0-0")
            val code =
                runCatching { get.responseCode }.getOrElse {
                    return Result(null, false)
                }
            if (code !in 200..299) {
                return Result(null, false)
            }
            return Result(parseLastModified(get), true)
        } finally {
            get.disconnect()
        }
    }

    private fun request(url: String, method: String, userAgent: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 5_000
            readTimeout = 5_000
            instanceFollowRedirects = true
            useCaches = false
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Accept", "*/*")
        }

    private fun parseLastModified(connection: HttpURLConnection): Date? =
        connection
            .getHeaderFieldDate("Last-Modified", 0L)
            .takeIf { it > 0L }
            ?.let(::Date)
}

/**
 * Refreshes [TypedProfile.remoteLastModified] from the endpoint. Returns true
 * when the stored value changed and the profile should be saved again.
 *
 * Call from an IO thread. A failed request keeps the previous value.
 */
fun TypedProfile.refreshRemoteLastModified(
    userAgent: String = HTTPClient.userAgent,
    fetcher: (String, String) -> RemoteProfileMetadata.Result = { url, agent ->
        RemoteProfileMetadata.fetch(url, agent)
    },
): Boolean {
    if (type != TypedProfile.Type.Remote) return false
    val result =
        runCatching {
            fetcher(remoteURL, userAgent)
        }.getOrNull() ?: return false
    if (!result.fetched) return false
    if (remoteLastModified == result.lastModified) return false
    remoteLastModified = result.lastModified
    return true
}

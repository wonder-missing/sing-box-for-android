package io.nekohasekai.sfa.vendor

import android.os.Build
import io.nekohasekai.sfa.BuildConfig
import io.nekohasekai.sfa.update.GitHubReleaseFeed
import io.nekohasekai.sfa.update.UpdateCheckException
import io.nekohasekai.sfa.update.UpdateInfo
import java.io.Closeable
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection

/**
 * Looks for newer WonderSing releases on this fork.
 *
 * Uses the public release Atom feed and asset pages, not the REST API, so an
 * exhausted unauthenticated GitHub API quota (HTTP 403) cannot block updates.
 *
 * versionCode is [GitHubReleaseFeed.WONDER_VERSION_CODE_BASE] plus the `wonder.N`
 * build in the tag. Keep that base in sync with app/build.gradle.kts.
 */
class GitHubUpdateChecker : Closeable {
    companion object {
        private const val REPO_URL = "https://github.com/wonder-missing/sing-box-for-android/releases"
        const val FEED_URL = "$REPO_URL.atom"
        private const val ASSETS_URL = "$REPO_URL/expanded_assets"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 20_000
    }

    fun checkUpdate(): UpdateInfo? {
        val entries = GitHubReleaseFeed.parseFeed(httpGet(FEED_URL))
        if (entries.isEmpty()) {
            throw UpdateCheckException.Unreachable("Empty release feed")
        }
        for (entry in entries.sortedByDescending { GitHubReleaseFeed.versionCodeOf(it.tag) }) {
            val versionCode = GitHubReleaseFeed.versionCodeOf(entry.tag)
            if (versionCode <= 0) continue
            if (versionCode <= BuildConfig.VERSION_CODE) return null
            val assetsPage = httpGet("$ASSETS_URL/${URLEncoder.encode(entry.tag, "UTF-8")}")
            val apk = selectApk(GitHubReleaseFeed.parseAssets(assetsPage)) ?: continue
            return UpdateInfo(
                versionCode = versionCode,
                versionName = entry.tag.removePrefix("v"),
                downloadUrl = apk.downloadUrl,
                releaseUrl = entry.releaseUrl,
                releaseNotes = entry.notes,
                isPrerelease = false,
                fileSize = apk.size,
            )
        }
        return null
    }

    override fun close() = Unit

    private fun selectApk(assets: List<GitHubReleaseFeed.Asset>): GitHubReleaseFeed.Asset? =
        GitHubReleaseFeed.selectApk(
            assets = assets,
            abis = Build.SUPPORTED_ABIS.toList(),
            legacy = Build.VERSION.SDK_INT < Build.VERSION_CODES.M,
        )

    private fun httpGet(url: String): String {
        val connection = (URL(url).openConnection() as HttpsURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("Accept", "application/atom+xml, text/html;q=0.9, */*;q=0.8")
            setRequestProperty("User-Agent", "WonderSing")
        }
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code == HttpURLConnection.HTTP_FORBIDDEN || code == 429) {
                throw UpdateCheckException.Unreachable("GitHub rate limit (HTTP $code)")
            }
            if (code !in 200..299) {
                throw UpdateCheckException.Unreachable("HTTP $code")
            }
            return body
        } catch (e: UpdateCheckException) {
            throw e
        } catch (e: Exception) {
            throw UpdateCheckException.Unreachable(e.message ?: e.javaClass.simpleName, e)
        } finally {
            connection.disconnect()
        }
    }
}

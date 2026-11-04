package io.nekohasekai.sfa.vendor

import android.os.Build
import io.nekohasekai.sfa.BuildConfig
import io.nekohasekai.sfa.update.UpdateCheckException
import io.nekohasekai.sfa.update.UpdateInfo
import io.nekohasekai.sfa.update.UpdateTrack
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.Closeable
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Looks for newer WonderSing releases on this fork.
 *
 * versionCode is [WONDER_VERSION_CODE_BASE] plus the `wonder.N` build in the tag.
 * Keep that base in sync with app/build.gradle.kts.
 */
class GitHubUpdateChecker : Closeable {
    companion object {
        const val RELEASES_URL = "https://api.github.com/repos/wonder-missing/sing-box-for-android/releases"
        const val WONDER_VERSION_CODE_BASE = 11_404_000
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 20_000
        private val WONDER_BUILD = Regex("""wonder\.(\d+)""")
        private val ABI_SUFFIXES = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")

        fun wonderBuild(tagOrName: String): Int? {
            return WONDER_BUILD.find(tagOrName)?.groupValues?.get(1)?.toIntOrNull()
        }

        fun versionCodeOf(tagOrName: String): Int {
            val build = wonderBuild(tagOrName) ?: return 0
            return WONDER_VERSION_CODE_BASE + build
        }

        fun matchesApk(name: String, abi: String, legacy: Boolean): Boolean {
            if (!name.startsWith("SFA-") || !name.endsWith("-$abi.apk")) return false
            return name.contains("-legacy-android-5-") == legacy
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    fun checkUpdate(track: UpdateTrack, githubToken: String): UpdateInfo? {
        val content = httpGet("$RELEASES_URL?per_page=30", githubToken)
        val releases = json.decodeFromString<List<GitHubRelease>>(content)
            .filter { release -> !release.draft && includeRelease(track, release) }
            .sortedByDescending { versionCodeOf(it.tagName) }
        for (release in releases) {
            val versionCode = versionCodeOf(release.tagName)
            if (versionCode <= BuildConfig.VERSION_CODE) {
                return null
            }
            val apk = selectApk(release.assets) ?: continue
            return UpdateInfo(
                versionCode = versionCode,
                versionName = release.tagName.removePrefix("v"),
                downloadUrl = apk.browserDownloadUrl,
                releaseUrl = release.htmlUrl,
                releaseNotes = release.body,
                isPrerelease = release.prerelease,
                fileSize = apk.size,
            )
        }
        return null
    }

    override fun close() = Unit

    private fun includeRelease(track: UpdateTrack, release: GitHubRelease): Boolean {
        if (wonderBuild(release.tagName) != null) return true
        return when (track) {
            UpdateTrack.BETA -> true
            UpdateTrack.STABLE -> !release.prerelease
        }
    }

    private fun selectApk(assets: List<GitHubAsset>): GitHubAsset? {
        val legacy = Build.VERSION.SDK_INT < Build.VERSION_CODES.M
        val preferences = Build.SUPPORTED_ABIS.toList() + "universal"
        for (abi in preferences) {
            if (abi != "universal" && abi !in ABI_SUFFIXES) continue
            val match = assets.find { asset -> matchesApk(asset.name, abi, legacy) }
            if (match != null) return match
        }
        return null
    }

    private fun httpGet(url: String, githubToken: String): String {
        val connection = (URL(url).openConnection() as HttpsURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "WonderSing")
            val token = githubToken.trim()
            if (token.isNotEmpty() && token.none { it == '\r' || it == '\n' }) {
                setRequestProperty("Authorization", "Bearer $token")
            }
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

    @Serializable
    data class GitHubRelease(
        @SerialName("tag_name") val tagName: String = "",
        val name: String = "",
        val body: String? = null,
        val draft: Boolean = false,
        val prerelease: Boolean = false,
        @SerialName("html_url") val htmlUrl: String = "",
        val assets: List<GitHubAsset> = emptyList(),
    )

    @Serializable
    data class GitHubAsset(
        val name: String = "",
        @SerialName("browser_download_url") val browserDownloadUrl: String = "",
        val size: Long = 0,
    )
}

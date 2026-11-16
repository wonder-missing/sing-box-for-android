package io.nekohasekai.sfa.update

import android.content.Context
import io.nekohasekai.sfa.BuildConfig

/**
 * Release notes are generated from the annotated release tag at build time.
 * They are part of the APK, so the `release_notes` tool works offline and does
 * not depend on GitHub or the network.
 */
object BundledReleaseNotes {
    private const val REPO_URL = "https://github.com/wonder-missing/sing-box-for-android/releases"

    fun read(context: Context): ReleaseNotes? {
        val version = BuildConfig.VERSION_NAME
        val asset = "release-notes-$version.txt"
        val notes =
            runCatching {
                context.assets.open(asset).bufferedReader(Charsets.UTF_8).use { it.readText() }.trim()
            }.getOrNull()
        if (!looksLikeCurrentReleaseNotes(notes, version)) return null
        return ReleaseNotes(
            version = version,
            tag = "v$version",
            notes = notes.orEmpty(),
            releaseUrl = "$REPO_URL/tag/v$version",
        )
    }

    internal fun looksLikeCurrentReleaseNotes(
        notes: String?,
        version: String,
    ): Boolean {
        val build = version.substringAfterLast("wonder.", missingDelimiterValue = "")
        return build.isNotBlank() && notes?.trimStart()?.startsWith("wonder.$build") == true
    }
}

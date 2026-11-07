package io.nekohasekai.sfa.update

import java.net.URLDecoder

/**
 * Parses the public GitHub release feed and asset pages.
 *
 * These are regular github.com pages rather than REST API endpoints, so they
 * are not subject to the unauthenticated API limit that returns HTTP 403.
 */
object GitHubReleaseFeed {
    const val WONDER_VERSION_CODE_BASE = 11_404_000

    data class Entry(
        val tag: String,
        val releaseUrl: String,
        val notes: String?,
    )

    data class Asset(
        val name: String,
        val downloadUrl: String,
        val size: Long,
    )

    private val WONDER_BUILD = Regex("""wonder\.(\d+)""")
    private val ABI_SUFFIXES = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
    private val ENTRY = Regex("""<entry(?:\s[^>]*)?>(.*?)</entry>""", RegexOption.DOT_MATCHES_ALL)
    private val TAG_LINK = Regex("""href="([^"]*/releases/tag/[^"]+)"""")
    private val CONTENT = Regex("""<content type="html">(.*?)</content>""", RegexOption.DOT_MATCHES_ALL)
    private val HTML_TAG = Regex("""<[^>]+>""")
    private val LINE_BREAK = Regex("""(?i)<br\s*/?>""")
    private val PARAGRAPH_END = Regex("""(?i)</p\s*>""")
    private val ENTITY = Regex("""&(#x[0-9a-fA-F]+|#\d+|[a-zA-Z]+);""")
    private val LIST_ITEM = Regex("""(?is)<li\b[^>]*>(.*?)</li>""")
    private val ASSET_LINK = Regex("""href="([^"]*?/releases/download/[^"]+?\.apk)"""")
    private val ASSET_SIZE = Regex(""">\s*(\d+(?:\.\d+)?)\s*(B|KB|MB|GB)\s*<""")
    private val NAMED_ENTITIES =
        mapOf(
            "amp" to "&",
            "lt" to "<",
            "gt" to ">",
            "quot" to "\"",
            "apos" to "'",
            "nbsp" to " ",
        )

    fun wonderBuild(tagOrName: String): Int? = WONDER_BUILD.find(tagOrName)?.groupValues?.get(1)?.toIntOrNull()

    fun versionCodeOf(tagOrName: String): Int {
        val build = wonderBuild(tagOrName) ?: return 0
        return WONDER_VERSION_CODE_BASE + build
    }

    fun parseFeed(xml: String): List<Entry> = ENTRY.findAll(xml).mapNotNull { entry ->
        val body = entry.groupValues[1]
        val link = TAG_LINK.find(body)?.groupValues?.get(1)?.let(::decodeEntities) ?: return@mapNotNull null
        val tag = link.substringAfterLast('/').let(::urlDecode)
        if (tag.isBlank()) return@mapNotNull null
        Entry(
            tag = tag,
            releaseUrl = link,
            notes = CONTENT.find(body)?.groupValues?.get(1)?.let(::htmlToText),
        )
    }.toList()

    fun parseAssets(html: String): List<Asset> {
        val blocks = LIST_ITEM.findAll(html).map { it.groupValues[1] }.toList().ifEmpty { listOf(html) }
        return blocks.mapNotNull { block ->
            val href = ASSET_LINK.find(block)?.groupValues?.get(1) ?: return@mapNotNull null
            val name = urlDecode(href.substringAfterLast('/'))
            Asset(
                name = name,
                downloadUrl = if (href.startsWith("http")) href else "https://github.com$href",
                size = ASSET_SIZE.find(block)?.let(::sizeBytes) ?: 0L,
            )
        }.distinctBy { it.name }
    }

    fun selectApk(
        assets: List<Asset>,
        abis: List<String>,
        legacy: Boolean,
    ): Asset? {
        val preferences = abis + "universal"
        for (abi in preferences) {
            if (abi != "universal" && abi !in ABI_SUFFIXES) continue
            val match = assets.find { asset -> matchesApk(asset.name, abi, legacy) }
            if (match != null) return match
        }
        return null
    }

    fun matchesApk(
        name: String,
        abi: String,
        legacy: Boolean,
    ): Boolean {
        if (!name.startsWith("SFA-") || !name.endsWith("-$abi.apk")) return false
        return name.contains("-legacy-android-5-") == legacy
    }

    fun htmlToText(html: String): String? {
        val text =
            decodeEntities(html)
                .replace(LINE_BREAK, "\n")
                .replace(PARAGRAPH_END, "\n")
                .replace(HTML_TAG, "")
                .replace("\r\n", "\n")
                .replace(Regex("""[ \t]+\n"""), "\n")
                .replace(Regex("""\n{3,}"""), "\n\n")
                .trim()
        return text.ifBlank { null }
    }

    fun decodeEntities(text: String): String {
        if ('&' !in text) return text
        return ENTITY.replace(text) { match ->
            val token = match.groupValues[1]
            when {
                token.startsWith("#x", ignoreCase = true) -> codePoint(token.substring(2).toIntOrNull(16)) ?: match.value
                token.startsWith("#") -> codePoint(token.substring(1).toIntOrNull()) ?: match.value
                else -> NAMED_ENTITIES[token] ?: match.value
            }
        }
    }

    private fun urlDecode(value: String): String = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

    private fun codePoint(value: Int?): String? {
        if (value == null || value < 0 || value > 0x10FFFF) return null
        return StringBuilder().appendCodePoint(value).toString()
    }

    private fun sizeBytes(match: MatchResult): Long {
        val number = match.groupValues[1].toDoubleOrNull() ?: return 0L
        val multiplier =
            when (match.groupValues[2].uppercase()) {
                "GB" -> 1024.0 * 1024 * 1024
                "MB" -> 1024.0 * 1024
                "KB" -> 1024.0
                else -> 1.0
            }
        return (number * multiplier).toLong()
    }
}

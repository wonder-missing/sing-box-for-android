package io.nekohasekai.sfa.agent

/**
 * The single logical namespace for files the user has shared with Conductor.
 * Inputs are virtual: the VFS provider maps this path to the original
 * content URI or image-store file. No copy is made.
 */
object InboxPaths {
    const val ROOT = "conductor/resources/inbox"

    private val idPattern = Regex("[A-Za-z0-9._-]{1,64}")

    fun validId(id: String): Boolean =
        id != "." && id != ".." && idPattern.matches(id)

    fun path(
        id: String,
        originalName: String,
        mimeType: String,
    ): String {
        require(validId(id)) { "Invalid inbox id." }
        return "$ROOT/$id/${fileName(originalName, mimeType)}"
    }

    /**
     * Keeps a display name safe for a path segment. Unicode letters and
     * digits are preserved; separators and control characters are replaced.
     */
    fun fileName(
        originalName: String,
        mimeType: String,
    ): String {
        val base = originalName.substringAfterLast('/').substringAfterLast('\\').trim()
        val cleaned =
            buildString {
                base.forEach { ch ->
                    when {
                        ch.isLetterOrDigit() || ch == '.' || ch == '_' || ch == '-' -> append(ch)
                        ch.isWhitespace() -> append('_')
                        else -> append('_')
                    }
                }
            }.replace(Regex("_+"), "_").trim('.', '_', '-').take(MAX_NAME_CHARS)
        val safe = cleaned.ifBlank { "file" }
        val withExtension =
            if ('.' in safe) {
                safe
            } else {
                "$safe.${extensionFor(mimeType)}"
            }
        return if (withExtension == "." || withExtension == "..") {
            "file.${extensionFor(mimeType)}"
        } else {
            withExtension
        }
    }

    private fun extensionFor(mimeType: String): String =
        when (mimeType.substringBefore(';').trim().lowercase()) {
            "image/jpeg", "image/jpg" -> "jpg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            "image/svg+xml" -> "svg"
            "application/pdf" -> "pdf"
            "application/json" -> "json"
            "text/plain", "text/markdown" -> "txt"
            "text/csv" -> "csv"
            else -> "bin"
        }

    private const val MAX_NAME_CHARS = 120
}

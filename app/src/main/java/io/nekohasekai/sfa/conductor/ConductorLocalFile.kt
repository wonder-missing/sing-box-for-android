package io.nekohasekai.sfa.conductor

/**
 * A local resource link rendered by Markdown. Only files under the
 * Conductor resource directory are opened; other schemes stay untouched.
 */
internal fun conductorLocalResourcePath(url: String): String? {
    val clean =
        url
            .substringBefore('#')
            .substringBefore('?')
            .trim()
            .removePrefix("./")
            .trimStart('/')
    return when {
        clean.startsWith("conductor/resources/") -> clean
        clean.startsWith("resources/") -> "conductor/$clean"
        else -> null
    }
}

data class LocalFilePreview(
    val path: String,
    val text: String,
    val truncated: Boolean,
    val error: String? = null,
)

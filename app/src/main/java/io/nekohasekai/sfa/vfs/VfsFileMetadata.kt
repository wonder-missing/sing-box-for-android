package io.nekohasekai.sfa.vfs

/** Shared metadata helpers for read-only VFS sources. */
object VfsFileMetadata {
    fun mimeTypeFor(name: String): String =
        when (name.substringAfterLast('.', "").lowercase()) {
            "md", "markdown" -> "text/markdown; charset=utf-8"
            "txt", "log" -> "text/plain; charset=utf-8"
            "json" -> "application/json; charset=utf-8"
            "yaml", "yml" -> "application/yaml; charset=utf-8"
            "csv" -> "text/csv; charset=utf-8"
            else -> "application/octet-stream"
        }

    fun fileEtag(
        size: Long,
        modifiedAt: Long,
    ): String = "\"$size-$modifiedAt\""
}

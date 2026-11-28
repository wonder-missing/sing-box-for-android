package io.nekohasekai.sfa.vfs

private const val MAX_PATH_CHARS = 4096

/**
 * Splits a provider path into segments. Paths are relative, without traversal;
 * the app rejects anything it would not serve.
 */
internal fun virtualDirectorySegments(path: String): List<String> {
    if (path.length > MAX_PATH_CHARS) {
        throw VfsException(VfsErrorCode.INVALID_PATH, "Path is too long.")
    }
    if (path.startsWith("/")) {
        throw VfsException(VfsErrorCode.INVALID_PATH, "Paths must be relative.")
    }
    if (path.any { it == '\u0000' || it == '\\' || it.code < 0x20 }) {
        throw VfsException(VfsErrorCode.INVALID_PATH, "Path contains a forbidden character.")
    }
    if (path.isEmpty()) return emptyList()
    val segments = path.split('/')
    if (segments.any { it.isEmpty() || it == "." || it == ".." }) {
        throw VfsException(VfsErrorCode.INVALID_PATH, "Path traversal is not allowed.")
    }
    return segments
}

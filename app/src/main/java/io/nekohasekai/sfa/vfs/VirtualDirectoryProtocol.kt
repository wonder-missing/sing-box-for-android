package io.nekohasekai.sfa.vfs

data class VfsEntry(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val mtimeMs: Long,
    val mimeType: String? = null,
    val etag: String? = null,
)

data class VfsRead(
    val data: ByteArray,
    val eof: Boolean,
)

object VfsErrorCode {
    const val NOT_FOUND = "not_found"
    const val INVALID_PATH = "invalid_path"
    const val BAD_REQUEST = "bad_request"
    const val NOT_SUPPORTED = "not_supported"
    const val PROVIDER_ERROR = "provider_error"
    const val RANGE_NOT_SATISFIABLE = "range_not_satisfiable"
    const val PERMISSION_LOST = "permission_lost"
}

class VfsException(
    val code: String,
    message: String,
) : Exception(message)

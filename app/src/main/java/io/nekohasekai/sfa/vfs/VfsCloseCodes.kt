package io.nekohasekai.sfa.vfs

/** The numeric close codes frozen in VFS.md. */
object VfsCloseCodes {
    const val BACKGROUND: Int = 4000
    const val REVOKED: Int = 4001
    const val LINK_EXPIRED: Int = 4002
    const val HEARTBEAT_TIMEOUT: Int = 4003
    const val REPLACED: Int = 4004
    const val VFSD_RESTART: Int = 4005
    const val UNAUTHORIZED: Int = 4401
    const val DEVICE_MISMATCH: Int = 4403
    const val REBIND_REQUIRED: Int = 4409
    const val RATE_LIMITED: Int = 4429

    fun isTerminal(code: Int): Boolean =
        code == REVOKED ||
            code == LINK_EXPIRED ||
            code == UNAUTHORIZED ||
            code == DEVICE_MISMATCH ||
            code == REBIND_REQUIRED

    fun isBackoff(code: Int): Boolean =
        code == HEARTBEAT_TIMEOUT ||
            code == VFSD_RESTART ||
            code == RATE_LIMITED

    fun errorCode(code: Int): String? =
        when (code) {
            REVOKED -> "VFS_LINK_REVOKED"
            LINK_EXPIRED -> "VFS_LINK_EXPIRED"
            HEARTBEAT_TIMEOUT,
            VFSD_RESTART,
            RATE_LIMITED -> "VFS_CONNECT_FAILED"
            UNAUTHORIZED -> "VFS_UNAUTHORIZED"
            DEVICE_MISMATCH -> "VFS_DEVICE_MISMATCH"
            REBIND_REQUIRED -> "VFS_DEVICE_REBIND_REQUIRED"
            else -> null
        }

    fun retryAfterMs(reason: String?): Long? =
        Regex("retryAfterMs[^0-9]{0,10}(\\d+)", RegexOption.IGNORE_CASE)
            .find(reason.orEmpty())
            ?.groupValues
            ?.get(1)
            ?.toLongOrNull()
}

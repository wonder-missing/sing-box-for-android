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
}

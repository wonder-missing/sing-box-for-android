package io.nekohasekai.sfa.vfs

/**
 * Decides whether the sharing foreground service should stay up. The service
 * is what keeps a backgrounded provider connection alive; it must exist for
 * any live or retrying session and must not linger after the session ended.
 */
object VfsSharingPolicy {
    fun shouldRun(
        state: VfsConnectionState,
        hasActiveConnection: Boolean,
    ): Boolean =
        when (state) {
            is VfsConnectionState.Connecting,
            is VfsConnectionState.Connected,
            is VfsConnectionState.Backoff,
            -> true

            is VfsConnectionState.Confirming,
            is VfsConnectionState.Error,
            -> hasActiveConnection

            VfsConnectionState.Idle -> false
        }
}

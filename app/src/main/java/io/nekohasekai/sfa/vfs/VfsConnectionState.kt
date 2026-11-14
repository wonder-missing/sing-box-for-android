package io.nekohasekai.sfa.vfs

sealed interface VfsConnectionState {
    data object Idle : VfsConnectionState

    data class Confirming(
        val link: VfsLink,
        val serverName: String,
    ) : VfsConnectionState

    data object Connecting : VfsConnectionState

    data class Connected(
        val issuer: String,
        val scope: Set<VfsScope>,
        val since: Long,
    ) : VfsConnectionState

    data class Backoff(
        val attempt: Int,
        val retryInMs: Long,
        val reason: String,
    ) : VfsConnectionState

    data class Error(
        val code: String,
        val message: String,
    ) : VfsConnectionState
}

sealed interface VfsConnectionEvent {
    data class Request(
        val link: VfsLink,
        val serverName: String,
    ) : VfsConnectionEvent

    data object ConsentApproved : VfsConnectionEvent

    data class ConsentDenied(
        val message: String,
    ) : VfsConnectionEvent

    data class Open(
        val issuer: String,
        val scope: Set<VfsScope>,
        val since: Long,
    ) : VfsConnectionEvent

    data class Drop(
        val reason: String,
        val attempt: Int,
        val retryInMs: Long,
    ) : VfsConnectionEvent

    data object Retry : VfsConnectionEvent

    data class Fail(
        val code: String,
        val message: String,
    ) : VfsConnectionEvent

    data object Background : VfsConnectionEvent

    data object Reset : VfsConnectionEvent
}

/**
 * Pure transition table. Network and consent side effects live outside; this
 * only decides which visible state follows an event.
 */
object VfsConnectionStateMachine {
    fun reduce(
        state: VfsConnectionState,
        event: VfsConnectionEvent,
    ): VfsConnectionState =
        when (event) {
            is VfsConnectionEvent.Request ->
                when (state) {
                    VfsConnectionState.Idle,
                    is VfsConnectionState.Error,
                    is VfsConnectionState.Backoff -> VfsConnectionState.Confirming(event.link, event.serverName)
                    else -> state
                }

            VfsConnectionEvent.ConsentApproved ->
                if (state is VfsConnectionState.Confirming) VfsConnectionState.Connecting else state

            is VfsConnectionEvent.ConsentDenied ->
                if (state is VfsConnectionState.Confirming) {
                    VfsConnectionState.Error("VFS_USER_DENIED", event.message)
                } else {
                    state
                }

            is VfsConnectionEvent.Open ->
                if (state == VfsConnectionState.Connecting) {
                    VfsConnectionState.Connected(event.issuer, event.scope, event.since)
                } else {
                    state
                }

            is VfsConnectionEvent.Drop ->
                if (state is VfsConnectionState.Connected || state == VfsConnectionState.Connecting) {
                    VfsConnectionState.Backoff(event.attempt, event.retryInMs, event.reason)
                } else {
                    state
                }

            VfsConnectionEvent.Retry ->
                if (state is VfsConnectionState.Backoff) VfsConnectionState.Connecting else state

            is VfsConnectionEvent.Fail ->
                when (state) {
                    VfsConnectionState.Idle -> state
                    else -> VfsConnectionState.Error(event.code, event.message)
                }

            VfsConnectionEvent.Background,
            VfsConnectionEvent.Reset -> VfsConnectionState.Idle
        }
}

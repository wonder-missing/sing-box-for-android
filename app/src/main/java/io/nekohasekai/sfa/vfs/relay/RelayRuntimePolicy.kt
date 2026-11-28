package io.nekohasekai.sfa.vfs.relay

/** Pure runtime policies so Stop/Backoff/FGS behavior is testable on the JVM. */
object RelayRuntimePolicy {
    fun backoffMs(attempt: Int): Long =
        when {
            attempt <= 1 -> 1_000L
            attempt == 2 -> 2_000L
            attempt == 3 -> 4_000L
            attempt == 4 -> 8_000L
            attempt == 5 -> 15_000L
            else -> 30_000L
        }

    fun shouldReconnect(desiredConnected: Boolean): Boolean = desiredConnected

    /** A Relay session only exists while the selected server has Shares to serve. */
    fun shouldServe(activeShares: Int): Boolean = activeShares > 0

    /**
     * Revoke must not cancel a reconnect that other Shares still need. Only a
     * successfully tombstoned last Share may stop the session; an offline
     * Revoke with remaining Shares must resume the backoff.
     */
    fun shouldRescheduleAfterRevoke(
        remaining: Int,
        tombstoned: Boolean,
        desiredConnected: Boolean,
        clientOpen: Boolean,
    ): Boolean = desiredConnected && !(tombstoned && remaining == 0) && (!clientOpen || remaining == 0)

    /** Only Shares of the current Relay Server count toward an empty session. */
    fun currentServerActiveShares(
        records: List<VfsShareRecord>,
        serverId: String,
        canonicalBase: String,
    ): List<VfsShareRecord> =
        records.filter {
            it.serverId == serverId &&
                it.canonicalBase == canonicalBase &&
                it.revokedAt == null &&
                it.revokingAt == null
        }

    fun shouldStopEmptySession(remainingInCurrentServer: Int): Boolean = remainingInCurrentServer == 0

    /** Display status for one Share; stopped wins over pair/registration states. */
    fun shareStatus(
        record: VfsShareRecord,
        selectedServerId: String?,
        selectedBase: String?,
    ): String =
        when {
            record.revokedAt != null || record.revokingAt != null -> "revoked"
            selectedServerId != null &&
                (record.serverId != selectedServerId || record.canonicalBase != selectedBase) -> "offline"
            record.stopped -> "stopped"
            record.registrationState == VfsShareRecord.STATE_FAILED -> "failed"
            record.registrationState != VfsShareRecord.STATE_REGISTERED -> "pending"
            record.pairState == VfsShareRecord.PAIR_CONSUMED -> "paired"
            else -> record.pairState
        }

    fun responseAllowed(
        record: VfsShareRecord,
        revokedLinkIds: Set<String>,
    ): Boolean =
        record.revokedAt == null &&
            record.revokingAt == null &&
            !record.stopped &&
            record.linkId !in revokedLinkIds
}

object RelayForegroundGuard {
    const val BLOCKED = "FOREGROUND_SERVICE_BLOCKED"

    /** The provider WS may only exist while the visible sharing FGS is alive. */
    fun blocked(
        started: Boolean,
        serviceActive: Boolean,
    ): Boolean = !started || !serviceActive
}

data class RelayPairFrame(
    val state: String,
    val pairId: String?,
    val ttlSeconds: Long?,
)

object RelayPairFrames {
    fun frameFor(record: VfsShareRecord): RelayPairFrame? =
        when (record.pairState) {
            VfsShareRecord.PAIR_ACTIVE -> {
                val pairId = record.pairId ?: return null
                val remaining =
                    ((record.pairExpiresAt ?: 0) - System.currentTimeMillis())
                        .coerceIn(1, 300_000L)
                RelayPairFrame("active", pairId, (remaining + 999) / 1000)
            }

            VfsShareRecord.PAIR_CONSUMED ->
                record.pairId?.let { RelayPairFrame("consumed", it, null) }

            VfsShareRecord.PAIR_EXPIRED ->
                record.pairId?.let { RelayPairFrame("expired", it, null) }

            VfsShareRecord.PAIR_PAUSED -> RelayPairFrame("paused", null, null)
            else -> null
        }
}

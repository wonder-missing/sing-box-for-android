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

    /** Only Shares of the current Portal count toward an empty session. */
    fun currentPortalActiveShares(
        records: List<VfsShareRecord>,
        portalServerId: String,
        canonicalBase: String,
    ): List<VfsShareRecord> =
        records.filter {
            it.portalServerId == portalServerId &&
                it.canonicalBase == canonicalBase &&
                it.revokedAt == null &&
                it.revokingAt == null
        }

    fun shouldStopEmptySession(remainingInCurrentPortal: Int): Boolean = remainingInCurrentPortal == 0

    fun responseAllowed(
        record: VfsShareRecord,
        revokedLinkIds: Set<String>,
    ): Boolean =
        record.revokedAt == null &&
            record.revokingAt == null &&
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

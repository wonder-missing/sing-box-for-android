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

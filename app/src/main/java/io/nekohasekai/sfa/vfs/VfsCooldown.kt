package io.nekohasekai.sfa.vfs

/**
 * Per-server confirmation cooldown after a denial: 30s, 2m, then 10m. The
 * counter is reset only after a successful connection, not after the timer
 * expires.
 */
class VfsCooldown(
    private val delaysMs: List<Long> = listOf(30_000L, 120_000L, 600_000L),
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var denials = 0
    private var untilMs = 0L

    init {
        require(delaysMs.isNotEmpty()) { "delaysMs must not be empty" }
        require(delaysMs.all { it >= 0 }) { "delaysMs must not be negative" }
    }

    fun recordDenial(): Long {
        val delay = delaysMs[minOf(denials, delaysMs.lastIndex)]
        denials += 1
        untilMs = now() + delay
        return delay
    }

    fun remainingMs(): Long = (untilMs - now()).coerceAtLeast(0L)

    val isCoolingDown: Boolean
        get() = remainingMs() > 0L

    fun reset() {
        denials = 0
        untilMs = 0L
    }
}

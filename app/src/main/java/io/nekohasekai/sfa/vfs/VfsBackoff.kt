package io.nekohasekai.sfa.vfs

import kotlin.random.Random

/**
 * Foreground retry schedule: 1s, 2s, 4s... capped at 60s, with +/-20% jitter
 * and at most [maxAttempts] retries. Returns null once the budget is spent.
 */
class VfsBackoff(
    private val initialMs: Long = 1_000L,
    private val maxMs: Long = 60_000L,
    private val maxAttempts: Int = 10,
    private val jitterFraction: Double = 0.2,
    private val random: () -> Double = { Random.nextDouble() },
) {
    private var attempts = 0
    private var baseMs = initialMs

    init {
        require(initialMs > 0) { "initialMs must be positive" }
        require(maxMs >= initialMs) { "maxMs must not be smaller than initialMs" }
        require(maxAttempts > 0) { "maxAttempts must be positive" }
        require(jitterFraction in 0.0..1.0) { "jitterFraction must be between 0 and 1" }
    }

    val attemptCount: Int
        get() = attempts

    fun reset() {
        attempts = 0
        baseMs = initialMs
    }

    fun nextDelayMs(): Long? {
        if (attempts >= maxAttempts) return null
        val base = baseMs
        attempts += 1
        baseMs = minOf(maxMs, baseMs * 2)
        val factor = 1.0 + (random() * 2.0 - 1.0) * jitterFraction
        return (base * factor).toLong().coerceAtLeast(0L)
    }
}

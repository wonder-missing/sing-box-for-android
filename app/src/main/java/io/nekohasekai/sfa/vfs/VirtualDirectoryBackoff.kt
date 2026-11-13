package io.nekohasekai.sfa.vfs

/** 1s, 2s, 4s, ... capped at 60s. Reset after a completed handshake. */
class VirtualDirectoryBackoff(
    private val initialMs: Long = 1_000L,
    private val maxMs: Long = 60_000L,
) {
    private var nextMs = initialMs

    init {
        require(initialMs > 0) { "initialMs must be positive" }
        require(maxMs >= initialMs) { "maxMs must not be smaller than initialMs" }
    }

    fun reset() {
        nextMs = initialMs
    }

    fun nextDelayMs(): Long {
        val delay = nextMs
        nextMs = minOf(maxMs, nextMs * 2)
        return delay
    }
}

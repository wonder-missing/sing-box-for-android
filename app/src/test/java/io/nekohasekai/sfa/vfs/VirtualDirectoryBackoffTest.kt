package io.nekohasekai.sfa.vfs

import org.junit.Assert.assertEquals
import org.junit.Test

class VirtualDirectoryBackoffTest {
    @Test
    fun delaysDoubleAndCapAtSixtySeconds() {
        val backoff = VirtualDirectoryBackoff()

        val delays = List(8) { backoff.nextDelayMs() }

        assertEquals(
            listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L),
            delays,
        )
    }

    @Test
    fun resetStartsFromTheInitialDelay() {
        val backoff = VirtualDirectoryBackoff()
        repeat(4) { backoff.nextDelayMs() }

        backoff.reset()

        assertEquals(1_000L, backoff.nextDelayMs())
        assertEquals(2_000L, backoff.nextDelayMs())
    }

    @Test
    fun customLimitsAreHonored() {
        val backoff = VirtualDirectoryBackoff(initialMs = 250L, maxMs = 1_000L)

        assertEquals(listOf(250L, 500L, 1_000L, 1_000L), List(4) { backoff.nextDelayMs() })
    }

    @Test(expected = IllegalArgumentException::class)
    fun initialDelayMustBePositive() {
        VirtualDirectoryBackoff(initialMs = 0L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun maximumMustNotBeSmallerThanInitial() {
        VirtualDirectoryBackoff(initialMs = 2_000L, maxMs = 1_000L)
    }
}

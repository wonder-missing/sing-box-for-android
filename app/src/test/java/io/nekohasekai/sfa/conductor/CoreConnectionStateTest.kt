package io.nekohasekai.sfa.conductor

import org.junit.Assert.assertEquals
import org.junit.Test

class CoreConnectionStateTest {
    @Test
    fun reconnectsWhenTheStreamDiesWhileTheServiceStaysStarted() {
        var connected = false
        var connects = 0
        var disconnects = 0
        var now = 0L
        val state =
            CoreConnectionState(
                connect = { connects += 1 },
                disconnect = { disconnects += 1 },
                isConnected = { connected },
                nowMs = { now },
                retryIntervalMs = 5_000,
            )

        state.onServiceState(shouldConnect = true)
        assertEquals(1, connects)

        connected = true
        state.onServiceState(shouldConnect = true)
        assertEquals(1, connects)

        // The service state did not change, but the command stream broke.
        connected = false
        now = 5_000
        state.onServiceState(shouldConnect = true)
        assertEquals(2, connects)
        assertEquals(0, disconnects)
    }

    @Test
    fun doesNotDialAgainBeforeTheRetryInterval() {
        var connected = false
        var connects = 0
        var now = 0L
        val state =
            CoreConnectionState(
                connect = { connects += 1 },
                disconnect = {},
                isConnected = { connected },
                nowMs = { now },
                retryIntervalMs = 5_000,
            )

        state.onServiceState(shouldConnect = true)
        now = 4_999
        state.onServiceState(shouldConnect = true)
        assertEquals(1, connects)

        now = 5_000
        state.onServiceState(shouldConnect = true)
        assertEquals(2, connects)
    }

    @Test
    fun disconnectsOnlyWhenTheServiceGoesAwayAndReconnectsOnRestart() {
        var connected = false
        var connects = 0
        var disconnects = 0
        var now = 0L
        val state =
            CoreConnectionState(
                connect = { connects += 1 },
                disconnect = { disconnects += 1 },
                isConnected = { connected },
                nowMs = { now },
                retryIntervalMs = 5_000,
            )

        state.onServiceState(shouldConnect = false)
        assertEquals(0, disconnects)

        state.onServiceState(shouldConnect = true)
        assertEquals(1, connects)

        state.onServiceState(shouldConnect = false)
        state.onServiceState(shouldConnect = false)
        assertEquals(1, disconnects)

        now = 1
        state.onServiceState(shouldConnect = true)
        assertEquals(2, connects)
    }
}

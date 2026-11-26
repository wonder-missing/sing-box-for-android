package io.nekohasekai.sfa.vfs.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayRuntimePolicyTest {
    @Test
    fun backoffIsBoundedAndMonotonic() {
        val values = (1..8).map(RelayRuntimePolicy::backoffMs)
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L, 30_000L, 30_000L, 30_000L), values)
        assertTrue(values.zipWithNext().all { (a, b) -> b >= a })
    }

    @Test
    fun stopOrDisconnectDisablesTheReconnectDecision() {
        assertTrue(RelayRuntimePolicy.shouldReconnect(true))
        assertFalse(RelayRuntimePolicy.shouldReconnect(false))
    }

    @Test
    fun foregroundGuardBlocksMissingOrStoppedService() {
        assertTrue(RelayForegroundGuard.blocked(started = false, serviceActive = true))
        assertTrue(RelayForegroundGuard.blocked(started = true, serviceActive = false))
        assertFalse(RelayForegroundGuard.blocked(started = true, serviceActive = true))
    }

    @Test
    fun revokeSuppressesInFlightResponses() {
        val base =
            VfsShareRecord(
                shareId = "s", linkId = "l", secretCiphertext = "c", scopes = listOf("app.files"),
                portalServerId = "p", hookId = "h", canonicalBase = "https://x", deviceId = "d", createdAt = 0,
            )
        assertTrue(RelayRuntimePolicy.responseAllowed(base, emptySet()))
        assertFalse(RelayRuntimePolicy.responseAllowed(base.copy(revokedAt = 1), emptySet()))
        assertFalse(RelayRuntimePolicy.responseAllowed(base.copy(revokingAt = 1), emptySet()))
        assertFalse(RelayRuntimePolicy.responseAllowed(base, setOf("l")))
    }

    @Test
    fun revokeKeepsReconnectAliveWhileOtherSharesRemain() {
        assertTrue(RelayRuntimePolicy.shouldRescheduleAfterRevoke(1, tombstoned = false, desiredConnected = true, clientOpen = false))
        assertTrue(RelayRuntimePolicy.shouldRescheduleAfterRevoke(1, tombstoned = true, desiredConnected = true, clientOpen = false))
        assertFalse(RelayRuntimePolicy.shouldRescheduleAfterRevoke(0, tombstoned = true, desiredConnected = true, clientOpen = false))
        assertFalse(RelayRuntimePolicy.shouldRescheduleAfterRevoke(1, tombstoned = false, desiredConnected = false, clientOpen = false))
        assertFalse(RelayRuntimePolicy.shouldRescheduleAfterRevoke(1, tombstoned = false, desiredConnected = true, clientOpen = true))
    }

    @Test
    fun pairFramesPreserveStateRulesForReconnectReplay() {
        val now = System.currentTimeMillis()
        val base =
            VfsShareRecord(
                shareId = "s", linkId = "l", secretCiphertext = "c", scopes = listOf("app.files"),
                portalServerId = "p", hookId = "h", canonicalBase = "https://x", deviceId = "d", createdAt = 0,
            )
        val active = RelayPairFrames.frameFor(base.copy(pairState = "active", pairId = "pid", pairExpiresAt = now + 300_000))
        assertEquals("active", active!!.state)
        assertEquals("pid", active.pairId)
        assertTrue(active.ttlSeconds!! in 1..300)
        val consumed = RelayPairFrames.frameFor(base.copy(pairState = "consumed", pairId = "pid"))
        assertEquals("consumed", consumed!!.state)
        assertEquals("pid", consumed.pairId)
        assertNull(consumed.ttlSeconds)
        val expired = RelayPairFrames.frameFor(base.copy(pairState = "expired", pairId = "pid"))
        assertEquals("expired", expired!!.state)
        assertEquals("pid", expired.pairId)
        val paused = RelayPairFrames.frameFor(base.copy(pairState = "paused", pairId = "pid"))
        assertEquals("paused", paused!!.state)
        assertNull(paused.pairId)
        assertNull(paused.ttlSeconds)
        assertNull(RelayPairFrames.frameFor(base.copy(pairState = "waiting")))
    }
}

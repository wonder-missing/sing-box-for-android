package io.nekohasekai.sfa.vfs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VfsPolicyTest {
    @Test
    fun backoffFollowsTheAgreedScheduleAndJitter() {
        val delays = mutableListOf<Long>()
        val backoff = VfsBackoff(random = { 0.5 })
        repeat(10) { delays += backoff.nextDelayMs()!! }

        assertEquals(
            listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L, 60_000L, 60_000L),
            delays,
        )
        assertNull(backoff.nextDelayMs())
        backoff.reset()
        assertEquals(1_000L, backoff.nextDelayMs())
    }

    @Test
    fun backoffJitterStaysWithinTwentyPercent() {
        val slow = VfsBackoff(random = { 0.0 })
        val fast = VfsBackoff(random = { 1.0 })

        assertEquals(800L, slow.nextDelayMs())
        assertEquals(1_200L, fast.nextDelayMs())
    }

    @Test
    fun cooldownEscalatesAndResetsOnlyAfterSuccess() {
        var now = 0L
        val cooldown = VfsCooldown(now = { now })

        assertEquals(30_000L, cooldown.recordDenial())
        assertEquals(30_000L, cooldown.remainingMs())
        now += 30_000L
        assertFalse(cooldown.isCoolingDown)

        assertEquals(120_000L, cooldown.recordDenial())
        now += 120_000L
        assertEquals(600_000L, cooldown.recordDenial())
        assertEquals(600_000L, cooldown.remainingMs())

        cooldown.reset()
        assertFalse(cooldown.isCoolingDown)
        assertEquals(30_000L, cooldown.recordDenial())
    }

    @Test
    fun trustCoversSubsetsButNotWiderScopes() {
        var now = 1_000L
        val store = VfsTrustStore(now = { now }, inactivityMs = 100L)
        val server = "https://example.com"
        store.grant(
            VfsTrust(
                server = server,
                scope = setOf(VfsScope.CONDUCTOR, VfsScope.TRACE),
                lastUsedAt = now,
                expiresAt = now + 100L,
                processScoped = false,
            ),
        )

        assertNotNull(store.find(server, setOf(VfsScope.CONDUCTOR)))
        assertNotNull(store.find(server, setOf(VfsScope.CONDUCTOR, VfsScope.TRACE)))
        assertNull(store.find(server, setOf(VfsScope.CONVERSATION)))
        assertNull(store.find(server, setOf(VfsScope.CONDUCTOR, VfsScope.CONVERSATION)))

        now += 101L
        assertNull(store.find(server, setOf(VfsScope.CONDUCTOR)))
    }

    @Test
    fun processTrustDiesWithoutAffectingPersistentTrust() {
        val now = 1_000L
        val store = VfsTrustStore(now = { now })
        val server = "https://example.com"
        store.grant(
            VfsTrust(
                server = server,
                scope = setOf(VfsScope.CONDUCTOR),
                lastUsedAt = now,
                expiresAt = now,
                processScoped = false,
            ),
        )
        store.grant(
            VfsTrust(
                server = server,
                scope = setOf(VfsScope.CONVERSATION),
                lastUsedAt = now,
                expiresAt = 0L,
                processScoped = true,
            ),
        )

        assertNotNull(store.find(server, setOf(VfsScope.CONVERSATION)))
        store.clearProcessScope()
        assertNull(store.find(server, setOf(VfsScope.CONVERSATION)))
        assertNotNull(store.find(server, setOf(VfsScope.CONDUCTOR)))
    }

    @Test
    fun touchingATrustExtendsItsExpiry() {
        var now = 1_000L
        val store = VfsTrustStore(now = { now }, inactivityMs = 100L)
        val server = "https://example.com"
        store.grant(
            VfsTrust(
                server = server,
                scope = setOf(VfsScope.CONDUCTOR),
                lastUsedAt = now,
                expiresAt = now + 100L,
                processScoped = false,
            ),
        )

        now = 1_050L
        store.touch(server, setOf(VfsScope.CONDUCTOR))
        now = 1_140L
        assertNotNull(store.find(server, setOf(VfsScope.CONDUCTOR)))
    }

    @Test
    fun connectionStateMachineFollowsTheHappyPathAndStops() {
        val link = VfsLink("https://example.com", "link_1", setOf(VfsScope.CONDUCTOR))
        var state: VfsConnectionState = VfsConnectionState.Idle

        state = VfsConnectionStateMachine.reduce(state, VfsConnectionEvent.Request(link, "Portal"))
        assertTrue(state is VfsConnectionState.Confirming)

        state = VfsConnectionStateMachine.reduce(state, VfsConnectionEvent.ConsentApproved)
        assertEquals(VfsConnectionState.Connecting, state)

        state =
            VfsConnectionStateMachine.reduce(
                state,
                VfsConnectionEvent.Open(link.server, link.scope, 10L),
            )
        assertTrue(state is VfsConnectionState.Connected)

        state =
            VfsConnectionStateMachine.reduce(
                state,
                VfsConnectionEvent.Drop("offline", 1, 1_000L),
            )
        assertTrue(state is VfsConnectionState.Backoff)

        state = VfsConnectionStateMachine.reduce(state, VfsConnectionEvent.Retry)
        assertEquals(VfsConnectionState.Connecting, state)

        state =
            VfsConnectionStateMachine.reduce(
                state,
                VfsConnectionEvent.Fail("VFS_UNAUTHORIZED", "token rotated"),
            )
        assertTrue(state is VfsConnectionState.Error)

        state = VfsConnectionStateMachine.reduce(state, VfsConnectionEvent.Reset)
        assertEquals(VfsConnectionState.Idle, state)
    }

    @Test
    fun invalidTransitionsDoNotChangeTheState() {
        val state = VfsConnectionState.Idle

        assertEquals(
            state,
            VfsConnectionStateMachine.reduce(state, VfsConnectionEvent.ConsentApproved),
        )
        assertEquals(
            state,
            VfsConnectionStateMachine.reduce(state, VfsConnectionEvent.Retry),
        )
    }
}

package io.nekohasekai.sfa.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnSessionStateTest {
    @Test
    fun startPublishesIdAndStopClearsIt() {
        val state = VpnSessionState(idFactory = { "session-1" })
        assertNull(state.current)

        assertEquals("session-1", state.start())
        assertEquals("session-1", state.current)

        state.stop()
        assertNull(state.current)
    }

    @Test
    fun nextSessionGetsANewId() {
        val ids = ArrayDeque(listOf("session-1", "session-2"))
        val state = VpnSessionState(idFactory = { ids.removeFirst() })

        assertEquals("session-1", state.start())
        state.stop()
        assertEquals("session-2", state.start())
        assertEquals("session-2", state.current)
    }

    @Test
    fun defaultIdIsShortHexAndUnique() {
        val first = VpnSessionState.defaultId()
        val second = VpnSessionState.defaultId()

        assertEquals(VpnSessionState.ID_LENGTH, first.length)
        assertTrue(first, first.matches(Regex("[0-9a-f]{${VpnSessionState.ID_LENGTH}}")))
        assertNotEquals(first, second)
    }
}

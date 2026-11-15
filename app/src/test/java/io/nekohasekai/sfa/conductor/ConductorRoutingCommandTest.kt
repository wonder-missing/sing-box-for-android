package io.nekohasekai.sfa.conductor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConductorRoutingCommandTest {
    @Test
    fun `no args reports the current state`() {
        val on = ConductorRoutingCommand.apply(emptyList(), true)
        val off = ConductorRoutingCommand.apply(emptyList(), false)

        assertTrue(on is ConductorRoutingCommand.Outcome.Shown)
        assertTrue((on as ConductorRoutingCommand.Outcome.Shown).value)
        assertTrue(off is ConductorRoutingCommand.Outcome.Shown)
        assertFalse((off as ConductorRoutingCommand.Outcome.Shown).value)
    }

    @Test
    fun `on and off update the setting`() {
        val on = ConductorRoutingCommand.apply(listOf("ON"), false)
        val off = ConductorRoutingCommand.apply(listOf("off"), true)

        assertTrue(on is ConductorRoutingCommand.Outcome.Updated)
        assertTrue((on as ConductorRoutingCommand.Outcome.Updated).value)
        assertTrue(off is ConductorRoutingCommand.Outcome.Updated)
        assertFalse((off as ConductorRoutingCommand.Outcome.Updated).value)
    }

    @Test
    fun `anything else is rejected`() {
        assertTrue(
            ConductorRoutingCommand.apply(listOf("maybe"), false) is
                ConductorRoutingCommand.Outcome.Invalid,
        )
    }

    @Test
    fun `suggestions appear only after the command and a space`() {
        assertTrue(ConductorRoutingCommand.suggestions("/routing").isEmpty())
        assertEquals(listOf("on", "off"), ConductorRoutingCommand.suggestions("/routing "))
        assertEquals(listOf("off"), ConductorRoutingCommand.suggestions("/routing of"))
        assertTrue(ConductorRoutingCommand.suggestions("/routing on extra").isEmpty())
        assertTrue(ConductorRoutingCommand.suggestions("/brief ").isEmpty())
    }
}

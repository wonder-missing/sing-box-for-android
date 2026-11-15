package io.nekohasekai.sfa.conductor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConductorBriefCommandTest {
    @Test
    fun `no args reports the current state`() {
        val on = ConductorBriefCommand.apply(emptyList(), true)
        val off = ConductorBriefCommand.apply(emptyList(), false)

        assertTrue(on is ConductorBriefCommand.Outcome.Shown)
        assertTrue((on as ConductorBriefCommand.Outcome.Shown).value)
        assertTrue(on.message.contains("on"))
        assertTrue(off is ConductorBriefCommand.Outcome.Shown)
        assertFalse((off as ConductorBriefCommand.Outcome.Shown).value)
    }

    @Test
    fun `on and off update the setting`() {
        val on = ConductorBriefCommand.apply(listOf("ON"), false)
        val off = ConductorBriefCommand.apply(listOf("off"), true)

        assertTrue(on is ConductorBriefCommand.Outcome.Updated)
        assertTrue((on as ConductorBriefCommand.Outcome.Updated).value)
        assertTrue(off is ConductorBriefCommand.Outcome.Updated)
        assertFalse((off as ConductorBriefCommand.Outcome.Updated).value)
    }

    @Test
    fun `anything else is rejected`() {
        assertTrue(ConductorBriefCommand.apply(listOf("short"), false) is ConductorBriefCommand.Outcome.Invalid)
        val both = ConductorBriefCommand.apply(listOf("on", "off"), false)
        assertTrue(both is ConductorBriefCommand.Outcome.Invalid)
    }

    @Test
    fun `suggestions appear only after the command and a space`() {
        assertTrue(ConductorBriefCommand.suggestions("/brief").isEmpty())
        assertEquals(listOf("on", "off"), ConductorBriefCommand.suggestions("/brief "))
        assertEquals(listOf("off"), ConductorBriefCommand.suggestions("/brief O"))
        assertEquals(listOf("on"), ConductorBriefCommand.suggestions("/brief o"))
        assertTrue(ConductorBriefCommand.suggestions("/brief on extra").isEmpty())
        assertTrue(ConductorBriefCommand.suggestions("/reasoning ").isEmpty())
    }
}

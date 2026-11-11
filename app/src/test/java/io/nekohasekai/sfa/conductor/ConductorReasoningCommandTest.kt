package io.nekohasekai.sfa.conductor

import io.nekohasekai.sfa.agent.ReasoningEffort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConductorReasoningCommandTest {
    @Test
    fun `no args reports the current value`() {
        val outcome = ConductorReasoningCommand.apply(emptyList(), ReasoningEffort.LOW)

        assertTrue(outcome is ConductorReasoningCommand.Outcome.Shown)
        outcome as ConductorReasoningCommand.Outcome.Shown
        assertEquals(ReasoningEffort.LOW, outcome.value)
        assertTrue(outcome.message.contains("low"))
    }

    @Test
    fun `known level updates and default omits the field`() {
        val high = ConductorReasoningCommand.apply(listOf("HIGH"), ReasoningEffort.DEFAULT)
        val default = ConductorReasoningCommand.apply(listOf("default"), ReasoningEffort.HIGH)

        assertTrue(high is ConductorReasoningCommand.Outcome.Updated)
        assertEquals(ReasoningEffort.HIGH, (high as ConductorReasoningCommand.Outcome.Updated).value)
        assertTrue(default is ConductorReasoningCommand.Outcome.Updated)
        assertTrue((default as ConductorReasoningCommand.Outcome.Updated).message.contains("omits"))
    }

    @Test
    fun `unknown level is rejected`() {
        val outcome = ConductorReasoningCommand.apply(listOf("max"), ReasoningEffort.DEFAULT)

        assertTrue(outcome is ConductorReasoningCommand.Outcome.Invalid)
    }

    @Test
    fun `suggestions appear only after the command and a space`() {
        assertTrue(ConductorReasoningCommand.suggestions("/reasoning").isEmpty())
        assertEquals(
            ReasoningEffort.options,
            ConductorReasoningCommand.suggestions("/reasoning "),
        )
        assertEquals(listOf("low"), ConductorReasoningCommand.suggestions("/reasoning L"))
        assertTrue(ConductorReasoningCommand.suggestions("/reasoning low extra").isEmpty())
        assertTrue(ConductorReasoningCommand.suggestions("/help ").isEmpty())
    }
}

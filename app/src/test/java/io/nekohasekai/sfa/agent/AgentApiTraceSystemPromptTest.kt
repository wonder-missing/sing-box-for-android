package io.nekohasekai.sfa.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentApiTraceSystemPromptTest {
    @Test
    fun recordsAndReturnsTheLatestSystemPrompt() {
        AgentApiTrace.clear()

        AgentApiTrace.recordSystemPrompt("You are Conductor.")
        assertEquals("You are Conductor.", AgentApiTrace.lastSystemPrompt())

        AgentApiTrace.recordSystemPrompt("You are Conductor. Updated.")
        assertEquals("You are Conductor. Updated.", AgentApiTrace.lastSystemPrompt())

        AgentApiTrace.clear()
    }

    @Test
    fun clearForgetsTheSystemPrompt() {
        AgentApiTrace.recordSystemPrompt("You are Conductor.")

        AgentApiTrace.clear()

        assertNull(AgentApiTrace.lastSystemPrompt())
    }
}

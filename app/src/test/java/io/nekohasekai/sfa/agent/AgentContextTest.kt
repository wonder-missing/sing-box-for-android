package io.nekohasekai.sfa.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentContextTest {
    @Test
    fun trimHysteresisKeepsTheBoundaryStableBelowTheHighWaterMark() {
        val history = (0 until 48).map { AgentMessage.User("m$it") }
        assertEquals(48, AgentContext.trim(history, 48, 36).size)

        val trimmed = AgentContext.trim(history + AgentMessage.User("m48"), 48, 36)
        assertEquals(36, trimmed.size)

        val next = AgentContext.trim(trimmed + AgentMessage.User("m49"), 48, 36)
        assertEquals(37, next.size)
        assertEquals(trimmed, next.dropLast(1))
    }

    @Test
    fun trimNeverStartsInsideAToolBatch() {
        val history = mutableListOf<AgentMessage>()
        repeat(15) { history.add(AgentMessage.User("u$it")) }
        history.add(AgentMessage.Assistant(null, listOf(ToolCall("c1", "x", "{}"))))
        history.add(AgentMessage.Tool("c1", "x", "{}"))
        repeat(35) { history.add(AgentMessage.User("tail$it")) }

        val trimmed = AgentContext.trim(history, 48, 36)

        assertTrue(trimmed.first() is AgentMessage.User)
        assertTrue(trimmed.size > 36)
    }
}

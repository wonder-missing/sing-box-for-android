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
    fun compactOldObservationsKeepsTheLastTurnAndShrinksOldOutput() {
        val big = "x".repeat(5_000)
        val history =
            listOf(
                AgentMessage.User("old"),
                AgentMessage.Assistant(null, listOf(ToolCall("c1", "list_nodes", "{}"))),
                AgentMessage.Tool("c1", "list_nodes", """{"ok":true,"tool":"list_nodes","output":{"data":"$big"}}"""),
                AgentMessage.Assistant("old answer"),
                AgentMessage.User("new"),
                AgentMessage.Assistant(null, listOf(ToolCall("c2", "list_nodes", "{}"))),
                AgentMessage.Tool("c2", "list_nodes", """{"ok":true,"tool":"list_nodes","output":{"data":"$big"}}"""),
            )

        val compacted = AgentContext.compactOldObservations(history, 4_000)

        assertEquals(history.size, compacted.size)
        val oldTool = compacted[2] as AgentMessage.Tool
        assertTrue(oldTool.content.length < 1_000)
        assertTrue(oldTool.content.contains("\"compacted\":true"))
        assertEquals(history[6], compacted[6])
        assertTrue(validateTranscriptPairing(compacted))
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

    @Test
    fun trimStartsAtATurnBoundaryWhenTheCutFallsOnAFinalAssistant() {
        val history =
            listOf(
                AgentMessage.User("u1"),
                AgentMessage.Assistant(null, listOf(ToolCall("c1", "x", "{}"))),
                AgentMessage.Tool("c1", "x", "{}"),
                AgentMessage.Assistant("done"),
                AgentMessage.User("u2"),
                AgentMessage.Assistant("done2"),
            )

        val trimmed = AgentContext.trim(history, maxMessages = 5, targetMessages = 3)

        assertTrue(trimmed.first() is AgentMessage.User)
        assertTrue(trimmed.size >= 3)
        assertTrue(validateTranscriptPairing(trimmed))
    }

    @Test
    fun estimateTokensCountsCjkAsOneAndAsciiAsQuarter() {
        assertEquals(4, AgentContext.estimateTokens("你好世界"))
        assertEquals(2, AgentContext.estimateTokens("abcdefgh"))
    }

    @Test
    fun fitToBudgetDropsOldTurnsAndKeepsTheCurrentUser() {
        val big = "x".repeat(20_000)
        val history =
            listOf(
                AgentMessage.User("old"),
                AgentMessage.Assistant(null, listOf(ToolCall("c1", "x", "{}"))),
                AgentMessage.Tool("c1", "x", big),
                AgentMessage.Assistant("old answer"),
                AgentMessage.User("new"),
            )

        val fitted = AgentContext.fitToBudget(history, maxTokens = 500)

        assertEquals(AgentMessage.User("new"), fitted.last())
        assertTrue(fitted.first() is AgentMessage.User)
        assertTrue(fitted.size < history.size)
        assertTrue(validateTranscriptPairing(fitted))
    }
}

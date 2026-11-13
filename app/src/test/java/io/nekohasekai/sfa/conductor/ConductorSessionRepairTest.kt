package io.nekohasekai.sfa.conductor

import io.nekohasekai.sfa.agent.AgentMessage
import io.nekohasekai.sfa.agent.validateTranscriptPairing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConductorSessionRepairTest {
    @Test
    fun restoresFailedUserMessageThatWasOnlyInItems() {
        val transcript =
            listOf(
                AgentMessage.User("first"),
                AgentMessage.Assistant("answer", emptyList()),
                AgentMessage.User("[conductor:event turn_failed reason=model_unavailable]\nthird"),
            )
        val items =
            listOf(
                ConductorItem.User("first"),
                ConductorItem.Assistant("answer"),
                ConductorItem.User("second"),
                ConductorItem.Notice("conductor", "The provider request failed; retrying."),
                ConductorItem.Error("agent", "offline"),
                ConductorItem.User("third"),
                ConductorItem.Assistant("done"),
            )

        val repaired = reconcileAgentUserMessages(transcript, items)

        assertEquals(
            listOf("first", "second", "[conductor:event turn_failed reason=model_unavailable]\nthird"),
            repaired.filterIsInstance<AgentMessage.User>().map { it.content },
        )
        assertTrue(validateTranscriptPairing(repaired))
    }

    @Test
    fun restoresAnUnansweredUserMessageBetweenTwoUsers() {
        val transcript =
            listOf(
                AgentMessage.User("first"),
                AgentMessage.Assistant("answer", emptyList()),
                AgentMessage.User("what happened"),
                AgentMessage.Assistant("checking", emptyList()),
            )
        val items =
            listOf(
                ConductorItem.User("first"),
                ConductorItem.Assistant("answer"),
                ConductorItem.User("export the trace"),
                ConductorItem.User("what happened"),
                ConductorItem.Assistant("checking"),
            )

        val repaired = reconcileAgentUserMessages(transcript, items)

        assertEquals(
            listOf("first", "export the trace", "what happened"),
            repaired.filterIsInstance<AgentMessage.User>().map { it.content },
        )
        assertTrue(validateTranscriptPairing(repaired))
    }

    @Test
    fun restoresALastUserMessageWithNoAgentOutput() {
        val transcript =
            listOf(
                AgentMessage.User("first"),
                AgentMessage.Assistant("answer", emptyList()),
            )
        val items =
            listOf(
                ConductorItem.User("first"),
                ConductorItem.Assistant("answer"),
                ConductorItem.User("second"),
            )

        val repaired = reconcileAgentUserMessages(transcript, items)

        assertEquals(
            listOf("first", "second"),
            repaired.filterIsInstance<AgentMessage.User>().map { it.content },
        )
    }

    @Test
    fun ignoresCommandUserItems() {
        val transcript =
            listOf(
                AgentMessage.User("hello"),
                AgentMessage.Assistant("hi", emptyList()),
            )
        val items =
            listOf(
                ConductorItem.User("/mcp"),
                ConductorItem.Notice("mcp", "MCP servers"),
                ConductorItem.User("hello"),
                ConductorItem.Assistant("hi"),
            )

        val repaired = reconcileAgentUserMessages(transcript, items)

        assertEquals(listOf("hello"), repaired.filterIsInstance<AgentMessage.User>().map { it.content })
    }

    @Test
    fun doesNotReinsertUsersTrimmedFromTheTranscript() {
        val transcript =
            listOf(
                AgentMessage.User("later"),
                AgentMessage.Assistant("answer", emptyList()),
            )
        val items =
            listOf(
                ConductorItem.User("old"),
                ConductorItem.Assistant("old answer"),
                ConductorItem.User("later"),
                ConductorItem.Assistant("answer"),
            )

        val repaired = reconcileAgentUserMessages(transcript, items)

        assertEquals(transcript, repaired)
    }

    @Test
    fun matchesUserMessagesThatCarryAnEvent() {
        val transcript =
            listOf(
                AgentMessage.User("first"),
                AgentMessage.Assistant("a", emptyList()),
                AgentMessage.User("[conductor:event turn_failed reason=model_unavailable]\nsecond"),
                AgentMessage.Assistant("b", emptyList()),
            )
        val items =
            listOf(
                ConductorItem.User("first"),
                ConductorItem.Assistant("a"),
                ConductorItem.User("second"),
                ConductorItem.Assistant("b"),
            )

        val repaired = reconcileAgentUserMessages(transcript, items)

        assertEquals(transcript, repaired)
    }

    @Test
    fun scrubsPersonalDataInHistoricalToolMessagesOnly() {
        val personal = "wonder@example.com 100.106.21.44 fd7a:115c:a1e0::9b33:152d"
        val transcript =
            listOf(
                AgentMessage.User(personal),
                AgentMessage.Tool("call-1", "tailscale_status", """{"result":"$personal"}"""),
                AgentMessage.Assistant("ok", emptyList()),
            )

        val repaired = repairRestoredTranscript(transcript, emptyList())

        val user = repaired[0] as AgentMessage.User
        val tool = repaired[1] as AgentMessage.Tool
        assertEquals(personal, user.content)
        assertFalse(tool.content.contains("wonder@example.com"))
        assertFalse(tool.content.contains("100.106.21.44"))
        assertFalse(tool.content.contains("fd7a:115c:a1e0::9b33:152d"))
        assertTrue(tool.content.contains("[redacted-email]"))
        assertTrue(tool.content.contains("[redacted-ip]"))
    }
}

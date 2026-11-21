package io.nekohasekai.sfa.conductor

import io.nekohasekai.sfa.agent.AgentImageRef
import io.nekohasekai.sfa.agent.AgentMessage
import io.nekohasekai.sfa.agent.formatUserMessageWithAttachments
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
                AgentMessage.User("[runtime:event turn_failed reason=model_unavailable]\nthird"),
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
            listOf("first", "second", "[runtime:event turn_failed reason=model_unavailable]\nthird"),
            repaired.filterIsInstance<AgentMessage.User>().map { it.content },
        )
        assertTrue(validateTranscriptPairing(repaired))
    }

    @Test
    fun restoresAnImageUserMessageUsingItsRuntimeAttachmentMetadata() {
        val image = AgentImageRef("img1", "shot.jpg", "image/jpeg", 10, 20, 3)
        val transcript =
            listOf(
                AgentMessage.User("first"),
                AgentMessage.Assistant("answer", emptyList()),
            )
        val items =
            listOf(
                ConductorItem.User("first"),
                ConductorItem.Assistant("answer"),
                ConductorItem.User("look", images = listOf(image)),
            )

        val repaired = reconcileAgentUserMessages(transcript, items)

        val users = repaired.filterIsInstance<AgentMessage.User>()
        assertEquals(2, users.size)
        assertEquals(
            formatUserMessageWithAttachments("look", null, listOf(image)),
            users[1].content,
        )
        assertEquals(listOf(image), users[1].images)
        assertTrue(validateTranscriptPairing(repaired))
    }

    @Test
    fun historicalRuntimeSummaryIsScrubbedBeforeReuse() {
        val summary =
            AgentMessage.User(
                "[runtime:summary]\nuser@example.com 100.64.1.2 token=secret\n[/runtime:summary]",
            )

        val scrubbed = scrubHistoricalToolOutputs(listOf(summary)).single() as AgentMessage.User

        assertFalse(scrubbed.content.contains("user@example.com"))
        assertFalse(scrubbed.content.contains("100.64.1.2"))
        assertFalse(scrubbed.content.contains("secret"))
        assertTrue(scrubbed.content.contains("token=***"))
    }

    @Test
    fun compactedSummaryDoesNotResurrectOlderRepeatedUsers() {
        val summary =
            AgentMessage.User("[runtime:summary]\nold conversation\n[/runtime:summary]")
        val transcript =
            listOf(
                summary,
                AgentMessage.User("继续"),
                AgentMessage.Assistant("current answer", emptyList()),
            )
        val items =
            listOf(
                ConductorItem.User("继续"),
                ConductorItem.Assistant("old answer"),
                ConductorItem.User("middle request"),
                ConductorItem.Assistant("middle answer"),
                ConductorItem.User("继续"),
                ConductorItem.Assistant("current answer"),
            )

        val repaired = reconcileAgentUserMessages(transcript, items)

        assertEquals(2, repaired.filterIsInstance<AgentMessage.User>().size)
        assertTrue(repaired.none { it is AgentMessage.User && it.content == "middle request" })
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
                AgentMessage.User("[runtime:event turn_failed reason=model_unavailable]\nsecond"),
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

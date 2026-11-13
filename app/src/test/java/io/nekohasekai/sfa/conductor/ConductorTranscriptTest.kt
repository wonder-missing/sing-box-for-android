package io.nekohasekai.sfa.conductor

import io.nekohasekai.sfa.agent.ConfirmationRequest
import io.nekohasekai.sfa.agent.ToolCallView
import io.nekohasekai.sfa.agent.ToolResultView
import io.nekohasekai.sfa.agent.ToolStatus
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConductorTranscriptTest {
    @Test
    fun rendersEveryItemKind() {
        val items =
            listOf(
                ConductorItem.User("hello"),
                ConductorItem.Assistant("hi there"),
                ConductorItem.Reasoning("thinking"),
                ConductorItem.Tool(
                    call = ToolCallView("call-1", "file_read", """{"path":"a.md"}"""),
                    status = ToolStatus.SUCCEEDED,
                    result =
                        ToolResultView(
                            callId = "call-1",
                            name = "file_read",
                            ok = true,
                            output = buildJsonObject { put("text", "contents") },
                            errorCode = null,
                            message = null,
                            truncated = true,
                        ),
                ),
                ConductorItem.Notice("Notice title", "Notice body"),
                ConductorItem.Confirmation(
                    ConfirmationRequest(1, "docker_run", """{"command":["true"]}""", "Run a container?"),
                ),
                ConductorItem.Error("Request failed", "The provider rejected the request."),
            )

        val markdown = conductorConversationMarkdown(items, "1.2.3", "1.2.0")

        assertTrue(markdown.startsWith("# Conductor conversation"))
        assertTrue(markdown.contains("- app version: 1.2.3"))
        assertTrue(markdown.contains("- session started on: 1.2.0"))
        assertTrue(markdown.contains("## User\n\nhello"))
        assertTrue(markdown.contains("## Conductor\n\nhi there"))
        assertTrue(markdown.contains("## Thinking\n\nthinking"))
        assertTrue(markdown.contains("## Tool: file_read"))
        assertTrue(markdown.contains("- status: succeeded"))
        assertTrue(markdown.contains("\"path\": \"a.md\""))
        assertTrue(markdown.contains("\"text\": \"contents\""))
        assertTrue(markdown.contains("(truncated)"))
        assertTrue(markdown.contains("## Notice title\n\nNotice body"))
        assertTrue(markdown.contains("## Pending confirmation: docker_run"))
        assertTrue(markdown.contains("Run a container?"))
        assertTrue(markdown.contains("## Error: Request failed"))
        assertTrue(markdown.contains("The provider rejected the request."))
    }

    @Test
    fun omitsTheSessionVersionWhenItMatches() {
        val markdown = conductorConversationMarkdown(listOf(ConductorItem.User("hello")), "1.2.3", "1.2.3")

        assertFalse(markdown.contains("session started on"))
    }

    @Test
    fun rendersDegradedAndFailedToolResults() {
        val items =
            listOf(
                ConductorItem.Tool(
                    call = ToolCallView("call-2", "docker_run", "{}"),
                    status = ToolStatus.FAILED,
                    result =
                        ToolResultView(
                            callId = "call-2",
                            name = "docker_run",
                            ok = false,
                            output = null,
                            errorCode = "DOCKER_UNAVAILABLE",
                            message = "Docker is not running.",
                            truncated = false,
                            degraded = true,
                        ),
                ),
            )

        val markdown = conductorConversationMarkdown(items, "1.0")

        assertTrue(markdown.contains("- status: failed"))
        assertTrue(markdown.contains("- degraded: true"))
        assertTrue(markdown.contains("~~~text\nDocker is not running.\n~~~"))
        assertFalse(markdown.contains("errorCode: DOCKER_UNAVAILABLE"))
    }

    @Test
    fun emptyConversationsKeepTheHeader() {
        val markdown = conductorConversationMarkdown(emptyList(), "1.0")

        assertTrue(markdown.startsWith("# Conductor conversation"))
        assertTrue(markdown.contains("- app version: 1.0"))
        assertFalse(markdown.endsWith("\n"))
    }
}

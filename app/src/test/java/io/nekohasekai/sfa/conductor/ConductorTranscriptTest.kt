package io.nekohasekai.sfa.conductor

import io.nekohasekai.sfa.agent.ConfirmationRequest
import io.nekohasekai.sfa.agent.PlanClearedBy
import io.nekohasekai.sfa.agent.PlanEvent
import io.nekohasekai.sfa.agent.PlanEventAction
import io.nekohasekai.sfa.agent.PlanItem
import io.nekohasekai.sfa.agent.PlanItemStatus
import io.nekohasekai.sfa.agent.PlanState
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
                ConductorItem.Question(
                    id = "q1",
                    question = "Continue?",
                    options = listOf("Yes", "No"),
                    selected = listOf("Yes"),
                    answered = true,
                ),
            )

        val markdown =
            conductorConversationMarkdown(items, "1.2.3", "1.2.0", includeReasoning = true)

        assertTrue(markdown.startsWith("# Conductor transcript"))
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
        assertTrue(markdown.contains("## Question: Continue?"))
        assertTrue(markdown.contains("- options: Yes | No"))
        assertTrue(markdown.contains("- answered: Yes"))
    }

    @Test
    fun omitsReasoningByDefault() {
        val items =
            listOf(
                ConductorItem.User("hello"),
                ConductorItem.Reasoning("thinking"),
                ConductorItem.Assistant("hi"),
            )

        val markdown = conductorConversationMarkdown(items, "1.0")

        assertFalse(markdown.contains("## Thinking"))
        assertFalse(markdown.contains("thinking"))
    }

    @Test
    fun sharedTranscriptMasksPersonalData() {
        val items =
            listOf(
                ConductorItem.Tool(
                    call = ToolCallView("call-3", "tailscale_status", "{}"),
                    status = ToolStatus.SUCCEEDED,
                    result =
                        ToolResultView(
                            callId = "call-3",
                            name = "tailscale_status",
                            ok = true,
                            output =
                                buildJsonObject {
                                    put("text", "self wonder@example.com 100.106.21.44")
                                },
                            errorCode = null,
                            message = null,
                            truncated = false,
                        ),
                ),
            )

        val markdown = conductorConversationMarkdown(items, "1.0")

        assertFalse(markdown.contains("wonder@example.com"))
        assertFalse(markdown.contains("100.106.21.44"))
        assertTrue(markdown.contains("[redacted-email]"))
        assertTrue(markdown.contains("[redacted-ip]"))
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
        assertTrue(markdown.contains("```text\nDocker is not running.\n```"))
        assertFalse(markdown.contains("errorCode: DOCKER_UNAVAILABLE"))
    }

    @Test
    fun widensBacktickFenceWhenContentContainsTripleBackticks() {
        val items =
            listOf(
                ConductorItem.Tool(
                    call = ToolCallView("call-3", "docker_run", "{}"),
                    status = ToolStatus.FAILED,
                    result =
                        ToolResultView(
                            callId = "call-3",
                            name = "docker_run",
                            ok = false,
                            output = null,
                            errorCode = "DOCKER_UNAVAILABLE",
                            message = "```\ninner\n```",
                            truncated = false,
                            degraded = false,
                        ),
                ),
            )

        val markdown = conductorConversationMarkdown(items, "1.0")

        assertTrue(markdown.contains("````text\n```\ninner\n```\n````"))
        assertFalse(markdown.contains("~~~"))
    }

    @Test
    fun planIsExportedAndSensitiveTextIsRedacted() {
        val plan =
            PlanState(
                version = 2,
                goal = "token=secret",
                items = listOf(PlanItem("fetch https://example.com/private?token=abc")),
                note = "api_key=top-secret",
            )

        val markdown = conductorConversationMarkdown(emptyList(), "1.0", plan = plan)

        assertTrue(markdown.contains("## Plan"))
        assertTrue(markdown.contains("[pending]"))
        assertTrue(markdown.contains("token=***"))
        assertTrue(markdown.contains("api_key=***"))
        assertFalse(markdown.contains("secret"))
        assertFalse(markdown.contains("token=abc"))
    }

    @Test
    fun planHistoryShowsModelAndUserChanges() {
        val created =
            PlanState(
                version = 1,
                goal = "task",
                items =
                    listOf(
                        PlanItem("one", PlanItemStatus.DONE),
                        PlanItem("two", PlanItemStatus.PENDING),
                    ),
                updatedBy = PlanClearedBy.MODEL,
            )
        val edited =
            PlanState(
                version = 2,
                goal = "task",
                items =
                    listOf(
                        PlanItem("one", PlanItemStatus.DONE),
                        PlanItem("two", PlanItemStatus.PENDING),
                    ),
                updatedBy = PlanClearedBy.USER,
            )
        val events =
            listOf(
                PlanEvent(1, 1_700_000_000_000L, PlanClearedBy.MODEL, PlanEventAction.CREATED, created),
                PlanEvent(2, 1_700_000_001_000L, PlanClearedBy.USER, PlanEventAction.UPDATED, edited),
            )

        val markdown = conductorConversationMarkdown(emptyList(), "1.0", plan = edited, planEvents = events)

        assertTrue(markdown.contains("## Plan history"))
        assertTrue(markdown.contains("### v1"))
        assertTrue(markdown.contains("model · created"))
        assertTrue(markdown.contains("### v2"))
        assertTrue(markdown.contains("user · updated"))
        assertTrue(markdown.contains("[pending] two"))
        assertTrue(markdown.contains("## Plan"))
    }

    @Test
    fun emptyConversationsKeepTheHeader() {
        val markdown = conductorConversationMarkdown(emptyList(), "1.0")

        assertTrue(markdown.startsWith("# Conductor transcript"))
        assertTrue(markdown.contains("- app version: 1.0"))
        assertFalse(markdown.endsWith("\n"))
    }
}

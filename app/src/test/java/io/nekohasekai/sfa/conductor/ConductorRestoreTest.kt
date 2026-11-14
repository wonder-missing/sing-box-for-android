package io.nekohasekai.sfa.conductor

import io.nekohasekai.sfa.agent.ConfirmationRequest
import io.nekohasekai.sfa.agent.ToolCallView
import io.nekohasekai.sfa.agent.ToolResultView
import io.nekohasekai.sfa.agent.ToolStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConductorRestoreTest {
    private val runningTool =
        ConductorItem.Tool(
            call = ToolCallView("call-1", "docker_run", """{"image":"alpine"}"""),
            status = ToolStatus.RUNNING,
            result = null,
        )

    private val completedTool =
        ConductorItem.Tool(
            call = ToolCallView("call-2", "file_read", """{"path":"a.md"}"""),
            status = ToolStatus.SUCCEEDED,
            result =
                ToolResultView(
                    callId = "call-2",
                    name = "file_read",
                    ok = true,
                    output = null,
                    errorCode = null,
                    message = "done",
                    truncated = false,
                ),
        )

    @Test
    fun runningToolsBecomeCancelledAndMarkInterruption() {
        val restored =
            restoreConductorItems(
                listOf(
                    ConductorItem.User("hello"),
                    ConductorItem.Assistant("partial", streaming = true),
                    ConductorItem.Reasoning("thinking", streaming = true),
                    runningTool,
                    ConductorItem.Confirmation(
                        ConfirmationRequest(1, "docker_run", "{}", "Run it?"),
                    ),
                    completedTool,
                ),
            )

        assertTrue(restored.interrupted)
        val items = restored.items
        assertEquals(5, items.size)
        assertTrue(items.none { it is ConductorItem.Confirmation })
        val assistant = items.filterIsInstance<ConductorItem.Assistant>().single()
        assertFalse(assistant.streaming)
        val reasoning = items.filterIsInstance<ConductorItem.Reasoning>().single()
        assertFalse(reasoning.streaming)
        val cancelled = items.filterIsInstance<ConductorItem.Tool>().first()
        assertEquals(ToolStatus.CANCELLED, cancelled.status)
        assertEquals("interrupted", cancelled.result?.errorCode)
        assertTrue(cancelled.result?.message.orEmpty().contains("Interrupted"))
        assertEquals(completedTool, items.last())
    }

    @Test
    fun completedSessionsDoNotMarkInterruption() {
        val restored =
            restoreConductorItems(
                listOf(
                    ConductorItem.User("hello"),
                    completedTool,
                    ConductorItem.Notice("notice", "body"),
                ),
            )

        assertFalse(restored.interrupted)
        assertEquals(3, restored.items.size)
        assertEquals(completedTool, restored.items[1])
    }

    @Test
    fun interruptionNoticeExplainsWhatHappened() {
        val notice = interruptedConductorNotice()

        assertEquals("conductor", notice.title)
        assertTrue(notice.body.contains("app process stopped"))
        assertTrue(notice.body.contains("send the message again"))
    }
}

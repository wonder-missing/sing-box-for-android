package io.nekohasekai.sfa.agent

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AskUserToolTest {
    private val requests = mutableListOf<AskUserRequest>()

    private fun tool(result: AskUserResult): AskUserTool =
        AskUserTool(
            object : AskUserPort {
                override suspend fun ask(
                    request: AskUserRequest,
                    cancellation: ToolCancellation?,
                ): AskUserResult {
                    requests += request
                    return result
                }
            },
        )

    private fun ask(
        tool: AskUserTool,
        argsJson: String,
    ): AgentInvocationResult =
        runBlocking {
            AgentToolRegistry(listOf(tool)).invokeAgent(ToolCall("call-1", "ask_user", argsJson), humanApproved = false)
        }

    @Test
    fun returnsTheSelectedLabels() {
        val tool = tool(AskUserResult(selected = listOf("Yes")))

        val result =
            ask(
                tool,
                """{"question":"Continue?","options":["Yes","No"],"header":"Confirm"}""",
            )

        assertTrue(result.errorMessage ?: "", result.ok)
        assertEquals("Continue?", requests.single().question)
        assertEquals(listOf("Yes", "No"), requests.single().options)
        assertFalse(requests.single().multiple)
        assertEquals("Confirm", requests.single().header)
        val output = result.output!!
        assertEquals("true", (output["answered"] as JsonPrimitive).content)
        assertEquals("false", (output["cancelled"] as JsonPrimitive).content)
        val selected = output["selected"] as JsonArray
        assertEquals("Yes", (selected.single() as JsonPrimitive).content)
    }

    @Test
    fun multipleChoiceIsForwarded() {
        val tool = tool(AskUserResult(selected = listOf("Conductor", "Trace")))

        val result =
            ask(
                tool,
                """{"question":"Which scopes?","options":["Conductor","Trace","VFS"],"multiple":true}""",
            )

        assertTrue(result.errorMessage ?: "", result.ok)
        assertTrue(requests.single().multiple)
        assertEquals(2, (result.output!!["selected"] as JsonArray).size)
    }

    @Test
    fun cancelledReportKeepsTheMessage() {
        val tool = tool(AskUserResult(cancelled = true, message = "The user dismissed the question."))

        val result = ask(tool, """{"question":"Continue?","options":["Yes","No"]}""")

        assertTrue(result.errorMessage ?: "", result.ok)
        val output = result.output!!
        assertEquals("false", (output["answered"] as JsonPrimitive).content)
        assertEquals("true", (output["cancelled"] as JsonPrimitive).content)
        assertTrue(output["message"].toString().contains("dismissed"))
    }

    @Test
    fun optionCountAndDuplicatesAreValidatedBeforeTheCardShows() {
        val tool = tool(AskUserResult(selected = listOf("Yes")))

        val tooFew = ask(tool, """{"question":"Continue?","options":["Yes"]}""")
        val tooMany =
            ask(
                tool,
                """{"question":"Pick","options":["a","b","c","d","e","f","g"]}""",
            )
        val duplicate = ask(tool, """{"question":"Pick","options":["Yes","yes"]}""")

        assertEquals("invalid_arguments", tooFew.errorCode)
        assertEquals("invalid_arguments", tooMany.errorCode)
        assertEquals("invalid_arguments", duplicate.errorCode)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun theToolRunsWithoutASeparateConfirmationCard() {
        val tool = tool(AskUserResult(selected = listOf("No")))

        val result = ask(tool, """{"question":"Continue?","options":["Yes","No"]}""")

        assertTrue(result.errorMessage ?: "", result.ok)
        assertFalse(result.confirmation != null)
        assertEquals(AgentPermission.USER_INTERACTION, tool.definition.permission)
        assertNotNull(tool.definition.timeoutMs)
    }
}

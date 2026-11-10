package io.nekohasekai.sfa.conductor

import io.nekohasekai.sfa.agent.AgentPermission
import io.nekohasekai.sfa.agent.AgentToolDefinition
import io.nekohasekai.sfa.agent.ConfirmationPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConductorCommandsTest {
    @Test
    fun `parses command with arguments`() {
        val input = ConductorCommands.parse("/help nodes")!!

        assertEquals("help", input.command?.name)
        assertEquals(listOf("nodes"), input.args)
    }

    @Test
    fun `parses newline separated arguments`() {
        val input = ConductorCommands.parse("/help\nnodes")!!

        assertEquals("help", input.command?.name)
        assertEquals(listOf("nodes"), input.args)
    }

    @Test
    fun `parses no-arg command`() {
        val input = ConductorCommands.parse("/tailscale")!!

        assertEquals("tailscale", input.command?.name)
        assertTrue(input.args.isEmpty())
    }

    @Test
    fun `parses probe arguments`() {
        val input = ConductorCommands.parse("/probe node-a https://example.com")!!

        assertEquals("probe", input.command?.name)
        assertEquals(listOf("node-a", "https://example.com"), input.args)
    }

    @Test
    fun `unknown slash command has no command`() {
        assertNull(ConductorCommands.parse("/nope")?.command)
    }

    @Test
    fun `suggestions filter by prefix`() {
        assertEquals(listOf("tailscale"), ConductorCommands.suggestions("/ta").map { it.name })
        assertEquals(ConductorCommands.all.size, ConductorCommands.suggestions("/").size)
        assertTrue(ConductorCommands.suggestions("tailscale").isEmpty())
    }

    @Test
    fun `suggestions close while typing arguments`() {
        assertTrue(ConductorCommands.suggestions("/help nodes").isEmpty())
    }

    @Test
    fun `help lists commands and details`() {
        assertTrue(ConductorCommands.helpText().contains("/tailscale"))
        assertTrue(ConductorCommands.helpText(ConductorCommands.find("nodes")).contains("/nodes"))
    }

    @Test
    fun `capabilities include tool metadata`() {
        val tool =
            AgentToolDefinition(
                name = "list_nodes",
                description = "List nodes",
                permission = AgentPermission.READ_NETWORK_STATE,
                parameters = emptyList(),
                confirmationPolicy = ConfirmationPolicy.NEVER,
                category = "network",
            )

        val text = ConductorCommands.capabilitiesText(listOf(tool))

        assertTrue(text.contains("list_nodes"))
        assertTrue(text.contains("read_network_state"))
        assertTrue(text.contains("confirmation: NEVER"))
    }
}

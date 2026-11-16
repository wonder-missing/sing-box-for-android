package io.nekohasekai.sfa.conductor

import io.nekohasekai.sfa.agent.AgentPermission
import io.nekohasekai.sfa.agent.AgentToolDefinition
import io.nekohasekai.sfa.agent.ConfirmationPolicy
import io.nekohasekai.sfa.agent.mcp.McpListedTool
import io.nekohasekai.sfa.agent.mcp.McpPhase
import io.nekohasekai.sfa.agent.mcp.McpServerView
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
    fun `parses reasoning level`() {
        val input = ConductorCommands.parse("/reasoning high")!!

        assertEquals("reasoning", input.command?.name)
        assertEquals(listOf("high"), input.args)
        assertTrue(input.command?.takesArgs == true)
    }

    @Test
    fun `parses probe arguments`() {
        val input = ConductorCommands.parse("/probe node-a https://example.com")!!

        assertEquals("probe", input.command?.name)
        assertEquals(listOf("node-a", "https://example.com"), input.args)
    }

    @Test
    fun `tool command preserves raw json arguments`() {
        val input = ConductorCommands.parse("/tool cpk_orb_status {\"note\": \"hello world\"}")!!

        assertEquals("tool", input.command?.name)
        assertEquals("cpk_orb_status {\"note\": \"hello world\"}", input.tail)
    }

    @Test
    fun `tool list includes permission and confirmation`() {
        val tool =
            AgentToolDefinition(
                name = "list_nodes",
                description = "List nodes",
                permission = AgentPermission.READ_NETWORK_STATE,
                parameters = emptyList(),
                confirmationPolicy = ConfirmationPolicy.NEVER,
                category = "network",
            )

        val text = ConductorCommands.toolListText(listOf(tool))

        assertTrue(text.contains("list_nodes"))
        assertTrue(text.contains("read_network_state"))
        assertTrue(text.contains("confirmation: NEVER"))
    }

    @Test
    fun `tool schema includes parameters`() {
        val tool =
            AgentToolDefinition(
                name = "probe_via_node",
                description = "Probe a node",
                permission = AgentPermission.READ_NETWORK_STATE,
                parameters =
                    listOf(
                        io.nekohasekai.sfa.agent.AgentParameterSpec(
                            name = "url",
                            type = io.nekohasekai.sfa.agent.AgentParameterType.STRING,
                            description = "URL to probe",
                            required = true,
                        ),
                    ),
                confirmationPolicy = ConfirmationPolicy.ALWAYS,
                category = "network",
            )

        val text = ConductorCommands.toolSchemaText(tool)

        assertTrue(text.contains("probe_via_node"))
        assertTrue(text.contains("url"))
        assertTrue(text.contains("confirmation: ALWAYS"))
    }

    @Test
    fun `unknown slash command has no command`() {
        assertNull(ConductorCommands.parse("/nope")?.command)
    }

    @Test
    fun `suggestions put prefix matches first`() {
        val matches = ConductorCommands.suggestions("/ta").map { it.name }
        assertEquals("tailscale", matches.first())
        assertTrue(matches.contains("telemetry-upload"))
        assertEquals(ConductorCommands.all.size, ConductorCommands.suggestions("/").size)
        assertTrue(ConductorCommands.suggestions("tailscale").isEmpty())
    }

    @Test
    fun `suggestions close while typing arguments`() {
        assertTrue(ConductorCommands.suggestions("/help nodes").isEmpty())
    }

    @Test
    fun `suggestions fuzzy match command names`() {
        assertEquals(
            listOf("capabilities"),
            ConductorCommands.suggestions("/capa").map { it.name },
        )
        assertEquals(listOf("brief"), ConductorCommands.suggestions("/brf").map { it.name })
        assertTrue(ConductorCommands.suggestions("/tls").map { it.name }.contains("tailscale"))
        assertTrue(ConductorCommands.suggestions("/rting").map { it.name }.contains("routing"))
    }

    @Test
    fun `value suggestions fuzzy match`() {
        assertEquals(listOf("off"), ConductorBriefCommand.suggestions("/brief of"))
        assertEquals(listOf("off"), ConductorRoutingCommand.suggestions("/routing of"))
        assertEquals(listOf("medium"), ConductorReasoningCommand.suggestions("/reasoning med"))
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

    @Test
    fun `mcp command parses an optional name`() {
        val input = ConductorCommands.parse("/mcp knocks")!!

        assertEquals("mcp", input.command?.name)
        assertEquals(listOf("knocks"), input.args)
    }

    @Test
    fun `mcp list shows remote names and required arguments`() {
        val server =
            McpServerView(
                id = "clawdy_portal_knocks",
                name = "Clawdy Portal Knocks",
                namespace = "portal",
                url = "https://portal.example/mcp",
                hasToken = true,
                enabled = true,
                phase = McpPhase.READY,
                toolCount = 2,
                fetched = true,
                error = null,
            )
        val tools =
            listOf(
                McpListedTool(
                    serverId = server.id,
                    serverName = server.name,
                    remoteName = "orb_status",
                    localName = "mcp_clawdy_portal_knocks_orb_status",
                    summary = "Read whether Orb is running.",
                    asksFirst = true,
                    required = emptyList(),
                ),
                McpListedTool(
                    serverId = server.id,
                    serverName = server.name,
                    remoteName = "note_create",
                    localName = "mcp_clawdy_portal_knocks_note_create",
                    summary = "Create a markdown note.",
                    asksFirst = true,
                    required = listOf("name", "content"),
                ),
            )

        val text = ConductorCommands.mcpText(listOf(server), tools)

        assertTrue(text.contains("Clawdy Portal Knocks — 2 tools"))
        assertTrue(text.contains("orb_status — asks first"))
        assertTrue(text.contains("required: name, content"))
        assertTrue(!text.contains("mcp_clawdy_portal_knocks"))
        val filtered = ConductorCommands.mcpText(listOf(server), tools, "note")
        assertTrue(filtered.contains("note_create"))
        assertTrue(!filtered.contains("orb_status"))
    }

    @Test
    fun `mcp tool suggestions fuzzy match`() {
        val tool =
            McpListedTool(
                serverId = "server",
                serverName = "Portal",
                remoteName = "orb_status",
                localName = "cpk_orb_status",
                summary = "Orb status",
                asksFirst = true,
                required = emptyList(),
            )

        assertEquals(
            listOf("orb_status"),
            ConductorMcpUse.toolSuggestions("/mcp use os", listOf(tool)).map { it.remoteName },
        )
    }

    @Test
    fun `mcp list explains when nothing is saved`() {
        assertTrue(ConductorCommands.mcpText(emptyList(), emptyList()).contains("No MCP server yet"))
    }
}

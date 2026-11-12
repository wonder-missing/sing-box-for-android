package io.nekohasekai.sfa.conductor

import io.nekohasekai.sfa.agent.mcp.McpListedTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConductorMcpUseTest {
    private val orb =
        tool(
            serverName = "Clawdy Portal Knocks",
            remoteName = "orb_status",
            localName = "mcp_clawdy_portal_knocks_orb_status",
        )
    private val note =
        tool(
            serverName = "Clawdy Portal Knocks",
            remoteName = "note_create",
            localName = "mcp_clawdy_portal_knocks_note_create",
        )
    private val otherOrb =
        tool(
            serverName = "Other Node",
            remoteName = "orb_status",
            localName = "mcp_other_node_orb_status",
        )

    @Test
    fun `a unique short name becomes an input draft`() {
        val outcome = ConductorMcpUse.resolve(listOf("use", "orb_status"), listOf(orb, note))

        assertEquals(
            "Use the MCP tool orb_status from Clawdy Portal Knocks. ",
            (outcome as ConductorMcpUse.Outcome.Draft).text,
        )
    }

    @Test
    fun `the full local name selects one tool`() {
        val outcome = ConductorMcpUse.resolve(listOf("use", orb.localName), listOf(orb, otherOrb))

        assertTrue(outcome is ConductorMcpUse.Outcome.Draft)
        assertTrue((outcome as ConductorMcpUse.Outcome.Draft).text.contains("Clawdy Portal Knocks"))
    }

    @Test
    fun `two servers with the same tool name are not guessed`() {
        val outcome = ConductorMcpUse.resolve(listOf("use", "orb_status"), listOf(orb, otherOrb))

        val message = (outcome as ConductorMcpUse.Outcome.Rejected).message
        assertTrue(message.contains("Clawdy Portal Knocks: orb_status"))
        assertTrue(message.contains("Other Node: orb_status"))
    }

    @Test
    fun `suggestions follow the use verb`() {
        assertTrue(ConductorMcpUse.offerVerb("/mcp "))
        assertTrue(ConductorMcpUse.offerVerb("/mcp u"))
        assertFalse(ConductorMcpUse.offerVerb("/mcp knocks"))
        assertFalse(ConductorMcpUse.offerVerb("/mcp use "))
        assertEquals(
            listOf("note_create", "orb_status"),
            ConductorMcpUse.toolSuggestions("/mcp use ", listOf(orb, note)).map { it.remoteName },
        )
        assertEquals(
            listOf("orb_status"),
            ConductorMcpUse.toolSuggestions("/mcp use orb", listOf(orb, note)).map { it.remoteName },
        )
    }

    private fun tool(
        serverName: String,
        remoteName: String,
        localName: String,
    ) = McpListedTool(
        serverId = "server",
        serverName = serverName,
        remoteName = remoteName,
        localName = localName,
        summary = remoteName,
        asksFirst = true,
        required = emptyList(),
    )
}

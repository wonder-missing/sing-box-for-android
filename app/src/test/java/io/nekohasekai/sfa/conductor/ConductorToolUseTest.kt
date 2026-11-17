package io.nekohasekai.sfa.conductor

import io.nekohasekai.sfa.agent.AgentMetaTools
import io.nekohasekai.sfa.agent.AgentParameterSpec
import io.nekohasekai.sfa.agent.AgentParameterType
import io.nekohasekai.sfa.agent.AgentPermission
import io.nekohasekai.sfa.agent.AgentToolDefinition
import io.nekohasekai.sfa.agent.ConfirmationPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConductorToolUseTest {
    private val listNodes =
        AgentToolDefinition(
            name = "list_nodes",
            description = "List all outbound nodes.",
            permission = AgentPermission.READ_NETWORK_STATE,
            parameters = emptyList(),
            confirmationPolicy = ConfirmationPolicy.NEVER,
            category = "network",
        )
    private val probe =
        AgentToolDefinition(
            name = "probe_via_node",
            description = "Probe a URL through an outbound node.",
            permission = AgentPermission.NETWORK_PROBE,
            parameters =
                listOf(
                    AgentParameterSpec(
                        name = "url",
                        type = AgentParameterType.STRING,
                        description = "URL to probe",
                        required = true,
                    ),
                    AgentParameterSpec(
                        name = "outbound",
                        type = AgentParameterType.STRING,
                        description = "Optional outbound tag",
                        required = false,
                    ),
                ),
            confirmationPolicy = ConfirmationPolicy.ALWAYS,
            category = "network",
        )
    private val tools = listOf(listNodes, probe)

    @Test
    fun `tool name suggestions start after the command space`() {
        assertTrue(ConductorToolUse.toolSuggestions("/tool", tools).isEmpty())
        assertEquals(listOf("list_nodes", "probe_via_node"), ConductorToolUse.toolSuggestions("/tool ", tools).map { it.name })
        assertEquals(listOf("probe_via_node"), ConductorToolUse.toolSuggestions("/tool prb", tools).map { it.name })
        assertTrue(ConductorToolUse.hint("/tool ", tools)!!.contains("Type /tool <name> [json]"))
    }

    @Test
    fun `argument hint explains required parameters and example`() {
        val hint = ConductorToolUse.hint("/tool probe_via_node ", tools)!!

        assertTrue(hint.contains("url: string (required)"))
        assertTrue(hint.contains("outbound: string?"))
        assertTrue(hint.contains("/tool probe_via_node {\"url\":\"...\"}"))
    }

    @Test
    fun `no-argument tools require an empty json object to invoke`() {
        val hint = ConductorToolUse.hint("/tool list_nodes ", tools)!!

        assertTrue(hint.contains("No arguments"))
        assertTrue(hint.contains("/tool list_nodes {}"))
    }

    @Test
    fun `unknown tool and json typing stay quiet or explain the miss`() {
        assertEquals(
            "No registered tool matches \"missing\". Type /tool to list tools.",
            ConductorToolUse.hint("/tool missing ", tools),
        )
        assertNull(ConductorToolUse.hint("/tool probe_via_node {", tools))
    }

    @Test
    fun `no-space command still uses the command menu`() {
        assertTrue(ConductorToolUse.toolSuggestions("/tool", tools).isEmpty())
        assertNull(ConductorToolUse.hint("/tool", tools))
    }

    @Test
    fun `console meta tools are listed and hinted`() {
        val meta = AgentMetaTools.consoleDefinitions()
        assertEquals(listOf("search_tools"), ConductorToolUse.toolSuggestions("/tool ", meta).map { it.name })
        val hint = ConductorToolUse.hint("/tool search_tools ", meta)!!
        assertTrue(hint.contains("query: string (required)"))
        assertTrue(hint.contains("/tool search_tools {\"query\":\"...\"}"))
    }

    @Test
    fun `argument actions run no-argument tools without braces`() {
        val actions = ConductorToolUse.argumentActions("/tool list_nodes ", tools)

        assertEquals(1, actions.size)
        assertEquals("Run", actions.single().label)
        assertEquals("/tool list_nodes {}", actions.single().command)
        assertTrue(actions.single().run)
    }

    @Test
    fun `argument actions fill required parameters with a template`() {
        val actions = ConductorToolUse.argumentActions("/tool probe_via_node ", tools)

        assertEquals(1, actions.size)
        assertEquals("Fill required", actions.single().label)
        assertEquals("/tool probe_via_node {\"url\":\"\"}", actions.single().command)
        assertEquals(29, actions.single().cursor)
        assertFalse(actions.single().run)
    }

    @Test
    fun `blank required parameters also offer a direct run`() {
        val meta = AgentMetaTools.consoleDefinitions()
        val actions = ConductorToolUse.argumentActions("/tool search_tools ", meta)

        assertEquals(listOf("Run", "Fill required"), actions.map { it.label })
        assertEquals("/tool search_tools {\"query\":\"\"}", actions.first().command)
        assertTrue(actions.first().run)
    }
}

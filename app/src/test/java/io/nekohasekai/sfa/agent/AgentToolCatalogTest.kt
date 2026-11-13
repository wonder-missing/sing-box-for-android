package io.nekohasekai.sfa.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolCatalogTest {
    private val listNodes = definition("list_nodes", "network", listOf("nodes"), core = true)
    private val probe =
        definition(
            "probe_via_node",
            "network",
            listOf("probe"),
            confirmationPolicy = ConfirmationPolicy.ALWAYS,
        )
    private val tailscale = definition("tailscale_status", "devices", listOf("devices"))
    private val catalog = AgentToolCatalog(listOf(tailscale, probe, listNodes))

    @Test
    fun definitionsAreSortedByName() {
        assertEquals(
            listOf("list_nodes", "probe_via_node", "tailscale_status"),
            catalog.definitions.map { it.name },
        )
    }

    @Test
    fun exactNameAndEnglishAliasBothWork() {
        assertEquals("probe_via_node", catalog.search("probe_via_node").first().name)
        assertEquals("list_nodes", catalog.search("nodes").first().name)
        assertEquals("tailscale_status", catalog.search("devices").first().name)
    }

    @Test
    fun exactNameDoesNotPullInToolsThatShareTokens() {
        val echo = definition("mcp_demo_echo", "mcp", listOf("echo", "Demo", "mcp"))
        val write = definition("mcp_demo_write", "mcp", listOf("write", "Demo", "mcp"))
        val scoped = AgentToolCatalog(listOf(echo, write, listNodes))

        assertEquals(listOf("mcp_demo_echo"), scoped.search("mcp_demo_echo").map { it.name })
        assertEquals(listOf("mcp_demo_echo", "mcp_demo_write"), scoped.search("mcp").map { it.name })
        assertEquals(listOf("mcp_demo_echo", "mcp_demo_write"), scoped.search("Demo").map { it.name })
    }

    @Test
    fun categoryQueryReturnsTheWholeCategory() {
        assertEquals(
            listOf("list_nodes", "probe_via_node"),
            catalog.search("network").map { it.name },
        )
    }

    @Test
    fun aGenericAliasDoesNotHideOtherMatchingTools() {
        val fileList = definition("file_list", "files", listOf("file"))
        val fileRead = definition("file_read", "files", listOf("read file"))
        val fileWrite = definition("file_write", "files", listOf("write file"))
        val scoped = AgentToolCatalog(listOf(fileList, fileRead, fileWrite))

        assertEquals(
            listOf("file_list", "file_read", "file_write"),
            scoped.search("file").map { it.name },
        )
    }

    @Test
    fun suggestionsCoverNearMisses() {
        assertTrue(catalog.suggestions("list_node").contains("list_nodes"))
    }

    @Test
    fun promptSectionGroupsByCategoryAndMarksCoreTools() {
        val text = catalog.promptSection()
        assertTrue(text.contains("- devices: tailscale_status"))
        assertTrue(text.contains("- network: list_nodes, probe_via_node"))
        assertTrue(text.contains("Core tools."))
        assertTrue(text.contains("list_nodes()"))
    }

    @Test
    fun searchOutputCarriesSchemasAndConfirmationFlag() {
        val output = catalog.searchOutput("probe", limit = 5)
        val matches = output["matches"] as JsonArray
        assertEquals(1, matches.size)
        val first = matches.first() as JsonObject
        assertEquals("probe_via_node", (first["name"] as JsonPrimitive).content)
        assertTrue(first.containsKey("parameters"))
        assertEquals("true", (first["requiresConfirmation"] as JsonPrimitive).content)
    }

    @Test
    fun searchOutputListsCategoriesEvenWhenMatchesAreWeak() {
        val mcp = definition("mcp_demo_echo", "mcp", listOf("echo", "Demo", "mcp"))
        val scoped = AgentToolCatalog(listOf(listNodes, mcp))
        val output = scoped.searchOutput("something unrelated", limit = 5)
        val categories = output["categories"] as JsonObject
        assertEquals("1", (categories["mcp"] as JsonPrimitive).content)
        assertEquals("1", (categories["network"] as JsonPrimitive).content)
    }

    @Test
    fun catalogOutputListsNamesAndCategoriesWithoutSchemas() {
        val mcp = definition("mcp_demo_echo", "mcp", listOf("echo", "Demo", "mcp"))
        val scoped = AgentToolCatalog(listOf(listNodes, mcp))

        val output = scoped.catalogOutput()

        assertEquals("2", (output["total"] as JsonPrimitive).content)
        val categories = output["categories"] as JsonObject
        assertEquals("1", (categories["mcp"] as JsonPrimitive).content)
        val tools = output["tools"] as JsonArray
        assertTrue(tools.any { it is JsonObject && (it["name"] as JsonPrimitive).content == "mcp_demo_echo" })
        assertTrue(tools.none { it is JsonObject && it.containsKey("parameters") })
    }

    @Test
    fun searchOutputMarksWeakMatches() {
        val weak = catalog.searchOutput("something unrelated", limit = 5)
        assertEquals("true", (weak["weak"] as JsonPrimitive).content)
        val exact = catalog.searchOutput("probe", limit = 5)
        assertEquals("false", (exact["weak"] as JsonPrimitive).content)
    }

    @Test
    fun searchOutputDropsMatchesToStayInsideBudget() {
        val big =
            definition("big_tool", "network", listOf("big"))
                .copy(description = "x".repeat(1_000))
        val smallCatalog = AgentToolCatalog(listOf(listNodes, probe, big))
        val output = smallCatalog.searchOutput("network", limit = 5, maxChars = 1_400)
        assertTrue(output.toString().length <= 1_400)
        assertEquals("true", (output["truncated"] as JsonPrimitive).content)
    }

    private fun definition(
        name: String,
        category: String,
        aliases: List<String>,
        core: Boolean = false,
        confirmationPolicy: ConfirmationPolicy = ConfirmationPolicy.NEVER,
    ): AgentToolDefinition =
        AgentToolDefinition(
            name = name,
            description = "Description for $name",
            permission = AgentPermission.READ_NETWORK_STATE,
            parameters = emptyList(),
            confirmationPolicy = confirmationPolicy,
            category = category,
            aliases = aliases,
            core = core,
        )
}

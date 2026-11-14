package io.nekohasekai.sfa.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentMetaToolsTest {
    @Test
    fun schemasAreStaticAndContainRequiredFields() {
        val schemas = AgentMetaTools.schemas()
        assertEquals(listOf(AgentMetaTools.ROUTE_CALL, AgentMetaTools.SEARCH_TOOLS), schemas.map { it.name })
        assertEquals(schemas[0].parameters, AgentMetaTools.schemas()[0].parameters)

        val routeText = schemas.first { it.name == AgentMetaTools.ROUTE_CALL }.parameters.toString()
        assertTrue(routeText.contains("\"tool\""))
        assertTrue(routeText.contains("\"args\""))
        assertTrue(routeText.contains("\"additionalProperties\":true"))

        val searchText = schemas.first { it.name == AgentMetaTools.SEARCH_TOOLS }.parameters.toString()
        assertTrue(searchText.contains("\"query\""))
        assertTrue(searchText.contains("\"required\":[\"query\"]"))
        assertTrue(
            schemas
                .first { it.name == AgentMetaTools.SEARCH_TOOLS }
                .description
                .contains("When the user asks what you can do"),
        )
    }

    @Test
    fun metaToolNamesAreReserved() {
        assertTrue(AgentMetaTools.isMetaTool(AgentMetaTools.ROUTE_CALL))
        assertTrue(AgentMetaTools.isMetaTool(AgentMetaTools.SEARCH_TOOLS))
        assertTrue(!AgentMetaTools.isMetaTool("list_nodes"))
    }
}

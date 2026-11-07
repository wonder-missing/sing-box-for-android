package io.nekohasekai.sfa.agent

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentIntentParserTest {
    @Test
    fun mapsNodeKeywordToListNodes() {
        val planned = AgentIntentParser.parse("看看现在的节点")

        assertEquals("list_nodes", planned?.tool)
        assertEquals("{}", planned?.argsJson)
    }

    @Test
    fun mapsNetworkKeywordToSnapshot() {
        val planned = AgentIntentParser.parse("当前网络环境怎么样")

        assertEquals("network_snapshot", planned?.tool)
    }

    @Test
    fun mapsChineseProbeSentence() {
        val planned = AgentIntentParser.parse("用 node-a 探测 https://example.com/health")

        assertEquals("probe_via_node", planned?.tool)
        val args = AgentJson.parseObject(planned!!.argsJson)!!
        assertEquals("node-a", (args["outbound"] as JsonPrimitive).content)
        assertEquals("https://example.com/health", (args["url"] as JsonPrimitive).content)
    }

    @Test
    fun mapsEnglishProbeSentence() {
        val planned = AgentIntentParser.parse("probe node-a https://example.com/health")

        assertEquals("probe_via_node", planned?.tool)
        val args = AgentJson.parseObject(planned!!.argsJson)!!
        assertEquals("node-a", (args["outbound"] as JsonPrimitive).content)
    }

    @Test
    fun mapsExplicitToolInvocation() {
        val planned =
            AgentIntentParser.parse(
                """probe_via_node {"outbound":"node-b","url":"https://example.com/"}""",
            )

        assertEquals("probe_via_node", planned?.tool)
        assertTrue(planned!!.argsJson.contains("\"node-b\""))
    }

    @Test
    fun urlWithoutOutboundIsNotGuessed() {
        assertNull(AgentIntentParser.parse("probe https://example.com/"))
    }

    @Test
    fun unrelatedSentenceReturnsNull() {
        assertNull(AgentIntentParser.parse("今天天气不错"))
    }
}

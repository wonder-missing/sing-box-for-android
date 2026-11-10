package io.nekohasekai.sfa.agent

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress

class DeepSeekModelClientTest {
    private lateinit var server: HttpServer

    @Volatile
    private var requestBody: String = ""

    @Volatile
    private var responseBody: String = ""

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/chat/completions") { exchange ->
            requestBody = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            val bytes = responseBody.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.stop(0)
    }

    @Test
    fun serializesToolChoiceNoneAndParsesCacheUsage() {
        responseBody =
            """
            {
              "choices": [{"message": {"content": "done"}, "finish_reason": "stop"}],
              "usage": {
                "prompt_tokens": 120,
                "completion_tokens": 8,
                "prompt_cache_hit_tokens": 100,
                "prompt_cache_miss_tokens": 20
              }
            }
            """.trimIndent()

        val client = DeepSeekModelClient(baseUrl = "http://127.0.0.1:${server.address.port}")
        val response =
            runBlocking {
                client.complete(
                    AgentModelRequest(
                        messages = listOf(AgentMessage.User("hi")),
                        tools = AgentMetaTools.schemas(),
                        maxOutputTokens = 16,
                        timeoutMs = 5_000,
                        toolChoice = AgentToolChoice.NONE,
                    ),
                    apiKey = "sk-test",
                )
            }

        assertTrue(requestBody.contains("\"tool_choice\":\"none\""))
        assertEquals(100, response.usage?.cacheHitTokens ?: 0)
        assertEquals(20, response.usage?.cacheMissTokens ?: 0)
    }
}

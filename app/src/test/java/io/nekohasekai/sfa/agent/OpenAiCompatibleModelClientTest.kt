package io.nekohasekai.sfa.agent

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket

class OpenAiCompatibleModelClientTest {
    private lateinit var server: ServerSocket

    @Volatile
    private var requestLine: String = ""

    @Volatile
    private var requestBody: String = ""

    @Volatile
    private var responseBody: String = ""

    @Before
    fun setUp() {
        server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        Thread {
            runCatching {
                server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
                    requestLine = reader.readLine().orEmpty()
                    var contentLength = 0
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("Content-Length:", ignoreCase = true)) {
                            contentLength = line.substringAfter(':').trim().toInt()
                        }
                    }
                    val chars = CharArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val count = reader.read(chars, read, contentLength - read)
                        if (count < 0) break
                        read += count
                    }
                    requestBody = String(chars, 0, read)

                    val bytes = responseBody.toByteArray(Charsets.UTF_8)
                    val output = socket.getOutputStream()
                    output.write(
                        (
                            "HTTP/1.1 200 OK\r\n" +
                                "Content-Type: application/json\r\n" +
                                "Content-Length: ${bytes.size}\r\n" +
                                "Connection: close\r\n\r\n"
                        ).toByteArray(Charsets.US_ASCII),
                    )
                    output.write(bytes)
                    output.flush()
                }
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    @After
    fun tearDown() {
        server.close()
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

        val client = clientFor("http://127.0.0.1:${server.localPort}")
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

    @Test
    fun parsesSseContentToolCallsAndUsage() {
        responseBody =
            """
            data: {"choices":[{"delta":{"reasoning_content":"think "}}]}
            data: {"choices":[{"delta":{"content":"Hel"}}]}
            data: {"choices":[{"delta":{"content":"lo"}}]}
            data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"list_nodes","arguments":"{}"}}]}}]}
            data: {"choices":[{"delta":{},"finish_reason":"tool_calls"}]}
            data: {"choices":[],"usage":{"prompt_tokens":10,"completion_tokens":2,"prompt_cache_hit_tokens":4,"prompt_cache_miss_tokens":6}}
            data: [DONE]
            """.trimIndent()

        val client = clientFor("http://127.0.0.1:${server.localPort}")
        val chunks =
            runBlocking {
                client
                    .stream(
                        AgentModelRequest(
                            messages = listOf(AgentMessage.User("hi")),
                            tools = emptyList(),
                            maxOutputTokens = 16,
                            timeoutMs = 5_000,
                        ),
                        apiKey = "sk-test",
                    ).toList()
            }

        assertEquals(
            listOf("think "),
            chunks.filterIsInstance<AgentModelChunk.ReasoningDelta>().map { it.text },
        )
        assertEquals(
            listOf("Hel", "lo"),
            chunks.filterIsInstance<AgentModelChunk.ContentDelta>().map { it.text },
        )
        val toolCall = chunks.filterIsInstance<AgentModelChunk.ToolCallDelta>().single()
        assertEquals(0, toolCall.index)
        assertEquals("call_1", toolCall.id)
        assertEquals("list_nodes", toolCall.name)
        assertEquals("{}", toolCall.argumentsDelta)

        val completed = chunks.filterIsInstance<AgentModelChunk.Completed>().single()
        assertEquals(FinishReason.TOOL_CALLS, completed.finishReason)
        assertEquals(4, completed.usage?.cacheHitTokens ?: 0)
        assertEquals(6, completed.usage?.cacheMissTokens ?: 0)
    }

    @Test
    fun appendsChatCompletionsToVersionedBaseUrl() {
        responseBody = """{"choices":[{"message":{"content":"done"},"finish_reason":"stop"}]}"""
        val client = clientFor("http://127.0.0.1:${server.localPort}/v1")

        runBlocking { client.complete(request(), apiKey = "sk-test") }

        assertTrue(requestLine.startsWith("POST /v1/chat/completions "))
    }

    @Test
    fun acceptsAnExplicitChatCompletionsUrl() {
        responseBody = """{"choices":[{"message":{"content":"done"},"finish_reason":"stop"}]}"""
        val client = clientFor("http://127.0.0.1:${server.localPort}/v1/chat/completions")

        runBlocking { client.complete(request(), apiKey = "sk-test") }

        assertTrue(requestLine.startsWith("POST /v1/chat/completions "))
    }

    @Test
    fun parsesOpenAiStyleCachedTokens() {
        responseBody =
            """
            {
              "choices": [{"message": {"content": "done"}, "finish_reason": "stop"}],
              "usage": {
                "prompt_tokens": 100,
                "completion_tokens": 5,
                "prompt_tokens_details": {"cached_tokens": 30}
              }
            }
            """.trimIndent()
        val client = clientFor("http://127.0.0.1:${server.localPort}")

        val response = runBlocking { client.complete(request(), apiKey = "sk-test") }

        assertEquals(30, response.usage?.cacheHitTokens ?: 0)
        assertEquals(70, response.usage?.cacheMissTokens ?: 0)
    }

    @Test
    fun rejectsAnEmptyBaseUrlBeforeNetwork() {
        val client = clientFor("")

        val error =
            runCatching {
                runBlocking { client.complete(request(), apiKey = "sk-test") }
            }.exceptionOrNull()

        assertTrue(error is AgentModelException)
        assertEquals(AgentTurnErrorCode.INVALID_REQUEST, (error as AgentModelException).code)
    }

    @Test
    fun buildsChatCompletionsUrlFromBase() {
        assertEquals(
            "https://api.deepseek.com/chat/completions",
            chatCompletionsUrl("https://api.deepseek.com"),
        )
        assertEquals(
            "https://api.x.ai/v1/chat/completions",
            chatCompletionsUrl("https://api.x.ai/v1/"),
        )
        assertEquals(
            "https://example.com/v1/chat/completions",
            chatCompletionsUrl("https://example.com/v1/chat/completions"),
        )
    }

    private fun request(): AgentModelRequest =
        AgentModelRequest(
            messages = listOf(AgentMessage.User("hi")),
            tools = emptyList(),
            maxOutputTokens = 1,
            timeoutMs = 5_000,
        )

    private fun clientFor(
        baseUrl: String,
        model: String = "test-model",
    ): OpenAiCompatibleModelClient =
        OpenAiCompatibleModelClient {
            ModelEndpoint(
                providerId = ModelProviders.GENERIC,
                providerLabel = "Test",
                baseUrl = baseUrl,
                model = model,
            )
        }
}

package io.nekohasekai.sfa.agent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class DeepSeekWebSearchPortTest {
    @Test
    fun searchUsesAnthropicMessagesEndpointAndServerTool() =
        runBlocking {
            val seen = AtomicReference<String?>(null)
            withServer(
                response =
                    """
                    {
                      "content": [
                        {
                          "type": "server_tool_use",
                          "id": "srvtoolu_1",
                          "name": "web_search",
                          "input": {"query": "latest Kotlin"}
                        },
                        {
                          "type": "web_search_tool_result",
                          "tool_use_id": "srvtoolu_1",
                          "content": [
                            {
                              "type": "web_search_result",
                              "title": "Kotlin news",
                              "url": "https://kotlinlang.org/news",
                              "page_age": "2 days",
                              "encrypted_content": "opaque"
                            }
                          ]
                        },
                        {
                          "type": "text",
                          "text": "Kotlin 2.2 is out.",
                          "citations": [
                            {
                              "url": "https://kotlinlang.org/news",
                              "title": "Kotlin news",
                              "cited_text": "Kotlin 2.2"
                            }
                          ]
                        }
                      ],
                      "stop_reason": "end_turn"
                    }
                    """.trimIndent(),
                seen = seen,
            ) { port ->
                val endpoint =
                    ModelEndpoint(
                        providerId = ModelProviders.DEEPSEEK,
                        providerLabel = "DeepSeek",
                        baseUrl = "http://127.0.0.1:$port",
                        model = "deepseek-flash",
                    )
                val search =
                    DeepSeekWebSearchPort(
                        endpointProvider = { endpoint },
                        apiKeyProvider = { "test-key" },
                        today = { "2026-11-18" },
                    )

                val outcome = search.search("latest Kotlin", 5)

                assertNull(outcome.error, outcome.error)
                assertEquals("Kotlin 2.2 is out.", outcome.answer)
                assertEquals(listOf("latest Kotlin"), outcome.searchQueries)
                assertEquals(1, outcome.sources.size)
                assertEquals("Kotlin news", outcome.sources.single().title)
                assertEquals("2 days", outcome.sources.single().pageAge)
                assertEquals("Kotlin 2.2", outcome.sources.single().citedText)
                assertFalse(outcome.sources.single().url.contains("opaque"))

                val request = seen.get().orEmpty()
                assertTrue(request.startsWith("POST /anthropic/v1/messages"))
                assertTrue(request.contains("x-api-key: test-key"))
                assertTrue(request.contains("\"type\":\"web_search_20250305\""))
                assertTrue(request.contains("\"max_uses\":3"))
                assertTrue(request.contains("latest Kotlin"))
            }
        }

    @Test
    fun searchRejectsAProviderWithoutTheAnthropicEndpoint() =
        runBlocking {
            val endpoint =
                ModelEndpoint(
                    providerId = ModelProviders.XAI,
                    providerLabel = "xAI",
                    baseUrl = "https://api.x.ai/v1",
                    model = "grok-4",
                )
            val search =
                DeepSeekWebSearchPort(
                    endpointProvider = { endpoint },
                    apiKeyProvider = { "test-key" },
                )

            val outcome = search.search("anything", 5)

            assertTrue(outcome.error.orEmpty().contains("DeepSeek"))
        }

    @Test
    fun parseKeepsOneSourceForResultAndCitation() {
        val outcome =
            parseWebSearchResponse(
                """
                {
                  "content": [
                    {
                      "type": "web_search_tool_result",
                      "content": [
                        {"url": "https://example.com/a", "title": "A"},
                        {"url": "https://example.com/a", "title": "A again"},
                        {"url": "https://example.com/b", "title": "B"}
                      ]
                    }
                  ]
                }
                """.trimIndent(),
                2,
            )

        assertNull(outcome.error)
        assertEquals(2, outcome.sources.size)
        assertEquals("A again", outcome.sources.first().title)
        assertEquals("https://example.com/b", outcome.sources[1].url)
    }

    @Test
    fun parseTreatsAnEmptyResultAsSuccess() {
        val outcome = parseWebSearchResponse("""{"content":[],"stop_reason":"end_turn"}""", 5)

        assertNull(outcome.error)
        assertNull(outcome.answer)
        assertTrue(outcome.sources.isEmpty())
        assertTrue(outcome.searchErrors.isEmpty())
    }

    @Test
    fun parseReportsSearchToolErrors() {
        val outcome =
            parseWebSearchResponse(
                """
                {
                  "content": [
                    {
                      "type": "web_search_tool_result",
                      "content": {
                        "type": "web_search_tool_result_error",
                        "error_code": "max_uses_exceeded",
                        "error_message": "too many searches"
                      }
                    },
                    {
                      "type": "web_search_tool_result",
                      "content": [
                        {
                          "type": "web_search_tool_result_error",
                          "error_code": "unavailable"
                        }
                      ]
                    }
                  ]
                }
                """.trimIndent(),
                5,
            )

        assertEquals(
            listOf("max_uses_exceeded: too many searches", "unavailable"),
            outcome.searchErrors,
        )
    }

    @Test
    fun anthropicMessagesUrlHandlesCommonBases() {
        assertEquals(
            "https://api.deepseek.com/anthropic/v1/messages",
            anthropicMessagesUrl("https://api.deepseek.com"),
        )
        assertEquals(
            "https://api.deepseek.com/anthropic/v1/messages",
            anthropicMessagesUrl("https://api.deepseek.com/v1/"),
        )
        assertEquals(
            "https://proxy.example.com/anthropic/v1/messages",
            anthropicMessagesUrl("https://proxy.example.com/anthropic"),
        )
        assertEquals(
            "https://proxy.example.com/anthropic/v1/messages",
            anthropicMessagesUrl("https://proxy.example.com/anthropic/v1/messages"),
        )
    }

    private suspend fun withServer(
        response: String,
        seen: AtomicReference<String?>,
        block: suspend (port: Int) -> Unit,
    ) {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val worker =
            thread(isDaemon = true, name = "web-search-test") {
                try {
                    server.accept().use { socket ->
                        socket.soTimeout = 5_000
                        val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
                        val requestLine = reader.readLine().orEmpty()
                        val headerLines = mutableListOf<String>()
                        var contentLength = 0
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            headerLines += line
                            if (line.startsWith("Content-Length:", ignoreCase = true)) {
                                contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
                            }
                        }
                        val bodyChars = CharArray(contentLength)
                        var read = 0
                        while (read < contentLength) {
                            val count = reader.read(bodyChars, read, contentLength - read)
                            if (count < 0) break
                            read += count
                        }
                        seen.set(
                            buildString {
                                appendLine(requestLine)
                                headerLines.forEach { appendLine(it) }
                                appendLine()
                                append(String(bodyChars, 0, read))
                            },
                        )
                        val bytes = response.toByteArray(Charsets.UTF_8)
                        val headers =
                            buildString {
                                append("HTTP/1.1 200 OK\r\n")
                                append("Content-Type: application/json\r\n")
                                append("Content-Length: ${bytes.size}\r\n")
                                append("Connection: close\r\n\r\n")
                            }.toByteArray(Charsets.UTF_8)
                        socket.getOutputStream().use { output ->
                            output.write(headers)
                            output.write(bytes)
                            output.flush()
                        }
                    }
                } catch (_: Exception) {
                    // The assertions in the test body report the failure.
                }
            }
        try {
            block(server.localPort)
        } finally {
            server.close()
            worker.join(1_000)
        }
    }
}

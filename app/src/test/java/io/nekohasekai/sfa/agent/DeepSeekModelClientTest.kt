package io.nekohasekai.sfa.agent

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket

class DeepSeekModelClientTest {
    private lateinit var server: ServerSocket

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

        val client = DeepSeekModelClient(baseUrl = "http://127.0.0.1:${server.localPort}")
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

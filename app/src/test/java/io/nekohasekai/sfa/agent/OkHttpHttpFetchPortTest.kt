package io.nekohasekai.sfa.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class OkHttpHttpFetchPortTest {
    @Test
    fun fetchUsesTheRequestedMethodBodyAndReturnsHeaders() {
        val seen = AtomicReference<Pair<String, String>?>(null)
        val fetchPort = OkHttpHttpFetchPort()
        withServer(
            response = {
                RawResponse(
                    status = "201 Created",
                    headers =
                        linkedMapOf(
                            "Content-Type" to "text/plain; charset=utf-8",
                            "X-Test" to "yes",
                        ),
                    body = "hello",
                )
            },
            seen = seen,
        ) { port ->
            val outcome =
                fetchPort.fetch(
                    url = "http://127.0.0.1:$port/echo",
                    method = "POST",
                    headers = listOf("X-Request" to "one"),
                    body = "payload",
                    maxBytes = 64,
                    cancellation = null,
                )

            assertNull(outcome.error)
            assertEquals(201, outcome.statusCode)
            assertEquals("Created", outcome.statusText)
            assertEquals("hello", outcome.content)
            assertEquals("text/plain; charset=utf-8", outcome.contentType)
            assertTrue(
                outcome.headers.entries.any { (name, values) ->
                    name.equals("X-Test", ignoreCase = true) && values == listOf("yes")
                },
            )
            assertEquals("POST" to "payload", seen.get())
            assertFalse(outcome.truncated)
        }
    }

    @Test
    fun fetchReturnsTheBodyForNon200Responses() {
        val fetchPort = OkHttpHttpFetchPort()
        withServer(
            response = { RawResponse("404 Not Found", emptyMap(), "gone") },
        ) { port ->
            val outcome =
                fetchPort.fetch(
                    url = "http://127.0.0.1:$port/missing",
                    method = "GET",
                    headers = emptyList(),
                    body = null,
                    maxBytes = 64,
                    cancellation = null,
                )

            assertNull(outcome.error)
            assertEquals(404, outcome.statusCode)
            assertEquals("gone", outcome.content)
        }
    }

    @Test
    fun fetchStopsReadingAtMaxBytes() {
        val fetchPort = OkHttpHttpFetchPort()
        withServer(
            response = { RawResponse("200 OK", emptyMap(), "abcdefghij") },
        ) { port ->
            val outcome =
                fetchPort.fetch(
                    url = "http://127.0.0.1:$port/long",
                    method = "GET",
                    headers = emptyList(),
                    body = null,
                    maxBytes = 4,
                    cancellation = null,
                )

            assertNull(outcome.error)
            assertEquals("abcd", outcome.content)
            assertEquals(4, outcome.bytes)
            assertTrue(outcome.truncated)
        }
    }

    private data class RawResponse(
        val status: String,
        val headers: Map<String, String>,
        val body: String,
    )

    private fun withServer(
        response: () -> RawResponse,
        seen: AtomicReference<Pair<String, String>?>? = null,
        block: (port: Int) -> Unit,
    ) {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val worker =
            thread(isDaemon = true, name = "http-fetch-test") {
                try {
                    server.accept().use { socket ->
                        val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
                        val requestLine = reader.readLine().orEmpty()
                        val method = requestLine.substringBefore(' ')
                        var contentLength = 0
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
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
                        seen?.set(method to String(bodyChars, 0, read))
                        val payload = response()
                        val bytes = payload.body.toByteArray(Charsets.UTF_8)
                        val headers =
                            buildString {
                                append("HTTP/1.1 ${payload.status}\r\n")
                                payload.headers.forEach { (name, value) ->
                                    append("$name: $value\r\n")
                                }
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

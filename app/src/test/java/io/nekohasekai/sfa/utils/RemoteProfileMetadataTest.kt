package io.nekohasekai.sfa.utils

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.concurrent.thread

class RemoteProfileMetadataTest {
    private val lastModified = "Sun, 08 Nov 2026 13:19:38 GMT"
    private lateinit var server: TestHttpServer
    private lateinit var baseUrl: String

    @Before
    fun setUp() {
        server =
            TestHttpServer { method, path ->
                when (path) {
                    "/ok" -> Response(200, mapOf("Last-Modified" to lastModified))
                    "/head-405" ->
                        if (method == "HEAD") {
                            Response(405)
                        } else {
                            Response(200, mapOf("Last-Modified" to lastModified))
                        }
                    "/no-header" -> Response(200)
                    "/malformed" -> Response(200, mapOf("Last-Modified" to "not-a-date"))
                    "/error" -> Response(500)
                    else -> Response(404)
                }
            }
        baseUrl = "http://127.0.0.1:${server.port}"
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `parses last modified from head response`() {
        val result = RemoteProfileMetadata.fetch("$baseUrl/ok", "WonderSing-test")

        assertTrue(result.fetched)
        assertNotNull(result.lastModified)
        assertEquals(lastModified, httpDate(result.lastModified!!))
    }

    @Test
    fun `falls back to get when head is not allowed`() {
        val result = RemoteProfileMetadata.fetch("$baseUrl/head-405", "WonderSing-test")

        assertTrue(result.fetched)
        assertNotNull(result.lastModified)
        assertEquals(lastModified, httpDate(result.lastModified!!))
    }

    @Test
    fun `reports a reachable endpoint without the header`() {
        val result = RemoteProfileMetadata.fetch("$baseUrl/no-header", "WonderSing-test")

        assertTrue(result.fetched)
        assertNull(result.lastModified)
    }

    @Test
    fun `ignores a malformed header`() {
        val result = RemoteProfileMetadata.fetch("$baseUrl/malformed", "WonderSing-test")

        assertTrue(result.fetched)
        assertNull(result.lastModified)
    }

    @Test
    fun `reports a failed request`() {
        val result = RemoteProfileMetadata.fetch("$baseUrl/error", "WonderSing-test")

        assertFalse(result.fetched)
        assertNull(result.lastModified)
    }

    private fun httpDate(date: Date): String =
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("GMT") }
            .format(date)

    private data class Response(
        val code: Int,
        val headers: Map<String, String> = emptyMap(),
    )

    private class TestHttpServer(
        private val responder: (method: String, path: String) -> Response,
    ) : Closeable {
        private val serverSocket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = serverSocket.localPort

        init {
            thread(isDaemon = true, name = "remote-profile-metadata-test") {
                while (!serverSocket.isClosed) {
                    val socket =
                        try {
                            serverSocket.accept()
                        } catch (_: Exception) {
                            break
                        }
                    socket.use { handle(it) }
                }
            }
        }

        private fun handle(socket: Socket) {
            val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
            val requestLine = reader.readLine() ?: return
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
            }

            val parts = requestLine.split(' ')
            val method = parts.getOrElse(0) { "GET" }
            val path = parts.getOrElse(1) { "/" }
            val response = responder(method, path)

            val headers =
                buildString {
                    append("HTTP/1.1 ${response.code} ${reason(response.code)}\r\n")
                    response.headers.forEach { (name, value) -> append("$name: $value\r\n") }
                    append("Content-Length: 0\r\n")
                    append("Connection: close\r\n\r\n")
                }
            socket.getOutputStream().apply {
                write(headers.toByteArray(Charsets.US_ASCII))
                flush()
            }
        }

        private fun reason(code: Int): String =
            when (code) {
                200 -> "OK"
                404 -> "Not Found"
                405 -> "Method Not Allowed"
                500 -> "Internal Server Error"
                else -> "Response"
            }

        override fun close() {
            serverSocket.close()
        }
    }
}

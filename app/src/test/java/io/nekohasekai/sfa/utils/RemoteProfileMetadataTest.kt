package io.nekohasekai.sfa.utils

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class RemoteProfileMetadataTest {
    private lateinit var server: HttpServer
    private lateinit var baseUrl: String

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
        baseUrl = "http://127.0.0.1:${server.address.port}"
    }

    @After
    fun tearDown() {
        server.stop(0)
    }

    @Test
    fun `parses last modified from head response`() {
        val expected = "Sun, 08 Nov 2026 13:19:38 GMT"
        server.createContext("/ok") { exchange ->
            exchange.responseHeaders.add("Last-Modified", expected)
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }

        val result = RemoteProfileMetadata.fetch("$baseUrl/ok", "WonderSing-test")

        assertTrue(result.fetched)
        assertNotNull(result.lastModified)
        assertEquals(expected, httpDate(result.lastModified!!))
    }

    @Test
    fun `falls back to get when head is not allowed`() {
        val expected = "Sun, 08 Nov 2026 13:19:38 GMT"
        server.createContext("/head-405") { exchange ->
            if (exchange.requestMethod == "HEAD") {
                exchange.sendResponseHeaders(405, -1)
            } else {
                exchange.responseHeaders.add("Last-Modified", expected)
                exchange.sendResponseHeaders(200, -1)
            }
            exchange.close()
        }

        val result = RemoteProfileMetadata.fetch("$baseUrl/head-405", "WonderSing-test")

        assertTrue(result.fetched)
        assertNotNull(result.lastModified)
        assertEquals(expected, httpDate(result.lastModified!!))
    }

    @Test
    fun `reports a reachable endpoint without the header`() {
        server.createContext("/no-header") { exchange ->
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }

        val result = RemoteProfileMetadata.fetch("$baseUrl/no-header", "WonderSing-test")

        assertTrue(result.fetched)
        assertNull(result.lastModified)
    }

    @Test
    fun `ignores a malformed header`() {
        server.createContext("/malformed") { exchange ->
            exchange.responseHeaders.add("Last-Modified", "not-a-date")
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }

        val result = RemoteProfileMetadata.fetch("$baseUrl/malformed", "WonderSing-test")

        assertTrue(result.fetched)
        assertNull(result.lastModified)
    }

    @Test
    fun `reports a failed request`() {
        server.createContext("/error") { exchange ->
            exchange.sendResponseHeaders(500, -1)
            exchange.close()
        }

        val result = RemoteProfileMetadata.fetch("$baseUrl/error", "WonderSing-test")

        assertFalse(result.fetched)
        assertNull(result.lastModified)
    }

    private fun httpDate(date: Date): String =
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("GMT") }
            .format(date)
}

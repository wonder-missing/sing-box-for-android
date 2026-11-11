package io.nekohasekai.sfa.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.ProtocolException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

class ModelTransportErrorsTest {
    private val baseUrl = "https://api.x.ai/v1"

    @Test
    fun settingsPrefaceIsActionableAndNotRetried() {
        val error =
            modelTransportException(
                ProtocolException("Required SETTINGS preface not received"),
                "xAI",
                baseUrl,
            )

        assertEquals(AgentTurnErrorCode.MODEL_UNAVAILABLE, error.code)
        assertFalse(error.retryable)
        assertTrue(error.message.contains("handshake"))
        assertFalse(error.message.contains("SETTINGS preface"))
    }

    @Test
    fun connectErrorHidesTheResolvedFakeIp() {
        val error =
            modelTransportException(
                ConnectException("Failed to connect to api.x.ai/198.18.0.4:443"),
                "xAI",
                baseUrl,
            )

        assertEquals(AgentTurnErrorCode.MODEL_UNAVAILABLE, error.code)
        assertFalse(error.retryable)
        assertTrue(error.message.contains("api.x.ai"))
        assertFalse(error.message.contains("198.18.0.4"))
        assertFalse(error.message.contains("Failed to connect"))
    }

    @Test
    fun ipLiteralBaseUrlStaysHidden() {
        val error =
            modelTransportException(
                ConnectException("Failed to connect to 192.168.1.10:8080"),
                "Custom (OpenAI-compatible)",
                "http://192.168.1.10:8080/v1",
            )

        assertTrue(error.message.contains("Custom"))
        assertFalse(error.message.contains("192.168.1.10"))
    }

    @Test
    fun dnsFailureIsNotRetried() {
        val error =
            modelTransportException(
                UnknownHostException("api.x.ai"),
                "xAI",
                baseUrl,
            )

        assertFalse(error.retryable)
        assertTrue(error.message.contains("api.x.ai"))
        assertTrue(error.message.contains("DNS"))
    }

    @Test
    fun tlsFailureIsActionableAndNotRetried() {
        val error =
            modelTransportException(
                SSLException("handshake failed"),
                "xAI",
                baseUrl,
            )

        assertFalse(error.retryable)
        assertTrue(error.message.contains("TLS"))
        assertFalse(error.message.contains("handshake failed"))
    }

    @Test
    fun timeoutKeepsTheRetryPath() {
        val error =
            modelTransportException(
                SocketTimeoutException("timeout"),
                "xAI",
                baseUrl,
            )

        assertTrue(error.retryable)
        assertTrue(error.message.contains("Timed out"))
    }

    @Test
    fun genericIoErrorKeepsTheRetryPath() {
        val error =
            modelTransportException(
                IOException("unexpected EOF"),
                "xAI",
                baseUrl,
            )

        assertTrue(error.retryable)
        assertTrue(error.message.contains("api.x.ai"))
        assertFalse(error.message.contains("unexpected EOF"))
    }
}

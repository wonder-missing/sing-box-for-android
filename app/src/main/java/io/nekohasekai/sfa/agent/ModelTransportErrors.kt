package io.nekohasekai.sfa.agent

import java.io.IOException
import java.net.ConnectException
import java.net.ProtocolException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Turns transport exceptions into messages that mean something to the user.
 * The raw exception is still recorded in [AgentApiTrace]; this classifier is
 * only for the UI-facing error.
 *
 * Protocol/handshake, DNS, connection refused and TLS failures are
 * deterministic: retrying the same request immediately usually only hides the
 * real problem behind a spinner. Timeouts and generic I/O failures keep the
 * retry path. Each class also gets its own error code so turn events and logs
 * can distinguish a DNS failure from an HTTP 5xx.
 */
internal fun modelTransportException(
    error: IOException,
    providerLabel: String,
    baseUrl: String,
): AgentModelException {
    val raw = error.message.orEmpty()
    val host =
        runCatching { URL(baseUrl).host }.getOrNull()
            ?.takeIf { it.isNotBlank() && !it.isIpLiteral() }
            ?: providerLabel
    val prefaceFailure =
        error is ProtocolException ||
            raw.contains("SETTINGS preface", ignoreCase = true) ||
            raw.contains("Expected HTTP 101", ignoreCase = true)
    val (code, message, retryable) =
        when {
            prefaceFailure ->
                Triple(
                    AgentTurnErrorCode.NETWORK_HANDSHAKE,
                    "$providerLabel closed the HTTPS connection before the API handshake completed. " +
                        "A local VPN or proxy may be interfering; check the proxy rules and try again.",
                    false,
                )

            error is UnknownHostException ->
                Triple(
                    AgentTurnErrorCode.NETWORK_DNS,
                    "Cannot resolve $host. Check the VPN, proxy and DNS, then try again.",
                    false,
                )

            error is ConnectException ->
                Triple(
                    AgentTurnErrorCode.NETWORK_CONNECT,
                    "Cannot reach $host. Check the VPN or proxy and network, then try again.",
                    false,
                )

            error is SSLException ->
                Triple(
                    AgentTurnErrorCode.NETWORK_TLS,
                    "The TLS handshake with $host failed. A proxy, VPN or certificate issue may be blocking it.",
                    false,
                )

            error is SocketTimeoutException ->
                Triple(
                    AgentTurnErrorCode.NETWORK_TIMEOUT,
                    "Timed out connecting to $host. Check the network or proxy and try again.",
                    true,
                )

            else ->
                Triple(
                    AgentTurnErrorCode.MODEL_UNAVAILABLE,
                    "Could not reach $host. Check the VPN or proxy and network, then try again.",
                    true,
                )
        }
    return AgentModelException(
        code,
        message,
        retryable = retryable,
    )
}

private fun String.isIpLiteral(): Boolean =
    matches(Regex("""\d{1,3}(\.\d{1,3}){3}""")) || contains(':')

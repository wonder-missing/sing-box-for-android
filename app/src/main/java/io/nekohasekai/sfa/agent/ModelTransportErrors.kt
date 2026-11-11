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
 * retry path.
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
    val (message, retryable) =
        when {
            prefaceFailure ->
                "$providerLabel closed the HTTPS connection before the API handshake completed. " +
                    "A local VPN or proxy may be interfering; check the proxy rules and try again." to false

            error is UnknownHostException ->
                "Cannot resolve $host. Check the VPN, proxy and DNS, then try again." to false

            error is ConnectException ->
                "Cannot reach $host. Check the VPN or proxy and network, then try again." to false

            error is SSLException ->
                "The TLS handshake with $host failed. A proxy, VPN or certificate issue may be blocking it." to false

            error is SocketTimeoutException ->
                "Timed out connecting to $host. Check the network or proxy and try again." to true

            else ->
                "Could not reach $host. Check the VPN or proxy and network, then try again." to true
        }
    return AgentModelException(
        AgentTurnErrorCode.MODEL_UNAVAILABLE,
        message,
        retryable = retryable,
    )
}

private fun String.isIpLiteral(): Boolean =
    matches(Regex("""\d{1,3}(\.\d{1,3}){3}""")) || contains(':')

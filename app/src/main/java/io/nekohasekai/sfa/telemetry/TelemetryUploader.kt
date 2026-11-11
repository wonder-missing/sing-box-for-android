package io.nekohasekai.sfa.telemetry

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import io.nekohasekai.sfa.agent.ToolCancellation
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.net.UnknownHostException
import java.security.SecureRandom
import java.util.zip.GZIPOutputStream
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket

internal sealed class UploadResult {
    data object Success : UploadResult()

    data object Unauthorized : UploadResult()

    data object TooLarge : UploadResult()

    data object Cancelled : UploadResult()

    data class Failed(val retryable: Boolean, val detail: String) : UploadResult()
}

internal fun interface SocketProtector {
    fun protect(socket: Socket): Boolean
}

/**
 * HTTPS POST that prefers the underlying network (and a protected socket) so a
 * broken VPN tunnel does not swallow the upload.
 */
internal class TelemetryUploader(
    private val context: Context,
    private val protect: SocketProtector?,
) {
    /**
     * Tries the last path that worked first ("direct" = protected socket on the
     * underlying network, "vpn" = default network, i.e. through the tunnel), then
     * the other one. Every attempt is recorded in [TelemetryEventLog] with its
     * duration so upload success rate and latency are measurable even when the
     * upload itself failed. [tags] are copied onto those events.
     */
    fun post(
        endpoint: String,
        token: String,
        body: ByteArray,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        tags: Map<String, Any?> = emptyMap(),
        cancellation: ToolCancellation? = null,
    ): UploadResult {
        if (cancellation?.isCancelled() == true) return UploadResult.Cancelled
        val gzipped = gzip(body)
        if (gzipped.size > TelemetryPayload.MAX_GZIP_BYTES) {
            return UploadResult.TooLarge
        }
        val normalized = TelemetryPreferences.normalizeEndpoint(endpoint)
        val target = parseHttps(normalized) ?: return UploadResult.Failed(false, "endpoint is not https")
        val preferred = preferredPath(context)
        val order = if (preferred == PATH_VPN) listOf(PATH_VPN, PATH_DIRECT) else listOf(PATH_DIRECT, PATH_VPN)
        var last: UploadResult = UploadResult.Failed(true, "not sent")
        order.forEachIndexed { index, path ->
            if (cancellation?.isCancelled() == true) return UploadResult.Cancelled
            // The non-preferred path gets a shorter connect timeout so a blocked
            // route does not add a full 15 s to every upload.
            val connectTimeout = if (index == 0 && preferred != null) connectTimeoutMs else minOf(connectTimeoutMs, SECONDARY_CONNECT_TIMEOUT_MS)
            val started = System.currentTimeMillis()
            val result = try {
                if (path == PATH_DIRECT) {
                    execute(target, token, gzipped, connectTimeout, readTimeoutMs, cancellation)
                } else {
                    postDirect(normalized, token, gzipped, connectTimeout, readTimeoutMs, cancellation)
                }
            } catch (e: Exception) {
                if (cancellation?.isCancelled() == true) {
                    UploadResult.Cancelled
                } else {
                    UploadResult.Failed(true, describeFailure(e))
                }
            }
            val elapsed = System.currentTimeMillis() - started
            recordAttempt(path, index + 1, result, elapsed, gzipped.size, tags)
            last = result
            val definitive = result is UploadResult.Success ||
                result is UploadResult.Unauthorized ||
                result is UploadResult.TooLarge ||
                result is UploadResult.Cancelled ||
                (result is UploadResult.Failed && result.detail.startsWith("HTTP "))
            if (result is UploadResult.Success && path != preferred) {
                rememberPath(context, path)
            }
            if (definitive) return result
            Log.w(TAG, "$path upload failed after ${elapsed}ms (${(result as UploadResult.Failed).detail})")
        }
        return last
    }

    private fun recordAttempt(
        path: String,
        hop: Int,
        result: UploadResult,
        elapsedMs: Long,
        gzippedBytes: Int,
        tags: Map<String, Any?>,
    ) {
        val fields = LinkedHashMap<String, Any?>()
        fields["path"] = path
        fields["hop"] = hop
        fields["ms"] = elapsedMs
        fields["gz_bytes"] = gzippedBytes
        fields.putAll(tags)
        when (result) {
            UploadResult.Success -> TelemetryEventLog.record(context, "telemetry_upload_ok", fields)
            UploadResult.Unauthorized -> TelemetryEventLog.record(context, "telemetry_upload_failed", fields + ("status" to 401))
            UploadResult.TooLarge -> TelemetryEventLog.record(context, "telemetry_upload_failed", fields + ("status" to 413))
            UploadResult.Cancelled -> TelemetryEventLog.record(context, "telemetry_upload_cancelled", fields)
            is UploadResult.Failed -> {
                val status = Regex("^HTTP (\\d+)").find(result.detail)?.groupValues?.get(1)?.toIntOrNull()
                TelemetryEventLog.record(
                    context,
                    "telemetry_upload_failed",
                    fields + mapOf("status" to status, "error" to errorClass(result.detail), "detail" to TelemetryEvents.scrub(result.detail).take(120)),
                )
            }
        }
    }

    private fun errorClass(detail: String): String {
        val d = detail.lowercase()
        return when {
            d.startsWith("http ") -> "http"
            d.startsWith("dns") -> "dns"
            d.startsWith("timeout") -> "timeout"
            d.startsWith("tls") -> "tls"
            "refused" in d -> "refused"
            "reset" in d -> "reset"
            "unreachable" in d || "no route" in d -> "unreachable"
            "no http response" in d -> "no_response"
            else -> "other"
        }
    }

    private fun postDirect(
        url: String,
        token: String,
        gzipped: ByteArray,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        cancellation: ToolCancellation?,
    ): UploadResult {
        val connection = (URL(url).openConnection() as HttpsURLConnection).apply {
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            requestMethod = "POST"
            doOutput = true
            instanceFollowRedirects = false
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Content-Encoding", "gzip")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "WonderSing")
        }
        cancellation?.onCancel { runCatching { connection.disconnect() } }
        try {
            if (cancellation?.isCancelled() == true) return UploadResult.Cancelled
            connection.outputStream.use { it.write(gzipped) }
            if (cancellation?.isCancelled() == true) return UploadResult.Cancelled
            val status = connection.responseCode
            Log.i(TAG, "upload HTTP $status via default network (${gzipped.size} gzipped bytes)")
            return mapStatus(status)
        } finally {
            connection.disconnect()
        }
    }

    private fun mapStatus(status: Int): UploadResult {
        if (status < 100) {
            return UploadResult.Failed(true, "no HTTP response")
        }
        return when (status) {
            HttpURLConnection.HTTP_OK -> UploadResult.Success
            HttpURLConnection.HTTP_UNAUTHORIZED -> UploadResult.Unauthorized
            413 -> UploadResult.TooLarge
            408, 429 -> UploadResult.Failed(true, "HTTP $status")
            in 500..599 -> UploadResult.Failed(true, "HTTP $status")
            else -> UploadResult.Failed(false, "HTTP $status")
        }
    }

    private fun describeFailure(error: Exception): String = when (error) {
        is UnknownHostException -> "DNS: ${error.message ?: "unknown host"}"
        is SocketTimeoutException -> "timeout: ${error.message ?: "timed out"}"
        is SSLException -> "TLS: ${error.message ?: "handshake failed"}"
        else -> error.message ?: error.javaClass.simpleName
    }

    private fun execute(
        target: HttpsTarget,
        token: String,
        gzipped: ByteArray,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        cancellation: ToolCancellation?,
    ): UploadResult {
        if (cancellation?.isCancelled() == true) return UploadResult.Cancelled
        val network = underlyingNetwork()
        val address = resolve(target.host, network)
        val socket = Socket()
        cancellation?.onCancel { runCatching { socket.close() } }
        try {
            runCatching { protect?.protect(socket) }
            if (network != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                runCatching { network.bindSocket(socket) }
                    .onFailure { Log.d(TAG, "bindSocket failed: ${it.message}") }
            }
            socket.soTimeout = readTimeoutMs
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(address, target.port), connectTimeoutMs)
            if (cancellation?.isCancelled() == true) return UploadResult.Cancelled
            val ssl = handshake(socket, target.host, target.port)
            cancellation?.onCancel { runCatching { ssl.close() } }
            try {
                if (cancellation?.isCancelled() == true) return UploadResult.Cancelled
                val header = buildString {
                    append("POST ").append(target.path).append(" HTTP/1.1\r\n")
                    append("Host: ").append(hostHeader(target)).append("\r\n")
                    append("Authorization: Bearer ").append(token).append("\r\n")
                    append("Content-Type: application/json; charset=utf-8\r\n")
                    append("Content-Encoding: gzip\r\n")
                    append("Accept: application/json\r\n")
                    append("Content-Length: ").append(gzipped.size).append("\r\n")
                    append("Connection: close\r\n")
                    append("User-Agent: sing-box-wonder\r\n")
                    append("\r\n")
                }
                val output = ssl.outputStream
                output.write(header.toByteArray(Charsets.US_ASCII))
                output.write(gzipped)
                output.flush()
                if (cancellation?.isCancelled() == true) return UploadResult.Cancelled
                val (status, _) = readResponse(ssl.inputStream)
                Log.i(TAG, "upload HTTP $status via protected socket (${gzipped.size} gzipped bytes)")
                return mapStatus(status)
            } finally {
                runCatching { ssl.close() }
            }
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun handshake(socket: Socket, host: String, port: Int): SSLSocket {
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, null, SecureRandom())
        val ssl = sslContext.socketFactory.createSocket(socket, host, port, true) as SSLSocket
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val params = ssl.sslParameters
            params.endpointIdentificationAlgorithm = "HTTPS"
            if (!looksNumeric(host)) {
                params.serverNames = listOf(SNIHostName(host))
            }
            ssl.sslParameters = params
        }
        ssl.startHandshake()
        if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, ssl.session)) {
            runCatching { ssl.close() }
            throw java.io.IOException("TLS hostname verification failed")
        }
        return ssl
    }

    private fun resolve(host: String, network: Network?): InetAddress {
        val resolved = if (network != null) {
            network.getAllByName(host)
        } else {
            InetAddress.getAllByName(host)
        }
        return resolved.firstOrNull() ?: throw java.net.UnknownHostException(host)
    }

    private fun underlyingNetwork(): Network? {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null
        val networks = runCatching { manager.allNetworks }.getOrNull() ?: return null
        return networks.firstOrNull { network ->
            val caps = manager.getNetworkCapabilities(network) ?: return@firstOrNull false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
    }

    private fun readResponse(input: InputStream): Pair<Int, String> {
        val headerBytes = ByteArrayOutputStream()
        var matched = 0
        val delimiter = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
        while (headerBytes.size() < MAX_HEADER_BYTES) {
            val value = input.read()
            if (value < 0) break
            headerBytes.write(value)
            if (value.toByte() == delimiter[matched]) {
                matched++
                if (matched == delimiter.size) break
            } else {
                matched = if (value.toByte() == delimiter[0]) 1 else 0
            }
        }
        val headerText = headerBytes.toString(Charsets.ISO_8859_1.name())
        val status = headerText.lineSequence().firstOrNull()
            ?.split(' ')
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: -1
        val body = ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        while (body.size() < MAX_BODY_BYTES) {
            val count = input.read(buffer)
            if (count < 0) break
            val room = MAX_BODY_BYTES - body.size()
            body.write(buffer, 0, minOf(count, room))
            if (count > room) break
        }
        return status to body.toString(Charsets.UTF_8.name())
    }

    private data class HttpsTarget(val host: String, val port: Int, val path: String)

    private fun parseHttps(endpoint: String): HttpsTarget? {
        val uri = runCatching { URI(endpoint.trim()) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        val host = uri.host ?: return null
        if (host.isBlank()) return null
        val port = if (uri.port == -1) 443 else uri.port
        val path = buildString {
            append(uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/")
            if (!uri.rawQuery.isNullOrEmpty()) {
                append('?')
                append(uri.rawQuery)
            }
        }
        if (path.contains('\r') || path.contains('\n') || host.contains('\r') || host.contains('\n')) {
            return null
        }
        return HttpsTarget(host, port, path)
    }

    private fun hostHeader(target: HttpsTarget): String {
        val formatted = if (target.host.contains(':')) "[${target.host}]" else target.host
        return if (target.port == 443) formatted else "$formatted:${target.port}"
    }

    private fun looksNumeric(host: String): Boolean {
        return host.all { it.isDigit() || it == '.' } || host.contains(':')
    }

    private fun gzip(body: ByteArray): ByteArray {
        val output = ByteArrayOutputStream(body.size)
        GZIPOutputStream(output).use { it.write(body) }
        return output.toByteArray()
    }

    private companion object {
        const val TAG = "Telemetry"
        const val PATH_DIRECT = "direct"
        const val PATH_VPN = "vpn"
        const val SECONDARY_CONNECT_TIMEOUT_MS = 6_000
        const val PREFS = "telemetry_upload_path"

        @Volatile
        var cachedPath: String? = null

        fun preferredPath(context: Context): String? {
            cachedPath?.let { return it }
            return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("preferred", null)
                ?.also { cachedPath = it }
        }

        fun rememberPath(context: Context, path: String) {
            cachedPath = path
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("preferred", path).apply()
        }
        const val MAX_HEADER_BYTES = 64 * 1024
        const val MAX_BODY_BYTES = 8 * 1024
    }
}

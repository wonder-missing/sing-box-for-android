package io.nekohasekai.sfa.telemetry

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.security.SecureRandom
import java.util.zip.GZIPOutputStream
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

internal sealed class UploadResult {
    data object Success : UploadResult()

    data object Unauthorized : UploadResult()

    data object TooLarge : UploadResult()

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
    fun post(
        endpoint: String,
        token: String,
        body: ByteArray,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): UploadResult {
        val gzipped = gzip(body)
        if (gzipped.size > TelemetryPayload.MAX_GZIP_BYTES) {
            return UploadResult.TooLarge
        }
        val target = parseHttps(endpoint) ?: return UploadResult.Failed(false, "endpoint is not https")
        return try {
            val status = execute(target, token, gzipped, connectTimeoutMs, readTimeoutMs)
            when (status) {
                200 -> UploadResult.Success
                401 -> UploadResult.Unauthorized
                413 -> UploadResult.TooLarge
                408, 429 -> UploadResult.Failed(true, "HTTP $status")
                in 500..599 -> UploadResult.Failed(true, "HTTP $status")
                else -> UploadResult.Failed(false, "HTTP $status")
            }
        } catch (e: Exception) {
            UploadResult.Failed(true, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun execute(
        target: HttpsTarget,
        token: String,
        gzipped: ByteArray,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): Int {
        val network = underlyingNetwork()
        val address = resolve(target.host, network)
        val socket = Socket()
        try {
            runCatching { protect?.protect(socket) }
            if (network != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                runCatching { network.bindSocket(socket) }
                    .onFailure { Log.d(TAG, "bindSocket failed: ${it.message}") }
            }
            socket.soTimeout = readTimeoutMs
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(address, target.port), connectTimeoutMs)
            val ssl = handshake(socket, target.host, target.port)
            try {
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
                val (status, _) = readResponse(ssl.inputStream)
                Log.d(TAG, "upload HTTP $status (${gzipped.size} gzipped bytes)")
                return status
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
        const val MAX_HEADER_BYTES = 64 * 1024
        const val MAX_BODY_BYTES = 8 * 1024
    }
}

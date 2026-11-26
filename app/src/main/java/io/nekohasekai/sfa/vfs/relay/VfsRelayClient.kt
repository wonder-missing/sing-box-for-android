package io.nekohasekai.sfa.vfs.relay

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** New-path close codes. The old VfsCloseCodes.isTerminal must not be reused. */
object RelayCloseCodes {
    const val SUSPENDED = 4401
    const val DEVICE_MISMATCH_HANDSHAKE = 4403
    const val REPLACED = 4404
    const val KEY_EXPIRED = 4412
    const val KEY_ROTATED = 4413
    const val REVOKED = 4414
    const val RATE_LIMITED = 4429
    const val GOING_AWAY = 1001
    const val OVERLOAD = 1013

    fun isPermanent(code: Int): Boolean = code == REVOKED || code == DEVICE_MISMATCH_HANDSHAKE

    fun isReconnect(code: Int): Boolean =
        code == REPLACED || code == KEY_EXPIRED || code == KEY_ROTATED || code == 1000

    fun isSuspend(code: Int): Boolean = code == SUSPENDED
}

fun relayProviderUrl(canonicalBase: String): String? {
    val base = canonicalBase.trim().removeSuffix("/")
    return when {
        base.startsWith("https://") -> "wss://${base.removePrefix("https://")}/v1/provider"
        base.startsWith("http://") -> null
        else -> null
    }
}

interface VfsRelayClientListener {
    fun onRelayOpen()

    fun onRelayMessage(text: String)

    fun onRelayClosed(
        code: Int,
        reason: String,
    )

    fun onRelayFailure(message: String)
}

/**
 * One outbound Provider WebSocket; multiple Links share it via register/pair
 * controls. Application-level ping/pong (not protocol pings) is owned by the
 * runtime so it can observe liveness and reconnect.
 */
class VfsRelayClient(
    private val relayUrl: String,
    private val providerKey: String,
    private val listener: VfsRelayClientListener,
    private val client: OkHttpClient = defaultClient(),
) {
    private val socket = AtomicReference<WebSocket?>(null)

    @Volatile
    private var opened = false

    fun start() {
        val request =
            Request.Builder()
                .url(relayUrl)
                .header("Authorization", "Bearer $providerKey")
                .header("Accept", "*/*")
                .build()
        val created =
            client.newWebSocket(
                request,
                object : WebSocketListener() {
                    override fun onOpen(
                        webSocket: WebSocket,
                        response: Response,
                    ) {
                        opened = true
                        socket.set(webSocket)
                        listener.onRelayOpen()
                    }

                    override fun onMessage(
                        webSocket: WebSocket,
                        text: String,
                    ) {
                        listener.onRelayMessage(text)
                    }

                    override fun onClosed(
                        webSocket: WebSocket,
                        code: Int,
                        reason: String,
                    ) {
                        opened = false
                        socket.compareAndSet(webSocket, null)
                        listener.onRelayClosed(code, reason)
                    }

                    override fun onFailure(
                        webSocket: WebSocket,
                        t: Throwable,
                        response: Response?,
                    ) {
                        opened = false
                        socket.compareAndSet(webSocket, null)
                        val status = response?.code
                        val body = runCatching { response?.body?.string() }.getOrNull()
                        listener.onRelayFailure(
                            buildString {
                                if (status != null) append("HTTP $status")
                                if (!body.isNullOrBlank()) {
                                    if (isNotEmpty()) append(": ")
                                    append(body.take(160))
                                }
                                if (isEmpty()) append(t.message ?: "WebSocket failed")
                            },
                        )
                    }
                },
            )
        socket.compareAndSet(null, created)
    }

    fun send(text: String): Boolean = socket.get()?.send(text) == true

    fun close(
        code: Int,
        reason: String,
    ) {
        opened = false
        socket.getAndSet(null)?.close(code, reason)
    }

    fun isOpen(): Boolean = opened && socket.get() != null

    companion object {
        fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
    }
}

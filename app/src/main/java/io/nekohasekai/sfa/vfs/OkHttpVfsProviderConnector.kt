package io.nekohasekai.sfa.vfs

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

data class VfsSessionClose(
    val code: Int?,
    val reason: String?,
)

/**
 * Real provider transport. The app answers application-level ping frames in
 * [VfsProviderProtocol]; OkHttp's protocol-level pong is not a liveness signal.
 */
class OkHttpVfsProviderConnector(
    private val sourceFactory: (Set<VfsScope>) -> VirtualDirectorySource,
    private val client: OkHttpClient = defaultClient(),
) : VfsProviderConnector {
    override suspend fun connect(request: VfsProviderConnectRequest): VfsProviderSession {
        val hello = CompletableDeferred<VfsServerHello>()
        val closeEvent = MutableStateFlow<VfsSessionClose?>(null)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val socket = AtomicReference<WebSocket?>(null)
        val protocol =
            VfsProviderProtocol(
                source = sourceFactory(request.scope),
                send = { text -> socket.get()?.send(text) == true },
                onHello = { hello.complete(it) },
                onError = { error ->
                    hello.completeExceptionally(error)
                    socket.get()?.close(1008, "protocol error")
                },
            )

        val requestBuilder =
            Request.Builder()
                .url(request.url)
                .header("Authorization", "Bearer ${request.token}")
                .header("X-WonderSing-Link", request.linkId)
                .header("X-WonderSing-Device", request.deviceId)
        request.resumeToken?.takeIf { it.isNotBlank() }?.let {
            requestBuilder.header("X-WonderSing-Resume", it)
        }
        val httpRequest = requestBuilder.build()

        val webSocket =
            client.newWebSocket(
                httpRequest,
                object : WebSocketListener() {
                    override fun onOpen(
                        webSocket: WebSocket,
                        response: Response,
                    ) {
                        socket.set(webSocket)
                        if (!protocol.sendClientHello()) {
                            hello.completeExceptionally(
                                VfsConnectException("VFS_CONNECT_FAILED", "Could not send the provider hello."),
                            )
                            webSocket.close(1008, "hello send failed")
                        }
                    }

                    override fun onMessage(
                        webSocket: WebSocket,
                        text: String,
                    ) {
                        scope.launch { protocol.onMessage(text) }
                    }

                    override fun onClosed(
                        webSocket: WebSocket,
                        code: Int,
                        reason: String,
                    ) {
                        closeEvent.value = VfsSessionClose(code, reason)
                        if (!hello.isCompleted) {
                            hello.completeExceptionally(
                                VfsConnectException(
                                    "VFS_CONNECT_FAILED",
                                    "The provider closed before the handshake: $code $reason",
                                ),
                            )
                        }
                    }

                    override fun onFailure(
                        webSocket: WebSocket,
                        t: Throwable,
                        response: Response?,
                    ) {
                        closeEvent.value = VfsSessionClose(response?.code, t.message)
                        if (!hello.isCompleted) {
                            hello.completeExceptionally(mapFailure(response, t))
                        }
                    }
                },
            )
        socket.set(webSocket)

        return try {
            val serverHello = withTimeout(HELLO_TIMEOUT_MS) { hello.await() }
            OkHttpVfsProviderSession(serverHello, webSocket, scope, closeEvent)
        } catch (error: TimeoutCancellationException) {
            webSocket.close(1008, "hello timeout")
            scope.cancel()
            throw VfsConnectException(
                "VFS_CONNECT_FAILED",
                "The VFS provider did not complete the handshake in time.",
            )
        } catch (error: VfsConnectException) {
            webSocket.close(1008, "handshake failed")
            scope.cancel()
            throw error
        }
    }

    private fun mapFailure(
        response: Response?,
        error: Throwable,
    ): VfsConnectException {
        val code =
            when (response?.code) {
                401 -> "VFS_UNAUTHORIZED"
                403 -> "VFS_DEVICE_MISMATCH"
                404 -> "VFS_LINK_INVALID"
                409 -> "VFS_ALREADY_CONNECTED"
                410 -> "VFS_LINK_EXPIRED"
                429 -> "VFS_CONNECT_FAILED"
                else -> "VFS_CONNECT_FAILED"
            }
        val detail =
            response?.let { "HTTP ${it.code}" }
                ?: error.message
                ?: "Could not connect to the VFS provider."
        return VfsConnectException(code, detail)
    }

    private class OkHttpVfsProviderSession(
        override val hello: VfsServerHello,
        private val webSocket: WebSocket,
        private val scope: CoroutineScope,
        closeEvents: MutableStateFlow<VfsSessionClose?>,
    ) : VfsProviderSession {
        val closeEvent: StateFlow<VfsSessionClose?> = closeEvents.asStateFlow()

        override fun close(
            code: Int,
            reason: String,
        ) {
            webSocket.close(code, reason)
            scope.cancel()
        }
    }

    companion object {
        private const val HELLO_TIMEOUT_MS = 15_000L

        private fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .pingInterval(0, TimeUnit.MILLISECONDS)
                .build()
    }
}

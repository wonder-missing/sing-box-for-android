package io.nekohasekai.sfa.vfs

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

class OkHttpVirtualDirectoryTransport(
    private val client: OkHttpClient = defaultClient(),
) : VirtualDirectoryTransport {
    @Volatile
    private var socket: WebSocket? = null

    override fun connect(
        url: String,
        token: String?,
        listener: VirtualDirectoryTransport.Listener,
    ) {
        val request =
            Request.Builder()
                .url(url)
                .apply {
                    if (!token.isNullOrBlank()) header("Authorization", "Bearer $token")
                }
                .build()
        socket =
            client.newWebSocket(
                request,
                object : WebSocketListener() {
                    override fun onOpen(
                        webSocket: WebSocket,
                        response: Response,
                    ) {
                        listener.onOpen()
                    }

                    override fun onMessage(
                        webSocket: WebSocket,
                        text: String,
                    ) {
                        listener.onMessage(text)
                    }

                    override fun onClosing(
                        webSocket: WebSocket,
                        code: Int,
                        reason: String,
                    ) {
                        webSocket.close(1000, null)
                    }

                    override fun onClosed(
                        webSocket: WebSocket,
                        code: Int,
                        reason: String,
                    ) {
                        listener.onClosed(code, reason)
                    }

                    override fun onFailure(
                        webSocket: WebSocket,
                        t: Throwable,
                        response: Response?,
                    ) {
                        listener.onFailure(t.message ?: "Connection failed")
                    }
                },
            )
    }

    override fun send(text: String): Boolean = socket?.send(text) == true

    override fun close() {
        socket?.close(1000, "App stopped")
        socket = null
    }

    companion object {
        private fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .pingInterval(30, TimeUnit.SECONDS)
                .build()
    }
}

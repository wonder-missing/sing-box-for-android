package io.nekohasekai.sfa.vfs

/**
 * Translates the provider WebSocket frames into the existing file source. The
 * app process answers ping frames itself; OkHttp's protocol-level pong is not
 * used as a liveness signal.
 */
class VfsProviderProtocol(
    source: VirtualDirectorySource,
    private val send: (String) -> Boolean,
    private val onHello: (VfsServerHello) -> Unit,
    private val onError: (VfsConnectException) -> Unit = {},
) {
    private val session = VirtualDirectorySession(source)

    @Volatile
    private var helloSeen = false

    fun sendClientHello(): Boolean = send(VfsProtocol.clientHello())

    suspend fun onMessage(text: String) {
        val incoming = VfsProtocol.parseIncoming(text) ?: return
        when (incoming) {
            is VfsIncoming.ServerHello -> onServerHello(incoming)
            is VfsIncoming.Ping -> {
                send(VfsProtocol.pong(incoming.id))
            }

            else -> {
                if (!helloSeen) return
                val reply = session.respond(incoming) ?: return
                if (!send(reply.text)) {
                    onError(
                        VfsConnectException(
                            "VFS_CONNECT_FAILED",
                            "Could not send a provider response.",
                        ),
                    )
                }
            }
        }
    }

    private fun onServerHello(hello: VfsIncoming.ServerHello) {
        if (helloSeen) return
        if (hello.version != VfsProtocol.VERSION) {
            onError(
                VfsConnectException(
                    "VFS_CONNECT_FAILED",
                    "The server speaks provider protocol ${hello.version}; this app supports ${VfsProtocol.VERSION}.",
                ),
            )
            return
        }
        helloSeen = true
        onHello(
            VfsServerHello(
                server = hello.server?.takeIf { it.isNotBlank() } ?: "vfsd",
                resumeToken = hello.resumeToken,
                expiresAt = hello.expiresAt,
            ),
        )
    }
}

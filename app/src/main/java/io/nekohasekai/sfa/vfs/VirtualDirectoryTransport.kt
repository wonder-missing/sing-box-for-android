package io.nekohasekai.sfa.vfs

/** Test seam around the outbound WebSocket. Callbacks arrive on transport threads. */
interface VirtualDirectoryTransport {
    interface Listener {
        fun onOpen()

        fun onMessage(text: String)

        fun onClosed(
            code: Int,
            reason: String,
        )

        fun onFailure(message: String)
    }

    fun connect(
        url: String,
        token: String?,
        listener: Listener,
    )

    fun send(text: String): Boolean

    fun close()
}

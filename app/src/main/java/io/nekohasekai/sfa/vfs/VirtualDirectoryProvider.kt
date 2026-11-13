package io.nekohasekai.sfa.vfs

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class VirtualDirectoryConfig(
    val url: String,
    val token: String?,
)

sealed interface VirtualDirectoryState {
    data object Disabled : VirtualDirectoryState

    data object Connecting : VirtualDirectoryState

    data class Connected(
        val server: String,
        val since: Long,
    ) : VirtualDirectoryState

    data class Error(
        val message: String,
        val retryInMs: Long? = null,
    ) : VirtualDirectoryState
}

data class VirtualDirectoryActivity(
    val connectedAt: Long? = null,
    val lastRequestAt: Long? = null,
    val lastPath: String? = null,
    val lastKind: String? = null,
    val requestCount: Long = 0,
    val bytesRead: Long = 0,
    val lastError: String? = null,
)

/**
 * Owns one outbound provider connection. A new connection replaces the old
 * one; disconnects retry with exponential backoff until [stop] or [fail].
 */
class VirtualDirectoryProvider(
    private val source: VirtualDirectorySource,
    private val transport: VirtualDirectoryTransport,
    private val scope: CoroutineScope,
    private val backoff: VirtualDirectoryBackoff = VirtualDirectoryBackoff(),
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val session = VirtualDirectorySession(source)
    private val _state = MutableStateFlow<VirtualDirectoryState>(VirtualDirectoryState.Disabled)
    val state: StateFlow<VirtualDirectoryState> = _state.asStateFlow()
    private val _activity = MutableStateFlow(VirtualDirectoryActivity())
    val activity: StateFlow<VirtualDirectoryActivity> = _activity.asStateFlow()

    private var connectionId = 0
    private var config: VirtualDirectoryConfig? = null
    private var reconnectJob: Job? = null

    fun start(config: VirtualDirectoryConfig) {
        synchronized(lock) {
            this.config = config
            reconnectJob?.cancel()
            reconnectJob = null
            connectionId += 1
            transport.close()
            backoff.reset()
            _activity.value = VirtualDirectoryActivity()
            _state.value = VirtualDirectoryState.Connecting
            connectLocked(connectionId, config)
        }
    }

    fun stop() {
        synchronized(lock) {
            config = null
            reconnectJob?.cancel()
            reconnectJob = null
            connectionId += 1
            transport.close()
            _state.value = VirtualDirectoryState.Disabled
        }
    }

    /** A local configuration error: stop retrying until the settings change. */
    fun fail(message: String) {
        synchronized(lock) {
            config = null
            reconnectJob?.cancel()
            reconnectJob = null
            connectionId += 1
            transport.close()
            _state.value = VirtualDirectoryState.Error(message)
        }
    }

    private fun connectLocked(
        id: Int,
        config: VirtualDirectoryConfig,
    ) {
        try {
            transport.connect(config.url, config.token, listener(id))
        } catch (error: Exception) {
            scheduleReconnectLocked(id, "Could not connect: ${error.message ?: "unknown error"}")
        }
    }

    private fun listener(id: Int): VirtualDirectoryTransport.Listener =
        object : VirtualDirectoryTransport.Listener {
            override fun onOpen() {
                if (!send(id, VfsProtocol.clientHello())) {
                    disconnect(id, "Could not send the handshake.")
                }
            }

            override fun onMessage(text: String) {
                val message = VfsProtocol.parseIncoming(text) ?: return
                when (message) {
                    is VfsIncoming.ServerHello -> onServerHello(id, message)
                    else -> onRequest(id, message)
                }
            }

            override fun onClosed(
                code: Int,
                reason: String,
            ) {
                val suffix = if (reason.isBlank()) "." else ": ${reason.take(200)}"
                disconnect(id, "The endpoint closed the connection$suffix")
            }

            override fun onFailure(message: String) {
                disconnect(id, "Connection failed: ${message.take(300)}")
            }
        }

    private fun onServerHello(
        id: Int,
        hello: VfsIncoming.ServerHello,
    ) {
        synchronized(lock) {
            if (id != connectionId) return
            if (hello.version != VfsProtocol.VERSION) {
                scheduleReconnectLocked(
                    id,
                    "The endpoint speaks protocol version ${hello.version}; this app supports ${VfsProtocol.VERSION}.",
                )
                return
            }
            backoff.reset()
            val connectedAt = now()
            _state.value =
                VirtualDirectoryState.Connected(
                    server = hello.server?.takeIf { it.isNotBlank() } ?: "vfsd",
                    since = connectedAt,
                )
            _activity.update { it.copy(connectedAt = connectedAt) }
        }
    }

    private fun onRequest(
        id: Int,
        message: VfsIncoming,
    ) {
        val active = synchronized(lock) { id == connectionId }
        if (!active) return
        recordRequest(message)
        scope.launch {
            val reply = session.respond(message) ?: return@launch
            if (!send(id, reply.text)) {
                disconnect(id, "Could not send a response to the endpoint.")
                return@launch
            }
            _activity.update {
                it.copy(
                    bytesRead = it.bytesRead + reply.bytesRead,
                    lastError = reply.errorCode ?: it.lastError,
                )
            }
        }
    }

    private fun recordRequest(message: VfsIncoming) {
        val (kind, path) =
            when (message) {
                is VfsIncoming.Stat -> "stat" to message.path
                is VfsIncoming.List -> "list" to message.path
                is VfsIncoming.Read -> "read" to message.path
                is VfsIncoming.ServerHello -> return
            }
        _activity.update {
            it.copy(
                lastRequestAt = now(),
                lastPath = path,
                lastKind = kind,
                requestCount = it.requestCount + 1,
            )
        }
    }

    private fun send(
        id: Int,
        text: String,
    ): Boolean =
        synchronized(lock) {
            if (id != connectionId) {
                false
            } else {
                try {
                    transport.send(text)
                } catch (_: Exception) {
                    false
                }
            }
        }

    private fun disconnect(
        id: Int,
        message: String,
    ) {
        synchronized(lock) {
            scheduleReconnectLocked(id, message)
        }
    }

    private fun scheduleReconnectLocked(
        id: Int,
        message: String,
    ) {
        if (id != connectionId) return
        connectionId += 1
        reconnectJob?.cancel()
        reconnectJob = null
        transport.close()
        val delayMs = backoff.nextDelayMs()
        _state.value = VirtualDirectoryState.Error(message, delayMs)
        reconnectJob =
            scope.launch {
                try {
                    sleeper(delayMs)
                } catch (error: CancellationException) {
                    throw error
                }
                synchronized(lock) {
                    val nextConfig = config ?: return@launch
                    connectionId += 1
                    val nextId = connectionId
                    _state.value = VirtualDirectoryState.Connecting
                    connectLocked(nextId, nextConfig)
                }
            }
    }
}

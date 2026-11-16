package io.nekohasekai.sfa.vfs

import android.content.Context
import io.nekohasekai.sfa.utils.AppLifecycleObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Process-wide VFS handles shared by Conductor, Settings and the status bar.
 * The trust store is created once so every screen sees the same records.
 */
object VfsRuntime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<VfsConnectionState>(VfsConnectionState.Idle)
    val state: StateFlow<VfsConnectionState> = _state.asStateFlow()

    private val _uploadBytes = MutableStateFlow(0L)
    val uploadBytes: StateFlow<Long> = _uploadBytes.asStateFlow()
    private val uploadLock = Any()
    private var pendingUploadBytes = 0L
    private var lastUploadPublishNanos = 0L

    private val trustLock = Any()

    @Volatile
    private var trust: VfsTrustStore? = null

    @Volatile
    private var manager: VfsConnectionManager? = null

    private var collectJob: Job? = null
    private var lifecycleJob: Job? = null
    private var foregroundJob: Job? = null

    fun trustStore(context: Context): VfsTrustStore =
        synchronized(trustLock) {
            trust
                ?: VfsTrustStore(
                    persistence =
                        FileVfsTrustPersistence(
                            VfsPrivateFiles.trustFile(context.applicationContext.noBackupFilesDir),
                        ),
                ).also { trust = it }
        }

    fun trustStoreOrNull(): VfsTrustStore? = trust

    @Synchronized
    fun attach(manager: VfsConnectionManager) {
        if (this.manager === manager) return
        val previous = this.manager
        this.manager = manager
        collectJob?.cancel()
        collectJob =
            scope.launch {
                var wasConnected = false
                manager.state.collect { state ->
                    val connected = state is VfsConnectionState.Connected
                    if (connected != wasConnected) resetUploadBytes()
                    wasConnected = connected
                    _state.value = state
                }
            }
        lifecycleJob?.cancel()
        foregroundJob?.cancel()
        lifecycleJob =
            scope.launch {
                AppLifecycleObserver.isForeground.collect { foreground ->
                    // Never block the lifecycle collector on handshake/backoff.
                    // A later background event must still be able to stop it.
                    foregroundJob?.cancel()
                    foregroundJob =
                        scope.launch {
                            if (foreground) {
                                runCatching { manager.reconnectLast() }
                            } else {
                                runCatching { manager.disconnect(VfsCloseCodes.BACKGROUND, "background") }
                            }
                        }
                }
            }
        if (previous != null && previous !== manager) {
            scope.launch {
                runCatching { previous.disconnect(VfsCloseCodes.BACKGROUND, "replaced") }
            }
        }
    }

    fun recordUploadBytes(bytes: Long) {
        if (bytes <= 0) return
        synchronized(uploadLock) {
            pendingUploadBytes += bytes
            val now = System.nanoTime()
            // A status-bar counter must not recompose for every FUSE chunk.
            if (now - lastUploadPublishNanos < 250_000_000L && pendingUploadBytes < 64L * 1024L) {
                return
            }
            _uploadBytes.value = _uploadBytes.value + pendingUploadBytes
            pendingUploadBytes = 0L
            lastUploadPublishNanos = now
        }
    }

    private fun resetUploadBytes() {
        synchronized(uploadLock) {
            pendingUploadBytes = 0L
            lastUploadPublishNanos = 0L
            _uploadBytes.value = 0L
        }
    }

    fun disconnect() {
        scope.launch {
            manager?.disconnect(VfsCloseCodes.BACKGROUND, "user")
            manager?.forgetLastLink()
        }
    }

    fun revoke(server: String) {
        trustStoreOrNull()?.revoke(server)
        closeIfActive(server)
    }

    fun revoke(
        server: String,
        scope: Set<VfsScope>,
    ) {
        trustStoreOrNull()?.revoke(server, scope)
        closeIfActive(server)
    }

    private fun closeIfActive(server: String) {
        val activeServer = (_state.value as? VfsConnectionState.Connected)?.server
        if (activeServer == server) {
            scope.launch { manager?.disconnect(VfsCloseCodes.REVOKED, "revoked") }
        }
    }
}

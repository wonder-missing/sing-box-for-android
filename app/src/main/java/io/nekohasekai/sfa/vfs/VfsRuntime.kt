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
import kotlinx.coroutines.flow.update
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

    private val trustLock = Any()

    @Volatile
    private var trust: VfsTrustStore? = null

    @Volatile
    private var manager: VfsConnectionManager? = null

    private var collectJob: Job? = null
    private var lifecycleJob: Job? = null

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
        this.manager = manager
        collectJob?.cancel()
        collectJob =
            scope.launch {
                var wasConnected = false
                manager.state.collect { state ->
                    val connected = state is VfsConnectionState.Connected
                    if (connected && !wasConnected) _uploadBytes.value = 0L
                    if (!connected && wasConnected) _uploadBytes.value = 0L
                    wasConnected = connected
                    _state.value = state
                }
            }
        lifecycleJob?.cancel()
        lifecycleJob =
            scope.launch {
                AppLifecycleObserver.isForeground.collect { foreground ->
                    if (foreground) {
                        runCatching { manager.reconnectLast() }
                    } else {
                        runCatching { manager.disconnect(VfsCloseCodes.BACKGROUND, "background") }
                    }
                }
            }
    }

    fun recordUploadBytes(bytes: Long) {
        if (bytes > 0) _uploadBytes.update { it + bytes }
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

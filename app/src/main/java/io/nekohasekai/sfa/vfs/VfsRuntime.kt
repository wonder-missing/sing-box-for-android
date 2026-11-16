package io.nekohasekai.sfa.vfs

import android.content.Context
import io.nekohasekai.sfa.agent.mcp.McpRuntimes
import io.nekohasekai.sfa.agent.mcp.McpTokenCipherFactory
import io.nekohasekai.sfa.bg.VfsSharingService
import io.nekohasekai.sfa.telemetry.TelemetryEventLog
import io.nekohasekai.sfa.utils.AppLifecycleObserver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/** The app-level VFS consent dialog. It is not Conductor's tool confirmation. */
data class VfsConsentUi(
    val id: String,
    val serverName: String,
    val server: String,
    val scope: List<VfsScope>,
)

/**
 * Process-wide VFS handles shared by Conductor, Settings and the status bar.
 * The connection manager lives here instead of a ViewModel so closing the
 * Activity does not tear down a Link that the sharing service keeps alive.
 */
object VfsRuntime {
    /** Without a foreground service, a backgrounded session gets this long to return. */
    private const val BACKGROUND_GRACE_MS = 90_000L
    private const val CONSENT_TIMEOUT_MS = 5L * 60L * 1000L

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

    private val managerLock = Any()

    @Volatile
    private var manager: VfsConnectionManager? = null

    @Volatile
    private var appContext: Context? = null

    /** True from the moment the sharing service is requested until it stops. */
    @Volatile
    private var sharingActive = false

    private var collectJob: Job? = null
    private var lifecycleJob: Job? = null
    private var foregroundJob: Job? = null
    private var backgroundJob: Job? = null

    private val consentLock = Any()
    private var pendingConsent: PendingConsent? = null
    private val _consent = MutableStateFlow<VfsConsentUi?>(null)
    val consent: StateFlow<VfsConsentUi?> = _consent.asStateFlow()

    private data class PendingConsent(
        val id: String,
        val deferred: CompletableDeferred<VfsConsentDecision>,
    )

    private val consentPort =
        object : VfsConsentPort {
            override suspend fun request(request: VfsConsentRequest): VfsConsentDecision {
                val pending =
                    PendingConsent(
                        id = UUID.randomUUID().toString().take(8),
                        deferred = CompletableDeferred(),
                    )
                synchronized(consentLock) {
                    if (pendingConsent != null) {
                        throw VfsConnectException(
                            "VFS_CONNECT_FAILED",
                            "Another VFS confirmation is already waiting for the user.",
                        )
                    }
                    pendingConsent = pending
                }
                _consent.value =
                    VfsConsentUi(
                        id = pending.id,
                        serverName = request.serverName,
                        server = request.server,
                        scope = request.scope.sortedBy { it.ordinal },
                    )
                return try {
                    withTimeoutOrNull(CONSENT_TIMEOUT_MS) { pending.deferred.await() }
                        ?: throw VfsConnectException(
                            "VFS_CONFIRMATION_TIMEOUT",
                            "The user did not decide within 5 minutes.",
                        )
                } finally {
                    clearConsent(pending.id)
                }
            }
        }

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

    /**
     * Returns the process-wide connection manager, creating it on first use.
     * Conductor and the sharing service must call this instead of constructing
     * their own manager, or a reopened Activity would replace the live session.
     */
    fun manager(context: Context): VfsConnectionManager {
        manager?.let { return it }
        return synchronized(managerLock) {
            manager?.let { return it }
            val app = context.applicationContext
            appContext = app
            val deviceIds =
                VfsDeviceIdStore(
                    VfsPrivateFiles.deviceIdFile(app.noBackupFilesDir),
                )
            val resumes =
                FileVfsResumeTokenStore(
                    VfsPrivateFiles.resumeFile(app.noBackupFilesDir),
                    McpTokenCipherFactory.create(),
                )
            val created =
                VfsConnectionManager(
                    resolveServer = { server ->
                        val config = McpRuntimes.get(app).resolveServerForVfs(server)
                        val providerUrl = VfsServerBase.providerUrl(server)
                        if (config != null && providerUrl != null) {
                            VfsServerTarget(
                                name = config.name,
                                providerUrl = providerUrl,
                                token = config.token,
                            )
                        } else {
                            null
                        }
                    },
                    consent = consentPort,
                    connector =
                        OkHttpVfsProviderConnector(
                            sourceFactory = { scopes ->
                                VfsScopedSource(AndroidVirtualDirectorySource(app), scopes)
                            },
                            onUploadBytes = ::recordUploadBytes,
                        ),
                    deviceId = { deviceIds.getOrCreate() },
                    resumeTokens = resumes,
                    trust = trustStore(app),
                    cooldowns = VfsCooldownStore(),
                    isForeground = { AppLifecycleObserver.isForeground.value },
                    backgroundSharing = { sharingActive },
                )
            manager = created
            observe(app, created)
            created
        }
    }

    private fun observe(
        context: Context,
        manager: VfsConnectionManager,
    ) {
        collectJob?.cancel()
        collectJob =
            scope.launch {
                var wasConnected = false
                manager.state.collect { state ->
                    val connected = state is VfsConnectionState.Connected
                    if (connected != wasConnected) resetUploadBytes()
                    wasConnected = connected
                    _state.value = state
                    syncSharingService(context, state)
                }
            }
        lifecycleJob?.cancel()
        foregroundJob?.cancel()
        backgroundJob?.cancel()
        lifecycleJob =
            scope.launch {
                AppLifecycleObserver.isForeground.collect { foreground ->
                    // Never block the lifecycle collector on handshake/backoff.
                    foregroundJob?.cancel()
                    backgroundJob?.cancel()
                    if (foreground) {
                        lifecycleEvent(context, "foreground")
                        foregroundJob =
                            scope.launch {
                                runCatching { manager.reconnectLast() }
                            }
                    } else {
                        scheduleBackgroundGrace(context, manager)
                    }
                }
            }
    }

    private fun syncSharingService(
        context: Context,
        state: VfsConnectionState,
    ) {
        val keep =
            VfsSharingPolicy.shouldRun(
                state = state,
                hasActiveConnection = manager?.hasActiveConnection == true,
            )
        if (keep) {
            backgroundJob?.cancel()
            if (!VfsSharingService.start(context)) {
                lifecycleEvent(context, "sharing_start_failed")
                val current = manager
                if (current != null && !AppLifecycleObserver.isForeground.value) {
                    scheduleBackgroundGrace(context, current)
                }
            }
        } else {
            VfsSharingService.stop(context)
        }
    }

    private fun scheduleBackgroundGrace(
        context: Context,
        manager: VfsConnectionManager,
    ) {
        if (sharingActive || _state.value == VfsConnectionState.Idle) return
        backgroundJob =
            scope.launch {
                lifecycleEvent(context, "background_grace")
                delay(BACKGROUND_GRACE_MS)
                if (AppLifecycleObserver.isForeground.value || sharingActive) return@launch
                lifecycleEvent(context, "background_disconnect")
                runCatching { manager.disconnect(VfsCloseCodes.BACKGROUND, "background grace expired") }
            }
    }

    /** Called by the sharing service as it starts and stops. */
    internal fun setSharingActive(active: Boolean) {
        sharingActive = active
        if (active) {
            backgroundJob?.cancel()
            return
        }
        // The service vanished without an Idle state (system stop or crash).
        // Do not let the Link keep running in the background without its
        // user-visible notification; the normal grace timer applies again.
        val context = appContext ?: return
        val current = manager ?: return
        if (!AppLifecycleObserver.isForeground.value && current.hasActiveConnection) {
            backgroundJob?.cancel()
            scheduleBackgroundGrace(context, current)
        }
    }

    fun hasActiveConnection(): Boolean = manager?.hasActiveConnection == true

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

    fun resolveVfsConsent(decision: VfsConsentDecision) {
        val pending = synchronized(consentLock) { pendingConsent } ?: return
        completeConsent(pending, decision)
    }

    private fun completeConsent(
        pending: PendingConsent,
        decision: VfsConsentDecision,
    ) {
        synchronized(consentLock) {
            if (pendingConsent !== pending) return
            pendingConsent = null
        }
        _consent.value = null
        pending.deferred.complete(decision)
    }

    /** The host Activity is finishing, so no dialog can answer a pending request. */
    fun cancelPendingConsent(message: String) {
        val pending = synchronized(consentLock) { pendingConsent } ?: return
        synchronized(consentLock) {
            if (pendingConsent !== pending) return
            pendingConsent = null
        }
        _consent.value = null
        pending.deferred.completeExceptionally(
            VfsConnectException("VFS_CONFIRMATION_REQUIRED", message),
        )
    }

    private fun clearConsent(id: String) {
        synchronized(consentLock) {
            if (pendingConsent?.id == id) pendingConsent = null
        }
        _consent.value = _consent.value?.takeUnless { it.id == id }
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

    private fun lifecycleEvent(
        context: Context,
        stage: String,
    ) {
        TelemetryEventLog.recordAsync(
            context,
            "vfs_lifecycle",
            mapOf(
                "stage" to stage,
                "service" to sharingActive,
            ),
        )
    }
}

package io.nekohasekai.sfa.vfs.relay

import android.content.Context
import android.os.Build
import io.nekohasekai.sfa.bg.VfsSharingService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class VfsRelayShareView(
    val shareId: String,
    val serverId: String,
    val label: String,
    val linkId: String,
    val status: String,
    val scopes: List<String>,
    val pairedAt: Long?,
    val lastUsedAt: Long?,
    val pairExpiresAt: Long?,
    val error: String? = null,
    val stopped: Boolean = false,
)

sealed interface VfsRelayState {
    data object Idle : VfsRelayState

    data class Connecting(val message: String = "") : VfsRelayState

    data class Connected(
        val shares: List<VfsRelayShareView>,
        val totalBytesSent: Long,
    ) : VfsRelayState

    data class Backoff(
        val message: String,
        val retryAtMs: Long,
    ) : VfsRelayState

    data class Error(
        val message: String,
        val actionable: String? = null,
        val resetRecommended: Boolean = false,
    ) : VfsRelayState
}

data class VfsRelayServerView(
    val id: String,
    val name: String,
    val endpoint: String,
    val selected: Boolean,
)

data class VfsPairingUi(
    val shareId: String,
    val linkId: String,
    val code: String,
    val expiresAt: Long,
)

/**
 * Process-wide Share Key VFS provider. One WebSocket carries every Link for the
 * currently selected Relay Server. All state transitions persist before the network
 * ack where the contract requires it; the Pairing Code itself stays in memory.
 */
object VfsRelayRuntime {
    private const val PROVIDER_KEY_NAME = "WonderSing"
    private const val SHARE_FILE_NAME = "relay/shares-v3.json"
    private const val SERVER_FILE_NAME = "relay/servers.json"
    private const val CONTROL_TIMEOUT_MS = 5_000L
    private const val PAIR_TTL_SECONDS = 300L
    private const val LAST_USED_THROTTLE_MS = 5L * 60L * 1000L
    private const val RESPONSE_CACHE_MS = 120_000L
    private const val MAX_SHARES = 8

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Mutex()
    private val codes = VfsPairingCodes()
    private val nonces = RelayNonceCache()
    private val pendingControls = ConcurrentHashMap<String, CompletableDeferred<Any>>()
    private val linkLocks = ConcurrentHashMap<String, Mutex>()
    private val responseCache = ConcurrentHashMap<String, CachedResponse>()
    private val pairResponseCache = ConcurrentHashMap<String, CachedResponse>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val revokedLinkIds = ConcurrentHashMap.newKeySet<String>()
    private val linkGate = RelayLinkGate()
    private val pairLimiter = RelayPairRateLimiter()
    private val sourceCache = ConcurrentHashMap<String, VfsRelaySource>()
    private val activationJobs = ConcurrentHashMap<String, Job>()
    private val totalBytesSent = AtomicLong(0)

    private val _state = MutableStateFlow<VfsRelayState>(VfsRelayState.Idle)
    val state: StateFlow<VfsRelayState> = _state.asStateFlow()

    private val _shares = MutableStateFlow<List<VfsRelayShareView>>(emptyList())
    val shares: StateFlow<List<VfsRelayShareView>> = _shares.asStateFlow()

    private val _servers = MutableStateFlow<List<VfsRelayServerView>>(emptyList())
    val servers: StateFlow<List<VfsRelayServerView>> = _servers.asStateFlow()

    private val _selectedServerId = MutableStateFlow("")
    val selectedServerId: StateFlow<String> = _selectedServerId.asStateFlow()

    @Volatile private var appContext: Context? = null
    @Volatile private var egressInstance: VfsRelayEgress? = null
    @Volatile private var provisionerInstance: VfsProviderKeyProvisioner? = null
    @Volatile private var shareStore: VfsShareStore? = null
    @Volatile private var shareRepository: VfsShareRepository? = null
    @Volatile private var storeBlocked = false
    @Volatile private var serverStore: VfsRelayServerStore? = null
    @Volatile private var deviceSecretStore: VfsDeviceSecretStore? = null
    @Volatile private var client: VfsRelayClient? = null
    @Volatile private var target: RelayServerTarget? = null
    @Volatile private var providerKey: String? = null
    @Volatile private var clientGeneration = 0
    @Volatile private var manualClose = false
    @Volatile private var attachedToService = false
    @Volatile private var lastInboundAt = 0L
    private var reconnectJob: Job? = null
    private var heartbeatJob: Job? = null
    private var expiryJobs = ConcurrentHashMap<String, Job>()
    private var reconnectAttempt = 0

    fun isAvailable(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N

    fun ensure(context: Context) {
        if (appContext != null) return
        synchronized(this) {
            if (appContext != null) return
            val app = context.applicationContext
            val base = File(app.noBackupFilesDir, "vfs")
            val cipher = VfsShareCipherFactory.create()
            val shares = VfsShareStore(File(base, SHARE_FILE_NAME), cipher)
            shareStore = shares
            shareRepository = VfsShareRepository(shares)
            serverStore = VfsRelayServerStore(File(base, SERVER_FILE_NAME), cipher)
            deviceSecretStore = VfsDeviceSecretStore(File(base, "relay"), cipher)
            val egress = VfsRelayEgress.create(app)
            egressInstance = egress
            provisionerInstance = VfsProviderKeyProvisioner(egress.httpClient())
            appContext = app
            try {
                publishServers()
                normalizeAfterProcessDeath()
            } catch (error: RelayStoreException) {
                storeBlocked = !error.retryable
                _state.value =
                    if (error.retryable) {
                        VfsRelayState.Error(
                            error.message ?: "Share storage is temporarily unavailable.",
                            "Open VFS to retry. If this persists, check free space and file permissions.",
                        )
                    } else {
                        VfsRelayState.Error(
                            error.message ?: "The Share list is damaged.",
                            "Reset VFS data, or restore the relay state under noBackupFilesDir/vfs/relay from a backup.",
                            resetRecommended = true,
                        )
                    }
            }
        }
    }

    /** Called from Application.onCreate; reconnect while Shares still need serving. */
    fun onAppStart(context: Context) {
        if (!isAvailable()) return
        ensure(context)
        val settings = serverStore?.load() ?: return
        val server = settings.selectedServer() ?: return
        val activeShares =
            runCatching {
                RelayRuntimePolicy.currentServerActiveShares(
                    repository().load(),
                    server.id,
                    server.baseUrl,
                )
            }.getOrNull() ?: return
        if (!RelayRuntimePolicy.shouldServe(activeShares.size)) {
            if (settings.desiredConnected) {
                runCatching { serverStore?.update { it.copy(desiredConnected = false) } }
            }
            return
        }
        if (!settings.desiredConnected) {
            serverStore?.update { it.copy(desiredConnected = true) }
        }
        scope.launch {
            runCatching { connectInternal(context.applicationContext, auto = true) }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    if (error is RelayProvisionException && !error.retryable) {
                        serverStore?.update { it.copy(desiredConnected = false) }
                        _state.value =
                            VfsRelayState.Error(
                                error.message ?: "Connection failed",
                                resetRecommended = isResetRecommended(error),
                            )
                    } else {
                        scheduleReconnect(context.applicationContext, error.message ?: "Connection failed")
                    }
                }
        }
    }

    suspend fun addServer(
        context: Context,
        name: String,
        url: String,
        token: String,
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                ensure(context)
                serverStore!!.addServer(name, url, token)
                publishServers()
            }
        }

    suspend fun updateServer(
        context: Context,
        serverId: String,
        name: String,
        url: String,
        token: String?,
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                ensure(context)
                serverStore!!.updateServer(serverId, name, url, token)
                publishServers()
            }
        }

    suspend fun selectServer(
        context: Context,
        serverId: String,
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                ensure(context)
                val settings = serverStore!!.load()
                if (settings.desiredConnected || client?.isOpen() == true) {
                    throw RelayProvisionException(
                        "DISCONNECT_FIRST",
                        "Revoke this Relay Server's Shares before switching Relay Servers.",
                        false,
                    )
                }
                serverStore!!.selectServer(serverId)
                publishServers()
            }
        }

    suspend fun removeServer(
        context: Context,
        serverId: String,
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                ensure(context)
                val settings = serverStore!!.load()
                if (settings.selectedServerId == serverId && (settings.desiredConnected || client?.isOpen() == true)) {
                    throw RelayProvisionException(
                        "DISCONNECT_FIRST",
                        "Revoke this Relay Server's Shares before deleting it.",
                        false,
                    )
                }
                val shares =
                    repository().load().filter {
                        it.serverId == serverId && it.revokedAt == null && it.revokingAt == null
                    }
                if (shares.isNotEmpty()) {
                    throw RelayProvisionException(
                        "SERVER_HAS_SHARES",
                        "This Relay Server still has Shares. Revoke them first.",
                        false,
                    )
                }
                serverStore!!.removeServer(serverId)
                publishServers()
            }
        }

    fun selectedServerId(context: Context): String {
        if (!isAvailable()) return ""
        ensure(context)
        return serverStore?.load()?.selectedServerId.orEmpty()
    }

    fun serversForDisplay(): List<VfsRelayServerView> = _servers.value

    suspend fun connect(context: Context): Result<Unit> = withContext(Dispatchers.IO) { connectResult(context) }

    private suspend fun connectResult(context: Context): Result<Unit> =
        runCatching {
            ensure(context)
            checkStore()
            val settings = serverStore!!.load()
            if (settings.selectedServer() == null) {
                throw RelayProvisionException(
                    "NO_SERVER",
                    "Select a Relay Server in Settings first.",
                    false,
                )
            }
            serverStore!!.update { it.copy(desiredConnected = true) }
            connectInternal(context.applicationContext, auto = false)
        }.onFailure { error ->
            if (error is CancellationException) throw error
            if (error is RelayProvisionException && !error.retryable) {
                runCatching { serverStore!!.update { it.copy(desiredConnected = false) } }
                _state.value =
                    VfsRelayState.Error(
                        error.message ?: "Connection failed",
                        resetRecommended = isResetRecommended(error),
                    )
            } else if (serverStore?.load()?.desiredConnected == true) {
                scheduleReconnect(context.applicationContext, error.message ?: "Connection failed")
            } else {
                _state.value = VfsRelayState.Idle
            }
        }

    /**
     * Connection follows Shares: keep a session only while the selected Relay
     * Server has a Share to serve. Called when VFS opens; Share creation also
     * brings the session up on demand.
     */
    suspend fun syncServing(context: Context): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                ensure(context)
                checkStore()
                val settings = serverStore!!.load()
                val server = settings.selectedServer() ?: return@runCatching
                val activeShares =
                    RelayRuntimePolicy.currentServerActiveShares(
                        repository().load(),
                        server.id,
                        server.baseUrl,
                    )
                if (!RelayRuntimePolicy.shouldServe(activeShares.size)) {
                    val relayActive =
                        client?.isOpen() == true ||
                            _state.value is VfsRelayState.Connecting ||
                            _state.value is VfsRelayState.Connected ||
                            _state.value is VfsRelayState.Backoff
                    if (relayActive) {
                        stopIfNoCurrentServerShares(context, server.id, server.baseUrl)
                    } else if (settings.desiredConnected) {
                        serverStore!!.update { it.copy(desiredConnected = false) }
                    }
                    return@runCatching
                }
                if (!settings.desiredConnected) {
                    serverStore!!.update { it.copy(desiredConnected = true) }
                }
                if (client?.isOpen() == true) {
                    publishConnected()
                } else {
                    connectResult(context.applicationContext).getOrThrow()
                }
            }
        }

    /**
     * Verifies a saved Relay Server without creating a Share or opening the
     * provider socket. The token and device identity only reach the configured
     * Relay origin.
     */
    suspend fun testServer(
        context: Context,
        serverId: String,
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                ensure(context)
                checkStore()
                val settings = serverStore!!.load()
                val server =
                    settings.server(serverId)
                        ?: throw RelayProvisionException("NO_SERVER", "Relay Server not found.", false)
                val token =
                    serverStore!!.openToken(server)
                        ?: throw RelayProvisionException(
                            "INVALID_TOKEN",
                            "Could not read this Relay Server's token. Edit the Relay Server and enter the token again.",
                            false,
                        )
                val target = provisioner().targetOf(server, token)
                val deviceId = serverStore!!.relayDeviceId()
                val account =
                    settings.accounts.firstOrNull {
                        it.serverId == serverId && it.canonicalBase == target.canonicalBase
                    }
                val secret =
                    if (account != null) {
                        deviceSecretStore!!.existing(target.canonicalBase, account.relayId, deviceId)
                            ?: deviceSecretStore!!.getOrCreatePending(
                                serverId,
                                target.canonicalBase,
                                deviceId,
                            )
                    } else {
                        deviceSecretStore!!.getOrCreatePending(
                            serverId,
                            target.canonicalBase,
                            deviceId,
                        )
                    }
                try {
                    provisioner().issue(target, deviceId, secret, providerName())
                } finally {
                    secret.fill(0)
                }
                Unit
            }
        }

    private suspend fun connectInternal(
        context: Context,
        auto: Boolean,
    ): Unit =
        gate.withLock {
            if (!isAvailable()) throw RelayProvisionException("UNSUPPORTED", "VFS needs Android 7.0 or newer.", false)
            checkStore()
            val settings = serverStore!!.load()
            val server =
                settings.selectedServer()
                    ?: throw RelayProvisionException("NO_SERVER", "Select a Relay Server first.", false)
            if (!settings.desiredConnected) {
                _state.value = VfsRelayState.Idle
                return@withLock
            }
            if (client?.isOpen() == true) {
                publishConnected()
                return@withLock
            }
            _state.value = VfsRelayState.Connecting("Contacting the Relay Server")
            if (RelayForegroundGuard.blocked(VfsSharingService.start(context), true)) {
                throw RelayProvisionException(
                    "FOREGROUND_SERVICE_BLOCKED",
                    "Android blocked the sharing notification, so WonderSing is not sharing. Open VFS to retry.",
                    true,
                )
            }
            val relayToken =
                serverStore!!.openToken(server)
                    ?: throw RelayProvisionException(
                        "INVALID_TOKEN",
                        "Could not read this Relay Server's token. Edit the Relay Server and enter the token again.",
                        false,
                    )
            val resolved = provisioner().targetOf(server, relayToken)
            val deviceId = serverStore!!.relayDeviceId()
            val account =
                serverStore!!.account(resolved.serverId)
                    ?.takeIf { it.canonicalBase == resolved.canonicalBase }
            val deviceSecret =
                if (account != null) {
                    deviceSecretStore!!.existing(resolved.canonicalBase, account.relayId, deviceId)
                        ?: deviceSecretStore!!.getOrCreatePending(
                            resolved.serverId,
                            resolved.canonicalBase,
                            deviceId,
                        )
                } else {
                    deviceSecretStore!!.getOrCreatePending(
                        resolved.serverId,
                        resolved.canonicalBase,
                        deviceId,
                    )
                }
            val issue =
                withContext(Dispatchers.IO) {
                    provisioner().issue(resolved, deviceId, deviceSecret, providerName())
                }
            val promoted =
                deviceSecretStore!!.promotePending(
                    resolved.serverId,
                    resolved.canonicalBase,
                    issue.relayId,
                    deviceId,
                    deviceSecret,
                )
            val sealedKey =
                serverStore!!.sealProviderKey(
                    resolved.canonicalBase,
                    issue.relayId,
                    deviceId,
                    issue.providerKeyId,
                    issue.providerKeyBytes(),
                )
            val newAccount =
                VfsRelayAccount(
                    serverId = resolved.serverId,
                    relayId = issue.relayId,
                    canonicalBase = resolved.canonicalBase,
                    deviceId = deviceId,
                    providerKeyId = issue.providerKeyId,
                    providerKeyCiphertext = sealedKey,
                    createdAt = account?.createdAt ?: issue.createdAt,
                    rotatedAt = account?.let { issue.createdAt },
                )
            val latest =
                serverStore!!.update { current ->
                    current.withAccount(
                        resolved.serverId,
                        issue.relayId,
                        resolved.canonicalBase,
                        newAccount,
                    )
                }
            if (!latest.desiredConnected) {
                _state.value = VfsRelayState.Idle
                return@withLock
            }
            val latestServer = latest.selectedServer()
            if (latestServer == null ||
                latestServer.id != resolved.serverId ||
                latestServer.baseUrl != resolved.canonicalBase
            ) {
                _state.value = VfsRelayState.Idle
                return@withLock
            }
            target = resolved
            providerKey = issue.providerKey
            lastInboundAt = System.currentTimeMillis()
            manualClose = false
            if (RelayForegroundGuard.blocked(started = true, serviceActive = VfsSharingService.isActive())) {
                throw RelayProvisionException(
                    "FOREGROUND_SERVICE_BLOCKED",
                    "Android stopped the sharing notification, so WonderSing is not sharing. Open VFS to retry.",
                    true,
                )
            }
            openSocket(context, resolved, issue.providerKey, auto)
            promoted.fill(0)
        }

    private suspend fun openSocket(
        context: Context,
        target: RelayServerTarget,
        key: String,
        auto: Boolean,
    ) {
        val url = relayProviderUrl(target.canonicalBase)
            ?: throw RelayProvisionException(
                "INVALID_RELAY_URL",
                "The Relay Server URL must be a full HTTPS base such as https://host/relay/v1.",
                false,
            )
        val generation = ++clientGeneration
        val opened = CompletableDeferred<Boolean>()
        val created =
            VfsRelayClient(
                relayUrl = url,
                providerKey = key,
                client = egress().relayClient(),
                listener =
                    object : VfsRelayClientListener {
                        override fun onRelayOpen() {
                            if (generation != clientGeneration) return
                            lastInboundAt = System.currentTimeMillis()
                            opened.complete(true)
                            scope.launch {
                                runCatching { onSocketOpen(context, generation, auto) }
                                    .onFailure { failOrRetry(context, it.message ?: "Registration failed") }
                            }
                        }

                        override fun onRelayMessage(text: String) {
                            lastInboundAt = System.currentTimeMillis()
                            if (generation != clientGeneration) return
                            handleMessage(context, text)
                        }

                        override fun onRelayClosed(
                            code: Int,
                            reason: String,
                        ) {
                            if (generation != clientGeneration) return
                            onSocketGone(context, code, reason)
                        }

                        override fun onRelayFailure(message: String) {
                            if (generation != clientGeneration) return
                            onSocketGone(context, null, message)
                        }
                    },
            )
        client = created
        created.start()
        val ready = withTimeoutOrNull(20_000L) { opened.await() } == true
        if (!ready) {
            created.close(1001, "open timeout")
            client = null
            throw RelayProvisionException(
                "RELAY_UNREACHABLE",
                "Could not open the Relay Server connection. Check the server and your network.",
                true,
            )
        }
        // onRelayOpen() completes `opened` before onSocketOpen() finishes
        // registration. Callers such as Share creation must not observe a
        // half-open client as Connected; wait for registration to settle.
        val settled =
            withTimeoutOrNull(20_000L) {
                _state.first {
                    it is VfsRelayState.Connected ||
                        it is VfsRelayState.Error ||
                        it is VfsRelayState.Backoff ||
                        it is VfsRelayState.Idle
                }
            }
        if (settled !is VfsRelayState.Connected) {
            created.close(1001, "not ready")
            client = null
            throw RelayProvisionException(
                "RELAY_UNREACHABLE",
                (settled as? VfsRelayState.Error)?.message
                    ?: "The Relay Server connection is not ready. Check the server and your network.",
                true,
            )
        }
        if (manualClose || serverStore?.load()?.desiredConnected != true) {
            created.close(1000, "sharing stopped")
            client = null
            _state.value = VfsRelayState.Idle
        }
    }

    private suspend fun onSocketOpen(
        context: Context,
        generation: Int,
        auto: Boolean,
    ) {
        if (manualClose || serverStore?.load()?.desiredConnected != true) {
            client?.close(1000, "sharing stopped")
            return
        }
        _state.value = VfsRelayState.Connecting("Registering Shares")
        registerAll(context, generation)
        if (serverStore?.load()?.desiredConnected != true) return
        if (generation != clientGeneration) return
        reconnectAttempt = 0
        publishConnected()
        startHeartbeat(context, generation)
    }

    private suspend fun registerAll(
        context: Context,
        generation: Int,
    ) {
        val settings = serverStore!!.load()
        val server = settings.selectedServer() ?: return
        val records = repository().load()
        for (record in records) {
            if (generation != clientGeneration) return
            if (record.serverId != server.id || record.canonicalBase != server.baseUrl) {
                continue
            }
            if (record.revokedAt != null || record.revokingAt != null) {
                if (sendUnregisterBestEffort(record.linkId, "revoked")) repository().remove(record.shareId)
                if (stopIfNoCurrentServerShares(context, server.id, server.baseUrl)) return
                continue
            }
            if (record.registrationState == VfsShareRecord.STATE_FAILED) continue
            val account = serverStore!!.account(server.id)
            val prepared =
                if (account != null &&
                    (record.relayId != account.relayId || record.deviceId != account.deviceId)
                ) {
                    repository().update(record.shareId) {
                        it.copy(relayId = account.relayId, deviceId = account.deviceId)
                    } ?: record
                } else {
                    record
                }
            if (prepared.registrationState != VfsShareRecord.STATE_REGISTERED || prepared.registeredAt == null) {
                val key = providerKey ?: continue
                target?.let { current ->
                    runCatching {
                        withContext(Dispatchers.IO) {
                            provisioner().postLink(current, key, prepared.linkId)
                        }
                    }
                        .onSuccess {
                            repository().update(prepared.shareId) {
                                it.copy(
                                    registrationState = VfsShareRecord.STATE_REGISTERED,
                                    registeredAt = it.registeredAt ?: System.currentTimeMillis(),
                                    lastError = null,
                                )
                            }
                        }
                        .onFailure { error ->
                            if (error is RelayProvisionException && !error.retryable) {
                                repository().update(prepared.shareId) {
                                    it.copy(
                                        registrationState = VfsShareRecord.STATE_FAILED,
                                        lastError = error.message,
                                    )
                                }
                            }
                        }
                }
            }
            val current = latestShare(prepared.shareId) ?: prepared
            if (current.registrationState != VfsShareRecord.STATE_REGISTERED) continue
            registerLink(current, generation)
        }
    }

    private suspend fun registerLink(
        record: VfsShareRecord,
        generation: Int,
        retried: Boolean = false,
    ) {
        if (generation != clientGeneration) return
        val updated =
            repository().update(record.shareId) {
                it.copy(generation = it.generation + 1, lastError = null)
            } ?: return
        val nextGeneration = updated.generation
        val controlId = RelayCrypto.randomToken(16)
        val json =
            RelayWire.registerControl(
                controlId, updated.linkId, updated.deviceId, nextGeneration,
            )
        when (val result = sendControlAwait(controlId, json, CONTROL_TIMEOUT_MS)) {
            is RelayIncoming.Ack -> {
                linkGate.onRegisterAck(updated.linkId)
                repository().update(updated.shareId) {
                    it.copy(
                        registrationState = VfsShareRecord.STATE_REGISTERED,
                        registeredAt = it.registeredAt ?: System.currentTimeMillis(),
                        lastError = null,
                    )
                }
                sendPairState(latestShare(updated.shareId) ?: updated)
            }

            is RelayIncoming.ControlError -> {
                when (result.code) {
                    "STALE_GENERATION" -> {
                        val current = result.currentGeneration ?: (nextGeneration + 1)
                        repository().update(updated.shareId) { it.copy(generation = current) }
                        registerLink(latestShare(updated.shareId) ?: updated, generation, retried)
                    }

                    "LINK_UNKNOWN" -> {
                        if (!retried && providerKey != null && target != null) {
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    provisioner().postLink(target!!, providerKey!!, updated.linkId)
                                }
                            }
                                .onSuccess {
                                    repository().update(updated.shareId) {
                                        it.copy(
                                            registrationState = VfsShareRecord.STATE_REGISTERED,
                                            registeredAt = it.registeredAt ?: System.currentTimeMillis(),
                                            lastError = null,
                                        )
                                    }
                                    registerLink(latestShare(updated.shareId) ?: updated, generation, retried = true)
                                }
                                .onFailure { error ->
                                    val permanent = error is RelayProvisionException && !error.retryable
                                    repository().update(updated.shareId) {
                                        it.copy(
                                            registrationState =
                                                if (permanent) {
                                                    VfsShareRecord.STATE_FAILED
                                                } else {
                                                    it.registrationState
                                                },
                                            lastError = error.message
                                                ?: "The Relay Server no longer knows this Share. Create a new Share.",
                                        )
                                    }
                                }
                        } else {
                            repository().update(updated.shareId) {
                                it.copy(
                                    registrationState = VfsShareRecord.STATE_FAILED,
                                    lastError = "The Relay Server no longer knows this Share. Create a new Share.",
                                )
                            }
                        }
                    }

                    "LINK_OWNED" ->
                        repository().update(updated.shareId) {
                            it.copy(
                                registrationState = VfsShareRecord.STATE_FAILED,
                                lastError = "This Share belongs to another device. Revoke it locally and create a new Share.",
                            )
                        }

                    "REVOKED" ->
                        repository().update(updated.shareId) {
                            it.copy(
                                registrationState = VfsShareRecord.STATE_FAILED,
                                lastError = "The Relay Server revoked this Share. Create a new Share.",
                            )
                        }

                    "DEVICE_MISMATCH" ->
                        repository().update(updated.shareId) {
                            it.copy(
                                registrationState = VfsShareRecord.STATE_FAILED,
                                lastError = "The Relay Server rejected this device identity. Reset VFS data and create a new Share.",
                            )
                        }
                }
            }

            else -> {
                // Timeout: leave the Share registered locally and retry on the
                // next connection instead of duplicating a Pairing Code now.
            }
        }
    }

    private suspend fun sendPairState(record: VfsShareRecord) {
        if (client?.isOpen() != true) return
        if (!linkGate.canSendPair(record.linkId)) return
        val frame = RelayPairFrames.frameFor(record) ?: return
        sendPairControl(record, frame.pairId, frame.ttlSeconds, frame.state)
    }

    private suspend fun sendPairControl(
        record: VfsShareRecord,
        pairId: String?,
        ttlSeconds: Long?,
        state: String,
    ) {
        val controlId = RelayCrypto.randomToken(16)
        val json = RelayWire.pairControl(controlId, record.linkId, pairId, ttlSeconds, state, record.pairEpoch)
        sendControlAwait(controlId, json, 3_000L)
    }

    private suspend fun sendUnregisterBestEffort(
        linkId: String,
        reason: String,
    ): Boolean {
        if (client?.isOpen() != true) return false
        val controlId = RelayCrypto.randomToken(16)
        return sendControlAwait(controlId, RelayWire.unregisterControl(controlId, linkId, reason), 2_000L) is RelayIncoming.Ack
    }

    private suspend fun sendControlAwait(
        controlId: String,
        json: String,
        timeoutMs: Long,
    ): Any? {
        val deferred = CompletableDeferred<Any>()
        pendingControls[controlId] = deferred
        try {
            repeat(2) { attempt ->
                if (client?.send(json) != true) return null
                val result = withTimeoutOrNull(timeoutMs) { deferred.await() }
                if (result != null) return result
                if (attempt == 0) return@repeat
            }
            return null
        } finally {
            pendingControls.remove(controlId)
        }
    }

    private fun handleMessage(
        context: Context,
        text: String,
    ) {
        when (val incoming = RelayWire.parseIncoming(text)) {
            is RelayIncoming.Ping -> client?.send(RelayWire.pong(incoming.id))
            is RelayIncoming.Pong -> lastInboundAt = System.currentTimeMillis()
            is RelayIncoming.Ack -> incoming.controlId?.let { pendingControls[it]?.complete(incoming) }
            is RelayIncoming.ControlError -> incoming.controlId?.let { pendingControls[it]?.complete(incoming) }
            is RelayIncoming.PairError, is RelayIncoming.LinkError -> Unit
            is RelayEnvelope -> scope.launch { handleEnvelope(context, incoming) }
            else -> Unit
        }
    }

    private fun startHeartbeat(
        context: Context,
        generation: Int,
    ) {
        heartbeatJob?.cancel()
        heartbeatJob =
            scope.launch {
                while (generation == clientGeneration && client?.isOpen() == true) {
                    delay(30_000L)
                    if (generation != clientGeneration) return@launch
                    client?.send(RelayWire.ping(RelayCrypto.randomToken(16)))
                    if (System.currentTimeMillis() - lastInboundAt > 90_000L) {
                        client?.close(1001, "heartbeat")
                        return@launch
                    }
                }
            }
    }

    private fun onSocketGone(
        context: Context,
        code: Int?,
        reason: String,
    ) {
        if (manualClose) return
        heartbeatJob?.cancel()
        linkGate.clear()
        client = null
        if (code != null && RelayCloseCodes.isPermanent(code)) {
            serverStore?.update { it.copy(desiredConnected = false) }
            _state.value =
                VfsRelayState.Error(
                    "This device was revoked on the Relay Server.",
                    "Reset VFS data and create a new Share. Administrator deletion permanently revokes this device.",
                    resetRecommended = true,
                )
            VfsSharingService.stop(context)
            return
        }
        if (code != null && RelayCloseCodes.isSuspend(code)) {
            _state.value =
                VfsRelayState.Error(
                    "The Relay Server suspended Share Key VFS for this token.",
                    "Check the Relay Server settings, then reopen VFS.",
                )
            return
        }
        val settings = serverStore?.load() ?: return
        if (!settings.desiredConnected) {
            _state.value = VfsRelayState.Idle
            VfsSharingService.stop(context)
            return
        }
        scheduleReconnect(context, reason.ifBlank { "Connection closed" })
    }

    private fun scheduleReconnect(
        context: Context,
        reason: String,
    ) {
        reconnectJob?.cancel()
        reconnectAttempt++
        val wait = RelayRuntimePolicy.backoffMs(reconnectAttempt)
        _state.value = VfsRelayState.Backoff(reason, System.currentTimeMillis() + wait)
        reconnectJob =
            scope.launch {
                delay(wait)
                if (!isAvailable()) return@launch
                if (!RelayRuntimePolicy.shouldReconnect(serverStore?.load()?.desiredConnected == true)) {
                    _state.value = VfsRelayState.Idle
                    return@launch
                }
                runCatching { connectInternal(context.applicationContext, auto = true) }
                    .onFailure { error ->
                        if (error is CancellationException) throw error
                        if (error is RelayProvisionException && !error.retryable) {
                            serverStore?.update { it.copy(desiredConnected = false) }
                            _state.value = VfsRelayState.Error(error.message ?: "Connection failed")
                            VfsSharingService.stop(context)
                        } else {
                            failOrRetry(context, error.message ?: "Reconnect failed")
                        }
                    }
            }
    }

    private fun failOrRetry(
        context: Context,
        message: String,
    ) {
        val settings = serverStore?.load()
        val retryable =
            !(message.contains("revoked", ignoreCase = true) ||
                message.contains("suspended", ignoreCase = true) ||
                message.contains("token", ignoreCase = true))
        if (settings?.desiredConnected == true && retryable) {
            scheduleReconnect(context, message)
        } else {
            _state.value = VfsRelayState.Error(message)
            VfsSharingService.stop(context)
        }
    }

    private fun handleEnvelope(
        context: Context,
        envelope: RelayEnvelope,
    ) {
        val record = runCatching { repository().load() }.getOrNull()?.firstOrNull { it.linkId == envelope.linkId }
        if (record == null) {
            if (envelope.linkId in revokedLinkIds) {
                sendLinkError(envelope, RelayLinkErrorCode.SHARE_REVOKED, "share revoked")
            } else {
                sendLinkError(envelope, RelayLinkErrorCode.LINK_UNKNOWN, "link unknown")
            }
            return
        }
        if (record.revokedAt != null || envelope.linkId in revokedLinkIds) {
            sendLinkError(envelope, RelayLinkErrorCode.SHARE_REVOKED, "share revoked")
            return
        }
        if (record.stopped && envelope.op != "pair") {
            sendLinkError(envelope, RelayLinkErrorCode.SHARE_PAUSED, "sharing is stopped")
            return
        }
        val lock = linkLocks.getOrPut(record.linkId) { Mutex() }
        scope.launch {
            lock.withLock {
                val current = latestShare(record.shareId) ?: return@withLock
                runCatching {
                    if (envelope.op == "pair") {
                        handlePairRequest(context, current, envelope)
                    } else {
                        handleDataRequest(context, current, envelope)
                    }
                }.onFailure { error ->
                    if (error is CancellationException) throw error
                    sendLinkError(envelope, RelayLinkErrorCode.AUTH_FAILED, "auth failed")
                }
            }
        }
    }

    private suspend fun handlePairRequest(
        context: Context,
        record: VfsShareRecord,
        envelope: RelayEnvelope,
    ) {
        val pairId = envelope.pairId ?: return
        val now = System.currentTimeMillis()
        purgeExpiredCaches(now)
        if (record.stopped) {
            sendPairError(envelope, RelayPairErrorCode.SHARE_PAUSED, "sharing is stopped")
            return
        }
        if (!RelayClock.isFresh(envelope.ts, now)) {
            sendPairError(envelope, RelayPairErrorCode.STALE_TS, "timestamp outside window", serverTs = now, skewMs = now - envelope.ts)
            return
        }
        val cacheKey = "${record.linkId}|$pairId|${envelope.reqId}|${envelope.clientNonce}"
        pairResponseCache[cacheKey]?.takeIf { it.expiresAt > now }?.let {
            client?.send(it.json)
            return
        }
        when (record.pairState) {
            VfsShareRecord.PAIR_PAUSED -> {
                sendPairError(envelope, RelayPairErrorCode.SHARE_PAUSED, "sharing is paused")
                return
            }

            VfsShareRecord.PAIR_EXPIRED -> {
                sendPairError(envelope, RelayPairErrorCode.CODE_EXPIRED, "pairing code expired")
                return
            }

            VfsShareRecord.PAIR_CONSUMED -> {
                sendPairError(envelope, RelayPairErrorCode.CODE_CONSUMED, "pairing code already used")
                return
            }

            VfsShareRecord.PAIR_ACTIVE -> Unit
            else -> {
                sendPairError(envelope, RelayPairErrorCode.PAIR_UNKNOWN, "unknown pairing code")
                return
            }
        }
        if (record.pairId != pairId) {
            sendPairError(envelope, RelayPairErrorCode.PAIR_UNKNOWN, "unknown pairing code")
            return
        }
        val code = codes.get(record.shareId)
        if (code == null || code.pairId != pairId || code.isExpired(now)) {
            markPairExpired(record, pairId)
            sendPairError(envelope, RelayPairErrorCode.CODE_EXPIRED, "pairing code expired")
            return
        }
        val secret = shareStore!!.openSecret(record)
        val pairKey = RelayCrypto.pairKey(VfsPairing.ascii(code.code), envelope.linkIdRaw)
        val aad =
            RelayAad.pairRequest(
                envelope.linkIdRaw,
                RelayBase64.decodeUrl(pairId)!!,
                envelope.reqIdRaw,
                envelope.clientNonceRaw,
                envelope.ts,
                envelope.ivRaw,
            )
        val plaintext =
            runCatching { RelayCrypto.open(pairKey, envelope.ivRaw, envelope.ctRaw, aad) }
                .getOrElse {
                    rejectPairAttempt(envelope, record.linkId)
                    return
                }
        val parsed = RelayWire.parsePairPlaintext(plaintext)
        if (parsed == null || parsed.pairId != pairId || parsed.clientNonce != envelope.clientNonce) {
            rejectPairAttempt(envelope, record.linkId)
            return
        }
        pairLimiter.reset(record.linkId)
        val scopes = record.concreteScopes
        val responsePlaintext =
            RelayWire.pairResponsePlaintext(
                shareId = record.shareId,
                shareSecret = RelayBase64.encodeUrl(secret),
                scopes = RelayRoots.scopesSorted(scopes),
                roots = RelayRoots.of(scopes),
                label = record.label,
            )
        val responseKey = RelayCrypto.pairResponseKey(VfsPairing.ascii(code.code), envelope.clientNonceRaw)
        val responseTs = System.currentTimeMillis()
        val responseIv = RelayCrypto.randomBytes(RelayCrypto.IV_BYTES)
        val responseAad =
            RelayAad.pairResponse(
                envelope.linkIdRaw,
                pairId.let { RelayBase64.decodeUrl(it)!! },
                envelope.reqIdRaw,
                envelope.clientNonceRaw,
                requestTs = envelope.ts,
                requestIv = envelope.ivRaw,
                ts = responseTs,
                iv = responseIv,
            )
        val sealed = RelayCrypto.seal(responseKey, responsePlaintext, responseAad, responseIv)
        val responseJson =
            RelayWire.responseEnvelope(
                request = envelope,
                op = "pair",
                pairId = pairId,
                ts = responseTs,
                iv = RelayBase64.encodeUrl(sealed.iv),
                ct = RelayBase64.encodeUrl(sealed.ciphertext),
            )
        val consumed =
            repository().update(record.shareId) { current ->
                if (current.revokedAt != null ||
                    current.revokingAt != null ||
                    current.pairState != VfsShareRecord.PAIR_ACTIVE ||
                    current.pairId != pairId
                ) {
                    null
                } else {
                    current.nextPairState(
                        VfsShareRecord.PAIR_CONSUMED,
                        expiresAt = null,
                        pairedAt = System.currentTimeMillis(),
                    )
                }
            }
        if (consumed == null) {
            when (val latest = latestShare(record.shareId)) {
                null -> sendLinkError(envelope, RelayLinkErrorCode.SHARE_REVOKED, "share revoked")
                else ->
                    when {
                        latest.revokedAt != null || latest.revokingAt != null ->
                            sendLinkError(envelope, RelayLinkErrorCode.SHARE_REVOKED, "share revoked")

                        latest.pairState == VfsShareRecord.PAIR_PAUSED ->
                            sendPairError(envelope, RelayPairErrorCode.SHARE_PAUSED, "sharing is paused")

                        latest.pairState == VfsShareRecord.PAIR_EXPIRED ->
                            sendPairError(envelope, RelayPairErrorCode.CODE_EXPIRED, "pairing code expired")

                        latest.pairState == VfsShareRecord.PAIR_CONSUMED ->
                            sendPairError(envelope, RelayPairErrorCode.CODE_CONSUMED, "pairing code already used")

                        else ->
                            sendPairError(envelope, RelayPairErrorCode.PAIR_UNKNOWN, "unknown pairing code")
                    }
            }
            return
        }
        pairResponseCache[cacheKey] = CachedResponse(responseJson, code.expiresAt)
        codes.remove(record.shareId)
        client?.send(responseJson)
        sendPairState(consumed)
        publishConnected()
    }

    private suspend fun handleDataRequest(
        context: Context,
        record: VfsShareRecord,
        envelope: RelayEnvelope,
    ) {
        val cacheKey = "${record.linkId}|${envelope.reqId}|${envelope.clientNonce}"
        val now = System.currentTimeMillis()
        purgeExpiredCaches(now)
        responseCache[cacheKey]?.takeIf { it.expiresAt > now }?.let {
            if (client?.send(it.json) == true) {
                totalBytesSent.addAndGet(it.json.toByteArray(Charsets.UTF_8).size.toLong())
                publishConnected()
            }
            return
        }
        if (!inFlight.add(cacheKey)) {
            sendEncryptedInner(
                context,
                record,
                envelope,
                RelayWire.innerError(RelayInnerError.IN_FLIGHT, "request is in flight"),
                cache = false,
            )
            return
        }
        try {
            val secret = shareStore!!.openSecret(record)
            val shareKey = RelayCrypto.shareKey(secret, envelope.linkIdRaw)
            val requestKey = RelayCrypto.requestKey(shareKey, envelope.clientNonceRaw)
            val aad =
                RelayAad.request(
                    envelope.linkIdRaw,
                    envelope.reqIdRaw,
                    envelope.clientNonceRaw,
                    envelope.ts,
                    envelope.ivRaw,
                )
            val plaintext =
                runCatching { RelayCrypto.open(requestKey, envelope.ivRaw, envelope.ctRaw, aad) }
                    .getOrElse {
                        sendLinkError(envelope, RelayLinkErrorCode.AUTH_FAILED, "auth failed")
                        return
                    }
            val now = System.currentTimeMillis()
            if (!RelayClock.isFresh(envelope.ts, now)) {
                sendEncryptedInner(
                    context,
                    record,
                    envelope,
                    RelayWire.innerError(RelayInnerError.STALE_TS, "timestamp outside window"),
                )
                return
            }
            if (!nonces.remember(record.linkId, envelope.clientNonce)) {
                sendEncryptedInner(
                    context,
                    record,
                    envelope,
                    RelayWire.innerError(RelayInnerError.NONCE_REUSED, "nonce reused"),
                )
                return
            }
            val inner =
                runCatching { RelayWire.parseInnerRequest(plaintext) }
                    .getOrElse { error ->
                        val code = (error as? RelayProtocolException)?.code ?: RelayInnerError.BAD_REQUEST
                        sendEncryptedInner(context, record, envelope, RelayWire.innerError(code, error.message ?: "bad request"))
                        return
                    }
            val source = sourceFor(context, record, secret)
            val response =
                runCatching { dispatch(source, inner) }
                    .getOrElse { error ->
                        val code =
                            when (error) {
                                is RelaySourceException -> error.code
                                is io.nekohasekai.sfa.vfs.VfsException -> error.code
                                else -> RelayInnerError.PROVIDER_ERROR
                            }
                        RelayDispatch(RelayWire.innerError(code, error.message ?: "provider error"), 0, cache = false)
                    }
            sendEncryptedInner(context, record, envelope, response.payload, shareKey, cache = response.cache)
            recordLastUsed(record.shareId)
        } finally {
            inFlight.remove(cacheKey)
        }
    }

    private suspend fun dispatch(
        source: VfsRelaySource,
        request: RelayInnerRequest,
    ): RelayDispatch =
        when (request) {
            is RelayInnerRequest.Stat -> RelayDispatch(RelayWire.statResponse(source.stat(request.path)), 0)
            is RelayInnerRequest.List -> {
                val page = source.list(request.path, request.cursor, request.limit)
                RelayDispatch(RelayWire.listResponse(page.entries, page.nextCursor), 0)
            }

            is RelayInnerRequest.Read -> {
                val result = source.read(request.path, request.offset, request.length, request.etag)
                RelayDispatch(
                    RelayWire.readResponse(result.data, result.etag, result.offset, result.nextOffset, result.eof),
                    result.data.size,
                )
            }
        }

    private fun sourceFor(
        context: Context,
        record: VfsShareRecord,
        secret: ByteArray,
    ): VfsRelaySource =
        sourceCache.getOrPut(record.shareId) {
            val count = repository().load().count { it.revokedAt == null }
            AndroidRelaySources.create(
                context = context,
                share = record,
                secret = secret,
                shareCount = count,
                deviceId = record.deviceId,
            )
        }

    private fun sendEncryptedInner(
        context: Context,
        record: VfsShareRecord,
        envelope: RelayEnvelope,
        inner: ByteArray,
        shareKey: ByteArray? = null,
        cache: Boolean = true,
    ) {
        if (client?.isOpen() != true) return
        val latest = latestShare(record.shareId) ?: return
        if (!RelayRuntimePolicy.responseAllowed(latest, revokedLinkIds)) return
        val key =
            shareKey ?: run {
                val secret = runCatching { shareStore!!.openSecret(record) }.getOrNull() ?: return
                RelayCrypto.shareKey(secret, envelope.linkIdRaw)
            }
        val responseKey = RelayCrypto.responseKey(key, envelope.clientNonceRaw)
        val ts = System.currentTimeMillis()
        val iv = RelayCrypto.randomBytes(RelayCrypto.IV_BYTES)
        val aad =
            RelayAad.response(
                envelope.linkIdRaw,
                envelope.reqIdRaw,
                envelope.clientNonceRaw,
                requestTs = envelope.ts,
                requestIv = envelope.ivRaw,
                ts = ts,
                iv = iv,
            )
        val sealed = RelayCrypto.seal(responseKey, inner, aad, iv)
        val text =
            RelayWire.responseEnvelope(
                request = envelope,
                op = "vfs",
                pairId = null,
                ts = ts,
                iv = RelayBase64.encodeUrl(sealed.iv),
                ct = RelayBase64.encodeUrl(sealed.ciphertext),
            )
        if (cache) {
            responseCache["${record.linkId}|${envelope.reqId}|${envelope.clientNonce}"] =
                CachedResponse(text, System.currentTimeMillis() + RESPONSE_CACHE_MS)
        }
        if (client?.send(text) == true) {
            totalBytesSent.addAndGet(text.toByteArray(Charsets.UTF_8).size.toLong())
            publishConnected()
        }
    }

    private fun rejectPairAttempt(
        envelope: RelayEnvelope,
        linkId: String,
    ) {
        if (pairLimiter.allow(linkId)) {
            sendPairError(envelope, RelayPairErrorCode.CODE_INVALID, "pairing code is invalid")
        } else {
            sendPairError(
                envelope,
                RelayPairErrorCode.RATE_LIMITED,
                "too many pairing attempts",
                retryAfterMs = pairLimiter.retryAfterMs(),
            )
        }
    }

    private fun sendPairError(
        envelope: RelayEnvelope,
        code: String,
        message: String,
        retryAfterMs: Long? = null,
        serverTs: Long? = null,
        skewMs: Long? = null,
    ) {
        client?.send(
            RelayWire.pairError(
                consumerId = envelope.consumerId,
                linkId = envelope.linkId,
                pairId = envelope.pairId ?: return,
                reqId = envelope.reqId,
                code = code,
                message = message,
                retryAfterMs = retryAfterMs,
                serverTs = serverTs,
                skewMs = skewMs,
            ),
        )
    }

    private fun purgeExpiredCaches(now: Long) {
        if (responseCache.size > 512) {
            responseCache.entries.removeIf { it.value.expiresAt <= now }
        }
        if (pairResponseCache.size > 64) {
            pairResponseCache.entries.removeIf { it.value.expiresAt <= now }
        }
    }

    private fun sendLinkError(
        envelope: RelayEnvelope,
        code: String,
        message: String,
    ) {
        client?.send(RelayWire.linkError(envelope.consumerId, envelope.linkId, envelope.reqId, code, message))
    }

    suspend fun createShare(
        context: Context,
        label: String,
        scopes: Set<RelayScope>,
        selectedUris: List<String> = emptyList(),
    ): Result<VfsShareRecord> = withContext(Dispatchers.IO) {
        createShareResult(context, label, scopes, selectedUris)
    }

    private suspend fun createShareResult(
        context: Context,
        label: String,
        scopes: Set<RelayScope>,
        selectedUris: List<String>,
    ): Result<VfsShareRecord> =
        runCatching {
            ensure(context)
            checkStore()
            val settings = serverStore!!.load()
            val server =
                settings.selectedServer()
                    ?: throw RelayProvisionException("NO_SERVER", "Select a Relay Server first.", false)
            val account =
                settings.accounts.firstOrNull {
                    it.serverId == server.id && it.canonicalBase == server.baseUrl
                }
            val existing = repository().load().filter { it.revokedAt == null && it.registrationState != VfsShareRecord.STATE_FAILED }
            if (existing.count { it.serverId == server.id && it.canonicalBase == server.baseUrl } >= MAX_SHARES) {
                throw RelayProvisionException("SHARE_LIMIT", "This Relay Server already has 8 Shares. Revoke one first.", false)
            }
            if (scopes.isEmpty()) throw RelayProvisionException("EMPTY_SCOPE", "Choose at least one scope.", false)
            val cleanLabel = sanitizeLabel(label)
            val linkId = RelayCrypto.randomToken(16)
            val shareId = RelayCrypto.randomToken(16)
            val secret = RelayCrypto.randomBytes(32)
            val created =
                shareStore!!.newRecord(
                    shareId = shareId,
                    label = cleanLabel,
                    linkId = linkId,
                    secret = secret,
                    scopes = scopes,
                    serverId = server.id,
                    relayId = account?.relayId.orEmpty(),
                    canonicalBase = server.baseUrl,
                    deviceId = account?.deviceId ?: serverStore!!.relayDeviceId(),
                    selectedUris = selectedUris,
                )
            secret.fill(0)
            repository().add(created)
            publishShares()
            scheduleActivation(context.applicationContext, shareId)
            created
        }

    private fun scheduleActivation(
        context: Context,
        shareId: String,
    ) {
        activationJobs.compute(shareId) { _, existing ->
            if (existing?.isActive == true) return@compute existing
            scope.launch {
                try {
                    activateShare(context, shareId)
                } finally {
                    activationJobs.remove(shareId)
                }
            }
        }
    }

    /** Completes the network half of Create after the local Link is already visible. */
    suspend fun activateShare(
        context: Context,
        shareId: String,
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                ensure(context)
                checkStore()
                val record =
                    latestShare(shareId)
                        ?: throw RelayProvisionException("SHARE_NOT_FOUND", "This Share no longer exists.", false)
                if (record.revokedAt != null) return@runCatching
                if (record.registrationState == VfsShareRecord.STATE_REGISTERED) return@runCatching
                if (_state.value is VfsRelayState.Connected) {
                    registerAll(context, clientGeneration)
                } else if (client?.isOpen() != true) {
                    syncServing(context).getOrThrow()
                }
                awaitRegistration(shareId)
                Unit
            }.onFailure { error ->
                if (error is CancellationException) throw error
                val message = error.message ?: "Could not register this Link."
                repository().update(shareId) { record ->
                    if (record.revokedAt != null) null else record.copy(lastError = message)
                }
                publishShares()
            }
        }

    private suspend fun awaitRegistration(
        shareId: String,
        timeoutMs: Long = 15_000L,
    ): VfsShareRecord {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val record =
                latestShare(shareId)
                    ?: throw RelayProvisionException("SHARE_NOT_FOUND", "This Share no longer exists.", false)
            if (record.revokedAt != null) {
                throw RelayProvisionException("SHARE_REVOKED", "This Share was revoked.", false)
            }
            when (record.registrationState) {
                VfsShareRecord.STATE_REGISTERED -> return record
                VfsShareRecord.STATE_FAILED ->
                    throw RelayProvisionException(
                        "SHARE_REGISTRATION_FAILED",
                        record.lastError ?: "The Relay Server rejected this Link.",
                        false,
                    )
            }
            if (System.currentTimeMillis() >= deadline) {
                throw RelayProvisionException(
                    "SHARE_NOT_REGISTERED",
                    "This Link is still registering. Try again in a moment.",
                    true,
                )
            }
            delay(100L)
        }
    }

    suspend fun newCode(
        context: Context,
        shareId: String,
    ): Result<VfsPairingUi> = withContext(Dispatchers.IO) { newCodeResult(context, shareId) }

    private suspend fun newCodeResult(
        context: Context,
        shareId: String,
    ): Result<VfsPairingUi> =
        runCatching {
            ensure(context)
            checkStore()
            if (client?.isOpen() != true) {
                syncServing(context).getOrThrow()
            }
            if (client?.isOpen() != true) {
                throw RelayProvisionException("NOT_CONNECTED", "VFS could not reach the Relay Server.", false)
            }
            val record = latestShare(shareId)
                ?: throw RelayProvisionException("SHARE_NOT_FOUND", "This Share no longer exists.", false)
            if (record.revokedAt != null) throw RelayProvisionException("SHARE_REVOKED", "This Share was revoked.", false)
            if (record.stopped) throw RelayProvisionException("SHARE_PAUSED", "This Link is stopped. Start it first.", true)
            val ready =
                if (record.registrationState == VfsShareRecord.STATE_REGISTERED) {
                    record
                } else {
                    awaitRegistration(shareId)
                }
            val settings = serverStore!!.load()
            val server =
                settings.selectedServer()
                    ?: throw RelayProvisionException("NO_SERVER", "Select a Relay Server first.", false)
            if (ready.serverId != server.id || ready.canonicalBase != server.baseUrl) {
                throw RelayProvisionException(
                    "SHARE_OFFLINE",
                    "This Share belongs to another Relay Server. Switch to that Relay Server first.",
                    false,
                )
            }
            if (ready.registrationState != VfsShareRecord.STATE_REGISTERED ||
                !linkGate.canSendPair(ready.linkId)
            ) {
                throw RelayProvisionException("SHARE_NOT_REGISTERED", "This Share is not registered with the Relay Server. Reconnect and try again.", true)
            }
            val now = System.currentTimeMillis()
            pairResponseCache.keys.removeIf { it.startsWith("${ready.linkId}|") }
            val code = VfsPairing.generate()
            val pairId = VfsPairing.pairId(ready.linkId, code)
            val expiresAt = now + VfsPairing.TTL_MS
            val activated =
                repository().update(shareId) { current ->
                    if (current.revokedAt != null ||
                        current.revokingAt != null ||
                        current.serverId != server.id ||
                        current.canonicalBase != server.baseUrl ||
                        current.registrationState != VfsShareRecord.STATE_REGISTERED
                    ) {
                        null
                    } else {
                        current.nextPairState(
                            VfsShareRecord.PAIR_ACTIVE,
                            pairId = pairId,
                            expiresAt = expiresAt,
                        )
                    }
                } ?: throw RelayProvisionException(
                    "SHARE_CHANGED",
                    "This Share changed or was revoked. Reconnect and try again.",
                    true,
                )
            codes.put(VfsPairingCode(shareId, pairId, code, now, expiresAt))
            val ack = sendPairControlAwait(activated, pairId, PAIR_TTL_SECONDS, "active")
            if (ack !is RelayIncoming.Ack) {
                codes.remove(shareId)
                repository().update(shareId) { current ->
                    if (current.pairId == pairId && current.pairState == VfsShareRecord.PAIR_ACTIVE) {
                        current.nextPairState(VfsShareRecord.PAIR_EXPIRED, expiresAt = null)
                    } else {
                        current
                    }
                }
                publishConnected()
                throw RelayProvisionException(
                    "PAIR_NOT_ACKED",
                    "The Relay Server did not confirm the Pairing Code. Try again.",
                    true,
                )
            }
            schedulePairExpiry(shareId, pairId, expiresAt)
            publishConnected()
            pairingUi(shareId)
                ?: throw RelayProvisionException("PAIR_NOT_READY", "Could not prepare the Pairing Code.", true)
        }

    private suspend fun sendPairControlAwait(
        record: VfsShareRecord,
        pairId: String,
        ttlSeconds: Long,
        state: String,
    ): Any? {
        val controlId = RelayCrypto.randomToken(16)
        val json = RelayWire.pairControl(controlId, record.linkId, pairId, ttlSeconds, state, record.pairEpoch)
        return sendControlAwait(controlId, json, CONTROL_TIMEOUT_MS)
    }

    private fun schedulePairExpiry(
        shareId: String,
        pairId: String,
        expiresAt: Long,
    ) {
        expiryJobs.remove(shareId)?.cancel()
        expiryJobs[shareId] =
            scope.launch {
                delay((expiresAt - System.currentTimeMillis()).coerceAtLeast(0))
                val expired =
                    repository().update(shareId) { current ->
                        if (current.pairId == pairId && current.pairState == VfsShareRecord.PAIR_ACTIVE) {
                            current.nextPairState(VfsShareRecord.PAIR_EXPIRED, expiresAt = null)
                        } else {
                            null
                        }
                    } ?: return@launch
                codes.remove(shareId)
                sendPairState(expired)
                publishConnected()
            }
    }

    fun pairingUi(shareId: String): VfsPairingUi? {
        val record = latestShare(shareId) ?: return null
        val code = codes.get(shareId) ?: return null
        if (record.pairState != VfsShareRecord.PAIR_ACTIVE) return null
        if (code.isExpired(System.currentTimeMillis())) return null
        val text = VfsPairing.ascii(code.code)
        return VfsPairingUi(
            shareId = shareId,
            linkId = record.linkId,
            code = text,
            expiresAt = code.expiresAt,
        )
    }

    fun isDesiredConnected(context: Context): Boolean {
        ensure(context)
        return serverStore?.load()?.desiredConnected == true
    }

    suspend fun stopSharing(context: Context): Result<Unit> =
        withContext(Dispatchers.IO) { stopSharingResult(context) }

    /**
     * Temporarily stop or resume one Link. The Link and its key stay on disk;
     * data and pair requests get LINK_PAUSED until it is started again.
     */
    suspend fun setShareStopped(
        context: Context,
        shareId: String,
        stopped: Boolean,
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                ensure(context)
                checkStore()
                val current =
                    latestShare(shareId)
                        ?: throw RelayProvisionException("SHARE_NOT_FOUND", "This Share no longer exists.", false)
                if (current.revokedAt != null || current.revokingAt != null) {
                    throw RelayProvisionException("SHARE_REVOKED", "This Share was revoked.", false)
                }
                if (stopped) {
                    codes.remove(shareId)
                    expiryJobs.remove(shareId)?.cancel()
                    responseCache.keys.removeIf { it.startsWith("${current.linkId}|") }
                    pairResponseCache.keys.removeIf { it.startsWith("${current.linkId}|") }
                }
                val updated =
                    repository().update(shareId) { record ->
                        if (record.revokedAt != null || record.revokingAt != null) {
                            null
                        } else {
                            val paused =
                                if (stopped && record.pairState == VfsShareRecord.PAIR_ACTIVE) {
                                    record.nextPairState(VfsShareRecord.PAIR_EXPIRED, expiresAt = null)
                                } else {
                                    record
                                }
                            paused.copy(
                                stopped = stopped,
                                lastError = if (stopped) paused.lastError else null,
                            )
                        }
                    } ?: throw RelayProvisionException("SHARE_CHANGED", "This Link changed. Try again.", true)
                publishShares()
                if (!stopped && client?.isOpen() != true) {
                    syncServing(context).getOrThrow()
                }
                publishConnected()
                updated
                Unit
            }
        }

    private suspend fun stopSharingResult(context: Context): Result<Unit> =
        runCatching {
            ensure(context)
            checkStore()
            reconnectJob?.cancel()
            val settings = serverStore!!.update { it.copy(desiredConnected = false) }
            val server = settings.selectedServer()
            val records =
                if (server == null) {
                    emptyList()
                } else {
                    repository().load().filter {
                        it.serverId == server.id &&
                            it.canonicalBase == server.baseUrl &&
                            it.revokedAt == null
                    }
                }
            for (record in records) {
                val paused =
                    repository().update(record.shareId) { current ->
                        if (current.revokedAt != null || current.revokingAt != null) {
                            null
                        } else {
                            current.nextPairState(
                                VfsShareRecord.PAIR_PAUSED,
                                expiresAt = null,
                            )
                        }
                    } ?: continue
                codes.remove(record.shareId)
                if (client?.isOpen() == true && paused.registrationState == VfsShareRecord.STATE_REGISTERED) {
                    sendPairControl(paused, null, null, "paused")
                }
            }
            manualClose = true
            heartbeatJob?.cancel()
            linkGate.clear()
            responseCache.clear()
            pairResponseCache.clear()
            client?.close(1000, "stop sharing")
            client = null
            _state.value = VfsRelayState.Idle
            VfsSharingService.stop(context)
            publishShares()
        }

    suspend fun disconnect(context: Context): Result<Unit> =
        withContext(Dispatchers.IO) { disconnectResult(context) }

    private suspend fun disconnectResult(context: Context): Result<Unit> =
        runCatching {
            ensure(context)
            checkStore()
            reconnectJob?.cancel()
            val settings = serverStore!!.update { it.copy(desiredConnected = false) }
            val server = settings.selectedServer()
            val records =
                if (server == null) {
                    emptyList()
                } else {
                    repository().load().filter {
                        it.serverId == server.id &&
                            it.canonicalBase == server.baseUrl &&
                            it.revokedAt == null &&
                            it.registrationState == VfsShareRecord.STATE_REGISTERED
                    }
                }
            for (record in records) {
                repository().update(record.shareId) { current ->
                    if (current.revokedAt != null || current.revokingAt != null) {
                        null
                    } else {
                        current.nextPairState(VfsShareRecord.PAIR_PAUSED, expiresAt = null)
                    }
                }
                codes.remove(record.shareId)
                sendUnregisterBestEffort(record.linkId, "shutdown")
            }
            manualClose = true
            heartbeatJob?.cancel()
            linkGate.clear()
            client?.close(1000, "disconnect")
            client = null
            _state.value = VfsRelayState.Idle
            VfsSharingService.stop(context)
            publishShares()
        }

    suspend fun revokeShare(
        context: Context,
        shareId: String,
    ): Result<Unit> = withContext(Dispatchers.IO) { revokeShareResult(context, shareId) }

    private suspend fun revokeShareResult(
        context: Context,
        shareId: String,
    ): Result<Unit> =
        runCatching {
            ensure(context)
            checkStore()
            val record = latestShare(shareId)
                ?: throw RelayProvisionException("SHARE_NOT_FOUND", "This Share no longer exists.", false)
            val revoked =
                repository().update(shareId) { current ->
                    if (current.revokedAt != null || current.revokingAt != null) {
                        current
                    } else {
                        current.copy(
                            revokedAt = System.currentTimeMillis(),
                            revokingAt = System.currentTimeMillis(),
                            pairState = VfsShareRecord.PAIR_PAUSED,
                            pairEpoch = current.pairEpoch + 1,
                            // The Share Key is gone locally even if the tombstone
                            // notification has to be retried on the next Connect.
                            secretCiphertext = "",
                            lastError = null,
                        )
                    }
                } ?: throw RelayProvisionException("SHARE_NOT_FOUND", "This Share no longer exists.", false)
            codes.remove(shareId)
            expiryJobs.remove(shareId)?.cancel()
            revokedLinkIds.add(revoked.linkId)
            linkGate.forget(revoked.linkId)
            responseCache.keys.removeIf { it.startsWith("${revoked.linkId}|") }
            pairResponseCache.keys.removeIf { it.startsWith("${revoked.linkId}|") }
            val settings = serverStore!!.load()
            val selected = settings.selectedServer()
            val owningServer =
                selected != null &&
                    revoked.serverId == selected.id &&
                    revoked.canonicalBase == selected.baseUrl
            // v1 has no cross-server pending Revoke: an offline Share owned by
            // another Relay Server is deleted locally; reconnect to that server
            // to tombstone it remotely.
            val tombstoned = !owningServer || sendUnregisterBestEffort(revoked.linkId, "revoked")
            if (tombstoned) {
                removeShare(shareId)
            }
            val remaining =
                if (selected == null) {
                    emptyList()
                } else {
                    RelayRuntimePolicy.currentServerActiveShares(
                        repository().load(),
                        selected.id,
                        selected.baseUrl,
                    )
                }
            if (remaining.isEmpty() && tombstoned) {
                reconnectJob?.cancel()
                serverStore!!.update { it.copy(desiredConnected = false) }
                manualClose = true
                heartbeatJob?.cancel()
                client?.close(1000, "no shares")
                client = null
                _state.value = VfsRelayState.Idle
                VfsSharingService.stop(context)
            } else if (remaining.isEmpty()) {
                // The last current-server Share is revoked but the tombstone
                // is still pending; close the empty session and retry later.
                manualClose = true
                client?.close(1000, "revoke pending")
                client = null
                manualClose = false
                scheduleReconnect(context, "Revoke still needs the Relay Server")
            } else if (RelayRuntimePolicy.shouldRescheduleAfterRevoke(
                    remaining = remaining.size,
                    tombstoned = tombstoned,
                    desiredConnected = serverStore!!.load().desiredConnected,
                    clientOpen = client?.isOpen() == true,
                )
            ) {
                scheduleReconnect(context, "Revoke still needs the Relay Server")
            }
            publishConnected()
        }

    private suspend fun stopEmptySession(context: Context) {
        val settings = serverStore?.load() ?: return
        val server = settings.selectedServer() ?: return
        stopIfNoCurrentServerShares(context, server.id, server.baseUrl)
    }

    /** After a revoked-link cleanup, never keep an empty "Connected" session.
     * Pending tombstone records stay on disk for the next Connect retry.
     */
    private suspend fun stopIfNoCurrentServerShares(
        context: Context,
        serverId: String,
        canonicalBase: String,
    ): Boolean {
        val remaining =
            RelayRuntimePolicy.currentServerActiveShares(
                repository().load(),
                serverId,
                canonicalBase,
            )
        if (!RelayRuntimePolicy.shouldStopEmptySession(remaining.size)) return false
        reconnectJob?.cancel()
        serverStore?.update { it.copy(desiredConnected = false) }
        manualClose = true
        heartbeatJob?.cancel()
        linkGate.clear()
        client?.close(1000, "no shares")
        client = null
        _state.value = VfsRelayState.Idle
        publishShares()
        return true
    }

    /** Called when the sharing FGS disappears; never keeps a silent Link alive. */
    fun onSharingServiceStopped() {
        reconnectJob?.cancel()
        manualClose = true
        heartbeatJob?.cancel()
        linkGate.clear()
        client?.close(1000, "sharing service stopped")
        client = null
        if (_state.value is VfsRelayState.Connecting ||
            _state.value is VfsRelayState.Connected ||
            _state.value is VfsRelayState.Backoff
        ) {
            _state.value = VfsRelayState.Idle
        }
    }

    /**
     * Drops every Link bound to one Relay Server. Server settings, token,
     * account and provider key stay; clients must pair again.
     */
    suspend fun resetLinksForServer(
        context: Context,
        serverId: String,
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                ensure(context)
                checkStore()
                val settings = serverStore!!.load()
                val server =
                    settings.server(serverId)
                        ?: throw RelayProvisionException("NO_SERVER", "Relay Server not found.", false)
                val records = repository().load().filter { it.serverId == serverId }
                val selected = settings.selectedServerId == serverId
                for (record in records) {
                    if (selected &&
                        client?.isOpen() == true &&
                        record.revokedAt == null &&
                        record.revokingAt == null &&
                        record.registrationState == VfsShareRecord.STATE_REGISTERED
                    ) {
                        // Best effort: the local key is removed either way, so a
                        // missed tombstone only leaves an unreachable remote Link.
                        val controlId = RelayCrypto.randomToken(16)
                        client?.send(RelayWire.unregisterControl(controlId, record.linkId, "revoked"))
                    }
                    codes.remove(record.shareId)
                    expiryJobs.remove(record.shareId)?.cancel()
                    sourceCache.remove(record.shareId)
                    revokedLinkIds.add(record.linkId)
                    responseCache.keys.removeIf { it.startsWith("${record.linkId}|") }
                    pairResponseCache.keys.removeIf { it.startsWith("${record.linkId}|") }
                    repository().remove(record.shareId)
                }
                publishShares()
                if (selected) {
                    totalBytesSent.set(0)
                    stopIfNoCurrentServerShares(context, server.id, server.baseUrl)
                }
            }
        }

    /**
     * Destructive recovery for DEVICE_REVOKED or a damaged Share store. The
     * caller must show an explicit confirmation: this also resets the shared
         */
    suspend fun resetShareFilesData(context: Context): Result<Unit> =
        withContext(Dispatchers.IO) { resetShareFilesDataResult(context) }

    private suspend fun resetShareFilesDataResult(context: Context): Result<Unit> =
        runCatching {
            ensure(context)
            reconnectJob?.cancel()
            manualClose = true
            heartbeatJob?.cancel()
            client?.close(1000, "share files reset")
            client = null
            target = null
            providerKey = null
            linkGate.clear()
            val app = appContext ?: context.applicationContext
            val vfs = File(app.noBackupFilesDir, "vfs")
            File(vfs, "relay").deleteRecursively()
            File(vfs, "device-secrets").deleteRecursively()
            File(vfs, "pending").deleteRecursively()
            codes.clear()
            responseCache.clear()
            pairResponseCache.clear()
            sourceCache.clear()
            revokedLinkIds.clear()
            totalBytesSent.set(0)
            _shares.value = emptyList()
            storeBlocked = false
            synchronized(this) {
                appContext = null
                shareStore = null
                shareRepository = null
                serverStore = null
                deviceSecretStore = null
            }
            ensure(app)
            _state.value = VfsRelayState.Idle
            VfsSharingService.stop(app)
        }

    fun sharesForDisplay(): List<VfsRelayShareView> = _shares.value

    private fun publishConnected() {
        publishShares()
        if (client?.isOpen() == true) {
            _state.value = VfsRelayState.Connected(_shares.value, totalBytesSent.get())
        }
    }

    private fun publishServers() {
        val settings = serverStore?.load() ?: return
        _selectedServerId.value = settings.selectedServerId
        _servers.value =
            settings.servers.map { server ->
                VfsRelayServerView(
                    id = server.id,
                    name = server.name,
                    endpoint = server.baseUrl,
                    selected = server.id == settings.selectedServerId,
                )
            }
    }

    private fun publishShares() {
        val settings = runCatching { serverStore?.load() }.getOrNull()
        val selected = settings?.selectedServer()
        val records = runCatching { repository().load() }.getOrNull() ?: return
        val views =
            records.map { record ->
                VfsRelayShareView(
                    shareId = record.shareId,
                    serverId = record.serverId,
                    label = record.label,
                    linkId = record.linkId,
                    status =
                        RelayRuntimePolicy.shareStatus(
                            record,
                            selected?.id,
                            selected?.baseUrl,
                        ),
                    scopes = record.scopes,
                    pairedAt = record.pairedAt,
                    lastUsedAt = record.lastUsedAt,
                    pairExpiresAt = record.pairExpiresAt,
                    error = record.lastError,
                    stopped = record.stopped,
                )
            }
        _shares.value = views
        if (_state.value is VfsRelayState.Connected) {
            _state.value = VfsRelayState.Connected(views, totalBytesSent.get())
        }
    }

    private fun latestShare(shareId: String): VfsShareRecord? =
        runCatching { repository().load().firstOrNull { it.shareId == shareId } }.getOrNull()

    private fun removeShare(shareId: String) {
        repository().remove(shareId)
        sourceCache.remove(shareId)
        publishShares()
    }

    private fun recordLastUsed(shareId: String) {
        val updated =
            runCatching {
                repository().markLastUsed(shareId, System.currentTimeMillis(), LAST_USED_THROTTLE_MS)
            }.getOrNull() ?: return
        if (updated.lastUsedAt != null) publishShares()
    }

    private suspend fun markPairExpired(
        record: VfsShareRecord,
        pairId: String,
    ) {
        codes.remove(record.shareId)
        val expired =
            runCatching {
                repository().update(record.shareId) { current ->
                    if (current.revokedAt != null ||
                        current.pairState != VfsShareRecord.PAIR_ACTIVE ||
                        current.pairId != pairId
                    ) {
                        null
                    } else {
                        current.nextPairState(
                            VfsShareRecord.PAIR_EXPIRED,
                            expiresAt = null,
                        )
                    }
                }
            }.getOrNull()
        if (expired != null) sendPairState(expired)
    }

    private fun normalizeAfterProcessDeath() {
        val records = repository().load()
        var changed = false
        val updated =
            records.map { record ->
                if (record.pairState == VfsShareRecord.PAIR_ACTIVE && codes.get(record.shareId) == null) {
                    changed = true
                    record.nextPairState(
                        VfsShareRecord.PAIR_EXPIRED,
                        expiresAt = null,
                    )
                } else {
                    record
                }
            }
        if (changed) repository().replaceAll(updated)
        publishShares()
    }

    private fun sanitizeLabel(label: String): String =
        label
            .filter { !Character.isISOControl(it) && it != '|' }
            .trim()
            .take(64)

    private fun repository(): VfsShareRepository =
        shareRepository ?: throw RelayProvisionException(
            "STORE_UNAVAILABLE",
            "VFS is not initialized yet. Try again.",
            true,
        )

    private fun checkStore() {
        if (storeBlocked) {
            throw RelayProvisionException(
                "SHARE_STORE_CORRUPT",
                "The Share list is damaged. Restore or remove noBackupFilesDir/vfs/relay/shares.json first.",
                false,
            )
        }
    }

    private fun egress(): VfsRelayEgress =
        egressInstance ?: throw RelayProvisionException(
            "EGRESS_UNAVAILABLE",
            "VFS is not initialized yet. Try again.",
            true,
        )

    private fun provisioner(): VfsProviderKeyProvisioner =
        provisionerInstance ?: throw RelayProvisionException(
            "EGRESS_UNAVAILABLE",
            "VFS is not initialized yet. Try again.",
            true,
        )

    private fun isResetRecommended(error: Throwable): Boolean =
        error is RelayProvisionException &&
            error.code in setOf("DEVICE_REVOKED", "DEVICE_AUTH", "SHARE_STORE_CORRUPT")

    private fun providerName(): String =
        Build.MODEL?.takeIf { it.isNotBlank() }?.take(64) ?: PROVIDER_KEY_NAME

    private data class CachedResponse(
        val json: String,
        val expiresAt: Long,
    )

    private data class RelayDispatch(
        val payload: ByteArray,
        val bytesRead: Int,
        val cache: Boolean = true,
    )
}

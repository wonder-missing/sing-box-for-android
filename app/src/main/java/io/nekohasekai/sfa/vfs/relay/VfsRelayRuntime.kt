package io.nekohasekai.sfa.vfs.relay

import android.content.Context
import android.os.Build
import io.nekohasekai.sfa.BuildConfig
import io.nekohasekai.sfa.agent.mcp.McpRuntimes
import io.nekohasekai.sfa.bg.VfsSharingService
import io.nekohasekai.sfa.vfs.VfsDeviceIdStore
import io.nekohasekai.sfa.vfs.VfsPrivateFiles
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class VfsRelayShareView(
    val shareId: String,
    val label: String,
    val linkId: String,
    val status: String,
    val scopes: List<String>,
    val pairedAt: Long?,
    val lastUsedAt: Long?,
    val pairExpiresAt: Long?,
)

sealed interface VfsRelayState {
    data object Idle : VfsRelayState

    data class Connecting(val message: String = "") : VfsRelayState

    data class Connected(
        val shares: List<VfsRelayShareView>,
        val totalBytesRead: Long,
    ) : VfsRelayState

    data class Backoff(
        val message: String,
        val retryAtMs: Long,
    ) : VfsRelayState

    data class Error(
        val message: String,
        val actionable: String? = null,
    ) : VfsRelayState
}

data class VfsPairingUi(
    val shareId: String,
    val linkId: String,
    val pairId: String,
    val code: String,
    val expiresAt: Long,
    val command: String,
)

/**
 * Process-wide Share Key VFS provider. One WebSocket carries every Link for the
 * currently selected Portal. All state transitions persist before the network
 * ack where the contract requires it; the Pairing Code itself stays in memory.
 */
object VfsRelayRuntime {
    private const val PROVIDER_KEY_NAME = "WonderSing"
    private const val RECONNECT_MAX_MS = 30_000L
    private const val CONTROL_TIMEOUT_MS = 5_000L
    private const val PAIR_TTL_SECONDS = 300L
    private const val LAST_USED_THROTTLE_MS = 5L * 60L * 1000L
    private const val RESPONSE_CACHE_MS = 120_000L
    private const val MAX_SHARES = 8

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Mutex()
    private val random = SecureRandom()
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
    private val totalBytesRead = AtomicLong(0)

    private val _state = MutableStateFlow<VfsRelayState>(VfsRelayState.Idle)
    val state: StateFlow<VfsRelayState> = _state.asStateFlow()

    private val _shares = MutableStateFlow<List<VfsRelayShareView>>(emptyList())
    val shares: StateFlow<List<VfsRelayShareView>> = _shares.asStateFlow()

    @Volatile private var appContext: Context? = null
    @Volatile private var shareStore: VfsShareStore? = null
    @Volatile private var configStore: VfsRelayConfigStore? = null
    @Volatile private var deviceSecretStore: VfsDeviceSecretStore? = null
    @Volatile private var deviceIdStore: VfsDeviceIdStore? = null
    @Volatile private var client: VfsRelayClient? = null
    @Volatile private var target: PortalTarget? = null
    @Volatile private var providerKey: String? = null
    @Volatile private var clientGeneration = 0
    @Volatile private var manualClose = false
    @Volatile private var attachedToService = false
    @Volatile private var lastInboundAt = 0L
    private var reconnectJob: Job? = null
    private var heartbeatJob: Job? = null
    private var expiryJobs = ConcurrentHashMap<String, Job>()
    private var reconnectAttempt = 0
    private val provisioner = VfsProviderKeyProvisioner()

    fun isAvailable(): Boolean = BuildConfig.SHARE_KEY_VFS && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N

    fun ensure(context: Context) {
        if (appContext != null) return
        synchronized(this) {
            if (appContext != null) return
            val app = context.applicationContext
            val base = VfsPrivateFiles.directory(app.noBackupFilesDir)
            val cipher = VfsShareCipherFactory.create()
            shareStore = VfsShareStore(File(base, "relay/shares.json"), cipher)
            configStore = VfsRelayConfigStore(File(base, "relay/config.json"), cipher)
            deviceSecretStore = VfsDeviceSecretStore(File(base, "relay"), cipher)
            deviceIdStore = VfsDeviceIdStore(VfsPrivateFiles.deviceIdFile(app.noBackupFilesDir))
            appContext = app
            normalizeAfterProcessDeath()
        }
    }

    /** Called from Application.onCreate; reconnect once if the user was connected. */
    fun onAppStart(context: Context) {
        if (!isAvailable()) return
        ensure(context)
        val config = configStore?.load() ?: return
        if (!config.desiredConnected) return
        if (config.portalServerId.isBlank()) return
        scope.launch {
            runCatching { connectInternal(context.applicationContext, auto = true) }
                .onFailure { error ->
                    if (error is RelayProvisionException && !error.retryable) {
                        configStore?.let { store ->
                            val latest = store.load()
                            if (latest.desiredConnected) store.save(latest.copy(desiredConnected = false))
                        }
                        _state.value = VfsRelayState.Error(error.message ?: "Connect failed")
                    } else {
                        scheduleReconnect(context.applicationContext, error.message ?: "Connect failed")
                    }
                }
        }
    }

    suspend fun setPortal(
        context: Context,
        serverId: String,
    ): Result<Unit> =
        runCatching {
            ensure(context)
            val config = configStore!!.load()
            val mcp = McpRuntimes.get(context).relayPortal(serverId)
                ?: throw RelayProvisionException("PORTAL_NOT_FOUND", "Enable that MCP server first.", false)
            val resolved = provisioner.targetOf(mcp)
            configStore!!.save(
                config.copy(portalServerId = serverId, canonicalBase = resolved.canonicalBase),
            )
        }

    suspend fun connect(context: Context): Result<Unit> =
        runCatching {
            ensure(context)
            val config = configStore!!.load()
            if (config.portalServerId.isBlank()) {
                throw RelayProvisionException(
                    "NO_PORTAL",
                    "Choose a Portal (an enabled MCP server) first.",
                    false,
                )
            }
            configStore!!.save(config.copy(desiredConnected = true))
            connectInternal(context.applicationContext, auto = false)
        }.onFailure { error ->
            if (error is RelayProvisionException && !error.retryable) {
                runCatching {
                    val config = configStore!!.load()
                    configStore!!.save(config.copy(desiredConnected = false))
                }
                _state.value = VfsRelayState.Error(error.message ?: "Connect failed")
            } else {
                scheduleReconnect(context.applicationContext, error.message ?: "Connect failed")
            }
        }

    private suspend fun connectInternal(
        context: Context,
        auto: Boolean,
    ): Unit =
        gate.withLock {
            if (!isAvailable()) throw RelayProvisionException("UNSUPPORTED", "Share files needs Android 7.0 or newer.", false)
            val config = configStore!!.load()
            if (config.portalServerId.isBlank()) {
                throw RelayProvisionException("NO_PORTAL", "Choose a Portal first.", false)
            }
            if (client?.isOpen() == true) {
                publishConnected()
                return@withLock
            }
            _state.value = VfsRelayState.Connecting("Contacting the Portal")
            VfsSharingService.start(context)
            val mcp = McpRuntimes.get(context).relayPortal(config.portalServerId)
                ?: throw RelayProvisionException(
                    "PORTAL_NOT_FOUND",
                    "The selected Portal is no longer enabled. Choose another Portal or enable it in Conductor settings.",
                    false,
                )
            val resolved = provisioner.targetOf(mcp)
            val deviceId = deviceIdStore!!.getOrCreate()
            val account =
                config.accounts.firstOrNull {
                    it.portalServerId == resolved.portalServerId &&
                        it.canonicalBase == resolved.canonicalBase
                }
            val deviceSecret =
                if (account != null) {
                    deviceSecretStore!!.existing(resolved.canonicalBase, account.hookId, deviceId)
                        ?: deviceSecretStore!!.getOrCreatePending(
                            resolved.portalServerId,
                            resolved.canonicalBase,
                            deviceId,
                        )
                } else {
                    deviceSecretStore!!.getOrCreatePending(
                        resolved.portalServerId,
                        resolved.canonicalBase,
                        deviceId,
                    )
                }
            val issue = provisioner.issue(resolved, deviceId, deviceSecret, providerName())
            val promoted =
                deviceSecretStore!!.promotePending(
                    resolved.portalServerId,
                    resolved.canonicalBase,
                    issue.hookId,
                    deviceId,
                    deviceSecret,
                )
            val sealedKey =
                configStore!!.sealProviderKey(
                    resolved.canonicalBase,
                    issue.hookId,
                    deviceId,
                    issue.providerKeyId,
                    issue.providerKeyBytes(),
                )
            val newAccount =
                VfsRelayAccount(
                    portalServerId = resolved.portalServerId,
                    canonicalBase = resolved.canonicalBase,
                    hookId = issue.hookId,
                    deviceId = deviceId,
                    providerKeyId = issue.providerKeyId,
                    providerKeyCiphertext = sealedKey,
                    createdAt = account?.createdAt ?: issue.createdAt,
                    rotatedAt = account?.let { issue.createdAt },
                )
            val accounts = config.accounts.filterNot {
                it.portalServerId == resolved.portalServerId && it.canonicalBase == resolved.canonicalBase
            } + newAccount
            configStore!!.save(
                config.copy(
                    portalServerId = resolved.portalServerId,
                    canonicalBase = resolved.canonicalBase,
                    desiredConnected = true,
                    accounts = accounts,
                ),
            )
            target = resolved
            providerKey = issue.providerKey
            lastInboundAt = System.currentTimeMillis()
            manualClose = false
            openSocket(context, resolved, issue.providerKey, auto)
            promoted.fill(0)
        }

    private suspend fun openSocket(
        context: Context,
        target: PortalTarget,
        key: String,
        auto: Boolean,
    ) {
        val url = relayProviderUrl(target.canonicalBase)
            ?: throw RelayProvisionException(
                "INVALID_PORTAL",
                "This Portal URL is not HTTPS. Share files needs an HTTPS Portal (Funnel).",
                false,
            )
        val generation = ++clientGeneration
        val opened = CompletableDeferred<Boolean>()
        val created =
            VfsRelayClient(
                relayUrl = url,
                providerKey = key,
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
                "Could not open the Portal Relay connection. Check the Portal and your network.",
                true,
            )
        }
    }

    private suspend fun onSocketOpen(
        context: Context,
        generation: Int,
        auto: Boolean,
    ) {
        _state.value = VfsRelayState.Connecting("Registering Shares")
        registerAll(context, generation)
        if (generation != clientGeneration) return
        reconnectAttempt = 0
        publishConnected()
        startHeartbeat(context, generation)
    }

    private suspend fun registerAll(
        context: Context,
        generation: Int,
    ) {
        val config = configStore!!.load()
        val records = shareStore!!.load()
        for (record in records) {
            if (generation != clientGeneration) return
            if (record.portalServerId != config.portalServerId || record.canonicalBase != config.canonicalBase) {
                continue
            }
            if (record.revokedAt != null || record.revokingAt != null) {
                if (sendUnregisterBestEffort(record.linkId, "revoked")) removeShare(record.shareId)
                continue
            }
            if (record.registrationState == VfsShareRecord.STATE_FAILED) continue
            if (record.registrationState != VfsShareRecord.STATE_REGISTERED || record.registeredAt == null) {
                val key = providerKey ?: continue
                target?.let { current ->
                    runCatching { provisioner.postLink(current, key, record.linkId) }
                        .onSuccess {
                            persistShare(record.copy(registrationState = VfsShareRecord.STATE_REGISTERED, registeredAt = System.currentTimeMillis()))
                        }
                        .onFailure { error ->
                            if (error is RelayProvisionException && !error.retryable) {
                                persistShare(record.copy(registrationState = VfsShareRecord.STATE_FAILED, revokingAt = null))
                            }
                        }
                }
            }
            val current = latestShare(record.shareId) ?: record
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
        val nextGeneration = record.generation + 1
        val updated = persistShare(record.copy(generation = nextGeneration))
        val controlId = RelayCrypto.randomToken(16)
        val json =
            RelayWire.registerControl(
                controlId, updated.linkId, updated.deviceId, nextGeneration,
            )
        when (val result = sendControlAwait(controlId, json, CONTROL_TIMEOUT_MS)) {
            is RelayIncoming.Ack -> {
                linkGate.onRegisterAck(updated.linkId)
                persistShare(updated.copy(registrationState = VfsShareRecord.STATE_REGISTERED, registeredAt = updated.registeredAt ?: System.currentTimeMillis()))
                sendPairState(latestShare(updated.shareId) ?: updated)
            }

            is RelayIncoming.ControlError -> {
                when (result.code) {
                    "STALE_GENERATION" -> {
                        val current = result.currentGeneration ?: (nextGeneration + 1)
                        persistShare(updated.copy(generation = current))
                        registerLink(latestShare(updated.shareId) ?: updated, generation, retried)
                    }

                    "LINK_UNKNOWN" -> {
                        if (!retried && providerKey != null && target != null) {
                            runCatching { provisioner.postLink(target!!, providerKey!!, updated.linkId) }
                                .onSuccess {
                                    persistShare(updated.copy(registrationState = VfsShareRecord.STATE_REGISTERED, registeredAt = System.currentTimeMillis()))
                                    registerLink(latestShare(updated.shareId) ?: updated, generation, retried = true)
                                }
                        } else {
                            persistShare(updated.copy(registrationState = VfsShareRecord.STATE_FAILED))
                        }
                    }

                    "LINK_OWNED", "REVOKED", "DEVICE_MISMATCH" ->
                        persistShare(updated.copy(registrationState = VfsShareRecord.STATE_FAILED))
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
        when (record.pairState) {
            VfsShareRecord.PAIR_ACTIVE -> {
                val pairId = record.pairId ?: return
                val remaining = ((record.pairExpiresAt ?: 0) - System.currentTimeMillis()).coerceIn(1, PAIR_TTL_SECONDS * 1000)
                sendPairControl(record, pairId, (remaining + 999) / 1000, "active")
            }

            VfsShareRecord.PAIR_CONSUMED -> record.pairId?.let { sendPairControl(record, it, null, "consumed") }
            VfsShareRecord.PAIR_EXPIRED -> record.pairId?.let { sendPairControl(record, it, null, "expired") }
            VfsShareRecord.PAIR_PAUSED -> sendPairControl(record, null, null, "paused")
            else -> Unit
        }
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
            configStore?.let { store ->
                val config = store.load()
                if (config.desiredConnected) store.save(config.copy(desiredConnected = false))
            }
            _state.value =
                VfsRelayState.Error(
                    "This device was revoked on the Portal.",
                    "Create a new Share with a new Provider key, or ask the Portal administrator.",
                )
            VfsSharingService.stop(context)
            return
        }
        if (code != null && RelayCloseCodes.isSuspend(code)) {
            _state.value =
                VfsRelayState.Error(
                    "The Portal suspended Share Key VFS for this hook.",
                    "Ask the Portal administrator to re-enable it, then tap Connect.",
                )
            return
        }
        val config = configStore?.load() ?: return
        if (!config.desiredConnected) {
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
        val wait =
            when {
                reconnectAttempt <= 1 -> 1_000L
                reconnectAttempt == 2 -> 2_000L
                reconnectAttempt == 3 -> 4_000L
                reconnectAttempt == 4 -> 8_000L
                reconnectAttempt == 5 -> 15_000L
                else -> RECONNECT_MAX_MS
            }
        _state.value = VfsRelayState.Backoff(reason, System.currentTimeMillis() + wait)
        reconnectJob =
            scope.launch {
                delay(wait)
                if (!isAvailable()) return@launch
                runCatching { connectInternal(context.applicationContext, auto = true) }
                    .onFailure { error ->
                        if (error is RelayProvisionException && !error.retryable) {
                            configStore?.let { store ->
                                val config = store.load()
                                if (config.desiredConnected) store.save(config.copy(desiredConnected = false))
                            }
                            _state.value = VfsRelayState.Error(error.message ?: "Connect failed")
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
        val config = configStore?.load()
        val retryable =
            !(message.contains("revoked", ignoreCase = true) ||
                message.contains("suspended", ignoreCase = true) ||
                message.contains("OAuth", ignoreCase = true))
        if (config?.desiredConnected == true && retryable) {
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
        val record = shareStore?.load()?.firstOrNull { it.linkId == envelope.linkId } ?: run {
            sendLinkError(envelope, RelayLinkErrorCode.LINK_UNKNOWN, "link unknown")
            return
        }
        if (record.revokedAt != null || envelope.linkId in revokedLinkIds) {
            sendLinkError(envelope, RelayLinkErrorCode.SHARE_REVOKED, "share revoked")
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
        val code = codes.get(record.shareId)
        if (code == null || code.pairId != pairId || code.isExpired(now)) {
            markPairExpired(record)
            sendPairError(envelope, RelayPairErrorCode.CODE_EXPIRED, "pairing code expired")
            return
        }
        if (record.pairId != pairId) {
            sendPairError(envelope, RelayPairErrorCode.PAIR_UNKNOWN, "unknown pairing code")
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
        pairResponseCache[cacheKey] = CachedResponse(responseJson, code.expiresAt)
        val consumed =
            persistShare(
                record.nextPairState(
                    VfsShareRecord.PAIR_CONSUMED,
                    expiresAt = null,
                    pairedAt = System.currentTimeMillis(),
                ),
            )
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
        responseCache[cacheKey]?.takeIf { it.expiresAt > System.currentTimeMillis() }?.let {
            client?.send(it.json)
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
            if (response.bytesRead > 0) {
                totalBytesRead.addAndGet(response.bytesRead.toLong())
                publishConnected()
            }
            recordLastUsed(record)
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
            val count = shareStore!!.load().count { it.revokedAt == null }
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
        client?.send(text)
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
    ): Result<VfsShareRecord> =
        runCatching {
            ensure(context)
            if (client?.isOpen() != true || _state.value !is VfsRelayState.Connected) {
                throw RelayProvisionException("NOT_CONNECTED", "Connect to the Portal before creating a Share.", false)
            }
            val config = configStore!!.load()
            val account =
                config.accounts.firstOrNull {
                    it.portalServerId == config.portalServerId && it.canonicalBase == config.canonicalBase
                } ?: throw RelayProvisionException("NO_ACCOUNT", "Connect to the Portal again.", false)
            val existing = shareStore!!.load().filter { it.revokedAt == null && it.registrationState != VfsShareRecord.STATE_FAILED }
            if (existing.count { it.portalServerId == config.portalServerId && it.canonicalBase == config.canonicalBase } >= MAX_SHARES) {
                throw RelayProvisionException("SHARE_LIMIT", "This Portal already has 8 Shares. Revoke one first.", false)
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
                    portalServerId = config.portalServerId,
                    hookId = account.hookId,
                    canonicalBase = config.canonicalBase,
                    deviceId = account.deviceId,
                    selectedUris = selectedUris,
                )
            saveShares(shareStore!!.load() + created)
            secret.fill(0)
            val key = providerKey ?: throw RelayProvisionException("NO_ACCOUNT", "Connect to the Portal again.", false)
            runCatching { provisioner.postLink(target!!, key, linkId) }
                .onFailure { error ->
                    val permanent = error is RelayProvisionException && !error.retryable
                    persistShare(
                        created.copy(
                            registrationState =
                                if (permanent) VfsShareRecord.STATE_FAILED else VfsShareRecord.STATE_PENDING,
                        ),
                    )
                    throw error
                }
            persistShare(created.copy(registrationState = VfsShareRecord.STATE_REGISTERED, registeredAt = System.currentTimeMillis()))
            registerLink(latestShare(shareId) ?: created, clientGeneration)
            publishConnected()
            latestShare(shareId) ?: created
        }

    suspend fun newCode(
        context: Context,
        shareId: String,
    ): Result<VfsPairingUi> =
        runCatching {
            ensure(context)
            if (client?.isOpen() != true) {
                throw RelayProvisionException("NOT_CONNECTED", "Connect to the Portal before creating a code.", false)
            }
            val record = latestShare(shareId)
                ?: throw RelayProvisionException("SHARE_NOT_FOUND", "This Share no longer exists.", false)
            if (record.revokedAt != null) throw RelayProvisionException("SHARE_REVOKED", "This Share was revoked.", false)
            if (record.registrationState != VfsShareRecord.STATE_REGISTERED ||
                !linkGate.canSendPair(record.linkId)
            ) {
                throw RelayProvisionException("SHARE_NOT_REGISTERED", "This Share is not registered with the Portal. Reconnect and try again.", true)
            }
            val now = System.currentTimeMillis()
            val pairId = RelayCrypto.randomToken(16)
            pairResponseCache.keys.removeIf { it.startsWith("${record.linkId}|") }
            val code = VfsPairing.generate()
            val expiresAt = now + VfsPairing.TTL_MS
            val activated =
                persistShare(
                    record.nextPairState(
                        VfsShareRecord.PAIR_ACTIVE,
                        pairId = pairId,
                        expiresAt = expiresAt,
                    ),
                )
            codes.put(VfsPairingCode(shareId, pairId, code, now, expiresAt))
            val ack = sendPairControlAwait(activated, pairId, PAIR_TTL_SECONDS, "active")
            if (ack !is RelayIncoming.Ack) {
                codes.remove(shareId)
                persistShare(
                    activated.nextPairState(
                        VfsShareRecord.PAIR_EXPIRED,
                        expiresAt = null,
                    ),
                )
                publishConnected()
                throw RelayProvisionException(
                    "PAIR_NOT_ACKED",
                    "The Portal did not confirm the Pairing Code. Try again.",
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
                val current = latestShare(shareId) ?: return@launch
                if (current.pairId != pairId || current.pairState != VfsShareRecord.PAIR_ACTIVE) return@launch
                val expired =
                    persistShare(
                        current.nextPairState(
                            VfsShareRecord.PAIR_EXPIRED,
                            expiresAt = null,
                        ),
                    )
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
            pairId = code.pairId,
            code = text,
            expiresAt = code.expiresAt,
            command = VfsPairing.pairingCommand(record.linkId, code.pairId, code.code),
        )
    }

    fun selectedPortalId(context: Context): String {
        if (!isAvailable()) return ""
        ensure(context)
        return configStore?.load()?.portalServerId.orEmpty()
    }

    fun isDesiredConnected(context: Context): Boolean {
        ensure(context)
        return configStore?.load()?.desiredConnected == true
    }

    suspend fun stopSharing(context: Context): Result<Unit> =
        runCatching {
            ensure(context)
            val config = configStore!!.load()
            val records =
                shareStore!!.load().filter {
                    it.portalServerId == config.portalServerId &&
                        it.canonicalBase == config.canonicalBase &&
                        it.revokedAt == null
                }
            for (record in records) {
                val paused =
                    persistShare(
                        record.nextPairState(
                            VfsShareRecord.PAIR_PAUSED,
                            expiresAt = null,
                        ),
                    )
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
        runCatching {
            ensure(context)
            val config = configStore!!.load()
            if (config.desiredConnected) configStore!!.save(config.copy(desiredConnected = false))
            val records = shareStore!!.load().filter { it.revokedAt == null && it.registrationState == VfsShareRecord.STATE_REGISTERED }
            for (record in records) {
                sendUnregisterBestEffort(record.linkId, "shutdown")
            }
            manualClose = true
            heartbeatJob?.cancel()
            linkGate.clear()
            client?.close(1000, "disconnect")
            client = null
            _state.value = VfsRelayState.Idle
            VfsSharingService.stop(context)
        }

    suspend fun revokeShare(
        context: Context,
        shareId: String,
    ): Result<Unit> =
        runCatching {
            ensure(context)
            val record = latestShare(shareId)
                ?: throw RelayProvisionException("SHARE_NOT_FOUND", "This Share no longer exists.", false)
            val revoked =
                persistShare(
                    record.copy(
                        revokedAt = System.currentTimeMillis(),
                        revokingAt = System.currentTimeMillis(),
                        pairState = VfsShareRecord.PAIR_PAUSED,
                        pairEpoch = record.pairEpoch + 1,
                        // The Share Key is gone locally even if the tombstone
                        // notification has to be retried on the next Connect.
                        secretCiphertext = "",
                    ),
                )
            codes.remove(shareId)
            expiryJobs.remove(shareId)?.cancel()
            revokedLinkIds.add(record.linkId)
            linkGate.forget(record.linkId)
            responseCache.keys.removeIf { it.startsWith("${record.linkId}|") }
            pairResponseCache.keys.removeIf { it.startsWith("${record.linkId}|") }
            val tombstoned = sendUnregisterBestEffort(record.linkId, "revoked")
            if (tombstoned) {
                removeShare(shareId)
            }
            val remaining =
                shareStore!!.load().filter {
                    it.revokedAt == null && it.registrationState != VfsShareRecord.STATE_FAILED
                }
            if (tombstoned && remaining.isEmpty()) {
                val config = configStore!!.load()
                configStore!!.save(config.copy(desiredConnected = false))
                manualClose = true
                heartbeatJob?.cancel()
                client?.close(1000, "no shares")
                client = null
                _state.value = VfsRelayState.Idle
                VfsSharingService.stop(context)
            }
            publishConnected()
        }

    /** Called when the sharing FGS disappears; never keeps a silent Link alive. */
    fun onSharingServiceStopped() {
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

    fun sharesForDisplay(): List<VfsRelayShareView> = _shares.value

    private fun publishConnected() {
        publishShares()
        if (client?.isOpen() == true) {
            _state.value = VfsRelayState.Connected(_shares.value, totalBytesRead.get())
        }
    }

    private fun publishShares() {
        val config = configStore?.load()
        val views =
            shareStore?.load().orEmpty().map { record ->
                val status =
                    when {
                        record.revokedAt != null || record.revokingAt != null -> "revoked"
                        record.registrationState == VfsShareRecord.STATE_FAILED -> "failed"
                        record.registrationState != VfsShareRecord.STATE_REGISTERED -> "pending"
                        record.pairState == VfsShareRecord.PAIR_CONSUMED -> "paired"
                        else -> record.pairState
                    }
                VfsRelayShareView(
                    shareId = record.shareId,
                    label = record.label,
                    linkId = record.linkId,
                    status = status,
                    scopes = record.scopes,
                    pairedAt = record.pairedAt,
                    lastUsedAt = record.lastUsedAt,
                    pairExpiresAt = record.pairExpiresAt,
                )
            }
        _shares.value = views
        if (_state.value is VfsRelayState.Connected) {
            _state.value = VfsRelayState.Connected(views, totalBytesRead.get())
        }
        config?.let { /* keep a stable share list even before Connecting */ }
    }

    private fun latestShare(shareId: String): VfsShareRecord? =
        shareStore?.load()?.firstOrNull { it.shareId == shareId }

    private fun persistShare(record: VfsShareRecord): VfsShareRecord {
        val records = shareStore!!.load().toMutableList()
        val index = records.indexOfFirst { it.shareId == record.shareId }
        if (index >= 0) records[index] = record else records += record
        saveShares(records)
        publishShares()
        return record
    }

    private fun saveShares(records: List<VfsShareRecord>) {
        shareStore!!.save(records)
        publishShares()
    }

    private fun removeShare(shareId: String) {
        val records = shareStore!!.load().filterNot { it.shareId == shareId }
        shareStore!!.save(records)
        sourceCache.remove(shareId)
        publishShares()
    }

    private fun recordLastUsed(record: VfsShareRecord) {
        val now = System.currentTimeMillis()
        if (record.lastUsedAt != null && now - record.lastUsedAt < LAST_USED_THROTTLE_MS) return
        runCatching { persistShare(record.copy(lastUsedAt = now)) }
    }

    private suspend fun markPairExpired(record: VfsShareRecord) {
        codes.remove(record.shareId)
        val expired =
            runCatching {
                persistShare(
                    record.nextPairState(
                        VfsShareRecord.PAIR_EXPIRED,
                        expiresAt = null,
                    ),
                )
            }.getOrNull()
        if (expired != null) sendPairState(expired)
    }

    private fun normalizeAfterProcessDeath() {
        val records = shareStore?.load() ?: return
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
        if (changed) runCatching { shareStore?.save(updated) }
        publishShares()
    }

    private fun sanitizeLabel(label: String): String =
        label
            .filter { it.code in 0x20..0x7e && it != '|' }
            .trim()
            .take(64)

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

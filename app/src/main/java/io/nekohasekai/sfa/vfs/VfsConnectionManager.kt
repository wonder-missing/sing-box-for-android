package io.nekohasekai.sfa.vfs

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class VfsServerTarget(
    val name: String,
    val providerUrl: String,
    val token: String,
)

data class VfsConnectResult(
    val issuer: String,
    val scope: Set<VfsScope>,
    val expiresAt: Long?,
)

interface VfsConnectPort {
    suspend fun connect(link: VfsLink): VfsConnectResult
}

/**
 * Owns the one active provider connection. A connection with a covering scope
 * is reused; replacing it requires a stored resume token. All decisions happen
 * under one mutex so two vfs_connect calls cannot race.
 */
class VfsConnectionManager(
    private val resolveServer: suspend (issuer: String) -> VfsServerTarget?,
    private val consent: VfsConsentPort,
    private val connector: VfsProviderConnector,
    private val deviceId: () -> String,
    private val resumeTokens: VfsResumeTokenStore,
    private val trust: VfsTrustStore,
    private val cooldowns: VfsCooldownStore = VfsCooldownStore(),
    private val now: () -> Long = System::currentTimeMillis,
    private val isForeground: () -> Boolean = { true },
) : VfsConnectPort {
    private data class ActiveConnection(
        val issuer: String,
        val linkId: String,
        val scope: Set<VfsScope>,
        val session: VfsProviderSession,
        val expiresAt: Long?,
        val connectedAt: Long,
    )

    private val gate = Mutex()
    private var active: ActiveConnection? = null
    private val _state = MutableStateFlow<VfsConnectionState>(VfsConnectionState.Idle)
    val state: StateFlow<VfsConnectionState> = _state.asStateFlow()

    override suspend fun connect(link: VfsLink): VfsConnectResult = gate.withLock { connectLocked(link) }

    private suspend fun connectLocked(link: VfsLink): VfsConnectResult {
        if (!isForeground()) {
            throw VfsConnectException(
                "VFS_CONFIRMATION_REQUIRED",
                "Open the app before approving a VFS connection.",
            )
        }

        val issuer = VfsIssuer.normalize(link.issuer)
            ?: throw VfsConnectException("VFS_LINK_INVALID", "The Link issuer is not a valid base URL.")
        val target = resolveServer(issuer)
            ?: throw VfsConnectException("VFS_ISSUER_UNKNOWN", "No configured MCP server matches this Link.")
        if (target.token.isBlank()) {
            throw VfsConnectException("VFS_UNAUTHORIZED", "The matched MCP server has no token.")
        }

        val cooldown = cooldowns.forIssuer(issuer)
        val remaining = cooldown.remainingMs()
        if (remaining > 0L) throw cooldownError(remaining)

        active?.let { current ->
            if (current.issuer == issuer && current.scope.containsAll(link.scope)) {
                _state.value =
                    VfsConnectionState.Connected(current.issuer, current.scope, current.connectedAt)
                return VfsConnectResult(current.issuer, current.scope, current.expiresAt)
            }
        }

        if (trust.find(issuer, link.scope) == null) {
            _state.value = VfsConnectionState.Confirming(link, target.name)
            val decision =
                try {
                    consent.request(
                        VfsConsentRequest(
                            serverName = target.name,
                            issuer = issuer,
                            scope = link.scope,
                        ),
                    )
                } catch (error: VfsConnectException) {
                    _state.value = VfsConnectionState.Error(error.code, error.message ?: error.code)
                    throw error
                }

            when (decision) {
                VfsConsentDecision.DENY -> {
                    val delay = cooldown.recordDenial()
                    _state.value = VfsConnectionState.Error("VFS_USER_DENIED", "The user denied the VFS request.")
                    throw VfsConnectException(
                        "VFS_USER_DENIED",
                        "The user denied the VFS request. Try again later.",
                        retryAfterMs = delay,
                        cooldownUntil = now() + delay,
                    )
                }

                VfsConsentDecision.ONCE -> {
                    trust.grant(
                        VfsTrust(
                            issuer = issuer,
                            scope = link.scope,
                            lastUsedAt = now(),
                            expiresAt = 0L,
                            processScoped = true,
                        ),
                    )
                }

                VfsConsentDecision.TRUST_15_DAYS -> {
                    trust.grant(
                        VfsTrust(
                            issuer = issuer,
                            scope = link.scope,
                            lastUsedAt = now(),
                            expiresAt = trust.expiryFromNow(),
                            processScoped = false,
                        ),
                    )
                }
            }
        }

        val previous = active
        val resume = resumeTokens.get(issuer, link.linkId)
        if (previous != null && previous.linkId == link.linkId && resume.isNullOrBlank()) {
            _state.value =
                VfsConnectionState.Connected(previous.issuer, previous.scope, previous.connectedAt)
            return VfsConnectResult(previous.issuer, previous.scope, previous.expiresAt)
        }

        _state.value = VfsConnectionState.Connecting
        val session =
            try {
                connector.connect(
                    VfsProviderConnectRequest(
                        url = target.providerUrl,
                        token = target.token,
                        linkId = link.linkId,
                        deviceId = deviceId(),
                        resumeToken = resume,
                        scope = link.scope,
                    ),
                )
            } catch (error: VfsConnectException) {
                clearIfTerminal(error, issuer, link.linkId)
                _state.value = VfsConnectionState.Error(error.code, error.message ?: error.code)
                throw error
            } catch (error: Exception) {
                val wrapped =
                    VfsConnectException(
                        "VFS_CONNECT_FAILED",
                        error.message ?: "Could not connect to the VFS provider.",
                    )
                _state.value = VfsConnectionState.Error(wrapped.code, wrapped.message ?: wrapped.code)
                throw wrapped
            }

        previous?.let { old -> runCatching { old.session.close(4004, "replaced") } }
        val hello = session.hello
        val connectedAt = now()
        active =
            ActiveConnection(
                issuer = issuer,
                linkId = link.linkId,
                scope = link.scope,
                session = session,
                expiresAt = hello.expiresAt,
                connectedAt = connectedAt,
            )
        if (!hello.resumeToken.isNullOrBlank()) {
            resumeTokens.put(issuer, link.linkId, hello.resumeToken)
        }
        trust.touch(issuer, link.scope)
        cooldown.reset()
        _state.value = VfsConnectionState.Connected(issuer, link.scope, connectedAt)
        return VfsConnectResult(issuer, link.scope, hello.expiresAt)
    }

    suspend fun disconnect(
        code: Int = 4000,
        reason: String = "background",
    ) = gate.withLock {
        val current = active
        if (current == null) {
            _state.value = VfsConnectionState.Idle
            return@withLock
        }
        runCatching { current.session.close(code, reason) }
        active = null
        if (code == 4001) {
            resumeTokens.remove(current.issuer, current.linkId)
            trust.revoke(current.issuer)
        }
        _state.value = VfsConnectionState.Idle
    }

    private fun clearIfTerminal(
        error: VfsConnectException,
        issuer: String,
        linkId: String,
    ) {
        when (error.code) {
            "VFS_UNAUTHORIZED",
            "VFS_DEVICE_MISMATCH",
            "VFS_DEVICE_REBIND_REQUIRED",
            "VFS_LINK_INVALID",
            "VFS_LINK_REVOKED",
            "VFS_LINK_EXPIRED" -> {
                resumeTokens.remove(issuer, linkId)
                trust.revoke(issuer)
            }
        }
    }

    private fun cooldownError(remainingMs: Long): VfsConnectException {
        val seconds = ((remainingMs + 999L) / 1000L).coerceAtLeast(1L)
        return VfsConnectException(
            "VFS_CONFIRMATION_COOLDOWN",
            "The user recently denied this server. Try again in about $seconds seconds.",
            retryAfterMs = remainingMs,
            cooldownUntil = now() + remainingMs,
        )
    }
}

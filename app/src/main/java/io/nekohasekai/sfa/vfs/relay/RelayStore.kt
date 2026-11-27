package io.nekohasekai.sfa.vfs.relay

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream

@Serializable
data class VfsShareRecord(
    val shareId: String,
    val label: String = "",
    val linkId: String,
    val secretCiphertext: String,
    val scopes: List<String>,
    val serverId: String,
    val relayId: String,
    val canonicalBase: String,
    val deviceId: String,
    val generation: Long = 0,
    val registrationState: String = STATE_PENDING,
    val registeredAt: Long? = null,
    val pairEpoch: Long = 0,
    val pairId: String? = null,
    val pairState: String = PAIR_WAITING,
    val pairExpiresAt: Long? = null,
    val revokingAt: Long? = null,
    val createdAt: Long,
    val pairedAt: Long? = null,
    val lastUsedAt: Long? = null,
    val expiresAt: Long? = null,
    val revokedAt: Long? = null,
    val selectedUris: List<String> = emptyList(),
    val lastError: String? = null,
) {
    val concreteScopes: Set<RelayScope>
        get() = RelayScope.parseAll(scopes)

    val isRevoked: Boolean
        get() = revokedAt != null || registrationState == STATE_FAILED

    companion object {
        const val STATE_PENDING = "pending"
        const val STATE_REGISTERED = "registered"
        const val STATE_FAILED = "failed"

        const val PAIR_WAITING = "waiting"
        const val PAIR_ACTIVE = "active"
        const val PAIR_CONSUMED = "consumed"
        const val PAIR_PAUSED = "paused"
        const val PAIR_EXPIRED = "expired"
    }
}

/**
 * Every pair-state transition bumps pairEpoch exactly once, so a late Relay
 * retry can never roll back a New code or Stop.
 */
fun VfsShareRecord.nextPairState(
    state: String,
    pairId: String? = this.pairId,
    expiresAt: Long? = this.pairExpiresAt,
    pairedAt: Long? = this.pairedAt,
): VfsShareRecord =
    copy(
        pairState = state,
        pairId = pairId,
        pairExpiresAt = expiresAt,
        pairedAt = pairedAt,
        pairEpoch = pairEpoch + 1,
    )

@Serializable
private data class VfsShareFile(
    val v: Int = 3,
    val shares: List<VfsShareRecord> = emptyList(),
)

class VfsShareStore(
    private val file: File,
    private val cipher: VfsShareCipher,
    private val clock: () -> Long = System::currentTimeMillis,
    private val reader: (File) -> String = { it.readText(Charsets.UTF_8) },
) {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    @Volatile
    private var corrupt = false

    @Synchronized
    fun load(): List<VfsShareRecord> {
        if (!file.isFile) return emptyList()
        if (corrupt) throw RelayStoreException(corruptMessage())
        val text =
            try {
                reader(file)
            } catch (error: java.io.IOException) {
                // A transient read failure is retryable and must not latch the
                // store as permanently damaged.
                throw RelayStoreException("Could not read the Share list: ${error.message}", retryable = true)
            }
        val decoded =
            runCatching { json.decodeFromString(VfsShareFile.serializer(), text).shares }.getOrNull()
        if (decoded == null) {
            corrupt = true
            throw RelayStoreException(corruptMessage())
        }
        return decoded
    }

    @Synchronized
    fun save(shares: List<VfsShareRecord>) {
        if (corrupt) throw RelayStoreException(corruptMessage())
        if (!cipher.supported) throw RelayStoreException("Encryption is unavailable on this device.")
        try {
            RelayFiles.writeAtomic(
                file,
                json.encodeToString(VfsShareFile.serializer(), VfsShareFile(shares = shares))
                    .toByteArray(Charsets.UTF_8),
            )
        } catch (error: java.io.IOException) {
            throw RelayStoreException("Could not save the Share list: ${error.message}", retryable = true)
        }
    }

    private fun corruptMessage(): String =
        "The Share list is damaged. WonderSing left the file in place; revoke or restore it from a backup before creating new Shares."

    fun sealSecret(
        shareId: String,
        secret: ByteArray,
    ): String {
        if (!cipher.supported) throw RelayStoreException("Encryption is unavailable on this device.")
        return cipher.encrypt(VfsShareAad.shareSecret(shareId), secret)
    }

    fun openSecret(record: VfsShareRecord): ByteArray =
        cipher.decrypt(VfsShareAad.shareSecret(record.shareId), record.secretCiphertext)
            ?: throw RelayStoreException("Could not decrypt this Share. Its Keystore key is gone.")

    fun newRecord(
        shareId: String,
        label: String,
        linkId: String,
        secret: ByteArray,
        scopes: Set<RelayScope>,
        serverId: String,
        relayId: String,
        canonicalBase: String,
        deviceId: String,
        selectedUris: List<String>,
    ): VfsShareRecord =
        VfsShareRecord(
            shareId = shareId,
            label = label,
            linkId = linkId,
            secretCiphertext = sealSecret(shareId, secret),
            scopes = RelayRoots.scopesSorted(scopes),
            serverId = serverId,
            relayId = relayId,
            canonicalBase = canonicalBase,
            deviceId = deviceId,
            generation = 0,
            registrationState = VfsShareRecord.STATE_PENDING,
            pairState = VfsShareRecord.PAIR_WAITING,
            createdAt = clock(),
            selectedUris = selectedUris,
        )
}

@Serializable
data class VfsRelayServer(
    val id: String,
    val name: String,
    val baseUrl: String,
    val tokenCiphertext: String,
    val createdAt: Long,
)

@Serializable
data class VfsRelayAccount(
    val serverId: String,
    val relayId: String,
    val canonicalBase: String,
    val deviceId: String,
    val providerKeyId: String,
    val providerKeyCiphertext: String,
    val createdAt: Long,
    val rotatedAt: Long? = null,
)

/**
 * v2 is the clean Relay Server schema. Legacy v1 configs are deliberately
 * not loaded or migrated.
 */
@Serializable
data class VfsRelayServerFile(
    val v: Int = SCHEMA_VERSION,
    val selectedServerId: String = "",
    val desiredConnected: Boolean = false,
    val relayDeviceId: String = "",
    val servers: List<VfsRelayServer> = emptyList(),
    val accounts: List<VfsRelayAccount> = emptyList(),
) {
    fun server(id: String): VfsRelayServer? = servers.firstOrNull { it.id == id }

    fun selectedServer(): VfsRelayServer? = server(selectedServerId)

    companion object {
        const val SCHEMA_VERSION = 3
    }
}

/** Adds/replaces the account for one local Relay Server, preserving all other state. */
fun VfsRelayServerFile.withAccount(
    serverId: String,
    relayId: String,
    canonicalBase: String,
    account: VfsRelayAccount,
): VfsRelayServerFile =
    copy(
        accounts =
            accounts.filterNot { it.serverId == serverId } +
                account.copy(relayId = relayId, canonicalBase = canonicalBase),
    )

object RelayServerValidation {
    const val MAX_NAME = 64
    const val MAX_URL = 300
    const val MAX_TOKEN = 512
    const val TOKEN_PREFIX = "relay_"

    /**
     * The Relay Server page shows a full API base such as
     * `https://host/relay/v1`. Origin-only URLs are rejected: silently adding
     * a default path is not part of the contract.
     */
    fun canonicalHttpsBaseUrl(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return null
        if (trimmed.length > MAX_URL) return null
        val uri = runCatching { java.net.URI(trimmed) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
        val host = uri.host ?: return null
        if (uri.port != -1 && uri.port !in 1..65535) return null
        val path = uri.rawPath.orEmpty().trimEnd('/')
        if (path.isEmpty() || path == "/" || path.startsWith("//")) return null
        val scheme = uri.scheme.lowercase(java.util.Locale.US)
        val authority =
            if (uri.port == -1 || uri.port == 443) {
                host.lowercase(java.util.Locale.US)
            } else {
                "${host.lowercase(java.util.Locale.US)}:${uri.port}"
            }
        return "$scheme://$authority$path"
    }

    fun normalizeToken(raw: String): String? {
        val token = raw.trim()
        if (token.isEmpty() || token.length > MAX_TOKEN) return null
        if (token.any { it == '\n' || it == '\r' || it.isWhitespace() }) return null
        return token.takeIf { it.startsWith(TOKEN_PREFIX) && it.length > TOKEN_PREFIX.length }
    }

    fun cleanName(
        raw: String,
        fallback: String,
    ): String = raw.trim().take(MAX_NAME).ifBlank { fallback.take(MAX_NAME) }
}

class VfsRelayServerStore(
    private val file: File,
    private val cipher: VfsShareCipher,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    @Synchronized
    fun load(): VfsRelayServerFile {
        if (!file.isFile) return VfsRelayServerFile()
        val text =
            try {
                file.readText(Charsets.UTF_8)
            } catch (error: java.io.IOException) {
                throw RelayStoreException("Could not read Relay Server settings: ${error.message}", retryable = true)
            }
        val decoded =
            runCatching { json.decodeFromString(VfsRelayServerFile.serializer(), text) }.getOrNull()
            ?: throw RelayStoreException("Relay Server settings are damaged.")
        if (decoded.v != VfsRelayServerFile.SCHEMA_VERSION) {
            // Legacy v1/v2 config: clean start, no migration.
            return VfsRelayServerFile()
        }
        return decoded
    }

    @Synchronized
    fun save(settings: VfsRelayServerFile) {
        try {
            RelayFiles.writeAtomic(
                file,
                json.encodeToString(VfsRelayServerFile.serializer(), settings).toByteArray(Charsets.UTF_8),
            )
        } catch (error: java.io.IOException) {
            throw RelayStoreException("Could not save Relay Server settings: ${error.message}", retryable = true)
        }
    }

    @Synchronized
    fun update(transform: (VfsRelayServerFile) -> VfsRelayServerFile): VfsRelayServerFile {
        val updated = transform(load())
        save(updated)
        return updated
    }

    fun addServer(
        name: String,
        url: String,
        token: String,
    ): VfsRelayServer {
        val base = RelayServerValidation.canonicalHttpsBaseUrl(url)
            ?: throw RelayStoreException("Relay URL must be a full HTTPS base such as https://relay.example/relay/v1.")
        val cleanToken = RelayServerValidation.normalizeToken(token)
            ?: throw RelayStoreException("Relay token must start with relay_ and contain no spaces.")
        if (!cipher.supported) throw RelayStoreException("Encryption is unavailable on this device.")
        val id = java.util.UUID.randomUUID().toString()
        val server =
            VfsRelayServer(
                id = id,
                name = RelayServerValidation.cleanName(name, base.removePrefix("https://")),
                baseUrl = base,
                tokenCiphertext =
                    cipher.encrypt(
                        VfsShareAad.relayServerToken(id),
                        cleanToken.toByteArray(Charsets.UTF_8),
                    ),
                createdAt = clock(),
            )
        update { latest ->
            latest.copy(
                servers = latest.servers + server,
                selectedServerId = if (latest.selectedServerId.isBlank()) id else latest.selectedServerId,
            )
        }
        return server
    }

    fun updateServer(
        id: String,
        name: String,
        url: String,
        token: String?,
    ): VfsRelayServer {
        val base = RelayServerValidation.canonicalHttpsBaseUrl(url)
            ?: throw RelayStoreException("Relay URL must be a full HTTPS base such as https://relay.example/relay/v1.")
        var updated: VfsRelayServer? = null
        update { latest ->
            val current = latest.server(id) ?: throw RelayStoreException("Relay Server not found.")
            val ciphertext =
                if (token.isNullOrBlank()) {
                    current.tokenCiphertext
                } else {
                    val cleanToken = RelayServerValidation.normalizeToken(token)
                        ?: throw RelayStoreException("Relay token must start with relay_ and contain no spaces.")
                    cipher.encrypt(
                        VfsShareAad.relayServerToken(id),
                        cleanToken.toByteArray(Charsets.UTF_8),
                    )
                }
            updated =
                current.copy(
                    name = RelayServerValidation.cleanName(name, base.removePrefix("https://")),
                    baseUrl = base,
                    tokenCiphertext = ciphertext,
                )
            latest.copy(servers = latest.servers.map { if (it.id == id) updated ?: it else it })
        }
        return updated!!
    }

    fun removeServer(id: String) {
        update { latest ->
            latest.copy(
                servers = latest.servers.filterNot { it.id == id },
                selectedServerId = if (latest.selectedServerId == id) "" else latest.selectedServerId,
                accounts = latest.accounts.filterNot { it.serverId == id },
            )
        }
    }

    /** Relay-only per-install identity; no other subsystem owns this value. */
    fun relayDeviceId(): String {
        var created = ""
        update { latest ->
            val id = latest.relayDeviceId.takeIf { it.isNotBlank() }
                ?: java.util.UUID.randomUUID().toString()
            created = id
            latest.copy(relayDeviceId = id)
        }
        return created
    }

    fun selectServer(id: String) {
        update { latest ->
            require(latest.server(id) != null) { "Relay Server not found." }
            latest.copy(selectedServerId = id)
        }
    }

    fun setDesiredConnected(desired: Boolean) {
        update { it.copy(desiredConnected = desired) }
    }

    fun openToken(server: VfsRelayServer): String? =
        cipher.decrypt(
            VfsShareAad.relayServerToken(server.id),
            server.tokenCiphertext,
        )?.toString(Charsets.UTF_8)

    fun account(serverId: String): VfsRelayAccount? = load().accounts.firstOrNull { it.serverId == serverId }

    fun sealProviderKey(
        canonicalBase: String,
        relayId: String,
        deviceId: String,
        providerKeyId: String,
        providerKey: ByteArray,
    ): String {
        if (!cipher.supported) throw RelayStoreException("Encryption is unavailable on this device.")
        return cipher.encrypt(
            VfsShareAad.providerKey(canonicalBase, relayId, deviceId, providerKeyId),
            providerKey,
        )
    }

    fun openProviderKey(account: VfsRelayAccount): ByteArray =
        cipher.decrypt(
            VfsShareAad.providerKey(
                account.canonicalBase,
                account.relayId,
                account.deviceId,
                account.providerKeyId,
            ),
            account.providerKeyCiphertext,
        ) ?: throw RelayStoreException("Could not decrypt the Provider key.")
}

/**
 * Device secrets live outside the Share file. A pending secret exists before
 * the Relay Server has told us the relayId; after issue-once succeeds it is re-encrypted
 * with the final AAD and atomically moved under `device-secrets/<relayId>/`.
 */
class VfsDeviceSecretStore(
    private val baseDir: File,
    private val cipher: VfsShareCipher,
) {
    fun existing(
        canonicalBase: String,
        relayId: String,
        deviceId: String,
    ): ByteArray? {
        val file = finalFile(relayId, deviceId)
        if (!file.isFile) return null
        val blob = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return null
        return cipher.decrypt(VfsShareAad.deviceSecret(canonicalBase, relayId, deviceId), blob)
    }

    fun getOrCreatePending(
        serverId: String,
        canonicalBase: String,
        deviceId: String,
    ): ByteArray {
        val file = pendingFile(serverId, canonicalBase, deviceId)
        if (file.isFile) {
            val blob = runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
            if (blob != null) {
                cipher.decrypt(VfsShareAad.pendingDeviceSecret(canonicalBase, deviceId), blob)
                    ?.let { return it }
            }
        }
        val created = RelayCrypto.randomBytes(32)
        writeSecret(file, VfsShareAad.pendingDeviceSecret(canonicalBase, deviceId), created)
        return created
    }

    /** Re-encrypts the pending secret with the final AAD, then deletes pending. */
    fun promotePending(
        serverId: String,
        canonicalBase: String,
        relayId: String,
        deviceId: String,
        secret: ByteArray,
    ): ByteArray {
        val final = finalFile(relayId, deviceId)
        val existing = existing(canonicalBase, relayId, deviceId)
        if (existing != null) return existing
        writeSecret(final, VfsShareAad.deviceSecret(canonicalBase, relayId, deviceId), secret)
        pendingFile(serverId, canonicalBase, deviceId).delete()
        return secret
    }

    private fun writeSecret(
        file: File,
        aad: String,
        secret: ByteArray,
    ) {
        if (!cipher.supported) throw RelayStoreException("Encryption is unavailable on this device.")
        val blob = cipher.encrypt(aad, secret)
        RelayFiles.writeAtomic(file, blob.toByteArray(Charsets.UTF_8))
    }

    private fun finalFile(
        relayId: String,
        deviceId: String,
    ): File = File(File(File(baseDir, "device-secrets"), safeName(relayId)), safeName(deviceId))

    private fun pendingFile(
        serverId: String,
        canonicalBase: String,
        deviceId: String,
    ): File {
        val digest = RelayBase64.hex(RelayCrypto.sha256("$canonicalBase|$deviceId".toByteArray(Charsets.UTF_8)))
        return File(File(File(baseDir, "pending"), safeName(serverId)), digest)
    }

    private fun safeName(value: String): String =
        value.map { if (it.isLetterOrDigit() || it == '-' || it == '_' || it == '.') it else '_' }.joinToString("")
}

class RelayStoreException(message: String, val retryable: Boolean = false) : Exception(message)

internal object RelayFiles {
    fun writeAtomic(
        file: File,
        bytes: ByteArray,
    ) {
        val parent = file.parentFile ?: throw RelayStoreException("Could not save VFS state.")
        if (!parent.isDirectory && !parent.mkdirs()) {
            throw RelayStoreException("Could not create the VFS state directory.")
        }
        val tmp = File(parent, file.name + ".tmp")
        FileOutputStream(tmp).use { output ->
            output.write(bytes)
            output.flush()
            runCatching { output.fd.sync() }
        }
        relaxPermissions(tmp)
        if (!tmp.renameTo(file)) {
            file.writeBytes(bytes)
            tmp.delete()
        }
        relaxPermissions(file)
    }

    private fun relaxPermissions(file: File) {
        runCatching {
            file.setReadable(false, false)
            file.setReadable(true, true)
            file.setWritable(false, false)
            file.setWritable(true, true)
        }
    }
}

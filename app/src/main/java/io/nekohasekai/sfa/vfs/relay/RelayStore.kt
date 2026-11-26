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
    val portalServerId: String,
    val hookId: String,
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
    val v: Int = 1,
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
        portalServerId: String,
        hookId: String,
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
            portalServerId = portalServerId,
            hookId = hookId,
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
data class VfsRelayAccount(
    val portalServerId: String,
    val canonicalBase: String,
    val hookId: String,
    val deviceId: String,
    val providerKeyId: String,
    val providerKeyCiphertext: String,
    val createdAt: Long,
    val rotatedAt: Long? = null,
)

@Serializable
data class VfsRelayConfigFile(
    val v: Int = 1,
    val portalServerId: String = "",
    val canonicalBase: String = "",
    val desiredConnected: Boolean = false,
    val egressMode: String = "public",
    val accounts: List<VfsRelayAccount> = emptyList(),
)

/**
 * Adds one `(portalServerId, canonicalBase)` account without touching
 * desiredConnected or any other field from the caller's stale snapshot.
 */
fun VfsRelayConfigFile.withAccount(
    portalServerId: String,
    canonicalBase: String,
    account: VfsRelayAccount,
): VfsRelayConfigFile =
    copy(
        portalServerId = portalServerId,
        canonicalBase = canonicalBase,
        accounts =
            accounts.filterNot {
                it.portalServerId == portalServerId && it.canonicalBase == canonicalBase
            } + account,
    )

class VfsRelayConfigStore(
    private val file: File,
    private val cipher: VfsShareCipher,
) {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    @Synchronized
    fun load(): VfsRelayConfigFile {
        if (!file.isFile) return VfsRelayConfigFile()
        return runCatching {
            json.decodeFromString(VfsRelayConfigFile.serializer(), file.readText(Charsets.UTF_8))
        }.getOrDefault(VfsRelayConfigFile())
    }

    @Synchronized
    fun save(config: VfsRelayConfigFile) {
        RelayFiles.writeAtomic(
            file,
            json.encodeToString(VfsRelayConfigFile.serializer(), config).toByteArray(Charsets.UTF_8),
        )
    }

    /** Re-reads the latest file, applies one targeted change, then saves. */
    @Synchronized
    fun update(transform: (VfsRelayConfigFile) -> VfsRelayConfigFile): VfsRelayConfigFile {
        val updated = transform(load())
        save(updated)
        return updated
    }

    fun sealProviderKey(
        canonicalBase: String,
        hookId: String,
        deviceId: String,
        providerKeyId: String,
        providerKey: ByteArray,
    ): String {
        if (!cipher.supported) throw RelayStoreException("Encryption is unavailable on this device.")
        return cipher.encrypt(
            VfsShareAad.providerKey(canonicalBase, hookId, deviceId, providerKeyId),
            providerKey,
        )
    }

    fun openProviderKey(account: VfsRelayAccount): ByteArray =
        cipher.decrypt(
            VfsShareAad.providerKey(
                account.canonicalBase,
                account.hookId,
                account.deviceId,
                account.providerKeyId,
            ),
            account.providerKeyCiphertext,
        ) ?: throw RelayStoreException("Could not decrypt the Provider key.")
}

/**
 * Device secrets live outside the Share file. A pending secret exists before
 * the Portal has told us the hookId; after issue-once succeeds it is re-encrypted
 * with the final AAD and atomically moved under `device-secrets/<hookId>/`.
 */
class VfsDeviceSecretStore(
    private val baseDir: File,
    private val cipher: VfsShareCipher,
) {
    fun existing(
        canonicalBase: String,
        hookId: String,
        deviceId: String,
    ): ByteArray? {
        val file = finalFile(hookId, deviceId)
        if (!file.isFile) return null
        val blob = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return null
        return cipher.decrypt(VfsShareAad.deviceSecret(canonicalBase, hookId, deviceId), blob)
    }

    fun getOrCreatePending(
        portalServerId: String,
        canonicalBase: String,
        deviceId: String,
    ): ByteArray {
        val file = pendingFile(portalServerId, canonicalBase, deviceId)
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
        portalServerId: String,
        canonicalBase: String,
        hookId: String,
        deviceId: String,
        secret: ByteArray,
    ): ByteArray {
        val final = finalFile(hookId, deviceId)
        val existing = existing(canonicalBase, hookId, deviceId)
        if (existing != null) return existing
        writeSecret(final, VfsShareAad.deviceSecret(canonicalBase, hookId, deviceId), secret)
        pendingFile(portalServerId, canonicalBase, deviceId).delete()
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
        hookId: String,
        deviceId: String,
    ): File = File(File(File(baseDir, "device-secrets"), safeName(hookId)), safeName(deviceId))

    private fun pendingFile(
        portalServerId: String,
        canonicalBase: String,
        deviceId: String,
    ): File {
        val digest = RelayBase64.hex(RelayCrypto.sha256("$canonicalBase|$deviceId".toByteArray(Charsets.UTF_8)))
        return File(File(File(baseDir, "pending"), safeName(portalServerId)), digest)
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

package io.nekohasekai.sfa.vfs.relay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Round-trips through the real store code without an Android Keystore. */
class FakeVfsShareCipher : VfsShareCipher {
    override val supported: Boolean = true

    override fun encrypt(
        aad: String,
        plaintext: ByteArray,
    ): String {
        val prefix = aad.toByteArray(Charsets.UTF_8)
        val out = ByteArray(prefix.size + 1 + plaintext.size)
        prefix.copyInto(out)
        plaintext.copyInto(out, destinationOffset = prefix.size + 1)
        return RelayBase64.encodeUrl(out)
    }

    override fun decrypt(
        aad: String,
        blob: String,
    ): ByteArray? {
        val raw = RelayBase64.decodeUrl(blob) ?: return null
        val prefix = aad.toByteArray(Charsets.UTF_8)
        if (raw.size <= prefix.size) return null
        if (!raw.copyOfRange(0, prefix.size).contentEquals(prefix)) return null
        if (raw[prefix.size].toInt() != 0) return null
        return raw.copyOfRange(prefix.size + 1, raw.size)
    }
}

class VfsShareStoreTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val cipher = FakeVfsShareCipher()

    @Test
    fun shareRoundTripsAndSecretIsNotPlaintext() {
        val file = File(temp.root, "relay/shares-v3.json")
        val store = VfsShareStore(file, cipher, clock = { 1_000L })
        val secret = ByteArray(32) { it.toByte() }
        val record =
            store.newRecord(
                shareId = "share-1",
                label = "Claude",
                linkId = "link-1",
                secret = secret,
                scopes = setOf(RelayScope.APP_DATA_ACTIVITY, RelayScope.APP_FILES),
                serverId = "server-1",
                relayId = "relay-1",
                canonicalBase = "https://relay.example/relay/v1",
                deviceId = "device-1",
                selectedUris = listOf("content://a"),
            )
        store.save(listOf(record))

        val loaded = VfsShareStore(file, cipher).load().single()
        assertEquals("share-1", loaded.shareId)
        assertEquals("server-1", loaded.serverId)
        assertEquals("relay-1", loaded.relayId)
        assertEquals(listOf("app.data.activity", "app.files"), loaded.scopes)
        assertEquals(VfsShareRecord.PAIR_WAITING, loaded.pairState)
        assertEquals(1_000L, loaded.createdAt)
        assertEquals(listOf("content://a"), loaded.selectedUris)
        assertNotEquals(RelayBase64.hex(secret), loaded.secretCiphertext)
        assertArrayEquals(secret, store.openSecret(loaded))
        assertEquals(setOf(RelayScope.APP_DATA_ACTIVITY, RelayScope.APP_FILES), loaded.concreteScopes)
    }

    @Test
    fun corruptShareFileFailsClosedAndIsPreserved() {
        val file = File(temp.root, "relay/shares-v3.json")
        file.parentFile.mkdirs()
        file.writeText("{not json", Charsets.UTF_8)
        val store = VfsShareStore(file, cipher)
        var thrown = false
        try {
            store.load()
        } catch (error: RelayStoreException) {
            assertFalse(error.retryable)
            thrown = true
        }
        assertTrue(thrown)
        assertEquals("{not json", file.readText(Charsets.UTF_8))
        try {
            store.save(emptyList())
            throw AssertionError("corrupt store must not be overwritten")
        } catch (_: RelayStoreException) {
            assertEquals("{not json", file.readText(Charsets.UTF_8))
        }
    }

    @Test
    fun temporaryReadFailureIsRetryableAndNotLatchedAsCorrupt() {
        val file = File(temp.root, "relay/shares-v3.json")
        file.parentFile.mkdirs()
        file.writeText("{\"v\":3,\"shares\":[]}", Charsets.UTF_8)
        val failing = VfsShareStore(file, cipher) { throw java.io.IOException("device busy") }
        try {
            failing.load()
            throw AssertionError("expected a transient read failure")
        } catch (error: RelayStoreException) {
            assertTrue(error.retryable)
        }
        assertTrue(VfsShareStore(file, cipher).load().isEmpty())
    }

    @Test
    fun deviceSecretIsStablePromotedAndServerScoped() {
        val store = VfsDeviceSecretStore(File(temp.root, "relay"), cipher)
        val first = store.getOrCreatePending("server-1", "https://relay.example/relay/v1", "device-1")
        val again = store.getOrCreatePending("server-1", "https://relay.example/relay/v1", "device-1")
        assertArrayEquals(first, again)
        assertEquals(32, first.size)
        assertNull(store.existing("https://relay.example/relay/v1", "relay-1", "device-1"))

        store.promotePending("server-1", "https://relay.example/relay/v1", "relay-1", "device-1", first)
        assertArrayEquals(first, store.existing("https://relay.example/relay/v1", "relay-1", "device-1"))
        assertNull(store.existing("https://other.example/relay/v1", "relay-1", "device-1"))
        val pendingFile = File(temp.root, "relay/pending/server-1")
        assertTrue(pendingFile.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun pairStateTransitionsBumpEpochAndKeepTheSameShareKey() {
        val store = VfsShareStore(File(temp.root, "relay/shares-v3.json"), cipher)
        val record =
            store.newRecord(
                "s", "l", "link", ByteArray(32) { 7 }, setOf(RelayScope.APP_DATA_ACTIVITY),
                "server", "relay", "https://x", "d", emptyList(),
            )
        val active = record.nextPairState(VfsShareRecord.PAIR_ACTIVE, pairId = "pair-1", expiresAt = 1_000L)
        val consumed = active.nextPairState(VfsShareRecord.PAIR_CONSUMED, expiresAt = null, pairedAt = 2_000L)
        val paused = consumed.nextPairState(VfsShareRecord.PAIR_PAUSED, expiresAt = null)
        assertEquals(record.pairEpoch + 1, active.pairEpoch)
        assertEquals(active.pairEpoch + 1, consumed.pairEpoch)
        assertEquals(consumed.pairEpoch + 1, paused.pairEpoch)
        assertEquals("pair-1", consumed.pairId)
        assertEquals(2_000L, consumed.pairedAt)
        assertNull(paused.pairExpiresAt)
        assertEquals(record.secretCiphertext, paused.secretCiphertext)
        assertArrayEquals(ByteArray(32) { 7 }, store.openSecret(paused))
    }

    @Test
    fun shareFilePermissionsAreOwnerOnly() {
        val file = File(temp.root, "relay/shares-v3.json")
        val store = VfsShareStore(file, cipher)
        store.save(
            listOf(
                store.newRecord(
                    "s", "l", "link", ByteArray(32), setOf(RelayScope.APP_FILES),
                    "server", "relay", "https://x", "d", emptyList(),
                ),
            ),
        )
        assertTrue(file.isFile)
        assertFalse(file.canExecute())
    }
}

class VfsRelayServerStoreTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val cipher = FakeVfsShareCipher()

    private fun store(file: File = File(temp.root, "relay/servers.json")) =
        VfsRelayServerStore(file, cipher, clock = { 42L })

    @Test
    fun addSelectEditDeleteAndTokenEncryptionRoundTrip() {
        val file = File(temp.root, "relay/servers.json")
        val store = store(file)
        val first = store.addServer("Mini", "https://relay.example/relay/v1", "relay_token_one")
        assertEquals("https://relay.example/relay/v1", first.baseUrl)
        assertEquals("relay_token_one", store.openToken(first))
        assertNotEquals("relay_token_one", first.tokenCiphertext)
        assertEquals(first.id, store.load().selectedServerId)

        val second = store.addServer("Backup", "https://backup.example:8443/relay/v1", "relay_token_two")
        assertEquals(2, store.load().servers.size)
        store.selectServer(second.id)
        assertEquals(second.id, store.load().selectedServerId)

        val edited =
            store.updateServer(
                second.id,
                "Backup 2",
                "https://backup2.example/relay/v1",
                "relay_token_three",
            )
        assertEquals("Backup 2", edited.name)
        assertEquals("https://backup2.example/relay/v1", edited.baseUrl)
        assertEquals("relay_token_three", store.openToken(edited))

        val keptToken = store.updateServer(second.id, "Backup 3", "https://backup3.example/relay/v1", null)
        assertEquals("relay_token_three", store.openToken(keptToken))
        assertEquals("Backup 3", keptToken.name)

        store.removeServer(second.id)
        assertEquals(listOf(first.id), store.load().servers.map { it.id })
        assertTrue(store.load().selectedServerId.isBlank() || store.load().selectedServerId == first.id)
    }

    @Test
    fun rejectsNonHttpsOriginsAndNonRelayTokens() {
        val store = store()
        for (url in listOf("http://relay.example/relay/v1", "https://relay.example", "https://relay.example/", "https://relay.example/relay/v1?x=1", "not a url")) {
            try {
                store.addServer("Bad", url, "relay_ok")
                throw AssertionError("expected invalid URL: $url")
            } catch (_: RelayStoreException) {
                assertTrue(store.load().servers.isEmpty())
            }
        }
        for (token in listOf("whsec_abc", "relay_", "relay_has space")) {
            try {
                store.addServer("Bad", "https://relay.example/relay/v1", token)
                throw AssertionError("expected invalid token: $token")
            } catch (_: RelayStoreException) {
                assertTrue(store.load().servers.isEmpty())
            }
        }
    }

    @Test
    fun relayDeviceIdIsStableAcrossReloads() {
        val file = File(temp.root, "relay/servers.json")
        val first = store(file).relayDeviceId()
        assertTrue(first.isNotBlank())
        assertEquals(first, store(file).relayDeviceId())
        assertEquals(first, store(file).load().relayDeviceId)
    }

    @Test
    fun providerKeyAndAccountRoundTripWithRelayIdAad() {
        val store = store()
        val server = store.addServer("Mini", "https://relay.example/relay/v1", "relay_token")
        val key = ByteArray(32) { (it + 1).toByte() }
        val account =
            VfsRelayAccount(
                serverId = server.id,
                relayId = "relay-1",
                canonicalBase = server.baseUrl,
                deviceId = "device-1",
                providerKeyId = "key-1",
                providerKeyCiphertext =
                    store.sealProviderKey(server.baseUrl, "relay-1", "device-1", "key-1", key),
                createdAt = 7L,
            )
        store.update { it.withAccount(server.id, "relay-1", server.baseUrl, account) }
        val loaded = store.load()
        assertEquals("relay-1", loaded.accounts.single().relayId)
        assertArrayEquals(key, store.openProviderKey(loaded.accounts.single()))
        assertEquals("relay-1", store.account(server.id)?.relayId)
        store.removeServer(server.id)
        assertTrue(store.load().accounts.isEmpty())
    }

    @Test
    fun fullBaseAadUsesThePathAndV2ConfigIsIgnored() {
        val fullBase = "https://relay.example/relay/v1"
        val aad = VfsShareAad.providerKey(fullBase, "relay-1", "device-1", "key-1")
        assertTrue(aad.contains("/relay/v1"))
        val file = File(temp.root, "relay/servers.json")
        file.parentFile.mkdirs()
        file.writeText(
            "{\"v\":2,\"selectedServerId\":\"old\",\"desiredConnected\":true,\"servers\":[{\"id\":\"old\",\"name\":\"Old\",\"baseUrl\":\"https://relay.example\",\"tokenCiphertext\":\"x\",\"createdAt\":1}]}",
            Charsets.UTF_8,
        )
        val loaded = store(file).load()
        assertTrue(loaded.servers.isEmpty())
        assertFalse(loaded.desiredConnected)
        assertTrue(loaded.selectedServerId.isBlank())
    }

    @Test
    fun legacySchemaAndOldConfigAreNotLoaded() {
        val file = File(temp.root, "relay/servers.json")
        file.parentFile.mkdirs()
        file.writeText(
            "{\"v\":1,\"portalServerId\":\"old\",\"canonicalBase\":\"https://old\",\"desiredConnected\":true}",
            Charsets.UTF_8,
        )
        val legacy = store(file).load()
        assertTrue(legacy.servers.isEmpty())
        assertFalse(legacy.desiredConnected)
        assertTrue(legacy.selectedServerId.isBlank())

        File(temp.root, "relay/config.json").writeText("{\"portalServerId\":\"old\"}", Charsets.UTF_8)
        assertTrue(store(File(temp.root, "relay/servers.json")).load().servers.isEmpty())
    }

    @Test
    fun connectAccountSaveCannotOverwriteAConcurrentStop() {
        val file = File(temp.root, "relay/servers.json")
        val store = store(file)
        val server = store.addServer("Mini", "https://relay.example/relay/v1", "relay_token")
        store.update { it.copy(desiredConnected = true) }
        val account =
            VfsRelayAccount(
                serverId = server.id,
                relayId = "relay-1",
                canonicalBase = server.baseUrl,
                deviceId = "device",
                providerKeyId = "key",
                providerKeyCiphertext = "blob",
                createdAt = 1L,
            )

        // Stop wins before Connect finishes writing the account.
        store.update { it.copy(desiredConnected = false) }
        val result = store.update { latest -> latest.withAccount(server.id, "relay-1", server.baseUrl, account) }
        assertFalse(result.desiredConnected)
        assertFalse(store.load().desiredConnected)
        assertEquals("relay-1", result.accounts.single().relayId)
        assertFalse(RelayRuntimePolicy.shouldReconnect(store.load().desiredConnected))

        // Connect finishes first; a later Stop still wins.
        store.update { it.copy(desiredConnected = true) }
        store.update { latest -> latest.withAccount(server.id, "relay-2", server.baseUrl, account) }
        store.update { it.copy(desiredConnected = false) }
        assertFalse(store.load().desiredConnected)
        assertEquals("relay-2", store.load().accounts.single().relayId)
    }

    @Test
    fun desiredConnectedFalseSurvivesARestartAfterStop() {
        val file = File(temp.root, "relay/servers.json")
        val store = store(file)
        store.addServer("Mini", "https://relay.example/relay/v1", "relay_token")
        store.update { it.copy(desiredConnected = false) }
        assertFalse(store(file).load().desiredConnected)
    }
}

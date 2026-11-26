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
        val file = File(temp.root, "relay/shares.json")
        val store = VfsShareStore(file, cipher) { 1_000L }
        val secret = ByteArray(32) { it.toByte() }
        val record =
            store.newRecord(
                shareId = "share-1",
                label = "Claude",
                linkId = "link-1",
                secret = secret,
                scopes = setOf(RelayScope.APP_DATA_ACTIVITY, RelayScope.CONDUCTOR_FILES),
                portalServerId = "portal",
                hookId = "hk_test",
                canonicalBase = "https://portal.example",
                deviceId = "device-1",
                selectedUris = listOf("content://a"),
            )
        store.save(listOf(record))

        val loaded = VfsShareStore(file, cipher).load().single()
        assertEquals("share-1", loaded.shareId)
        assertEquals("Claude", loaded.label)
        assertEquals(listOf("app.data.activity", "conductor.files"), loaded.scopes)
        assertEquals(VfsShareRecord.PAIR_WAITING, loaded.pairState)
        assertEquals(1_000L, loaded.createdAt)
        assertEquals(listOf("content://a"), loaded.selectedUris)
        assertNotEquals(RelayBase64.hex(secret), loaded.secretCiphertext)
        assertArrayEquals(secret, store.openSecret(loaded))
        assertEquals(setOf(RelayScope.APP_DATA_ACTIVITY, RelayScope.CONDUCTOR_FILES), loaded.concreteScopes)
    }

    @Test
    fun corruptShareFileLoadsEmptyInsteadOfCrashing() {
        val file = File(temp.root, "relay/shares.json")
        file.parentFile.mkdirs()
        file.writeText("{not json", Charsets.UTF_8)
        assertTrue(VfsShareStore(file, cipher).load().isEmpty())
    }

    @Test
    fun configRoundTripsProviderKeyWithAtRestAad() {
        val file = File(temp.root, "relay/config.json")
        val store = VfsRelayConfigStore(file, cipher)
        val key = ByteArray(32) { (it + 1).toByte() }
        val account =
            VfsRelayAccount(
                portalServerId = "portal",
                canonicalBase = "https://portal.example",
                hookId = "hk_test",
                deviceId = "device-1",
                providerKeyId = "key-1",
                providerKeyCiphertext =
                    store.sealProviderKey("https://portal.example", "hk_test", "device-1", "key-1", key),
                createdAt = 42L,
            )
        store.save(
            VfsRelayConfigFile(
                portalServerId = "portal",
                canonicalBase = "https://portal.example",
                desiredConnected = true,
                accounts = listOf(account),
            ),
        )
        val loaded = VfsRelayConfigStore(file, cipher).load()
        assertTrue(loaded.desiredConnected)
        assertEquals("portal", loaded.portalServerId)
        assertArrayEquals(key, store.openProviderKey(loaded.accounts.single()))
    }

    @Test
    fun deviceSecretIsStablePromotedAndPortalScoped() {
        val store = VfsDeviceSecretStore(File(temp.root, "relay"), cipher)
        val first = store.getOrCreatePending("portal", "https://portal.example", "device-1")
        val again = store.getOrCreatePending("portal", "https://portal.example", "device-1")
        assertArrayEquals(first, again)
        assertEquals(32, first.size)
        assertNull(store.existing("https://portal.example", "hk_test", "device-1"))

        store.promotePending("portal", "https://portal.example", "hk_test", "device-1", first)
        assertArrayEquals(first, store.existing("https://portal.example", "hk_test", "device-1"))
        assertNull(store.existing("https://other.example", "hk_test", "device-1"))
        val pendingFile = File(temp.root, "relay/pending/portal")
        assertTrue(pendingFile.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun pairStateTransitionsBumpEpochAndKeepTheSameShareKey() {
        val store = VfsShareStore(File(temp.root, "relay/shares.json"), cipher)
        val record =
            store.newRecord(
                "s", "l", "link", ByteArray(32) { 7 }, setOf(RelayScope.APP_DATA_ACTIVITY),
                "p", "h", "https://x", "d", emptyList(),
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
        val file = File(temp.root, "relay/shares.json")
        val store = VfsShareStore(file, cipher)
        val secret = ByteArray(32)
        store.save(
            listOf(
                store.newRecord(
                    "s", "l", "link", secret, setOf(RelayScope.APP_FILES), "p", "h", "https://x", "d", emptyList(),
                ),
            ),
        )
        assertTrue(file.isFile)
        assertFalse(file.canExecute())
    }
}

class VfsPairingTest {
    @Test
    fun generatedCodeUsesCrockfordAlphabetAndLength() {
        repeat(32) {
            val code = VfsPairing.generate()
            assertEquals(16, code.size)
            assertTrue(code.all { it in "0123456789ABCDEFGHJKMNPQRSTVWXYZ" })
            assertTrue(VfsPairing.isValid(String(code)))
        }
    }

    @Test
    fun normalizeAppliesAliasesAndSeparators() {
        assertEquals(
            "ABCDEFGHJKMNPQRS",
            String(VfsPairing.normalize("abcd efgh-jkmn pqrs")!!),
        )
        assertEquals(
            "0123456789ABCDEF",
            String(VfsPairing.normalize("O123456789ABCDEF")!!),
        )
        assertEquals(
            "1111ABCDEFGHJKMN",
            String(VfsPairing.normalize("I1lLABCDEFGHJKMN")!!),
        )
        assertEquals(
            "VABCDEFGHJKMNPQR",
            String(VfsPairing.normalize("UABCDEFGHJKMNPQR")!!),
        )
        assertNull(VfsPairing.normalize("ABCDEFGHJKMNPQRST"))
        assertNull(VfsPairing.normalize("ABCDEFGHJKMNPQR!"))
        assertNull(VfsPairing.normalize("ABCDEFGHJKMNPQR"))
    }

    @Test
    fun ttlIsFiveMinutesAndCommandCarriesRouteIds() {
        val code = VfsPairing.normalize("ABCDEFGHJKMNPQRS")!!
        val command = VfsPairing.pairingCommand("link-1", "pair-1", code)
        assertEquals(
            "\"\$HOME/bin/wonder-vfs\" pair --link link-1 --pair pair-1 --code ABCDEFGHJKMNPQRS",
            command,
        )
        assertTrue(VfsPairing.TTL_MS == 5L * 60L * 1000L)
        assertTrue(VfsPairingCode("s", "p", code, 0, VfsPairing.TTL_MS).isExpired(VfsPairing.TTL_MS))
        assertFalse(VfsPairingCode("s", "p", code, 0, VfsPairing.TTL_MS).isExpired(VfsPairing.TTL_MS - 1))
    }

    @Test
    fun codesAreMemoryOnlyAndZeroizedOnRemoval() {
        val codes = VfsPairingCodes()
        val code = VfsPairing.normalize("ABCDEFGHJKMNPQRS")!!
        codes.put(VfsPairingCode("share", "pair", code, 0, 1_000))
        assertEquals("ABCDEFGHJKMNPQRS", VfsPairing.ascii(codes.get("share")!!.code))
        val removed = codes.remove("share")!!
        assertTrue(removed.code.all { it == '\u0000' })
        assertNull(codes.get("share"))
    }

    @Test
    fun invalidPairAttemptsAreRateLimitedButDoNotBurnTheCode() {
        var now = 1_000L
        val limiter = RelayPairRateLimiter(now = { now })
        repeat(5) { assertTrue(limiter.allow("link")) }
        assertFalse(limiter.allow("link"))
        now += 61_000L
        assertTrue(limiter.allow("link"))
    }
}

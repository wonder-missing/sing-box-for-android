package io.nekohasekai.sfa.vfs

import io.nekohasekai.sfa.agent.mcp.PlaintextMcpTokenCipher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class VfsStorageTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun deviceIdIsStableAndRegeneratesInvalidContent() {
        val file = File(temp.root, "device-id")
        val store = VfsDeviceIdStore(file)

        val first = store.getOrCreate()
        assertEquals(first, store.getOrCreate())
        assertTrue(VfsDeviceId.isValid(first))

        file.writeText("bad id\n", Charsets.UTF_8)
        val second = store.getOrCreate()
        assertNotEquals(first, second)
        assertTrue(VfsDeviceId.isValid(second))
        assertEquals(second, file.readText(Charsets.UTF_8).trim())
    }

    @Test
    fun resumeTokensRoundTripThroughTheFileStore() {
        val file = File(temp.root, "resume.json")
        val store = FileVfsResumeTokenStore(file, PlaintextMcpTokenCipher)
        val issuer = "https://example.com"

        assertNull(store.get(issuer, "link_1"))
        store.put(issuer, "link_1", "token-a")
        assertEquals("token-a", store.get(issuer, "link_1"))

        val reopened = FileVfsResumeTokenStore(file, PlaintextMcpTokenCipher)
        assertEquals("token-a", reopened.get(issuer, "link_1"))
        reopened.remove(issuer, "link_1")
        assertNull(reopened.get(issuer, "link_1"))
    }

    @Test
    fun corruptResumeFileMeansNoResumeInsteadOfACrash() {
        val file = File(temp.root, "resume.json")
        file.writeText("{not json", Charsets.UTF_8)
        val store = FileVfsResumeTokenStore(file, PlaintextMcpTokenCipher)

        assertNull(store.get("https://example.com", "link_1"))
        store.put("https://example.com", "link_1", "token-a")
        assertNotNull(store.get("https://example.com", "link_1"))
    }

    @Test
    fun trustPersistsAcrossStoreInstances() {
        val file = File(temp.root, "trust.json")
        val now = 1_000L
        val issuer = "https://example.com"
        val first =
            VfsTrustStore(
                now = { now },
                inactivityMs = 1_000L,
                persistence = FileVfsTrustPersistence(file),
            )
        first.grant(
            VfsTrust(
                issuer = issuer,
                scope = setOf(VfsScope.CONDUCTOR),
                lastUsedAt = now,
                expiresAt = now + 1_000L,
                processScoped = false,
            ),
        )

        val second =
            VfsTrustStore(
                now = { now },
                inactivityMs = 1_000L,
                persistence = FileVfsTrustPersistence(file),
            )

        assertNotNull(second.find(issuer, setOf(VfsScope.CONDUCTOR)))
    }

    @Test
    fun processTrustIsNotPersisted() {
        val file = File(temp.root, "trust.json")
        val now = 1_000L
        val issuer = "https://example.com"
        val first =
            VfsTrustStore(
                now = { now },
                inactivityMs = 1_000L,
                persistence = FileVfsTrustPersistence(file),
            )
        first.grant(
            VfsTrust(
                issuer = issuer,
                scope = setOf(VfsScope.CONVERSATION),
                lastUsedAt = now,
                expiresAt = 0L,
                processScoped = true,
            ),
        )

        val second =
            VfsTrustStore(
                now = { now },
                inactivityMs = 1_000L,
                persistence = FileVfsTrustPersistence(file),
            )

        assertNull(second.find(issuer, setOf(VfsScope.CONVERSATION)))
    }

    @Test
    fun revokePersists() {
        val file = File(temp.root, "trust.json")
        val now = 1_000L
        val issuer = "https://example.com"
        val first =
            VfsTrustStore(
                now = { now },
                inactivityMs = 1_000L,
                persistence = FileVfsTrustPersistence(file),
            )
        first.grant(
            VfsTrust(
                issuer = issuer,
                scope = setOf(VfsScope.CONDUCTOR),
                lastUsedAt = now,
                expiresAt = now + 1_000L,
                processScoped = false,
            ),
        )
        first.revoke(issuer)

        val second =
            VfsTrustStore(
                now = { now },
                inactivityMs = 1_000L,
                persistence = FileVfsTrustPersistence(file),
            )

        assertNull(second.find(issuer, setOf(VfsScope.CONDUCTOR)))
    }

    @Test
    fun corruptTrustFileMeansNoTrustInsteadOfACrash() {
        val file = File(temp.root, "trust.json")
        file.writeText("{not json", Charsets.UTF_8)

        val store =
            VfsTrustStore(
                now = { 1_000L },
                inactivityMs = 1_000L,
                persistence = FileVfsTrustPersistence(file),
            )

        assertNull(store.find("https://example.com", setOf(VfsScope.CONDUCTOR)))
    }
}

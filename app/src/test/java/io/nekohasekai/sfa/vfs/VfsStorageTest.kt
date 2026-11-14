package io.nekohasekai.sfa.vfs

import io.nekohasekai.sfa.agent.mcp.PlaintextMcpTokenCipher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        val file = VfsPrivateFiles.trustFile(temp.root)
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
        val file = VfsPrivateFiles.trustFile(temp.root)
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
        val file = VfsPrivateFiles.trustFile(temp.root)
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
        val file = VfsPrivateFiles.trustFile(temp.root)
        file.writeText("{not json", Charsets.UTF_8)

        val store =
            VfsTrustStore(
                now = { 1_000L },
                inactivityMs = 1_000L,
                persistence = FileVfsTrustPersistence(file),
            )

        assertNull(store.find("https://example.com", setOf(VfsScope.CONDUCTOR)))
    }

    @Test
    fun privateFilesLiveUnderTheVfsDirectory() {
        assertEquals(
            "vfs/trust.json",
            VfsPrivateFiles.trustFile(temp.root).relativeTo(temp.root).invariantSeparatorsPath,
        )
        assertEquals(
            "vfs/resume.json",
            VfsPrivateFiles.resumeFile(temp.root).relativeTo(temp.root).invariantSeparatorsPath,
        )
        assertEquals(
            "vfs/device-id",
            VfsPrivateFiles.deviceIdFile(temp.root).relativeTo(temp.root).invariantSeparatorsPath,
        )
    }

    @Test
    fun privateFilesAreOutsideEveryVfsScope() {
        val files =
            listOf(
                VfsPrivateFiles.trustFile(temp.root),
                VfsPrivateFiles.resumeFile(temp.root),
                VfsPrivateFiles.deviceIdFile(temp.root),
            )

        files.forEach { file ->
            assertFalse(
                "$file must not be under a VFS scope root",
                VfsPrivateFiles.isInsideVfsScope(file, temp.root),
            )
        }
        assertTrue(
            VfsPrivateFiles.isInsideVfsScope(
                File(temp.root, "conductor/note.md"),
                temp.root,
            ),
        )
    }

    @Test
    fun scopedSourceCannotReachPrivateMetadata() =
        runBlocking {
            val permissive =
                object : VirtualDirectorySource {
                    override suspend fun stat(path: String): VfsEntry =
                        VfsEntry(path, path.substringAfterLast('/'), false, 1L, 1L)

                    override suspend fun list(path: String): List<VfsEntry> = emptyList()

                    override suspend fun read(
                        path: String,
                        offset: Long,
                        length: Int,
                    ): VfsRead = VfsRead(ByteArray(1), true)
                }
            val scoped = VfsScopedSource(permissive, VfsScope.entries.toSet())

            assertEquals("not_found", failure { scoped.stat("vfs/trust.json") }.code)
            assertEquals("not_found", failure { scoped.read("vfs/trust.json", 0, 1) }.code)
        }

    private suspend fun failure(block: suspend () -> Unit): VfsException {
        val error = runCatching { block() }.exceptionOrNull()
        assertTrue("expected VfsException, got $error", error is VfsException)
        return error as VfsException
    }
}

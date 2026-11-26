package io.nekohasekai.sfa.vfs.relay

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class VfsShareRepositoryTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun repository(): Pair<VfsShareRepository, VfsShareRecord> {
        val store = VfsShareStore(File(temp.root, "relay/shares.json"), FakeVfsShareCipher())
        val record =
            store.newRecord(
                "share-1", "Claude", "link-1", ByteArray(32) { 9 },
                setOf(RelayScope.APP_FILES), "portal", "hk", "https://portal.example", "device", emptyList(),
            )
        val repository = VfsShareRepository(store)
        repository.add(record)
        return repository to record
    }

    @Test
    fun concurrentLastUsedDoesNotResurrectARevokedShare() {
        val (repository, record) = repository()
        val revoked = AtomicReference<VfsShareRecord?>()
        val start = CountDownLatch(1)
        val revoker =
            Thread {
                start.await()
                revoked.set(
                    repository.update(record.shareId) {
                        it.copy(revokedAt = 1_000L, revokingAt = 1_000L, secretCiphertext = "")
                    },
                )
            }
        val reader =
            Thread {
                start.await()
                repository.markLastUsed(record.shareId, 2_000L, throttleMs = 0)
            }
        revoker.start()
        reader.start()
        start.countDown()
        revoker.join(TimeUnit.SECONDS.toMillis(5))
        reader.join(TimeUnit.SECONDS.toMillis(5))

        val final = repository.load().single()
        assertNotNull(final.revokedAt)
        assertEquals("", final.secretCiphertext)
        assertNotNull(revoked.get())
        assertNull(repository.markLastUsed(record.shareId, 3_000L, throttleMs = 0))
        assertEquals("", repository.load().single().secretCiphertext)
    }

    @Test
    fun markLastUsedOnlyTouchesTheTargetField() {
        val (repository, record) = repository()
        val before = repository.load().single()
        val updated = repository.markLastUsed(record.shareId, 10_000L, throttleMs = 0)
        assertNotNull(updated)
        assertEquals(10_000L, updated!!.lastUsedAt)
        assertEquals(before.secretCiphertext, updated.secretCiphertext)
        assertEquals(before.scopes, updated.scopes)
        assertNull(repository.markLastUsed(record.shareId, 10_010L, throttleMs = 5_000L))
    }

    @Test
    fun failedCreateRollsBackThePendingShare() =
        runBlocking {
            val (repository, record) = repository()
            repository.remove(record.shareId)
            assertTrue(repository.load().isEmpty())
            try {
                repository.addPendingAndRollback(record) {
                    throw IOException("postLink failed")
                }
                throw AssertionError("expected failure")
            } catch (_: IOException) {
                assertTrue(repository.load().isEmpty())
            }
        }
}

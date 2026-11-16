package io.nekohasekai.sfa.vfs

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class InternalFilesVirtualDirectorySourceTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun source(): InternalFilesVirtualDirectorySource =
        InternalFilesVirtualDirectorySource(temp.root, now = { 5_000L })

    private fun write(
        path: String,
        text: String,
    ): File {
        val file = File(temp.root, path)
        file.parentFile?.mkdirs()
        file.writeText(text)
        return file
    }

    @Test
    fun rootListsInternalFilesButNotVfsState() =
        runBlocking {
            write("logs/app.log", "hello")
            write("telemetry/events.log", "event")
            write("conductor/images/a.jpg", "image")
            write("vfs/trust.json", "private")

            val entries = source().list("")
            assertEquals(
                listOf("conductor", "logs", "telemetry"),
                entries.map { it.name },
            )
        }

    @Test
    fun readsAndStatsAnInternalFile() =
        runBlocking {
            write("logs/app.log", "hello app")

            val entry = source().stat("logs/app.log")
            assertEquals("app.log", entry.name)
            assertEquals(9L, entry.size)
            assertEquals("text/plain; charset=utf-8", entry.mimeType)

            val read = source().read("logs/app.log", 0, 5)
            assertArrayEquals("hello".toByteArray(), read.data)
            assertFalse(read.eof)
        }

    @Test
    fun vfsStateIsNotFoundAndNotListed() =
        runBlocking {
            write("vfs/trust.json", "private")
            write("logs/app.log", "hello")
            val source = source()

            assertEquals("not_found", failure { source.stat("vfs") }.code)
            assertEquals("not_found", failure { source.stat("vfs/trust.json") }.code)
            assertEquals("not_found", failure { source.list("vfs") }.code)
            assertEquals("not_found", failure { source.read("vfs/trust.json", 0, 1) }.code)
            assertFalse(source.list("").any { it.name == "vfs" })
        }

    @Test
    fun rejectsTraversalAndAbsolutePaths() =
        runBlocking {
            val source = source()

            assertEquals("invalid_path", failure { source.stat("../outside") }.code)
            assertEquals("invalid_path", failure { source.stat("/etc/passwd") }.code)
            assertEquals("invalid_path", failure { source.read("a/../../outside", 0, 1) }.code)
        }

    @Test
    fun doesNotFollowSymlinksOutsideTheInternalDirectory() =
        runBlocking {
            val outside = Files.createTempDirectory("vfs-internal-outside").toFile()
            try {
                File(outside, "secret.txt").writeText("secret")
                val link = File(temp.root, "outside-link")
                try {
                    Files.createSymbolicLink(link.toPath(), outside.toPath())
                } catch (_: UnsupportedOperationException) {
                    assumeTrue("symlinks are not supported", false)
                } catch (_: java.io.IOException) {
                    assumeTrue("symlinks are not supported", false)
                }

                assertEquals("not_found", failure { source().stat("outside-link/secret.txt") }.code)
                assertEquals("not_found", failure { source().stat("outside-link") }.code)
            } finally {
                outside.deleteRecursively()
            }
        }

    @Test
    fun listingAFileAndReadingADirectoryAreRejected() =
        runBlocking {
            write("logs/app.log", "hello")
            File(temp.root, "logs").mkdirs()
            val source = source()

            assertEquals("not_found", failure { source.list("logs/app.log") }.code)
            assertEquals("not_supported", failure { source.read("logs", 0, 1) }.code)
        }

    private suspend fun failure(block: suspend () -> Unit): VfsException {
        val error = runCatching { block() }.exceptionOrNull()
        assertTrue("expected VfsException, got $error", error is VfsException)
        return error as VfsException
    }
}

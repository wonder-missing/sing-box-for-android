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

class WorkspaceVirtualDirectorySourceTest {
    @get:Rule
    val temp = TemporaryFolder()

    private class FakeRuntimeFiles(
        var trace: String = "# Trace\n",
        var conversation: String = "# Conversation\n",
        var prompt: String = "# System prompt\n",
    ) : VirtualDirectoryRuntimeFiles {
        override fun traceMarkdown(): String = trace

        override fun conversationMarkdown(): String = conversation

        override fun systemPromptMarkdown(): String = prompt
    }

    private fun source(runtime: FakeRuntimeFiles = FakeRuntimeFiles()): WorkspaceVirtualDirectorySource {
        val conductor =
            ConductorVirtualDirectorySource(
                workingDir = temp.root,
                runtimeFiles = runtime,
                now = { 5_000L },
            )
        return WorkspaceVirtualDirectorySource(temp.root, conductor, now = { 5_000L })
    }

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
    fun rootListsTheWorkingDirectoryAndKeepsTheVirtualDirectories() =
        runBlocking {
            write("crash_reports/2026-11-16/metadata.json", "{}")
            write("cache.db", "db")
            val source = source()

            val entries = source.list("")
            assertEquals(
                listOf("conductor", "cache.db", "crash_reports", ".runtime"),
                entries.map { it.name },
            )
            assertTrue(entries.first { it.name == "conductor" }.isDirectory)
            assertTrue(entries.first { it.name == ".runtime" }.isDirectory)
            assertFalse(entries.first { it.name == "cache.db" }.isDirectory)
        }

    @Test
    fun readsAndStatsAWorkingDirectoryFile() =
        runBlocking {
            write("crash_reports/2026-11-16/metadata.json", "hello crash")
            val source = source()

            val entry = source.stat("crash_reports/2026-11-16/metadata.json")
            assertEquals("metadata.json", entry.name)
            assertEquals(11L, entry.size)
            assertEquals("application/json; charset=utf-8", entry.mimeType)

            val read = source.read("crash_reports/2026-11-16/metadata.json", 0, 5)
            assertArrayEquals("hello".toByteArray(), read.data)
            assertFalse(read.eof)

            val tail = source.read("crash_reports/2026-11-16/metadata.json", 6, 99)
            assertArrayEquals("crash".toByteArray(), tail.data)
            assertTrue(tail.eof)

            val empty = source.read("crash_reports/2026-11-16/metadata.json", 11, 1)
            assertEquals(0, empty.data.size)
            assertTrue(empty.eof)
        }

    @Test
    fun keepsConductorAndRuntimeSemantics() =
        runBlocking {
            write("conductor/note.md", "hello conductor")
            val runtime = FakeRuntimeFiles(trace = "# Trace\nline\n")
            val source = source(runtime)

            assertArrayEquals(
                "hello conductor".toByteArray(),
                source.read("conductor/note.md", 0, 99).data,
            )
            assertArrayEquals(
                "# Trace\nline\n".toByteArray(),
                source.read(".runtime/trace.md", 0, 99).data,
            )
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
    fun doesNotFollowSymlinksOutsideTheWorkingDirectory() =
        runBlocking {
            val outside = Files.createTempDirectory("vfs-outside").toFile()
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
                val source = source()

                assertEquals("not_found", failure { source.stat("outside-link/secret.txt") }.code)
                assertEquals("not_found", failure { source.stat("outside-link") }.code)
            } finally {
                outside.deleteRecursively()
            }
        }

    @Test
    fun listingAFileAndReadingADirectoryAreRejected() =
        runBlocking {
            write("cache.db", "db")
            File(temp.root, "crash_reports").mkdirs()
            val source = source()

            assertEquals("not_found", failure { source.list("cache.db") }.code)
            assertEquals("not_supported", failure { source.read("crash_reports", 0, 1) }.code)
        }

    private suspend fun failure(block: suspend () -> Unit): VfsException {
        val error = runCatching { block() }.exceptionOrNull()
        assertTrue("expected VfsException, got $error", error is VfsException)
        return error as VfsException
    }
}

package io.nekohasekai.sfa.vfs

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ConductorVirtualDirectorySourceTest {
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

    private fun source(
        runtime: FakeRuntimeFiles = FakeRuntimeFiles(),
        now: () -> Long = { 5_000L },
    ): ConductorVirtualDirectorySource =
        ConductorVirtualDirectorySource(
            workingDir = temp.root,
            runtimeFiles = runtime,
            now = now,
        )

    private fun writeConductor(
        path: String,
        text: String,
    ): File {
        val file = File(temp.root, path)
        file.parentFile?.mkdirs()
        file.writeText(text)
        return file
    }

    @Test
    fun rootIsADirectoryAndListsTheTwoGrantedEntries() =
        runBlocking {
            val source = source()

            val root = source.stat("")
            assertTrue(root.isDirectory)
            assertEquals("", root.path)
            assertEquals("", root.name)

            val entries = source.list("")
            assertEquals(listOf("conductor", ".runtime"), entries.map { it.name })
            assertTrue(entries.all { it.isDirectory })
        }

    @Test
    fun conductorIsEmptyBeforeTheWorkspaceExists() =
        runBlocking {
            val source = source()

            val entry = source.stat("conductor")
            assertTrue(entry.isDirectory)
            assertEquals("conductor", entry.name)
            assertTrue(source.list("conductor").isEmpty())
        }

    @Test
    fun conductorFilesAreListedAndStated() =
        runBlocking {
            writeConductor("conductor/note.md", "hello world")
            val source = source()

            val entries = source.list("conductor")
            assertEquals(1, entries.size)
            val entry = entries.single()
            assertEquals("conductor/note.md", entry.path)
            assertEquals("note.md", entry.name)
            assertFalse(entry.isDirectory)
            assertEquals(11L, entry.size)
            assertEquals("text/markdown; charset=utf-8", entry.mimeType)
            assertNotNull(entry.etag)
            assertTrue(entry.etag!!.startsWith("\""))

            val stat = source.stat("conductor/note.md")
            assertEquals(entry, stat)
        }

    @Test
    fun nestedConductorDirectoriesWork() =
        runBlocking {
            writeConductor("conductor/sub/nested.txt", "nested")
            val source = source()

            assertTrue(source.stat("conductor/sub").isDirectory)
            val entries = source.list("conductor/sub")
            assertEquals("nested.txt", entries.single().name)
            assertEquals("conductor/sub/nested.txt", entries.single().path)
            assertEquals(6L, source.stat("conductor/sub/nested.txt").size)
        }

    @Test
    fun conductorReadsRangesAndEof() =
        runBlocking {
            writeConductor("conductor/note.txt", "hello world")
            val source = source()

            val first = source.read("conductor/note.txt", 0, 5)
            assertArrayEquals("hello".toByteArray(), first.data)
            assertFalse(first.eof)

            val last = source.read("conductor/note.txt", 6, 100)
            assertArrayEquals("world".toByteArray(), last.data)
            assertTrue(last.eof)

            val atEnd = source.read("conductor/note.txt", 11, 1)
            assertEquals(0, atEnd.data.size)
            assertTrue(atEnd.eof)
        }

    @Test
    fun conductorReadRejectsBadRanges() =
        runBlocking {
            writeConductor("conductor/note.txt", "hello")
            val source = source()

            expectCode(VfsErrorCode.BAD_REQUEST) { source.read("conductor/note.txt", 0, 0) }
            expectCode(VfsErrorCode.BAD_REQUEST) {
                source.read("conductor/note.txt", 0, VfsProtocol.MAX_READ_BYTES + 1)
            }
            expectCode(VfsErrorCode.RANGE_NOT_SATISFIABLE) {
                source.read("conductor/note.txt", 6, 1)
            }
        }

    @Test
    fun directoriesAreNotReadable() =
        runBlocking {
            writeConductor("conductor/sub/x.txt", "x")
            val source = source()

            expectCode(VfsErrorCode.NOT_SUPPORTED) { source.read("conductor", 0, 1) }
            expectCode(VfsErrorCode.NOT_SUPPORTED) { source.read("conductor/sub", 0, 1) }
        }

    @Test
    fun missingPathsAreNotFound() =
        runBlocking {
            val source = source()

            expectCode(VfsErrorCode.NOT_FOUND) { source.stat("conductor/missing.md") }
            expectCode(VfsErrorCode.NOT_FOUND) { source.list("conductor/missing") }
            expectCode(VfsErrorCode.NOT_FOUND) { source.read("conductor/missing.md", 0, 1) }
            expectCode(VfsErrorCode.NOT_FOUND) { source.stat("other") }
            expectCode(VfsErrorCode.NOT_FOUND) { source.list("other") }
            expectCode(VfsErrorCode.NOT_FOUND) { source.read("other", 0, 1) }
        }

    @Test
    fun traversalAndAbsolutePathsAreRejected() =
        runBlocking {
            writeConductor("conductor/note.md", "hello")
            val source = source()

            listOf(
                "/conductor/note.md",
                "../outside.txt",
                "conductor/../note.md",
                "conductor/./note.md",
                "conductor//note.md",
                "conductor\\note.md",
                "conductor/\u0000note.md",
            ).forEach { path ->
                expectCode(VfsErrorCode.INVALID_PATH) { source.stat(path) }
                expectCode(VfsErrorCode.INVALID_PATH) { source.list(path) }
                expectCode(VfsErrorCode.INVALID_PATH) { source.read(path, 0, 1) }
            }
        }

    @Test
    fun symlinksThatEscapeTheRootAreNotServed() =
        runBlocking {
            val outsideDir = java.nio.file.Files.createTempDirectory("vfs-outside")
            try {
                val outside = File(outsideDir.toFile(), "secret.txt")
                outside.writeText("secret")
                val link = File(temp.root, "conductor/link.txt")
                link.parentFile.mkdirs()
                val created =
                    runCatching {
                        java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath())
                    }.isSuccess
                assumeTrue("symlinks are not supported here", created)
                val source = source()

                expectCode(VfsErrorCode.NOT_FOUND) { source.stat("conductor/link.txt") }
                expectCode(VfsErrorCode.NOT_FOUND) { source.read("conductor/link.txt", 0, 1) }
            } finally {
                outsideDir.toFile().deleteRecursively()
            }
        }

    @Test
    fun runtimeDirectoryListsGeneratedFiles() =
        runBlocking {
            val source = source()

            val directory = source.stat(".runtime")
            assertTrue(directory.isDirectory)

            val entries = source.list(".runtime")
            assertEquals(
                listOf("trace.md", "conversation.md", "system_prompt.md"),
                entries.map { it.name },
            )
            assertTrue(entries.all { !it.isDirectory && it.mimeType == ConductorVirtualDirectorySource.MIME_MARKDOWN })
        }

    @Test
    fun runtimeFilesComeFromTheGenerators() =
        runBlocking {
            val runtime = FakeRuntimeFiles(trace = "# Trace\nbody\n")
            val source = source(runtime = runtime)

            val trace = source.stat(".runtime/trace.md")
            assertEquals(".runtime/trace.md", trace.path)
            assertEquals("# Trace\nbody\n".toByteArray().size.toLong(), trace.size)
            assertFalse(trace.isDirectory)
            assertEquals(5_000L, trace.mtimeMs)

            val read = source.read(".runtime/trace.md", 0, 4096)
            assertArrayEquals("# Trace\nbody\n".toByteArray(), read.data)
            assertTrue(read.eof)

            runtime.trace = "# Trace\nchanged\n"
            val changed = source.stat(".runtime/trace.md")
            assertNotEquals(trace.etag, changed.etag)
            assertNotEquals(trace.size, changed.size)
        }

    @Test
    fun runtimeReadsUseUtf8ByteOffsets() =
        runBlocking {
            val runtime = FakeRuntimeFiles(prompt = "a中b")
            val source = source(runtime = runtime)

            val read = source.read(".runtime/system_prompt.md", 1, 3)

            assertArrayEquals("中".toByteArray(), read.data)
            assertFalse(read.eof)
            assertEquals(5L, source.stat(".runtime/system_prompt.md").size)
        }

    @Test
    fun unknownRuntimeFilesAreNotFound() =
        runBlocking {
            val source = source()

            expectCode(VfsErrorCode.NOT_FOUND) { source.stat(".runtime/other.md") }
            expectCode(VfsErrorCode.NOT_FOUND) { source.list(".runtime/sub") }
            expectCode(VfsErrorCode.NOT_FOUND) { source.read(".runtime/other.md", 0, 1) }
            expectCode(VfsErrorCode.NOT_FOUND) { source.stat(".runtime/trace.md/child") }
        }

    @Test
    fun emptyConductorFilesReadAsEof() =
        runBlocking {
            writeConductor("conductor/empty.txt", "")
            val source = source()

            val read = source.read("conductor/empty.txt", 0, 10)

            assertEquals(0, read.data.size)
            assertTrue(read.eof)
            assertEquals(0L, source.stat("conductor/empty.txt").size)
        }

    private suspend fun expectCode(
        code: String,
        block: suspend () -> Unit,
    ) {
        try {
            block()
            fail("expected VfsException with $code")
        } catch (error: VfsException) {
            assertEquals(code, error.code)
        }
    }
}

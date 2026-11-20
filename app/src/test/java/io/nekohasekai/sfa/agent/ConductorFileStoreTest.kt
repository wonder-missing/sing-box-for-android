package io.nekohasekai.sfa.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ConductorFileStoreTest {
    private val workingDir: File = Files.createTempDirectory("conductor-files").toFile()
    private val store = ConductorFileStore(workingDir)

    @Test
    fun readsTheWorkingDirectoryButWritesOnlyTheWorkspace() {
        File(workingDir, "report.txt").writeText("outside")
        store.writeText("conductor/report.txt", "inside")

        assertEquals("outside", store.readText("report.txt").text)
        assertEquals("inside", store.readText("conductor/report.txt").text)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsParentTraversal() {
        store.readText("../outside.txt")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsAbsolutePaths() {
        store.readText("/etc/passwd")
    }

    @Test
    fun rootRelativeConductorPathsResolveLikeMarkdownLinks() {
        store.writeText("conductor/note.md", "x")
        assertEquals("x", store.readText("/conductor/note.md").text)

        store.writeText("/conductor/other.md", "y")
        assertEquals("y", store.readText("conductor/other.md").text)

        store.writeResourceBytes("conductor/resources/frame.png", byteArrayOf(1), overwrite = true)
        assertEquals(1L, store.resolveResource("/conductor/resources/frame.png").length())
        assertEquals(1L, store.resolveResource("/resources/frame.png").length())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsWritesOutsideTheWorkspace() {
        store.writeText("report.txt", "nope")
    }

    @Test
    fun protectsExistingFilesAndAllowsExplicitOverwrite() {
        store.writeText("conductor/note.md", "one")
        assertTrue(runCatching { store.writeText("conductor/note.md", "two") }.isFailure)

        store.writeText("conductor/note.md", "two", overwrite = true)
        assertEquals("two", store.readText("conductor/note.md").text)
    }

    @Test
    fun truncatesLargeReadsAndReportsTheOriginalSize() {
        val text = "x".repeat(100)
        store.writeText("conductor/big.txt", text)

        val result = store.readText("conductor/big.txt", maxBytes = 10)

        assertEquals(100, result.size)
        assertEquals(10, result.text.length)
        assertTrue(result.truncated)
    }

    @Test
    fun readsLineRangesTailsAndGreps() {
        store.writeText("conductor/lines.txt", "a\nb\nc\nd")

        val page = store.readLines("conductor/lines.txt", offset = 1, limit = 2)
        assertEquals("b\nc", page.text)
        assertEquals(2, page.lineCount)
        assertTrue(page.truncated)

        val tail = store.tail("conductor/lines.txt", lines = 2)
        assertEquals("c\nd", tail.text)
        assertEquals(2, tail.offset)

        val grep = store.grep("conductor/lines.txt", "c", contextLines = 1)
        assertEquals(listOf(2, 3, 4), grep.matches.map { it.line })
        assertEquals("b\nc\nd", grep.matches.joinToString("\n") { it.text })
    }

    @Test
    fun grepCapsLongLines() {
        store.writeText("conductor/long.txt", "prefix " + "x".repeat(1000) + " suffix")

        val result = store.grep("conductor/long.txt", "prefix", maxLineChars = 20)

        val match = result.matches.single()
        assertEquals(20, match.text.length)
        assertTrue(match.truncated)
        assertTrue(result.truncated)
    }

    @Test
    fun listsAndDeletesInsideTheWorkspace() {
        store.writeText("conductor/a.txt", "a")
        store.mkdir("conductor/sub")
        val paths = store.list("conductor").map { it.path }
        assertTrue(paths.contains("conductor/a.txt"))
        assertTrue(paths.contains("conductor/sub"))

        store.delete("conductor/a.txt")
        assertFalse(store.list("conductor").any { it.path == "conductor/a.txt" })
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsBinaryReads() {
        File(workingDir, "bin.dat").writeBytes(byteArrayOf(0, 1, 2, 3))
        store.readText("bin.dat")
    }

    @Test
    fun workspaceCreatesTheArtifactsDirectory() {
        store.ensureWorkspace()

        assertTrue(File(workingDir, "conductor/resources/artifacts").isDirectory)
    }

    @Test
    fun streamsArtifactsAndReturnsTheirPathAndSize() {
        val stored =
            store.writeResourceStream("conductor/resources/artifacts/run/file.bin", maxBytes = 1024) { output ->
                output.write(byteArrayOf(1, 2, 3, 4))
            }

        assertEquals("conductor/resources/artifacts/run/file.bin", stored.path)
        assertEquals(4L, stored.size)
        assertEquals(4L, store.resolveResource(stored.path).length())
    }

    @Test
    fun streamedArtifactsRespectTheByteCap() {
        val failure =
            runCatching {
                store.writeResourceStream("conductor/resources/artifacts/run/too-big.bin", maxBytes = 3) { output ->
                    output.write(byteArrayOf(1, 2, 3, 4))
                }
            }

        assertTrue(failure.isFailure)
        assertFalse(File(workingDir, "conductor/resources/artifacts/run/too-big.bin").exists())
    }

    @Test
    fun theInboxIsReadOnly() {
        assertTrue(runCatching { store.writeText("conductor/resources/inbox/x/note.txt", "x") }.isFailure)
        assertTrue(
            runCatching {
                store.writeResourceBytes("inbox/x/note.txt", "x".toByteArray())
            }.isFailure,
        )
    }

    @Test
    fun resourcesDoNotConsumeTheWorkspaceFileLimit() {
        repeat(25) { index ->
            store.writeResourceBytes("conductor/resources/artifact-$index.txt", "x".toByteArray(), overwrite = true)
        }

        store.writeText("conductor/note.txt", "ok")

        assertEquals("ok", store.readText("conductor/note.txt").text)
    }
}

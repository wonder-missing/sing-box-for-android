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
}

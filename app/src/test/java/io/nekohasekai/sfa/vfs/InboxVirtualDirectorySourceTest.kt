package io.nekohasekai.sfa.vfs

import io.nekohasekai.sfa.agent.InboxRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class InboxVirtualDirectorySourceTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun source(registry: InboxRegistry = InboxRegistry()): Pair<InboxRegistry, InboxVirtualDirectorySource> =
        registry to InboxVirtualDirectorySource(registry) { 5_000L }

    private fun file(
        name: String,
        text: String,
    ): File = File(temp.root, name).apply { parentFile?.mkdirs(); writeText(text) }

    @Test
    fun rootListsRegisteredIds() =
        runBlocking {
            val registry = InboxRegistry()
            registry.registerFile("abc", "photo.jpg", "image/jpeg", file("photo.jpg", "hello"))
            val (_, source) = source(registry)

            val root = source.stat("conductor/resources/inbox")
            assertTrue(root.isDirectory)
            val entries = source.list("conductor/resources/inbox")
            assertEquals(listOf("abc"), entries.map { it.name })
            assertEquals("conductor/resources/inbox/abc", entries.single().path)
        }

    @Test
    fun fileIsStatedAndReadWithRanges() =
        runBlocking {
            val registry = InboxRegistry()
            registry.registerFile("abc", "note.txt", "text/plain", file("note.txt", "hello world"))
            val (_, source) = source(registry)

            val entry = source.stat("conductor/resources/inbox/abc/note.txt")
            assertEquals("note.txt", entry.name)
            assertEquals(11L, entry.size)
            assertEquals("text/plain", entry.mimeType)

            assertArrayEquals("hello".toByteArray(), source.read(entry.path, 0, 5).data)
            val tail = source.read(entry.path, 6, 100)
            assertArrayEquals("world".toByteArray(), tail.data)
            assertTrue(tail.eof)
            assertTrue(source.read(entry.path, 11, 1).eof)
        }

    @Test
    fun missingAndMismatchedNamesAreNotFound() =
        runBlocking {
            val registry = InboxRegistry()
            registry.registerFile("abc", "note.txt", "text/plain", file("note.txt", "x"))
            val (_, source) = source(registry)

            expect(VfsErrorCode.NOT_FOUND) { source.stat("conductor/resources/inbox/missing") }
            expect(VfsErrorCode.NOT_FOUND) { source.stat("conductor/resources/inbox/abc/other.txt") }
            expect(VfsErrorCode.NOT_FOUND) { source.stat("other") }
            expect(VfsErrorCode.NOT_SUPPORTED) { source.read("conductor/resources/inbox", 0, 1) }
        }

    @Test
    fun deletingTheOriginalHidesTheEntry() =
        runBlocking {
            val registry = InboxRegistry()
            val original = file("note.txt", "x")
            registry.registerFile("abc", "note.txt", "text/plain", original)
            val (_, source) = source(registry)

            original.delete()

            assertTrue(source.isEmpty())
            expect(VfsErrorCode.NOT_FOUND) { source.stat("conductor/resources/inbox/abc/note.txt") }
        }

    @Test
    fun badRangesAreRejected() =
        runBlocking {
            val registry = InboxRegistry()
            registry.registerFile("abc", "note.txt", "text/plain", file("note.txt", "hello"))
            val (_, source) = source(registry)
            val path = "conductor/resources/inbox/abc/note.txt"

            expect(VfsErrorCode.BAD_REQUEST) { source.read(path, 0, 0) }
            expect(VfsErrorCode.BAD_REQUEST) { source.read(path, 0, VfsProtocol.MAX_READ_BYTES + 1) }
            expect(VfsErrorCode.RANGE_NOT_SATISFIABLE) { source.read(path, 6, 1) }
        }

    private suspend fun expect(
        code: String,
        block: suspend () -> Any?,
    ) {
        try {
            block()
            fail("expected VfsException with $code")
        } catch (e: VfsException) {
            assertEquals(code, e.code)
        }
    }
}

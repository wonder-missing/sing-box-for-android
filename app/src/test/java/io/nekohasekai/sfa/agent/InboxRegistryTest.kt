package io.nekohasekai.sfa.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class InboxRegistryTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun filesAreRegisteredUnderTheLogicalPath() {
        val file = File(temp.root, "photo.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val registry = InboxRegistry()

        val entry = registry.registerFile("abc", "photo.jpg", "image/jpeg", file)

        assertNotNull(entry)
        assertEquals("photo.jpg", entry!!.name)
        assertEquals("image/jpeg", entry.mimeType)
        assertEquals(3L, entry.size)
        assertEquals(entry, registry.entry("abc"))
        assertEquals(listOf(entry), registry.entries())
        assertFalse(registry.isEmpty())
    }

    @Test
    fun replacingTheSameIdKeepsTheNewestEntry() {
        val first = File(temp.root, "first.jpg").apply { writeText("one") }
        val second = File(temp.root, "second.jpg").apply { writeText("two") }
        val registry = InboxRegistry()

        registry.registerFile("same", "first.jpg", "image/jpeg", first)
        registry.registerFile("same", "second.jpg", "image/jpeg", second)

        assertEquals("second.jpg", registry.entry("same")!!.name)
        assertEquals(1, registry.entries().size)
    }

    @Test
    fun invalidIdsAreRejected() {
        val file = File(temp.root, "photo.jpg").apply { writeText("x") }
        val registry = InboxRegistry()

        assertNull(registry.registerFile("../x", "photo.jpg", "image/jpeg", file))
        assertNull(registry.registerFile("", "photo.jpg", "image/jpeg", file))
        assertTrue(registry.isEmpty())
    }

    @Test
    fun clearRemovesEverything() {
        val file = File(temp.root, "photo.jpg").apply { writeText("x") }
        val registry = InboxRegistry()
        registry.registerFile("abc", "photo.jpg", "image/jpeg", file)

        registry.clear()

        assertNull(registry.entry("abc"))
        assertTrue(registry.isEmpty())
    }

    @Test
    fun entriesAreCapped() {
        val registry = InboxRegistry()
        repeat(InboxRegistry.MAX_ENTRIES + 2) { index ->
            val file = File(temp.root, "f$index.jpg").apply { writeText("x") }
            registry.registerFile("id$index", "f$index.jpg", "image/jpeg", file)
        }

        assertEquals(InboxRegistry.MAX_ENTRIES, registry.entries().size)
        assertNull(registry.entry("id0"))
        assertNotNull(registry.entry("id${InboxRegistry.MAX_ENTRIES + 1}"))
    }
}

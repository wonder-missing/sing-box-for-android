package io.nekohasekai.sfa.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InboxPathsTest {
    @Test
    fun validIdsRejectTraversalAndSeparators() {
        assertTrue(InboxPaths.validId("abc123"))
        assertTrue(InboxPaths.validId("6c0ccaa69d664b6aa0a8933f01d358d0"))
        assertFalse(InboxPaths.validId("."))
        assertFalse(InboxPaths.validId(".."))
        assertFalse(InboxPaths.validId("a/b"))
        assertFalse(InboxPaths.validId(""))
    }

    @Test
    fun pathUsesTheSameSanitizedNameAsTheRegistry() {
        assertEquals(
            "conductor/resources/inbox/abc/photo.jpg",
            InboxPaths.path("abc", "photo.jpg", "image/jpeg"),
        )
        assertEquals(
            "conductor/resources/inbox/abc/my_photo.jpg",
            InboxPaths.path("abc", "my photo.jpg", "image/jpeg"),
        )
    }

    @Test
    fun fileNamePreservesUnicodeAndAddsAnExtensionWhenMissing() {
        assertEquals("照片.jpg", InboxPaths.fileName("照片", "image/jpeg"))
        assertEquals("notes.txt", InboxPaths.fileName("notes", "text/plain"))
        assertEquals("file.png", InboxPaths.fileName("", "image/png"))
        assertEquals("file.bin", InboxPaths.fileName("../", "application/octet-stream"))
    }

    @Test
    fun fileNameStripsSeparatorsAndControlCharacters() {
        val name = InboxPaths.fileName("a/b\\c\n d.jpg", "image/jpeg")
        assertEquals("c_d.jpg", name)
        assertFalse(name.contains('/'))
        assertFalse(name.contains('\\'))
        assertFalse(name.contains('\n'))
    }
}

package io.nekohasekai.sfa.vfs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VfsServerBaseTest {
    @Test
    fun normalizesSchemeHostPortAndTrailingSlash() {
        assertEquals("https://example.com", VfsServerBase.normalize("HTTPS://Example.COM:443/"))
        assertEquals("http://example.com", VfsServerBase.normalize("http://example.com:80"))
        assertEquals("https://example.com:8443/base", VfsServerBase.normalize("https://example.com:8443/base/"))
        assertEquals("https://example.com/a/b", VfsServerBase.normalize("https://EXAMPLE.com/a/b///"))
    }

    @Test
    fun rejectsUnsafeOrMalformedUrls() {
        assertNull(VfsServerBase.normalize(""))
        assertNull(VfsServerBase.normalize("ftp://example.com"))
        assertNull(VfsServerBase.normalize("https://user:pass@example.com"))
        assertNull(VfsServerBase.normalize("https://example.com/?token=1"))
        assertNull(VfsServerBase.normalize("https://example.com/#fragment"))
        assertNull(VfsServerBase.normalize("https:///missing-host"))
    }

    @Test
    fun derivesBaseByRemovingTheGrantPath() {
        assertEquals(
            "https://clawdy-mini.tailf665f5.ts.net",
            VfsServerBase.resolve(null, "https://clawdy-mini.tailf665f5.ts.net/knocks/mcp/hk_abc123"),
        )
        assertEquals(
            "https://example.com/base",
            VfsServerBase.resolve(null, "https://example.com/base/knocks/mcp/hk_abc/"),
        )
        assertEquals(
            "https://example.com/mcp",
            VfsServerBase.resolve(null, "https://example.com/mcp"),
        )
    }

    @Test
    fun explicitServerBaseWins() {
        assertEquals(
            "https://example.com/base",
            VfsServerBase.resolve(
                "https://EXAMPLE.com/base/",
                "https://example.com/knocks/mcp/hk_abc",
            ),
        )
    }

    @Test
    fun providerUrlUsesTheFixedPath() {
        assertEquals(
            "wss://example.com/vfs/provider",
            VfsServerBase.providerUrl("https://example.com/"),
        )
        assertEquals(
            "ws://example.com:8080/base/vfs/provider",
            VfsServerBase.providerUrl("http://example.com:8080/base/"),
        )
        assertNull(VfsServerBase.providerUrl("ftp://example.com"))
    }

    @Test
    fun matchesOnlyCanonicalBaseUrls() {
        assertTrue(
            VfsServerBase.matches(
                "https://EXAMPLE.com/",
                null,
                "https://example.com/knocks/mcp/hk_abc",
            ),
        )
        assertFalse(
            VfsServerBase.matches(
                "https://other.example",
                null,
                "https://example.com/knocks/mcp/hk_abc",
            ),
        )
        assertFalse(VfsServerBase.matches("https://example.com", "https://example.com/base", "https://example.com"))
    }
}

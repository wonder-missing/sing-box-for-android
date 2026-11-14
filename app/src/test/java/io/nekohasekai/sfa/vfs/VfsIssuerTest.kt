package io.nekohasekai.sfa.vfs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VfsIssuerTest {
    @Test
    fun normalizesSchemeHostPortAndTrailingSlash() {
        assertEquals("https://example.com", VfsIssuer.normalize("HTTPS://Example.COM:443/"))
        assertEquals("http://example.com", VfsIssuer.normalize("http://example.com:80"))
        assertEquals("https://example.com:8443/base", VfsIssuer.normalize("https://example.com:8443/base/"))
        assertEquals("https://example.com/a/b", VfsIssuer.normalize("https://EXAMPLE.com/a/b///"))
    }

    @Test
    fun rejectsUnsafeOrMalformedUrls() {
        assertNull(VfsIssuer.normalize(""))
        assertNull(VfsIssuer.normalize("ftp://example.com"))
        assertNull(VfsIssuer.normalize("https://user:pass@example.com"))
        assertNull(VfsIssuer.normalize("https://example.com/?token=1"))
        assertNull(VfsIssuer.normalize("https://example.com/#fragment"))
        assertNull(VfsIssuer.normalize("https:///missing-host"))
    }

    @Test
    fun derivesBaseByRemovingTheGrantPath() {
        assertEquals(
            "https://clawdy-mini.tailf665f5.ts.net",
            VfsIssuer.resolve(null, "https://clawdy-mini.tailf665f5.ts.net/knocks/mcp/hk_abc123"),
        )
        assertEquals(
            "https://example.com/base",
            VfsIssuer.resolve(null, "https://example.com/base/knocks/mcp/hk_abc/"),
        )
        assertEquals(
            "https://example.com/mcp",
            VfsIssuer.resolve(null, "https://example.com/mcp"),
        )
    }

    @Test
    fun explicitIssuerBaseWins() {
        assertEquals(
            "https://example.com/base",
            VfsIssuer.resolve(
                "https://EXAMPLE.com/base/",
                "https://example.com/knocks/mcp/hk_abc",
            ),
        )
    }

    @Test
    fun matchesOnlyCanonicalBaseUrls() {
        assertTrue(
            VfsIssuer.matches(
                "https://EXAMPLE.com/",
                null,
                "https://example.com/knocks/mcp/hk_abc",
            ),
        )
        assertFalse(
            VfsIssuer.matches(
                "https://other.example",
                null,
                "https://example.com/knocks/mcp/hk_abc",
            ),
        )
        assertFalse(VfsIssuer.matches("https://example.com", "https://example.com/base", "https://example.com"))
    }
}

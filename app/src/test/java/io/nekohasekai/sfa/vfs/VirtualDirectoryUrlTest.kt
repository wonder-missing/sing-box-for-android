package io.nekohasekai.sfa.vfs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VirtualDirectoryUrlTest {
    @Test
    fun aBareHostGainsTheProviderPath() {
        assertEquals(
            "ws://mini:18081/provider",
            normalizeVirtualDirectoryUrl("ws://mini:18081"),
        )
        assertEquals(
            "ws://mini:18081/provider",
            normalizeVirtualDirectoryUrl("ws://mini:18081/"),
        )
    }

    @Test
    fun anExplicitPathIsKept() {
        assertEquals(
            "wss://mini.example.com/custom/provider",
            normalizeVirtualDirectoryUrl("wss://mini.example.com/custom/provider"),
        )
    }

    @Test
    fun whitespaceIsTrimmedAndTheSchemeIsLowercased() {
        assertEquals(
            "wss://mini:443/provider",
            normalizeVirtualDirectoryUrl("  WSS://mini:443  "),
        )
    }

    @Test
    fun credentialsQueriesAndFragmentsAreRejected() {
        val rejected =
            listOf(
                "ws://user:secret@mini:18081/provider",
                "ws://mini:18081/provider?token=abc",
                "ws://mini:18081/provider#fragment",
            )

        rejected.forEach { raw ->
            assertNull(raw, normalizeVirtualDirectoryUrl(raw))
        }
    }

    @Test
    fun nonWebSocketUrlsAreRejected() {
        val rejected =
            listOf(
                "",
                "   ",
                "http://mini:18081/provider",
                "https://mini:18081/provider",
                "mini:18081",
                "ws://",
                "ws:///provider",
                "//mini:18081/provider",
            )

        rejected.forEach { raw ->
            assertNull(raw, normalizeVirtualDirectoryUrl(raw))
        }
    }

    @Test
    fun ipv6HostsKeepTheirBrackets() {
        assertEquals(
            "ws://[fd7a:115c:a1e0::1]:18081/provider",
            normalizeVirtualDirectoryUrl("ws://[fd7a:115c:a1e0::1]:18081"),
        )
    }
}

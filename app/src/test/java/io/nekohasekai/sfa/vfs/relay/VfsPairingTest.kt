package io.nekohasekai.sfa.vfs.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VfsPairingTest {
    private val linkId = "EBESExQVFhcYGRobHB0eHw"
    private val code = "ABCDEFGHJKMNPQRS".toCharArray()

    @Test
    fun pairIdMatchesCliVector() {
        // Node: hkdfSync('sha256', codeASCII, linkIdRaw, 'wsvfs/pair/id', 16)
        assertEquals("n2NxoA743x71oXkKa1BkWg", VfsPairing.pairId(linkId, code))
    }

    @Test
    fun pairIdIsStableAcrossAliases() {
        val alias = "abcdefghjkmnpqrs".toCharArray()
        try {
            assertEquals(VfsPairing.pairId(linkId, code), VfsPairing.pairId(linkId, alias))
        } finally {
            VfsPairing.zeroize(alias)
        }
    }

    @Test
    fun pairIdChangesWithCode() {
        val other = "ABCDEFGHJKMNPQRT".toCharArray()
        try {
            assertTrue(VfsPairing.pairId(linkId, code) != VfsPairing.pairId(linkId, other))
        } finally {
            VfsPairing.zeroize(other)
        }
    }
}

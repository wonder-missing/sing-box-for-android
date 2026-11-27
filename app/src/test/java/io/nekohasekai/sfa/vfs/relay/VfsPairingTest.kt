package io.nekohasekai.sfa.vfs.relay

import org.junit.Assert.assertTrue
import org.junit.Test

class VfsPairingTest {
    @Test
    fun pairingCommandUsesVfsc() {
        val code = "ABCDEFGHJKMNPQRS".toCharArray()
        val command = VfsPairing.pairingCommand("link", "pair", code)
        assertTrue(command.startsWith("\"\$HOME/bin/vfsc\" pair"))
        assertTrue(command.contains("--link link"))
        assertTrue(command.contains("--pair pair"))
        assertTrue(command.endsWith("--code ABCDEFGHJKMNPQRS"))
        VfsPairing.zeroize(code)
        assertTrue(code.all { it == '\u0000' })
    }
}

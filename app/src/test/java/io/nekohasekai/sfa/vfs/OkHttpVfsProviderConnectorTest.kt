package io.nekohasekai.sfa.vfs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OkHttpVfsProviderConnectorTest {
    @Test
    fun `provider websocket keeps a protocol ping for dead paths`() {
        // Application ping/pong is the Portal's liveness signal. This protocol
        // ping only exists so a black-holed route fails the socket locally.
        assertEquals(20_000, OkHttpVfsProviderConnector.defaultClient().pingIntervalMillis)
    }

    @Test
    fun `hello timeout is reported as a provider handshake timeout`() {
        // Not VFS_CONNECT_FAILED and not VFS_CONFIRMATION_TIMEOUT: the socket
        // opened, so the user's consent answer is not the missing piece.
        val error = handshakeTimeoutError()

        assertEquals("VFS_HANDSHAKE_TIMEOUT", error.code)
        assertTrue(error.message.orEmpty().contains("did not return its hello in time"))
        assertTrue(error.message.orEmpty().contains("Portal/vfsd"))
    }
}

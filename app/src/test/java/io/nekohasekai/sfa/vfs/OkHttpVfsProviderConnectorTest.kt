package io.nekohasekai.sfa.vfs

import org.junit.Assert.assertEquals
import org.junit.Test

class OkHttpVfsProviderConnectorTest {
    @Test
    fun `provider websocket keeps a protocol ping for dead paths`() {
        // Application ping/pong is the Portal's liveness signal. This protocol
        // ping only exists so a black-holed route fails the socket locally.
        assertEquals(20_000, OkHttpVfsProviderConnector.defaultClient().pingIntervalMillis)
    }
}

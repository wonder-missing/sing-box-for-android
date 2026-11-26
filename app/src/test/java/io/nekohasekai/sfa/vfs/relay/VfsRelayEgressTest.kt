package io.nekohasekai.sfa.vfs.relay

import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.Socket

class VfsRelayEgressTest {
    @Test
    fun protectedSocketFactoryRunsProtectAndBindBeforeConnect() {
        var protected = false
        var bound = false
        val socket = Socket()
        val factory =
            RelayProtectedSocketFactory(
                protect = { protected = true },
                bind = { bound = true },
                newSocket = { socket },
            )
        val created = factory.createSocket()
        assertSame(socket, created)
        assertTrue(protected)
        assertTrue(bound)
    }

    @Test
    fun dnsUsesUnderlyingResolverAndFallsBackToSystem() {
        val resolver =
            RelayNetworkResolver { host ->
                listOf(InetAddress.getByAddress(host, byteArrayOf(127, 0, 0, 1)))
            }
        val egress = VfsRelayEgress(resolver = { resolver })
        val addresses = egress.dns.lookup("underlay.test")
        assertEquals(1, addresses.size)
        assertEquals("underlay.test", addresses.first().hostName)

        val fallback = VfsRelayEgress(resolver = { null })
        val local = fallback.dns.lookup("localhost")
        assertTrue(local.isNotEmpty())
        assertEquals(Dns.SYSTEM.lookup("localhost"), local)
    }

    @Test
    fun httpAndRelayClientsUseTheEgressDnsAndSocketFactory() {
        val egress = VfsRelayEgress(resolver = { null })
        val http = egress.httpClient()
        assertSame(egress.dns, http.dns)
        assertSame(egress.socketFactory, http.socketFactory)
        val relay = egress.relayClient()
        assertSame(egress.dns, relay.dns)
        assertSame(egress.socketFactory, relay.socketFactory)
    }
}

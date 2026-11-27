package io.nekohasekai.sfa.vfs.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VfsProviderKeyProvisionerTest {
    private val server =
        VfsRelayServer(
            id = "server-1",
            name = "Mini",
            baseUrl = "https://relay.example/relay/v1",
            tokenCiphertext = "unused",
            createdAt = 1,
        )

    @Test
    fun fullBaseUrlKeepsPathAndRejectsOriginOnly() {
        assertEquals(
            "https://relay.example/relay/v1",
            VfsProviderKeyProvisioner.canonicalBaseUrl("https://relay.example/relay/v1"),
        )
        assertEquals(
            "https://relay.example/relay/v1",
            VfsProviderKeyProvisioner.canonicalBaseUrl("https://RELAY.example:443/relay/v1/"),
        )
        assertEquals(
            "https://relay.example:8443/relay/v1",
            VfsProviderKeyProvisioner.canonicalBaseUrl("https://relay.example:8443/relay/v1"),
        )
        assertNull(VfsProviderKeyProvisioner.canonicalBaseUrl("https://relay.example"))
        assertNull(VfsProviderKeyProvisioner.canonicalBaseUrl("https://relay.example/"))
        assertNull(VfsProviderKeyProvisioner.canonicalBaseUrl("http://relay.example/relay/v1"))
        assertNull(VfsProviderKeyProvisioner.canonicalBaseUrl("https://user:pass@relay.example/relay/v1"))
        assertNull(VfsProviderKeyProvisioner.canonicalBaseUrl("https://relay.example/relay/v1?token=x"))
        assertNull(VfsProviderKeyProvisioner.canonicalBaseUrl("not a url"))
    }

    @Test
    fun requestUrlsKeepTheRelayBasePath() {
        val base = "https://relay.example/relay/v1"
        assertEquals("$base/provider-keys", VfsProviderKeyProvisioner.providerKeysUrl(base))
        assertEquals("$base/links", VfsProviderKeyProvisioner.linksUrl(base))
        assertEquals("wss://relay.example/relay/v1/provider", relayProviderUrl(base))
        assertNull(relayProviderUrl("https://relay.example"))
        assertNull(relayProviderUrl("http://relay.example/relay/v1"))
    }

    @Test
    fun targetUsesRelayServerAndRelayToken() {
        val provisioner = VfsProviderKeyProvisioner()
        val target = provisioner.targetOf(server, "relay_token_one")
        assertEquals("https://relay.example/relay/v1", target.canonicalBase)
        assertEquals("server-1", target.serverId)
        assertEquals("relay_token_one", target.relayToken)

        try {
            provisioner.targetOf(server, "whsec_abc")
            throw AssertionError("expected INVALID_TOKEN")
        } catch (error: RelayProvisionException) {
            assertEquals("INVALID_TOKEN", error.code)
        }
        try {
            provisioner.targetOf(server.copy(baseUrl = "http://relay.example/relay/v1"), "relay_ok")
            throw AssertionError("expected INVALID_RELAY_URL")
        } catch (error: RelayProvisionException) {
            assertEquals("INVALID_RELAY_URL", error.code)
        }
    }

    @Test
    fun issueResponseUsesRelayIdAndRejectsHookIds() {
        val provisioner = VfsProviderKeyProvisioner()
        val key = RelayBase64.encodeUrl(ByteArray(32) { 1 })
        val keyId = RelayBase64.encodeUrl(ByteArray(16) { 2 })
        val issue =
            provisioner.parseIssueResponse(
                """{"providerKey":"wsk_$key","providerKeyId":"$keyId","relayId":"relay-1","deviceId":"device-1","createdAt":7,"expiresAt":null}""",
                "device-1",
            )
        assertEquals("relay-1", issue.relayId)
        assertEquals("device-1", issue.deviceId)
        assertEquals(7L, issue.createdAt)
        assertFalse(issue.providerKey.contains("hook"))

        try {
            provisioner.parseIssueResponse(
                """{"providerKey":"wsk_$key","providerKeyId":"$keyId","hookId":"hk_1","deviceId":"device-1","createdAt":7}""",
                "device-1",
            )
            throw AssertionError("expected PROTOCOL_ERROR")
        } catch (error: RelayProvisionException) {
            assertTrue(error.code == "PROTOCOL_ERROR")
        }
    }
}

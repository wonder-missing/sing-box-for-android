package io.nekohasekai.sfa.vfs.relay

import io.nekohasekai.sfa.agent.mcp.McpServerConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VfsProviderKeyProvisionerTest {
    @Test
    fun canonicalOriginKeepsSchemeHostAndNonDefaultPortOnly() {
        assertEquals(
            "https://portal.example",
            VfsProviderKeyProvisioner.canonicalOrigin("https://portal.example/some/path"),
        )
        assertEquals(
            "https://portal.example",
            VfsProviderKeyProvisioner.canonicalOrigin("https://PORTAL.example:443/x"),
        )
        assertEquals(
            "https://portal.example:8443",
            VfsProviderKeyProvisioner.canonicalOrigin("https://portal.example:8443/"),
        )
        assertNull(VfsProviderKeyProvisioner.canonicalOrigin("http://portal.example"))
        assertNull(VfsProviderKeyProvisioner.canonicalOrigin("https://user:pass@portal.example"))
        assertNull(VfsProviderKeyProvisioner.canonicalOrigin("https://portal.example?token=x"))
        assertNull(VfsProviderKeyProvisioner.canonicalOrigin("not a url"))
    }

    @Test
    fun targetUsesServerBaseThenUrlAndRequiresHookSecret() {
        val provisioner = VfsProviderKeyProvisioner()
        val config =
            McpServerConfig(
                id = "portal",
                name = "Mini",
                url = "https://portal.example/mcp",
                token = "whsec_0123456789abcdef0123456789abcdef",
                enabled = true,
                serverBase = "https://portal.example",
            )
        val target = provisioner.targetOf(config)
        assertEquals("https://portal.example", target.canonicalBase)
        assertEquals("portal", target.portalServerId)

        val derived =
            provisioner.targetOf(
                config.copy(serverBase = "", url = "https://other.example/mcp/grant"),
            )
        assertEquals("https://other.example", derived.canonicalBase)

        try {
            provisioner.targetOf(config.copy(token = "oauth-access-token"))
            throw AssertionError("expected TOKEN_UNSUPPORTED")
        } catch (error: RelayProvisionException) {
            assertEquals("TOKEN_UNSUPPORTED", error.code)
        }
        try {
            provisioner.targetOf(config.copy(serverBase = "http://portal.example"))
            throw AssertionError("expected INVALID_PORTAL")
        } catch (error: RelayProvisionException) {
            assertEquals("INVALID_PORTAL", error.code)
        }
    }
}

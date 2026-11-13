package io.nekohasekai.sfa.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPromptStabilityTest {
    private val catalog =
        AgentToolCatalog(
            listOf(
                AgentToolDefinition(
                    name = "network_snapshot",
                    description = "Show the current network environment.",
                    permission = AgentPermission.READ_NETWORK_STATE,
                    parameters = emptyList(),
                    confirmationPolicy = ConfirmationPolicy.NEVER,
                    category = "network",
                    aliases = listOf("network", "network status"),
                    core = true,
                ),
            ),
        )

    @Test
    fun routingPromptIsByteStable() {
        val first = AgentContext.systemPrompt("locale=zh; tz=Asia/Shanghai", catalog.promptSection()).content
        val second = AgentContext.systemPrompt("locale=zh; tz=Asia/Shanghai", catalog.promptSection()).content

        assertEquals(first, second)
        assertTrue(first.contains("route_call"))
        assertTrue(first.contains("Registered capabilities"))
        assertTrue(first.contains("network_snapshot"))
        assertTrue(first.contains("locale=zh"))
        assertTrue(first.contains("If you know a capability's exact name and arguments"))
        assertTrue(first.contains("Translate the user's intent into English keywords"))
        assertTrue(first.contains("[conductor:event"))
    }

    /**
     * Byte lock for the routing prompt. A deliberate prompt change must update
     * this hash in the same commit; an accidental change would silently move
     * the cache prefix for every user.
     */
    @Test
    fun routingPromptMatchesGoldenBytes() {
        val prompt = AgentContext.systemPrompt("locale=zh; tz=Asia/Shanghai", catalog.promptSection()).content
        val digest =
            java.security.MessageDigest
                .getInstance("SHA-256")
                .digest(prompt.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }

        assertEquals("59b4154ffd779e90f3881b92ed963a932e52bc004f0d72b99004aa06c9e2a325", digest)
    }

    @Test
    fun nativePromptKeepsProvidedToolsWording() {
        val prompt = AgentContext.systemPrompt("locale=zh").content

        assertTrue(prompt.contains("tools provided in this request"))
        assertTrue(!prompt.contains("routing primitives"))
    }
}

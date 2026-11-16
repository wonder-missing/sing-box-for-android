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
        assertTrue(first.contains("Registered capability snapshot"))
        assertTrue(first.contains("not exhaustive"))
        assertTrue(first.contains("network_snapshot"))
        assertTrue(first.contains("locale=zh"))
        assertTrue(first.contains("If you know a capability's exact name and arguments"))
        assertTrue(first.contains("Translate the user's intent into English keywords"))
        assertTrue(first.contains("test the closest indirect tool once"))
        assertTrue(first.contains("snapshot, not the full set"))
        assertTrue(first.contains("Do not answer a capability question from the snapshot alone"))
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

        assertEquals("725912de6563069f446dd9c883e1211a7ea23f96c02e8c7088756c99f98f1598", digest)
    }

    @Test
    fun briefModeAddsOnlyTheStyleLine() {
        val normal = AgentContext.systemPrompt("locale=zh; tz=Asia/Shanghai", catalog.promptSection()).content
        val brief = AgentContext.systemPrompt("locale=zh; tz=Asia/Shanghai", catalog.promptSection(), brief = true).content

        val briefLine =
            "Brief mode is on. Answer in at most three short paragraphs or one short list. " +
                "Do not restate the question, do not open with a preamble, and omit optional background. " +
                "Keep exact values, required warnings and the next step."
        val styleLine =
            "Use short paragraphs or one short list. Always wrap file paths, commands, flags, " +
                "tool names, config keys and other literal identifiers in `inline code`. " +
                "Use **bold** sparingly for a warning or the key conclusion. " +
                "Do not use Markdown tables, headings or code fences."

        assertTrue(!normal.contains(briefLine))
        assertTrue(brief.contains(briefLine))
        assertEquals(brief, normal.replace(styleLine, "$styleLine\n$briefLine"))
    }

    @Test
    fun nativePromptKeepsProvidedToolsWording() {
        val prompt = AgentContext.systemPrompt("locale=zh").content

        assertTrue(prompt.contains("tools provided in this request"))
        assertTrue(!prompt.contains("routing primitives"))
    }
}

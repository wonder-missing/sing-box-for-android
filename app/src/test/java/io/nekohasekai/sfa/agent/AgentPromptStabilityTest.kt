package io.nekohasekai.sfa.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPromptStabilityTest {
    private val catalog =
        AgentToolCatalog(
            listOf(
                AgentToolDefinition(
                    name = "system_snapshot",
                    description = "Show the current system state.",
                    permission = AgentPermission.READ_NETWORK_STATE,
                    parameters = emptyList(),
                    confirmationPolicy = ConfirmationPolicy.NEVER,
                    category = "system",
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
        assertTrue(first.contains("system_snapshot"))
        assertTrue(first.contains("locale=zh"))
        assertTrue(first.contains("If you know a capability's exact name and arguments"))
        assertTrue(first.contains("Translate the user's intent into English keywords"))
        assertTrue(first.contains("test the closest indirect tool once"))
        assertTrue(first.contains("snapshot, not the full set"))
        assertTrue(first.contains("Do not answer a capability question from the snapshot alone"))
        assertTrue(first.contains("[runtime:...]"))
        assertTrue(first.contains("A [runtime:...] user message is state"))
        assertTrue(first.contains("plan_update"))
        assertTrue(first.contains("job_run(vfs=true)"))
        assertFalse(first.contains("docker_run(vfs=true)"))
        assertTrue(first.contains("headings sparingly"))
        assertTrue(first.contains("tables render with horizontal scroll"))
        assertTrue(first.contains("fenced code blocks"))
        assertFalse(first.contains("Do not use Markdown tables"))
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

        assertEquals("6671148f00e90a4b6bfc3e8a1d2a58a7a09bb9ad89a107146ab7cd5052f66ff5", digest)
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
            "Use short paragraphs. Always wrap file paths, commands, flags, " +
                "tool names, config keys and other literal identifiers in `inline code`. " +
                "Use **bold** sparingly for a warning or the key conclusion. " +
                "Use Markdown headings sparingly (H1–H3), lists, blockquotes, links, tables and " +
                "fenced code blocks when they help; tables render with horizontal scroll. Use one " +
                "```svg block containing only an `<svg>` element only for a real diagram, never for tabular data."

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

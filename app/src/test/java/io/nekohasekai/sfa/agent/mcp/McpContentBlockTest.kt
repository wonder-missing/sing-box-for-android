package io.nekohasekai.sfa.agent.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

class McpContentBlockTest {
    private fun result(body: String): JsonObject =
        Json.parseToJsonElement(body) as JsonObject

    @Test
    fun textAndStructuredContentAreParsed() {
        val parsed =
            McpMessages.callResult(
                result(
                    """{"content":[{"type":"text","text":"hello"},{"type":"text","text":"world"}],"structuredContent":{"a":1}}""",
                ),
            )

        assertEquals("hello\nworld", parsed.text)
        assertTrue(parsed.blocks.isEmpty())
    }

    @Test
    fun structuredContentIsUsedWhenNoTextBlocksExist() {
        val parsed =
            McpMessages.callResult(
                result("""{"content":[],"structuredContent":{"a":1}}"""),
            )

        assertEquals("""{"a":1}""", parsed.text)
    }

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun imageContentBecomesABinaryBlock() {
        val encoded = Base64.Default.encode("jpeg-bytes".toByteArray())
        val parsed =
            McpMessages.callResult(
                result(
                    """{"content":[{"type":"image","data":"$encoded","mimeType":"image/jpeg"}]}""",
                ),
            )

        assertEquals("", parsed.text)
        val block = parsed.blocks.single() as McpContentBlock.Binary
        assertArrayEquals("jpeg-bytes".toByteArray(), block.bytes)
        assertEquals("image/jpeg", block.mimeType)
        assertEquals("image.jpg", block.name)
    }

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun embeddedResourceBecomesABinaryBlock() {
        val encoded = Base64.Default.encode("pdf-bytes".toByteArray())
        val parsed =
            McpMessages.callResult(
                result(
                    """{"content":[{"type":"resource","resource":{"uri":"job://x/report.pdf","mimeType":"application/pdf","blob":"$encoded"}}]}""",
                ),
            )

        val block = parsed.blocks.single() as McpContentBlock.Binary
        assertArrayEquals("pdf-bytes".toByteArray(), block.bytes)
        assertEquals("application/pdf", block.mimeType)
        assertEquals("report.pdf", block.name)
    }

    @Test
    fun resourceLinkKeepsItsUriAndMetadata() {
        val parsed =
            McpMessages.callResult(
                result(
                    """{"content":[{"type":"resource_link","uri":"https://example.com/a.jpg","name":"a.jpg","mimeType":"image/jpeg","size":42}]}""",
                ),
            )

        val block = parsed.blocks.single() as McpContentBlock.ResourceLink
        assertEquals("https://example.com/a.jpg", block.uri)
        assertEquals("a.jpg", block.name)
        assertEquals("image/jpeg", block.mimeType)
        assertEquals(42L, block.size)
    }

    @Test
    fun invalidBase64FailsTheCall() {
        val failure =
            runCatching {
                McpMessages.callResult(
                    result("""{"content":[{"type":"image","data":"not base64!","mimeType":"image/jpeg"}]}"""),
                )
            }
        assertTrue(failure.exceptionOrNull() is McpException)
    }

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun embeddedContentOverTheLimitFailsInsteadOfTruncating() {
        val encoded = Base64.Default.encode(ByteArray(MAX_EMBEDDED_BYTES + 1))
        val failure =
            runCatching {
                McpMessages.callResult(
                    result(
                        """{"content":[{"type":"image","data":"$encoded","mimeType":"image/jpeg"}]}""",
                    ),
                )
            }
        assertTrue(failure.exceptionOrNull() is McpException)
    }

    @Test
    fun textOnlyCompatibilityHelperDropsBlocks() {
        val parsed =
            McpMessages.callText(
                result("""{"content":[{"type":"text","text":"ok"}]}"""),
            )

        assertEquals("ok", parsed.text)
        assertTrue(parsed.blocks.isEmpty())
    }

    @Test
    fun errorFlagIsPreserved() {
        val parsed =
            McpMessages.callResult(
                buildJsonObject {
                    put("isError", true)
                    put("content", Json.parseToJsonElement("""[{"type":"text","text":"bad"}]"""))
                },
            )
        assertTrue(parsed.isError)
        assertEquals("bad", parsed.text)
    }
}

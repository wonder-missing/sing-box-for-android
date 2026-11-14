package io.nekohasekai.sfa.vfs

import io.nekohasekai.sfa.agent.ToolCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VfsConnectToolTest {
    private val noCancel =
        object : ToolCancellation {
            override fun isCancelled(): Boolean = false

            override fun onCancel(action: () -> Unit) = Unit
        }

    @Test
    fun successReturnsTheConnectedScopeAndExpiry() =
        runBlocking {
            val tool =
                VfsConnectTool(
                    object : VfsConnectPort {
                        override suspend fun connect(link: VfsLink) =
                            VfsConnectResult(
                                issuer = link.issuer,
                                scope = link.scope,
                                expiresAt = 5_000L,
                            )
                    },
                )

            val execution = tool.execute(args(validLink()), noCancel)

            assertTrue(execution.ok)
            assertEquals(true, execution.output?.get("connected")?.jsonPrimitive?.booleanOrNull)
            assertEquals("https://example.com", execution.output?.get("issuer")?.jsonPrimitive?.content)
            assertEquals(5_000L, execution.output?.get("expiresAt")?.jsonPrimitive?.longOrNull)
        }

    @Test
    fun cooldownKeepsTheRetryFieldsInTheResult() =
        runBlocking {
            val tool =
                VfsConnectTool(
                    object : VfsConnectPort {
                        override suspend fun connect(link: VfsLink): VfsConnectResult =
                            throw VfsConnectException(
                                "VFS_CONFIRMATION_COOLDOWN",
                                "Wait.",
                                retryAfterMs = 30_000L,
                                cooldownUntil = 40_000L,
                            )
                    },
                )

            val execution = tool.execute(args(validLink()), noCancel)

            assertFalse(execution.ok)
            assertEquals("VFS_CONFIRMATION_COOLDOWN", execution.errorCode)
            assertEquals(30_000L, execution.output?.get("retryAfterMs")?.jsonPrimitive?.longOrNull)
            assertEquals(40_000L, execution.output?.get("cooldownUntil")?.jsonPrimitive?.longOrNull)
        }

    @Test
    fun invalidLinkIsRejectedBeforeCallingTheManager() =
        runBlocking {
            var called = false
            val tool =
                VfsConnectTool(
                    object : VfsConnectPort {
                        override suspend fun connect(link: VfsLink): VfsConnectResult {
                            called = true
                            return VfsConnectResult(link.issuer, link.scope, null)
                        }
                    },
                )

            val execution = tool.execute(args(buildJsonObject { put("type", "vfs-link") }), noCancel)

            assertFalse(execution.ok)
            assertEquals("VFS_LINK_INVALID", execution.errorCode)
            assertFalse(called)
        }

    private fun validLink() =
        buildJsonObject {
            put("type", "vfs-link")
            put("version", 1)
            put("issuer", "https://example.com")
            put("linkId", "link_1")
            put(
                "scope",
                buildJsonArray {
                    add("conductor")
                    add("trace")
                },
            )
        }

    private fun args(link: kotlinx.serialization.json.JsonObject) =
        buildJsonObject {
            put("link", link)
        }
}

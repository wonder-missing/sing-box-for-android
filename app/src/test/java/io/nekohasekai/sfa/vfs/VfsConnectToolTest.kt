package io.nekohasekai.sfa.vfs

import io.nekohasekai.sfa.agent.ToolCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
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
                                server = link.server,
                                scope = link.scope,
                                expiresAt = 5_000L,
                            )
                    },
                )

            val execution = tool.execute(args(validLink()), noCancel)

            assertTrue(execution.ok)
            assertEquals(true, execution.output?.get("connected")?.jsonPrimitive?.booleanOrNull)
            assertEquals("https://example.com", execution.output?.get("server")?.jsonPrimitive?.content)
            assertEquals(5_000L, execution.output?.get("expiresAt")?.jsonPrimitive?.longOrNull)
            assertEquals(0L, execution.output?.get("expiresInSeconds")?.jsonPrimitive?.longOrNull)
            assertEquals(
                listOf(".runtime/trace.md", "conductor/**"),
                execution.output?.get("visibleRoots")?.jsonArray?.map { it.jsonPrimitive.content },
            )
            assertEquals(
                listOf("conversation", "system_prompt"),
                execution.output?.get("missingScopes")?.jsonArray?.map { it.jsonPrimitive.content },
            )
            assertEquals(
                listOf("Full conversation", "System prompt"),
                execution.output?.get("missingScopeLabels")?.jsonArray?.map { it.jsonPrimitive.content },
            )
            assertTrue(
                execution.output?.get("hint")?.jsonPrimitive?.content.orEmpty()
                    .contains("Never ask the user to name scopes"),
            )
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
                            return VfsConnectResult(link.server, link.scope, null)
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
            put("version", 2)
            put("server", "https://example.com")
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

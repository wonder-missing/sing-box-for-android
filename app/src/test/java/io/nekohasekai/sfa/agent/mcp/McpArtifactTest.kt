package io.nekohasekai.sfa.agent.mcp

import io.nekohasekai.sfa.agent.ToolCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpArtifactTest {
    private class FakeSink : McpArtifactSink {
        val binaryNames = mutableListOf<String>()
        val linkOrigins = mutableListOf<Set<String>>()

        override suspend fun saveBinary(
            bytes: ByteArray,
            name: String,
            mimeType: String,
            runId: String,
        ): McpArtifact {
            binaryNames += name
            return McpArtifact("conductor/resources/artifacts/$runId/$name", bytes.size.toLong(), mimeType, "sha")
        }

        override suspend fun saveResourceLink(
            uri: String,
            name: String,
            mimeType: String?,
            runId: String,
            allowedOrigins: Set<String>,
            cancellation: ToolCancellation?,
        ): McpArtifact {
            linkOrigins += allowedOrigins
            return McpArtifact("conductor/resources/artifacts/$runId/$name", 1L, mimeType ?: "application/octet-stream", "sha")
        }
    }

    @Test
    fun binaryBlocksAreMaterializedAndBase64IsNotInTheObservation() =
        runBlocking {
            val sink = FakeSink()
            val result =
                McpCallResult(
                    text = "job done",
                    isError = false,
                    blocks =
                        listOf(
                            McpContentBlock.Binary("one".toByteArray(), "image.jpg", "image/jpeg"),
                        ),
                )

            val execution =
                mcpToolExecution(result, sink, "job_file", setOf("https://portal:443"), null)

            assertTrue(execution.ok)
            val observation = execution.output.toString()
            assertFalse(observation.contains("b25l")) // base64 for "one"
            assertTrue(observation.contains("conductor/resources/artifacts/"))
            assertTrue(observation.contains("image.jpg"))
        }

    @Test
    fun duplicateNamesGetUniqueSuffixes() =
        runBlocking {
            val sink = FakeSink()
            val result =
                McpCallResult(
                    text = "",
                    isError = false,
                    blocks =
                        listOf(
                            McpContentBlock.Binary("a".toByteArray(), "image.jpg", "image/jpeg"),
                            McpContentBlock.Binary("b".toByteArray(), "image.jpg", "image/jpeg"),
                        ),
                )

            mcpToolExecution(result, sink, "job_file", setOf("https://portal:443"), null)

            assertEquals(listOf("image.jpg", "image-2.jpg"), sink.binaryNames)
        }

    @Test
    fun resourceLinkOriginsAreForwardedToTheSink() =
        runBlocking {
            val sink = FakeSink()
            val result =
                McpCallResult(
                    text = "",
                    isError = false,
                    blocks =
                        listOf(
                            McpContentBlock.ResourceLink(
                                uri = "https://portal:443/file/a.jpg",
                                name = "a.jpg",
                                mimeType = "image/jpeg",
                                size = 10,
                            ),
                        ),
                )

            mcpToolExecution(result, sink, "job_file", setOf("https://portal:443"), null)

            assertEquals(listOf(setOf("https://portal:443")), sink.linkOrigins)
        }

    @Test
    fun missingSinkFailsBinaryResults() =
        runBlocking {
            val result =
                McpCallResult(
                    text = "",
                    isError = false,
                    blocks = listOf(McpContentBlock.Binary("a".toByteArray(), "a.jpg", "image/jpeg")),
                )

            val execution = mcpToolExecution(result, null, "job_file", emptySet(), null)

            assertFalse(execution.ok)
            assertEquals("mcp_artifact_failed", execution.errorCode)
        }
}

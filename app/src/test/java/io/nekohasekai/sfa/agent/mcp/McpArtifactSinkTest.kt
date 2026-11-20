package io.nekohasekai.sfa.agent.mcp

import io.nekohasekai.sfa.agent.ConductorFileStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class McpArtifactSinkTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun sink(): FileMcpArtifactSink =
        FileMcpArtifactSink(ConductorFileStore(temp.root))

    @Test
    fun binaryArtifactsUseTheRunIdAsADirectoryWithoutAddingAnExtension() =
        runBlocking {
            val artifact =
                sink().saveBinary(
                    bytes = byteArrayOf(1, 2, 3),
                    name = "head.jpg",
                    mimeType = "image/jpeg",
                    runId = "job_file-1795193031527",
                )

            assertEquals(
                "conductor/resources/artifacts/job_file-1795193031527/head.jpg",
                artifact.path,
            )
        }

    @Test
    fun rejectsPlainHttpResourceLinks() =
        runBlocking {
            val failure =
                runCatching {
                    sink().saveResourceLink(
                        uri = "http://portal.example/a.jpg",
                        name = "a.jpg",
                        mimeType = "image/jpeg",
                        runId = "run",
                        allowedOrigins = setOf("http://portal.example:80"),
                        cancellation = null,
                    )
                }
            assertTrue(failure.isFailure)
        }

    @Test
    fun rejectsAnOriginDifferentFromTheMcpServer() =
        runBlocking {
            val failure =
                runCatching {
                    sink().saveResourceLink(
                        uri = "https://other.example/a.jpg",
                        name = "a.jpg",
                        mimeType = "image/jpeg",
                        runId = "run",
                        allowedOrigins = setOf("https://portal.example:443"),
                        cancellation = null,
                    )
                }
            assertTrue(failure.isFailure)
        }
}

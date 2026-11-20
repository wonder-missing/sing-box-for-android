package io.nekohasekai.sfa.agent.mcp

import io.nekohasekai.sfa.agent.ConductorFileStore
import io.nekohasekai.sfa.agent.InboxPaths
import io.nekohasekai.sfa.agent.ToolCancellation
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class McpArtifact(
    val path: String,
    val size: Long,
    val mimeType: String,
    val sha256: String,
)

/** Materializes binary MCP results into the local Conductor workspace. */
interface McpArtifactSink {
    suspend fun saveBinary(
        bytes: ByteArray,
        name: String,
        mimeType: String,
        runId: String,
    ): McpArtifact

    suspend fun saveResourceLink(
        uri: String,
        name: String,
        mimeType: String?,
        runId: String,
        allowedOrigins: Set<String>,
        cancellation: ToolCancellation?,
    ): McpArtifact
}

class FileMcpArtifactSink(
    private val fileStore: ConductorFileStore,
    private val client: OkHttpClient = defaultClient(),
) : McpArtifactSink {
    override suspend fun saveBinary(
        bytes: ByteArray,
        name: String,
        mimeType: String,
        runId: String,
    ): McpArtifact {
        val path = artifactPath(runId, name, mimeType)
        val stored = fileStore.writeResourceBytes(path, bytes, overwrite = true)
        return McpArtifact(
            path = stored.path,
            size = stored.size,
            mimeType = mimeType,
            sha256 = fileStore.sha256(fileStore.resolveResource(stored.path)),
        )
    }

    override suspend fun saveResourceLink(
        uri: String,
        name: String,
        mimeType: String?,
        runId: String,
        allowedOrigins: Set<String>,
        cancellation: ToolCancellation?,
    ): McpArtifact {
        val parsed =
            runCatching { uri.toHttpUrlOrNull() }.getOrNull()
                ?: throw IllegalArgumentException("Invalid resource link.")
        require(parsed.scheme == "https") { "Only HTTPS resource links are supported." }
        val origin = "${parsed.scheme}://${parsed.host}:${parsed.port}"
        require(origin in allowedOrigins) {
            "Resource link origin is not allowed."
        }
        if (cancellation?.isCancelled() == true) throw IllegalStateException("cancelled")
        val request = Request.Builder().url(uri).get().build()
        val call = client.newCall(request)
        cancellation?.onCancel { call.cancel() }
        call.execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Resource download failed (${response.code}).")
            }
            val body = response.body ?: throw IllegalStateException("Resource download is empty.")
            val resolvedMime = mimeType?.takeIf { it.isNotBlank() }
                ?: body.contentType()?.toString()
                ?: "application/octet-stream"
            val path = artifactPath(runId, name, resolvedMime)
            val stored =
                fileStore.writeResourceStream(path, MAX_DOWNLOAD_BYTES, overwrite = true) { output ->
                    body.byteStream().use { input -> input.copyTo(output) }
                }
            return McpArtifact(
                path = stored.path,
                size = stored.size,
                mimeType = resolvedMime,
                sha256 = fileStore.sha256(fileStore.resolveResource(stored.path)),
            )
        }
    }

    private fun artifactPath(
        runId: String,
        name: String,
        mimeType: String,
    ): String {
        val safeRun =
            runId
                .map { ch -> if (ch.isLetterOrDigit() || ch == '.' || ch == '_' || ch == '-') ch else '_' }
                .joinToString("")
                .trim('.', '_', '-')
                .take(80)
                .ifBlank { "run" }
        val safeName = InboxPaths.fileName(name, mimeType)
        return "conductor/resources/artifacts/$safeRun/$safeName"
    }

    companion object {
        private const val MAX_DOWNLOAD_BYTES = 64L * 1024 * 1024

        fun defaultClient(): OkHttpClient =
            OkHttpClient
                .Builder()
                .followRedirects(false)
                .followSslRedirects(false)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .callTimeout(300, TimeUnit.SECONDS)
                .build()
    }
}

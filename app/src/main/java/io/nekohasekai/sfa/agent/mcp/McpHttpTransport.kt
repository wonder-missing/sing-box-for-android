package io.nekohasekai.sfa.agent.mcp

import io.nekohasekai.sfa.agent.ToolCancellation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Streamable HTTP. HttpURLConnection keeps this off the libbox client, which is
 * GET-only and loads native code. Redirects stay off so a bearer token is not
 * forwarded to another host.
 */
class McpHttpTransport(
    private val url: String,
    private val token: String = "",
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
    private val maxBytes: Int = 512 * 1024,
) : McpTransport {
    override suspend fun exchange(
        body: String,
        sessionId: String?,
        cancellation: ToolCancellation?,
    ): McpExchange =
        withContext(Dispatchers.IO) {
            if (cancellation?.isCancelled() == true) throw CancellationException("cancelled")
            val connection = URL(url).openConnection() as HttpURLConnection
            cancellation?.onCancel { connection.disconnect() }
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = connectTimeoutMs
                connection.readTimeout = readTimeoutMs
                connection.instanceFollowRedirects = false
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("Accept", "application/json, text/event-stream")
                connection.setRequestProperty("MCP-Protocol-Version", MCP_PROTOCOL)
                if (!sessionId.isNullOrBlank()) {
                    connection.setRequestProperty("Mcp-Session-Id", sessionId)
                }
                if (token.isNotBlank()) {
                    connection.setRequestProperty("Authorization", "Bearer $token")
                }
                val bytes = body.toByteArray(Charsets.UTF_8)
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
                val status = connection.responseCode
                val responseBody =
                    try {
                        if (status == 204) {
                            ""
                        } else {
                            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                            stream?.use { readCapped(it) }.orEmpty()
                        }
                    } catch (e: java.io.FileNotFoundException) {
                        if (status in 200..299) "" else throw e
                    }
                McpExchange(
                    status = status,
                    contentType = connection.contentType.orEmpty(),
                    body = responseBody,
                    sessionId = connection.getHeaderField("Mcp-Session-Id")?.takeIf { it.isNotBlank() },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: McpException) {
                throw e
            } catch (e: Exception) {
                if (cancellation?.isCancelled() == true) throw CancellationException("cancelled")
                throw McpException(McpMessages.redact(e.message ?: "Could not reach the MCP server.", token))
            } finally {
                connection.disconnect()
            }
        }

    private fun readCapped(input: java.io.InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) throw McpException("MCP response is too large.")
            output.write(buffer, 0, read)
        }
        return output.toString(Charsets.UTF_8.name())
    }
}

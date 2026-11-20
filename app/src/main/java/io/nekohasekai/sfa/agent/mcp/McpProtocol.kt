package io.nekohasekai.sfa.agent.mcp

import io.nekohasekai.sfa.agent.InboxPaths
import io.nekohasekai.sfa.agent.ToolCancellation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.security.MessageDigest
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

internal const val MCP_PROTOCOL = "2025-06-18"
internal const val MCP_MAX_TOOLS = 64
internal const val VFS_LINK_REQUEST_TOOL = "vfs_link_request"
internal const val VFS_LINK_REVOKE_TOOL = "vfs_link_revoke"
internal const val VFS_DEVICE_HEADER = "X-WonderSing-Device"
internal const val DOCKER_RUN_TOOL = "docker_run"
internal const val JOB_RUN_TOOL = "job_run"
internal const val MCP_DOCKER_RUN_READ_TIMEOUT_MS: Int = 3_700_000
internal const val MAX_EMBEDDED_BYTES: Int = 1024 * 1024
private const val MCP_MAX_PAGES = 8

class McpException(
    message: String,
    val status: Int = 0,
    val rpcCode: Int? = null,
) : Exception(message)

data class McpExchange(
    val status: Int,
    val contentType: String,
    val body: String,
    val sessionId: String?,
)

interface McpTransport {
    suspend fun exchange(
        body: String,
        sessionId: String?,
        cancellation: ToolCancellation?,
        extraHeaders: Map<String, String> = emptyMap(),
        readTimeoutMs: Int? = null,
    ): McpExchange
}

data class McpRemoteTool(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    val readOnly: Boolean,
    /** SHA-256 of name, description, schema and readOnlyHint. Used to detect rug pulls. */
    val fingerprint: String = "",
)

sealed interface McpContentBlock {
    data class Binary(
        val bytes: ByteArray,
        val name: String,
        val mimeType: String,
    ) : McpContentBlock

    data class ResourceLink(
        val uri: String,
        val name: String,
        val mimeType: String?,
        val size: Long?,
    ) : McpContentBlock
}

data class McpCallResult(
    val text: String,
    val isError: Boolean,
    val blocks: List<McpContentBlock> = emptyList(),
    val structured: JsonElement? = null,
)

internal object McpMessages {
    private val json = Json { ignoreUnknownKeys = true }

    fun rpcResult(
        exchange: McpExchange,
        id: Int?,
        token: String,
    ): JsonElement? {
        if (exchange.status in 300..399) {
            throw McpException("MCP server returned a redirect. Use the final URL.", exchange.status)
        }
        if (exchange.status == 404) {
            throw McpException("MCP session expired.", exchange.status)
        }
        if (exchange.status !in 200..299) {
            throw McpException(httpFailure(exchange, token), exchange.status)
        }
        if (id == null) {
            if (exchange.body.isBlank()) return null
            val payload = parsePayload(exchange.body, id = null)
            if (payload is RpcError) {
                throw McpException(redact(payload.message, token), exchange.status, payload.code)
            }
            return null
        }
        return when (val payload = parsePayload(exchange.body, id)) {
            is RpcResult -> payload.result
            is RpcError -> throw McpException(redact(payload.message, token), exchange.status, payload.code)
            null -> throw McpException("MCP response did not include a result.", exchange.status)
        }
    }

    fun callResult(result: JsonObject): McpCallResult {
        val isError = (result["isError"] as? JsonPrimitive)?.booleanOrNull == true
        val content = result["content"] as? JsonArray
        val texts = mutableListOf<String>()
        val blocks = mutableListOf<McpContentBlock>()
        content?.forEach { item ->
            val obj = item as? JsonObject ?: return@forEach
            val type = (obj["type"] as? JsonPrimitive)?.contentOrNull
            when (type) {
                null, "text" -> {
                    (obj["text"] as? JsonPrimitive)?.contentOrNull?.let(texts::add)
                }

                "image", "audio" -> {
                    val mime = (obj["mimeType"] as? JsonPrimitive)?.contentOrNull
                        ?: "application/octet-stream"
                    val data = (obj["data"] as? JsonPrimitive)?.contentOrNull
                        ?: return@forEach
                    blocks +=
                        McpContentBlock.Binary(
                            bytes = decodeEmbedded(data),
                            name = InboxPaths.fileName(if (type == "image") "image" else "audio", mime),
                            mimeType = mime,
                        )
                }

                "resource" -> {
                    val resource = obj["resource"] as? JsonObject ?: return@forEach
                    val mime = (resource["mimeType"] as? JsonPrimitive)?.contentOrNull
                        ?: "application/octet-stream"
                    val text = (resource["text"] as? JsonPrimitive)?.contentOrNull
                    if (text != null) {
                        texts += text
                    } else {
                        val blob = (resource["blob"] as? JsonPrimitive)?.contentOrNull
                            ?: return@forEach
                        val uri = (resource["uri"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                        val name = uri.substringAfterLast('/').ifBlank { "" }
                        blocks +=
                            McpContentBlock.Binary(
                                bytes = decodeEmbedded(blob),
                                name = InboxPaths.fileName(name, mime),
                                mimeType = mime,
                            )
                    }
                }

                "resource_link" -> {
                    val uri = (obj["uri"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                    if (uri.isBlank()) return@forEach
                    val name =
                        (obj["name"] as? JsonPrimitive)?.contentOrNull
                            ?.takeIf { it.isNotBlank() }
                            ?: uri.substringAfterLast('/').ifBlank { "resource" }
                    blocks +=
                        McpContentBlock.ResourceLink(
                            uri = uri,
                            name = InboxPaths.fileName(name, "application/octet-stream"),
                            mimeType = (obj["mimeType"] as? JsonPrimitive)?.contentOrNull,
                            size = (obj["size"] as? JsonPrimitive)?.longOrNull,
                        )
                }
            }
        }
        val structured = result["structuredContent"]
        var text = texts.joinToString("\n").trim()
        if (text.isEmpty() && structured != null) {
            text = structured.toString()
        }
        return McpCallResult(text, isError, blocks, structured)
    }

    fun callText(result: JsonObject): McpCallResult {
        val parsed = callResult(result)
        return McpCallResult(parsed.text, parsed.isError)
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun decodeEmbedded(data: String): ByteArray {
        val bytes =
            try {
                Base64.Default.decode(data)
            } catch (e: IllegalArgumentException) {
                throw McpException("MCP returned invalid base64 content.")
            }
        if (bytes.size > MAX_EMBEDDED_BYTES) {
            throw McpException("MCP embedded content is too large; use a resource_link.")
        }
        return bytes
    }

    fun redact(
        text: String,
        token: String,
    ): String {
        val cleaned = if (token.isBlank()) text else text.replace(token, "***")
        return cleaned.take(200)
    }

    private fun httpFailure(
        exchange: McpExchange,
        token: String,
    ): String {
        if (exchange.status == 401 || exchange.status == 403) {
            return "MCP server rejected the token (HTTP ${exchange.status})."
        }
        val snippet = redact(exchange.body.trim(), token).take(120)
        return if (snippet.isEmpty()) {
            "MCP server returned HTTP ${exchange.status}."
        } else {
            "MCP server returned HTTP ${exchange.status}. $snippet"
        }
    }

    private fun parsePayload(
        body: String,
        id: Int?,
    ): RpcPayload? {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("{")) {
            val obj =
                runCatching { json.parseToJsonElement(trimmed) as? JsonObject }.getOrNull()
                    ?: throw McpException("MCP response was not JSON-RPC.")
            return envelope(obj, id)
        }
        return parseSse(trimmed, id)
    }

    private fun parseSse(
        body: String,
        id: Int?,
    ): RpcPayload? {
        val normalized = body.replace("\r\n", "\n").replace('\r', '\n')
        var matched: RpcPayload? = null
        for (block in normalized.split("\n\n")) {
            val data =
                block
                    .lineSequence()
                    .map { it.trimEnd() }
                    .filter { it.startsWith("data:") }
                    .map { it.removePrefix("data:").trimStart() }
                    .joinToString("\n")
            if (data.isBlank() || data == "[DONE]") continue
            val obj = runCatching { json.parseToJsonElement(data) as? JsonObject }.getOrNull() ?: continue
            val payload = envelope(obj, id) ?: continue
            if (id == null) {
                if (payload is RpcError) return payload
            } else {
                matched = payload
            }
        }
        return matched
    }

    private fun envelope(
        obj: JsonObject,
        id: Int?,
    ): RpcPayload? {
        if (id != null && !idsMatch(obj["id"], id)) return null
        val error = obj["error"] as? JsonObject
        if (error != null) {
            val message = (error["message"] as? JsonPrimitive)?.contentOrNull ?: "MCP request failed."
            val code = (error["code"] as? JsonPrimitive)?.intOrNull
            return RpcError(message.take(200), code)
        }
        if (id != null && !obj.containsKey("result")) return null
        return RpcResult(obj["result"] ?: JsonObject(emptyMap()))
    }

    private fun idsMatch(
        element: JsonElement?,
        expected: Int,
    ): Boolean {
        val primitive = element as? JsonPrimitive ?: return false
        if (primitive is JsonNull) return false
        if (!primitive.isString) {
            return primitive.intOrNull == expected || primitive.content == expected.toString()
        }
        return primitive.content == expected.toString()
    }
}

private sealed interface RpcPayload

private data class RpcResult(
    val result: JsonElement,
) : RpcPayload

private data class RpcError(
    val message: String,
    val code: Int? = null,
) : RpcPayload

class McpSession(
    private val transport: McpTransport,
    private val token: String = "",
    private val clientVersion: String = "wondersing",
    private val deviceId: () -> String = { "" },
) {
    private val mutex = Mutex()
    private var sessionId: String? = null
    private var initialized = false
    private var nextId = 1

    suspend fun listTools(cancellation: ToolCancellation? = null): List<McpRemoteTool> =
        mutex.withLock {
            val collected = mutableListOf<McpRemoteTool>()
            var cursor: String? = null
            var pages = 0
            while (pages < MCP_MAX_PAGES && collected.size < MCP_MAX_TOOLS) {
                pages += 1
                val params =
                    buildJsonObject {
                        cursor?.let { put("cursor", it) }
                    }
                val result =
                    request("tools/list", params, cancellation) as? JsonObject
                        ?: throw McpException("MCP tools/list returned no result.")
                val tools = result["tools"] as? JsonArray ?: JsonArray(emptyList())
                for (element in tools) {
                    if (collected.size >= MCP_MAX_TOOLS) break
                    val tool = parseTool(element as? JsonObject ?: continue) ?: continue
                    if (collected.any { it.name == tool.name }) continue
                    collected += tool
                }
                cursor = (result["nextCursor"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                if (cursor == null) break
            }
            collected
        }

    suspend fun call(
        name: String,
        arguments: JsonObject,
        cancellation: ToolCancellation? = null,
    ): McpCallResult =
        mutex.withLock {
            val params =
                buildJsonObject {
                    put("name", name)
                    put("arguments", arguments)
                }
            val extraHeaders =
                if (name == VFS_LINK_REQUEST_TOOL || name == VFS_LINK_REVOKE_TOOL) {
                    deviceId().takeIf { it.isNotBlank() }?.let {
                        mapOf(VFS_DEVICE_HEADER to it)
                    }.orEmpty()
                } else {
                    emptyMap()
                }
            val result =
                request(
                    "tools/call",
                    params,
                    cancellation,
                    allowRetry = false,
                    extraHeaders = extraHeaders,
                    readTimeoutMs =
                        if (name == DOCKER_RUN_TOOL) {
                            MCP_DOCKER_RUN_READ_TIMEOUT_MS
                        } else {
                            null
                        },
                ) as? JsonObject
                    ?: throw McpException("MCP tools/call returned no result.")
            McpMessages.callResult(result)
        }

    private suspend fun request(
        method: String,
        params: JsonObject,
        cancellation: ToolCancellation?,
        allowRetry: Boolean = true,
        extraHeaders: Map<String, String> = emptyMap(),
        readTimeoutMs: Int? = null,
    ): JsonElement? {
        if (!initialized) ensureInitialized(cancellation)
        val id = nextId++
        val exchange = post(encode(method, params, id), sessionId, cancellation, extraHeaders, readTimeoutMs)
        if (!exchange.sessionId.isNullOrBlank()) sessionId = exchange.sessionId
        if (exchange.status == 404 && allowRetry) {
            initialized = false
            sessionId = null
            return request(
                method,
                params,
                cancellation,
                allowRetry = false,
                extraHeaders = extraHeaders,
                readTimeoutMs = readTimeoutMs,
            )
        }
        return McpMessages.rpcResult(exchange, id, token)
    }

    private suspend fun ensureInitialized(cancellation: ToolCancellation?) {
        if (initialized) return
        val id = nextId++
        val params =
            buildJsonObject {
                put("protocolVersion", MCP_PROTOCOL)
                put("capabilities", buildJsonObject {})
                putJsonObject("clientInfo") {
                    put("name", "wondersing")
                    put("version", clientVersion)
                }
            }
        val exchange = post(encode("initialize", params, id), sessionId = null, cancellation)
        if (!exchange.sessionId.isNullOrBlank()) sessionId = exchange.sessionId
        McpMessages.rpcResult(exchange, id, token)
        initialized = true
        val note = post(encode("notifications/initialized", params = null, id = null), sessionId, cancellation)
        if (!note.sessionId.isNullOrBlank()) sessionId = note.sessionId
        if (note.status !in listOf(200, 202, 204)) {
            throw McpException("MCP server rejected initialization (${note.status}).", note.status)
        }
        if (note.body.isNotBlank()) {
            McpMessages.rpcResult(note, id = null, token)
        }
    }

    private suspend fun post(
        body: String,
        sessionId: String?,
        cancellation: ToolCancellation?,
        extraHeaders: Map<String, String> = emptyMap(),
        readTimeoutMs: Int? = null,
    ): McpExchange {
        if (cancellation?.isCancelled() == true) throw CancellationException("cancelled")
        return try {
            transport.exchange(body, sessionId, cancellation, extraHeaders, readTimeoutMs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: McpException) {
            throw e
        } catch (e: Exception) {
            if (cancellation?.isCancelled() == true) throw CancellationException("cancelled")
            throw McpException(McpMessages.redact(e.message ?: "Could not reach the MCP server.", token))
        }
    }

    private fun encode(
        method: String,
        params: JsonObject?,
        id: Int?,
    ): String =
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", method)
            if (params != null) put("params", params)
            if (id != null) put("id", id)
        }.toString()

    private fun parseTool(element: JsonObject): McpRemoteTool? {
        val name = (element["name"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        if (!TOOL_NAME.matches(name)) return null
        val description = (element["description"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        val annotations = element["annotations"] as? JsonObject
        val readOnly = (annotations?.get("readOnlyHint") as? JsonPrimitive)?.booleanOrNull == true
        val schema = normalizeSchema(element["inputSchema"])
        return McpRemoteTool(
            name = name,
            description = description,
            inputSchema = schema,
            readOnly = readOnly,
            fingerprint = toolFingerprint(name, description, schema, readOnly),
        )
    }

    private fun toolFingerprint(
        name: String,
        description: String,
        schema: JsonObject,
        readOnly: Boolean,
    ): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest("$name\n$description\n$schema\n$readOnly".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun normalizeSchema(raw: JsonElement?): JsonObject {
        val obj = raw as? JsonObject
        val type = (obj?.get("type") as? JsonPrimitive)?.contentOrNull
        if (obj != null && type == "object" && obj.toString().length <= 4_000) return obj
        return buildJsonObject {
            put("type", "object")
            put("additionalProperties", true)
        }
    }

    private companion object {
        val TOOL_NAME = Regex("[A-Za-z0-9_.:-]{1,80}")
    }
}

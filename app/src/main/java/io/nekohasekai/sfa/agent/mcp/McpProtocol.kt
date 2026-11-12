package io.nekohasekai.sfa.agent.mcp

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
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.security.MessageDigest

internal const val MCP_PROTOCOL = "2025-06-18"
internal const val MCP_MAX_TOOLS = 64
private const val MCP_MAX_PAGES = 8

class McpException(
    message: String,
    val status: Int = 0,
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

data class McpCallResult(
    val text: String,
    val isError: Boolean,
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
            if (payload is RpcError) throw McpException(redact(payload.message, token), exchange.status)
            return null
        }
        return when (val payload = parsePayload(exchange.body, id)) {
            is RpcResult -> payload.result
            is RpcError -> throw McpException(redact(payload.message, token), exchange.status)
            null -> throw McpException("MCP response did not include a result.", exchange.status)
        }
    }

    fun callText(result: JsonObject): McpCallResult {
        val isError = (result["isError"] as? JsonPrimitive)?.booleanOrNull == true
        val content = result["content"] as? JsonArray
        val texts =
            content
                ?.mapNotNull { item ->
                    val obj = item as? JsonObject ?: return@mapNotNull null
                    val type = (obj["type"] as? JsonPrimitive)?.contentOrNull
                    if (type != null && type != "text") return@mapNotNull null
                    (obj["text"] as? JsonPrimitive)?.contentOrNull
                }.orEmpty()
        var text = texts.joinToString("\n").trim()
        if (text.isEmpty()) {
            result["structuredContent"]?.let { text = it.toString() }
        }
        if (text.length > 8_000) text = text.take(8_000)
        return McpCallResult(text, isError)
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
            return RpcError(message.take(200))
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
) : RpcPayload

class McpSession(
    private val transport: McpTransport,
    private val token: String = "",
    private val clientVersion: String = "wondersing",
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
            val result =
                request("tools/call", params, cancellation, allowRetry = false) as? JsonObject
                    ?: throw McpException("MCP tools/call returned no result.")
            McpMessages.callText(result)
        }

    private suspend fun request(
        method: String,
        params: JsonObject,
        cancellation: ToolCancellation?,
        allowRetry: Boolean = true,
    ): JsonElement? {
        if (!initialized) ensureInitialized(cancellation)
        val id = nextId++
        val exchange = post(encode(method, params, id), sessionId, cancellation)
        if (!exchange.sessionId.isNullOrBlank()) sessionId = exchange.sessionId
        if (exchange.status == 404 && allowRetry) {
            initialized = false
            sessionId = null
            return request(method, params, cancellation, allowRetry = false)
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
    ): McpExchange {
        if (cancellation?.isCancelled() == true) throw CancellationException("cancelled")
        return try {
            transport.exchange(body, sessionId, cancellation)
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

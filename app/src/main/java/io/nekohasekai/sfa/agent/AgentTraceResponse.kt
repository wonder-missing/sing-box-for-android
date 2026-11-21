package io.nekohasekai.sfa.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.util.TreeMap

/** A response body prepared for the bounded API trace. */
internal data class TraceBody(
    val text: String,
    val truncated: Boolean,
    val originalChars: Int,
    val normalized: Boolean,
)

/**
 * Rebuilds an SSE `chat.completion.chunk` stream into one ordinary
 * `chat.completion` JSON object. The trace stores the assembled object instead
 * of the wire chunks, so a 300k-character SSE stream does not hide the final
 * answer or tool calls behind JSON envelope overhead.
 */
internal object AgentTraceResponse {
    /**
     * Prepares a response for `api-trace.json`. Structured responses are
     * normalised and then capped field by field: reasoning is shortened first,
     * then content, and tool-call arguments last. The final answer and tool
     * calls therefore survive a reasoning-heavy stream that would otherwise be
     * cut off by a plain head truncation.
     */
    fun prepareForTrace(
        body: String?,
        maxChars: Int,
    ): TraceBody {
        if (body.isNullOrEmpty()) return TraceBody("", false, 0, false)
        val normalized = normalizeStreaming(body)
        val root =
            normalized
                ?: runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()
        if (root != null && hasMessage(root)) {
            val structuredChars = root.toString().length
            val (capped, changed) = capForTrace(root, maxChars)
            var text = capped.toString()
            var truncated = changed || text.length > maxChars
            if (text.length > maxChars) {
                text = edgeTruncate(text, maxChars)
                truncated = true
            }
            return TraceBody(
                text = text,
                truncated = truncated,
                originalChars = structuredChars,
                normalized = normalized != null,
            )
        }
        val text = if (body.length > maxChars) edgeTruncate(body, maxChars) else body
        return TraceBody(
            text = text,
            truncated = body.length > maxChars,
            originalChars = body.length,
            normalized = false,
        )
    }

    private fun hasMessage(root: JsonObject): Boolean =
        (root["choices"] as? JsonArray)
            ?.any { (it as? JsonObject)?.get("message") is JsonObject } == true

    /**
     * Applies progressively tighter field limits until the serialized object
     * fits. Returns the original object when it already fits.
     */
    private fun capForTrace(
        root: JsonObject,
        maxChars: Int,
    ): Pair<JsonObject, Boolean> {
        if (root.toString().length <= maxChars) return root to false
        val reasoning = (maxChars / 6).coerceIn(0, 24_000)
        val content = (maxChars / 2).coerceIn(0, 120_000)
        val toolArgs = (maxChars / 4).coerceIn(0, 60_000)
        val attempts =
            listOf(
                Triple(reasoning, content, toolArgs),
                Triple(reasoning.coerceAtMost(4_000), content.coerceAtMost(40_000), toolArgs.coerceAtMost(20_000)),
                Triple(0, content.coerceAtMost(20_000), toolArgs.coerceAtMost(8_000)),
                Triple(0, content.coerceAtMost(8_000), 0),
                Triple(0, 0, toolArgs.coerceAtMost(4_000)),
            )
        attempts.forEach { (reasoningLimit, contentLimit, argLimit) ->
            val candidate = applyLimits(root, reasoningLimit, contentLimit, argLimit)
            if (candidate.toString().length <= maxChars) return candidate to true
        }
        return root to true
    }

    private fun applyLimits(
        root: JsonObject,
        reasoningLimit: Int,
        contentLimit: Int,
        argLimit: Int,
    ): JsonObject {
        val choices = root["choices"] as? JsonArray ?: return root
        val newChoices =
            JsonArray(
                choices.map { rawChoice ->
                    val choice = rawChoice as? JsonObject ?: return@map rawChoice
                    val message = choice["message"] as? JsonObject ?: return@map choice
                    val newMessage = message.toMutableMap()
                    listOf(
                        "reasoning_content" to (reasoningLimit to false),
                        "content" to (contentLimit to true),
                    ).forEach { (key, limits) ->
                        if (newMessage.containsKey(key)) {
                            val current = newMessage[key]
                            val capped = capElement(current, limits.first, limits.second) ?: JsonNull
                            if (capped != current) newMessage[key] = capped
                        }
                    }
                    val toolCalls = message["tool_calls"] as? JsonArray
                    if (toolCalls != null) {
                        newMessage["tool_calls"] =
                            JsonArray(
                                toolCalls.map { rawCall ->
                                    val call = rawCall as? JsonObject ?: return@map rawCall
                                    val function = call["function"] as? JsonObject ?: return@map call
                                    val oldArguments = function["arguments"]
                                    val newArguments =
                                        capElement(oldArguments, argLimit, keepTail = true) ?: JsonNull
                                    if (newArguments == oldArguments) return@map call
                                    val newFunction = function.toMutableMap()
                                    newFunction["arguments"] = newArguments
                                    JsonObject(
                                        call.toMutableMap().apply {
                                            put("function", JsonObject(newFunction))
                                        },
                                    )
                                },
                            )
                    }
                    JsonObject(
                        choice.toMutableMap().apply {
                            put("message", JsonObject(newMessage))
                        },
                    )
                },
            )
        return JsonObject(
            root.toMutableMap().apply {
                put("choices", newChoices)
            },
        )
    }

    private fun capElement(
        element: JsonElement?,
        limit: Int,
        keepTail: Boolean,
    ): JsonElement? {
        if (element !is JsonPrimitive || !element.isString) return element
        val text = element.content
        if (text.length <= limit) return element
        return JsonPrimitive(capText(text, limit, keepTail))
    }

    private fun capText(
        text: String,
        limit: Int,
        keepTail: Boolean,
    ): String {
        if (text.length <= limit) return text
        if (limit <= 0) return "[trace: omitted ${text.length} chars]"
        val marker = "\n[trace truncated: ${text.length} chars total]\n"
        if (keepTail && limit >= 256) {
            val head = (limit * 3) / 4
            val tail = limit - head
            return text.take(head) + marker + text.takeLast(tail)
        }
        return text.take(limit) + marker
    }

    private fun edgeTruncate(
        text: String,
        maxChars: Int,
    ): String {
        if (text.length <= maxChars) return text
        if (maxChars < 256) return text.take(maxChars) + "\n[trace truncated]"
        val head = (maxChars * 4) / 5
        val tail = maxChars - head - 32
        if (tail <= 0) return text.take(maxChars) + "\n[trace truncated]"
        return text.take(head) + "\n[trace truncated]\n" + text.takeLast(tail)
    }

    fun normalizeStreaming(body: String): JsonObject? {
        val chunks = parseChunks(body)
        if (chunks.isEmpty()) return null
        val first = chunks.first()
        val objectName = first.string("object")
        if (objectName != "chat.completion.chunk") {
            // A plain (non-streaming) response is already handled by the
            // normal JSON pretty-printer.
            return null
        }

        val choices = TreeMap<Int, ChoiceAccumulator>()
        var usage: JsonObject? = null
        var role = "assistant"
        var finishReason: String? = null
        var id: String? = null
        var created: JsonElement? = null
        var model: String? = null
        var fingerprint: String? = null

        chunks.forEach { chunk ->
            id = id ?: chunk.string("id")
            created = created ?: chunk["created"]
            model = model ?: chunk.string("model")
            fingerprint = fingerprint ?: chunk.string("system_fingerprint")
            (chunk["usage"] as? JsonObject)?.let { usage = it }
            val chunkChoices = chunk["choices"] as? JsonArray ?: return@forEach
            chunkChoices.forEach { raw ->
                val choice = raw as? JsonObject ?: return@forEach
                val index = (choice["index"] as? JsonPrimitive)?.intOrNull ?: 0
                val accumulator = choices.getOrPut(index) { ChoiceAccumulator() }
                (choice["finish_reason"] as? JsonPrimitive)?.contentOrNull
                    ?.takeIf { it.isNotBlank() && it != "null" }
                    ?.let { finishReason = it }
                val delta = choice["delta"] as? JsonObject ?: return@forEach
                (delta["role"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { role = it }
                (delta["content"] as? JsonPrimitive)?.contentOrNull?.let { accumulator.content.append(it) }
                (delta["reasoning_content"] as? JsonPrimitive)?.contentOrNull
                    ?.let { accumulator.reasoning.append(it) }
                (delta["tool_calls"] as? JsonArray)?.forEachIndexed { position, rawCall ->
                    val call = rawCall as? JsonObject ?: return@forEachIndexed
                    val callIndex = (call["index"] as? JsonPrimitive)?.intOrNull ?: position
                    val target = accumulator.toolCalls.getOrPut(callIndex) { ToolCallAccumulator() }
                    (call["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { target.id = it }
                    (call["type"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { target.type = it }
                    val function = call["function"] as? JsonObject ?: return@forEachIndexed
                    (function["name"] as? JsonPrimitive)?.contentOrNull?.let { target.name.append(it) }
                    (function["arguments"] as? JsonPrimitive)?.contentOrNull?.let { target.arguments.append(it) }
                }
            }
        }

        if (choices.isEmpty()) return null
        return buildJsonObject {
            put("id", id?.let { JsonPrimitive(it) } ?: JsonNull)
            put("object", "chat.completion")
            put("created", created ?: JsonNull)
            put("model", model?.let { JsonPrimitive(it) } ?: JsonNull)
            fingerprint?.let { put("system_fingerprint", it) }
            put(
                "choices",
                buildJsonArray {
                    choices.forEach { (index, accumulator) ->
                        add(
                            buildJsonObject {
                                put("index", index)
                                put(
                                    "message",
                                    buildJsonObject {
                                        put("role", role)
                                        if (accumulator.content.isNotEmpty()) {
                                            put("content", accumulator.content.toString())
                                        } else if (accumulator.toolCalls.isEmpty()) {
                                            put("content", "")
                                        } else {
                                            put("content", JsonNull)
                                        }
                                        if (accumulator.reasoning.isNotEmpty()) {
                                            put("reasoning_content", accumulator.reasoning.toString())
                                        }
                                        if (accumulator.toolCalls.isNotEmpty()) {
                                            put(
                                                "tool_calls",
                                                buildJsonArray {
                                                    accumulator.toolCalls.values.forEachIndexed { callIndex, call ->
                                                        add(
                                                            buildJsonObject {
                                                                call.id?.let { put("id", it) }
                                                                put("type", call.type ?: "function")
                                                                put(
                                                                    "function",
                                                                    buildJsonObject {
                                                                        put("name", call.name.toString())
                                                                        put(
                                                                            "arguments",
                                                                            call.arguments.toString().ifBlank { "{}" },
                                                                        )
                                                                    },
                                                                )
                                                            },
                                                        )
                                                    }
                                                },
                                            )
                                        }
                                    },
                                )
                                put("finish_reason", finishReason?.let { JsonPrimitive(it) } ?: JsonNull)
                            },
                        )
                    }
                },
            )
            usage?.let { put("usage", it) }
        }
    }

    private fun parseChunks(body: String): List<JsonObject> {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return emptyList()
        runCatching { Json.parseToJsonElement(trimmed) }.getOrNull()?.let { element ->
            if (element is JsonArray) {
                return element.mapNotNull { it as? JsonObject }
            }
            if (element is JsonObject) {
                // A single non-streaming object is left to the ordinary
                // pretty-printer.
                return listOf(element)
            }
        }
        return body
            .lineSequence()
            .map { it.trim().removePrefix("data:").trim() }
            .filter { it.isNotEmpty() && it != "[DONE]" && it != "data: [DONE]" }
            .mapNotNull { line -> runCatching { Json.parseToJsonElement(line) as? JsonObject }.getOrNull() }
            .toList()
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private class ChoiceAccumulator {
        val content = StringBuilder()
        val reasoning = StringBuilder()
        val toolCalls = TreeMap<Int, ToolCallAccumulator>()
    }

    private class ToolCallAccumulator {
        var id: String? = null
        var type: String? = null
        val name = StringBuilder()
        val arguments = StringBuilder()
    }
}

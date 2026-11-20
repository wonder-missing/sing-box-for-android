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

/**
 * Rebuilds an SSE `chat.completion.chunk` stream into one ordinary
 * `chat.completion` JSON object for the readable API trace. The raw
 * `api-trace.json` body stays untouched; this is only for the exported
 * Markdown trace.
 */
internal object AgentTraceResponse {
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

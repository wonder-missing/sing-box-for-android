package io.nekohasekai.sfa.conductor

import io.nekohasekai.sfa.conductor.ConductorItem.Assistant
import io.nekohasekai.sfa.conductor.ConductorItem.Reasoning

/**
 * Transcript list transitions for streaming text.
 *
 * Reasoning and assistant content can overlap in the UI: content starts while
 * the reasoning block is still waiting for the stream's Completed chunk. Every
 * boundary must settle all streaming reasoning items, not only the last one,
 * otherwise an earlier Thinking spinner keeps rotating for the rest of the turn.
 */
internal fun List<ConductorItem>.settleReasoning(): List<ConductorItem> {
    if (none { it is Reasoning && it.streaming }) return this
    val updated = toMutableList()
    for (index in updated.indices.reversed()) {
        val item = updated[index]
        if (item is Reasoning && item.streaming) {
            if (item.text.isBlank()) {
                updated.removeAt(index)
            } else {
                updated[index] = item.copy(streaming = false)
            }
        }
    }
    return updated
}

internal fun List<ConductorItem>.settleStreamingAssistants(): List<ConductorItem> {
    if (none { it is Assistant && it.streaming }) return this
    val updated = toMutableList()
    for (index in updated.indices.reversed()) {
        val item = updated[index]
        if (item is Assistant && item.streaming) {
            if (item.text.isBlank()) {
                updated.removeAt(index)
            } else {
                updated[index] = item.copy(streaming = false)
            }
        }
    }
    return updated
}

internal fun List<ConductorItem>.withReasoningPlaceholder(): List<ConductorItem> {
    val settled = settleReasoning()
    return if (settled.lastOrNull().let { it is Reasoning && it.streaming }) {
        settled
    } else {
        settled + Reasoning("", streaming = true)
    }
}

internal fun List<ConductorItem>.withReasoningDelta(text: String): List<ConductorItem> {
    if (text.isEmpty()) return this
    val updated = toMutableList()
    val index = updated.indexOfLast { it is Reasoning && it.streaming }
    if (index >= 0) {
        val reasoning = updated[index] as Reasoning
        updated[index] = reasoning.copy(text = reasoning.text + text)
    } else {
        updated.add(Reasoning(text, streaming = true))
    }
    return updated
}

internal fun List<ConductorItem>.withAssistantDelta(text: String): List<ConductorItem> {
    if (text.isEmpty()) return this
    val updated = settleReasoning().toMutableList()
    val index = updated.indexOfLast { it is Assistant && it.streaming }
    if (index >= 0) {
        val assistant = updated[index] as Assistant
        updated[index] = assistant.copy(text = assistant.text + text)
    } else {
        updated.add(Assistant(text, streaming = true))
    }
    return updated
}

internal fun List<ConductorItem>.withAssistantFinal(text: String): List<ConductorItem> {
    val updated = settleReasoning().toMutableList()
    val index = updated.indexOfLast { it is Assistant && it.streaming }
    if (index >= 0) {
        updated[index] = Assistant(text, streaming = false)
    } else {
        updated.add(Assistant(text))
    }
    return updated.settleStreamingAssistants()
}

/** Discards partial streaming output before a retried request. */
internal fun List<ConductorItem>.dropStreamingOutput(): List<ConductorItem> =
    filterNot { item ->
        (item is Assistant && item.streaming) || (item is Reasoning && item.streaming)
    }

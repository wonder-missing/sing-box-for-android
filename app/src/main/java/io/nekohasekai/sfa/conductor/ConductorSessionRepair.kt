package io.nekohasekai.sfa.conductor

import io.nekohasekai.sfa.agent.AgentMessage
import io.nekohasekai.sfa.agent.AgentSanitizer
import io.nekohasekai.sfa.agent.RUNTIME_EVENT_PREFIX
import io.nekohasekai.sfa.agent.formatUserMessage

/**
 * A session can outlive several app versions. Older runtimes rolled back a
 * failed user message from the model transcript while keeping it in the UI, and
 * older tool schemas could include data that is redacted today. Repair the
 * persisted session before it is used again.
 */
internal fun repairRestoredTranscript(
    transcript: List<AgentMessage>,
    items: List<ConductorItem>,
): List<AgentMessage> =
    scrubHistoricalToolOutputs(
        reconcileAgentUserMessages(transcript, items),
    )

/**
 * Re-inserts user messages that are visible in the UI but absent from the
 * transcript. Only agent turns are considered: command entries start with `/`
 * and are handled by the command layer, not the model.
 */
internal fun reconcileAgentUserMessages(
    transcript: List<AgentMessage>,
    items: List<ConductorItem>,
): List<AgentMessage> {
    val expected =
        agentUserTexts(items).map { formatUserMessage(it, null) }
    if (expected.isEmpty()) return transcript

    val firstUserIndex = transcript.indexOfFirst { it is AgentMessage.User }
    val firstUser = transcript.getOrNull(firstUserIndex) as? AgentMessage.User
    // The session store may have trimmed older messages from the transcript
    // while the UI still lists them. Anchor reconciliation at the oldest user
    // the transcript actually kept so a restore cannot undo that trim.
    val anchor =
        if (firstUser == null) {
            expected.lastIndex
        } else {
            expected.indexOfFirst { userContentMatches(firstUser.content, it) }
                .takeIf { it >= 0 }
                ?: return transcript
        }

    val result = transcript.toMutableList()
    var searchFrom = firstUserIndex.coerceAtLeast(0)
    for (index in anchor until expected.size) {
        val content = expected[index]
        val found =
            (searchFrom until result.size).firstOrNull { position ->
                val message = result[position]
                message is AgentMessage.User && userContentMatches(message.content, content)
            }
        if (found != null) {
            searchFrom = found + 1
            continue
        }
        val insertAt =
            (searchFrom until result.size).firstOrNull { result[it] is AgentMessage.User }
                ?: result.size
        result.add(insertAt, AgentMessage.User(content))
        searchFrom = insertAt + 1
    }
    return result
}

private fun agentUserTexts(items: List<ConductorItem>): List<String> =
    items.mapNotNull { item ->
        if (item is ConductorItem.User && !item.text.startsWith("/")) item.text else null
    }

private fun userContentMatches(
    content: String,
    expected: String,
): Boolean =
    content == expected ||
        (
            content.startsWith(RUNTIME_EVENT_PREFIX) &&
                content.endsWith("\n$expected")
        )

/**
 * The Tailscale tool now hides the account email and peer IPs from the agent
 * view. Sessions written before that fix still contain the old text; redact the
 * personal parts before the transcript is sent to a model provider again.
 */
internal fun scrubHistoricalToolOutputs(transcript: List<AgentMessage>): List<AgentMessage> =
    transcript.map { message ->
        if (message is AgentMessage.Tool) {
            message.copy(content = AgentSanitizer.scrubPersonalData(message.content))
        } else {
            message
        }
    }

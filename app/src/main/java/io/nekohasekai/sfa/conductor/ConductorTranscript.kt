package io.nekohasekai.sfa.conductor

import io.nekohasekai.sfa.agent.AgentJson
import io.nekohasekai.sfa.agent.AgentSanitizer
import io.nekohasekai.sfa.agent.PlanState
import io.nekohasekai.sfa.agent.markdownFence

/**
 * Renders the plain transcript for the clipboard export, Plain Transcript
 * viewer and `.runtime/conversation.md`. Reasoning is model scratchpad and is
 * omitted by default; the API trace keeps it for diagnostics.
 */
internal fun conductorConversationMarkdown(
    items: List<ConductorItem>,
    appVersion: String,
    sessionStartVersion: String = "",
    includeReasoning: Boolean = false,
    plan: PlanState? = null,
): String =
    buildString {
        appendLine("# Conductor transcript")
        appendLine()
        appendLine("- app version: $appVersion")
        if (sessionStartVersion.isNotBlank() && sessionStartVersion != appVersion) {
            appendLine("- session started on: $sessionStartVersion")
        }
        appendLine()
        if (plan != null) {
            appendLine("## Plan")
            appendLine()
            if (plan.cleared) {
                appendLine("- state: no active plan (cleared)")
            } else {
                if (plan.goal.isNotBlank()) appendLine("- goal: ${AgentSanitizer.sanitizeText(plan.goal)}")
                plan.items.forEach { item ->
                    appendLine("- [${item.status.wire}] ${AgentSanitizer.sanitizeText(item.step)}")
                }
                if (plan.note.isNotBlank()) appendLine("- note: ${AgentSanitizer.sanitizeText(plan.note)}")
            }
            appendLine()
        }
        items.forEach { item ->
            when (item) {
                is ConductorItem.User -> {
                    appendLine("## User")
                    appendLine()
                    appendLine(item.text)
                    appendLine()
                }

                is ConductorItem.Assistant -> {
                    appendLine("## Conductor")
                    appendLine()
                    appendLine(item.text)
                    appendLine()
                }

                is ConductorItem.Reasoning -> {
                    if (includeReasoning) {
                        appendLine("## Thinking")
                        appendLine()
                        appendLine(item.text)
                        appendLine()
                    }
                }

                is ConductorItem.Tool -> {
                    appendLine("## Tool: ${item.call.name}")
                    appendLine()
                    appendLine("- status: ${item.status.name.lowercase()}")
                    if (item.result?.degraded == true) appendLine("- degraded: true")
                    appendLine()
                    val args = item.call.displayArgsJson
                    if (args.isNotBlank() && args != "{}") {
                        appendLine(
                            markdownFence(
                                AgentJson.parseObject(args)?.let { AgentJson.pretty(it) } ?: args,
                                "json",
                            ),
                        )
                        appendLine()
                    }
                    item.result?.let { result ->
                        result.output?.let { output ->
                            appendLine(markdownFence(AgentJson.pretty(output), "json"))
                            appendLine()
                        }
                        result.message?.takeIf { it.isNotBlank() }?.let { message ->
                            appendLine(markdownFence(message, "text"))
                            appendLine()
                        }
                        result.errorCode
                            ?.takeIf { result.output == null && result.message.isNullOrBlank() }
                            ?.let {
                                appendLine("- errorCode: $it")
                                appendLine()
                            }
                        if (result.truncated) {
                            appendLine("(truncated)")
                            appendLine()
                        }
                    }
                }

                is ConductorItem.Notice -> {
                    appendLine("## ${item.title}")
                    appendLine()
                    appendLine(item.body)
                    appendLine()
                }

                is ConductorItem.Error -> {
                    appendLine("## Error: ${item.title}")
                    appendLine()
                    appendLine(item.message)
                    appendLine()
                }

                is ConductorItem.Question -> {
                    appendLine("## Question: ${item.question}")
                    appendLine()
                    appendLine("- options: ${item.options.joinToString(" | ")}")
                    when {
                        item.answered -> appendLine("- answered: ${item.selected.joinToString(", ")}")
                        item.cancelled -> appendLine("- answered: (none; the card was dismissed or timed out)")
                        else -> appendLine("- answered: (pending)")
                    }
                    appendLine()
                }

                is ConductorItem.Confirmation -> {
                    appendLine("## Pending confirmation: ${item.request.toolName}")
                    appendLine()
                    appendLine(item.request.reason)
                    appendLine()
                    appendLine(
                        markdownFence(
                            AgentJson.parseObject(item.request.displayArgsJson)
                                ?.let { AgentJson.pretty(it) }
                                ?: item.request.displayArgsJson,
                            "json",
                        ),
                    )
                    appendLine()
                }
            }
        }
    }.trim().let { AgentSanitizer.scrubPersonalData(it) }

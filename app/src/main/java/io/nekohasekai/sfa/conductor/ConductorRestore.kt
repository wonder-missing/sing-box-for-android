package io.nekohasekai.sfa.conductor

import io.nekohasekai.sfa.agent.ToolResultView
import io.nekohasekai.sfa.agent.ToolStatus

internal data class RestoredConductorItems(
    val items: List<ConductorItem>,
    val interrupted: Boolean,
)

/**
 * Repairs the persisted UI items after a process restart. A tool that was
 * RUNNING cannot still be running in a new process, so it becomes CANCELLED
 * and the caller adds one interruption notice to the conversation.
 */
internal fun restoreConductorItems(items: List<ConductorItem>): RestoredConductorItems {
    var interrupted = false
    val restored =
        items.mapNotNull { item ->
            when (item) {
                is ConductorItem.Confirmation -> null
                is ConductorItem.Assistant ->
                    if (item.streaming) item.copy(streaming = false) else item

                is ConductorItem.Reasoning ->
                    if (item.streaming) item.copy(streaming = false) else item

                is ConductorItem.Tool ->
                    if (item.status == ToolStatus.RUNNING) {
                        interrupted = true
                        item.copy(
                            status = ToolStatus.CANCELLED,
                            result =
                                ToolResultView(
                                    callId = item.call.id,
                                    name = item.call.name,
                                    ok = false,
                                    output = null,
                                    errorCode = "interrupted",
                                    message = "Interrupted when the app process stopped.",
                                    truncated = false,
                                ),
                        )
                    } else {
                        item
                    }

                else -> item
            }
        }
    return RestoredConductorItems(restored, interrupted)
}

internal fun interruptedConductorNotice(): ConductorItem.Notice =
    ConductorItem.Notice(
        "conductor",
        "The previous turn was interrupted when the app process stopped. " +
            "Running tools were cancelled; send the message again to retry.",
    )

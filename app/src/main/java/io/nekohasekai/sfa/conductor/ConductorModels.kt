package io.nekohasekai.sfa.conductor

import io.nekohasekai.sfa.agent.ConfirmationRequest
import io.nekohasekai.sfa.agent.ToolCallView
import io.nekohasekai.sfa.agent.ToolResultView
import io.nekohasekai.sfa.agent.ToolStatus

sealed interface ConductorItem {
    data class User(
        val text: String,
    ) : ConductorItem

    data class Assistant(
        val text: String,
        val streaming: Boolean = false,
    ) : ConductorItem

    data class Tool(
        val call: ToolCallView,
        val status: ToolStatus,
        val result: ToolResultView? = null,
    ) : ConductorItem

    data class Notice(
        val title: String,
        val body: String,
    ) : ConductorItem

    data class Confirmation(
        val request: ConfirmationRequest,
    ) : ConductorItem

    data class Error(
        val title: String,
        val message: String,
        val retryable: Boolean = false,
    ) : ConductorItem
}

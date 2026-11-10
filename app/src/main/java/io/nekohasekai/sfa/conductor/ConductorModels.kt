package io.nekohasekai.sfa.conductor

import io.nekohasekai.sfa.agent.ConfirmationRequest
import io.nekohasekai.sfa.agent.ToolCallView
import io.nekohasekai.sfa.agent.ToolResultView
import io.nekohasekai.sfa.agent.ToolStatus
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface ConductorItem {
    @Serializable
    @SerialName("user")
    data class User(
        val text: String,
    ) : ConductorItem

    @Serializable
    @SerialName("assistant")
    data class Assistant(
        val text: String,
        val streaming: Boolean = false,
    ) : ConductorItem

    @Serializable
    @SerialName("tool")
    data class Tool(
        val call: ToolCallView,
        val status: ToolStatus,
        val result: ToolResultView? = null,
    ) : ConductorItem

    @Serializable
    @SerialName("notice")
    data class Notice(
        val title: String,
        val body: String,
    ) : ConductorItem

    @Serializable
    @SerialName("confirmation")
    data class Confirmation(
        val request: ConfirmationRequest,
    ) : ConductorItem

    @Serializable
    @SerialName("error")
    data class Error(
        val title: String,
        val message: String,
        val retryable: Boolean = false,
    ) : ConductorItem
}

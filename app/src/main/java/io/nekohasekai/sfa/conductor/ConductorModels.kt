package io.nekohasekai.sfa.conductor

import io.nekohasekai.sfa.agent.AgentConfirmation

enum class ConductorMessageKind {
    USER,
    SYSTEM,
    RESULT,
    ERROR,
}

data class ConductorMessage(
    val kind: ConductorMessageKind,
    val title: String,
    val body: String,
    val confirmation: AgentConfirmation? = null,
    val confirmationId: Long? = null,
)

package io.nekohasekai.sfa.conductor

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
)

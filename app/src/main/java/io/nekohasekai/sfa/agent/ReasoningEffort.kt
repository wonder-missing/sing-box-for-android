package io.nekohasekai.sfa.agent

/**
 * Shared reasoning-effort levels for OpenAI-compatible chat completions.
 *
 * Default omits the field. Low, medium and high are accepted by DeepSeek,
 * xAI and typical OpenAI-compatible gateways. Provider-specific levels
 * (`none`, `max`, `xhigh`) are intentionally absent: sending them makes some
 * models reject the whole request.
 */
object ReasoningEffort {
    const val DEFAULT = "default"
    const val LOW = "low"
    const val MEDIUM = "medium"
    const val HIGH = "high"

    val options = listOf(DEFAULT, LOW, MEDIUM, HIGH)

    fun normalize(stored: String?): String = if (stored != null && stored in options) stored else DEFAULT

    /** Value of the `reasoning_effort` field. Null means do not send it. */
    fun wireValue(stored: String?): String? {
        val normalized = normalize(stored)
        return if (normalized == DEFAULT) null else normalized
    }
}

package io.nekohasekai.sfa.conductor

import io.nekohasekai.sfa.agent.ReasoningEffort

/** `/reasoning` reads or sets the shared reasoning-effort preference. */
internal object ConductorReasoningCommand {
    const val USAGE = "/reasoning [default|low|medium|high]"

    fun apply(
        args: List<String>,
        current: String,
    ): Outcome {
        if (args.isEmpty()) {
            val value = ReasoningEffort.normalize(current)
            return Outcome.Shown(
                value,
                "Reasoning effort is $value. $USAGE",
            )
        }
        if (args.size == 1) {
            val raw = args[0].trim().lowercase()
            if (raw in ReasoningEffort.options) {
                val detail =
                    if (raw == ReasoningEffort.DEFAULT) {
                        "The next message omits reasoning_effort."
                    } else {
                        "The next message sends reasoning_effort=$raw."
                    }
                return Outcome.Updated(raw, "Reasoning effort set to $raw. $detail")
            }
        }
        return Outcome.Invalid("Unknown reasoning effort. Use default, low, medium, or high.")
    }

    /** Levels to offer once the user has typed `/reasoning` and a space. */
    fun suggestions(input: String): List<String> {
        val text = input.trimStart()
        if (!text.startsWith("/")) return emptyList()
        val body = text.removePrefix("/")
        val head = body.takeWhile { !it.isWhitespace() }
        if (!head.equals("reasoning", ignoreCase = true)) return emptyList()
        val afterHead = body.substring(head.length)
        if (afterHead.none { it.isWhitespace() }) return emptyList()
        val typed = afterHead.dropWhile { it.isWhitespace() }
        if (typed.any { it.isWhitespace() }) return emptyList()
        return ReasoningEffort.options.filter { typed.isEmpty() || it.startsWith(typed, ignoreCase = true) }
    }

    fun hint(value: String): String =
        if (value == ReasoningEffort.DEFAULT) {
            "Omit reasoning_effort"
        } else {
            "Send reasoning_effort=$value"
        }

    sealed class Outcome {
        data class Shown(
            val value: String,
            val message: String,
        ) : Outcome()

        data class Updated(
            val value: String,
            val message: String,
        ) : Outcome()

        data class Invalid(
            val message: String,
        ) : Outcome()
    }
}

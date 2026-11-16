package io.nekohasekai.sfa.conductor

/**
 * `/brief` controls a prompt-level style instruction, not a sampling
 * parameter. Temperature makes wording more or less random; it does not make
 * the model omit background, preamble or repeated questions.
 */
internal object ConductorBriefCommand {
    const val USAGE = "/brief [on|off]"

    val options = listOf("on", "off")

    fun apply(
        args: List<String>,
        current: Boolean,
    ): Outcome {
        if (args.isEmpty()) {
            return Outcome.Shown(
                current,
                "Brief answers are ${state(current)}. $USAGE",
            )
        }
        if (args.size == 1) {
            when (args[0].trim().lowercase()) {
                "on" ->
                    return Outcome.Updated(
                        true,
                        "Brief answers are on. Replies stay short: no preamble, no restating the question, " +
                            "no optional background. Exact values and required warnings stay.",
                    )

                "off" ->
                    return Outcome.Updated(
                        false,
                        "Brief answers are off. Replies use the normal length.",
                    )
            }
        }
        return Outcome.Invalid("Unknown brief setting. Use $USAGE.")
    }

    fun suggestions(input: String): List<String> {
        val text = input.trimStart()
        if (!text.startsWith("/")) return emptyList()
        val body = text.removePrefix("/")
        val head = body.takeWhile { !it.isWhitespace() }
        if (!head.equals("brief", ignoreCase = true)) return emptyList()
        val afterHead = body.substring(head.length)
        if (afterHead.none { it.isWhitespace() }) return emptyList()
        val typed = afterHead.dropWhile { it.isWhitespace() }
        if (typed.any { it.isWhitespace() }) return emptyList()
        return SlashFuzzy.rank(typed, options) { it }
    }

    fun hint(value: String): String =
        if (value == "on") "Short answers, no preamble" else "Normal answer length"

    private fun state(value: Boolean): String = if (value) "on" else "off"

    sealed class Outcome {
        data class Shown(
            val value: Boolean,
            val message: String,
        ) : Outcome()

        data class Updated(
            val value: Boolean,
            val message: String,
        ) : Outcome()

        data class Invalid(
            val message: String,
        ) : Outcome()
    }
}

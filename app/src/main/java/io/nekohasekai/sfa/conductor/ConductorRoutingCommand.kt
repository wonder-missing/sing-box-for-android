package io.nekohasekai.sfa.conductor

/** `/routing` controls whether new turns use the search_tools/route_call layer. */
internal object ConductorRoutingCommand {
    const val USAGE = "/routing [on|off]"

    val options = listOf("on", "off")

    fun apply(
        args: List<String>,
        current: Boolean,
    ): Outcome {
        if (args.isEmpty()) {
            return Outcome.Shown(current, "Tool routing is ${state(current)}. $USAGE")
        }
        if (args.size == 1) {
            when (args[0].trim().lowercase()) {
                "on" ->
                    return Outcome.Updated(
                        true,
                        "Tool routing is on. New turns search the tool catalog before calling a capability.",
                    )

                "off" ->
                    return Outcome.Updated(
                        false,
                        "Tool routing is off. New turns send the full tool schemas directly.",
                    )
            }
        }
        return Outcome.Invalid("Unknown routing setting. Use $USAGE.")
    }

    fun suggestions(input: String): List<String> {
        val text = input.trimStart()
        if (!text.startsWith("/")) return emptyList()
        val body = text.removePrefix("/")
        val head = body.takeWhile { !it.isWhitespace() }
        if (!head.equals("routing", ignoreCase = true)) return emptyList()
        val afterHead = body.substring(head.length)
        if (afterHead.none { it.isWhitespace() }) return emptyList()
        val typed = afterHead.dropWhile { it.isWhitespace() }
        if (typed.any { it.isWhitespace() }) return emptyList()
        return SlashFuzzy.rank(typed, options) { it }
    }

    fun hint(value: String): String =
        if (value == "on") "Search first, then route_call" else "Send full schemas"

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

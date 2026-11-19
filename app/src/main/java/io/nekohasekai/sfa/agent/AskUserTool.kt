package io.nekohasekai.sfa.agent

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Turns a small-choice question into a tappable card in Conductor. The card
 * lives in the conversation, so the question and the answer stay readable
 * after the turn. Answers are option labels, returned to the model verbatim.
 */
class AskUserTool(
    private val port: AskUserPort?,
) : AgentTool {
    override val definition =
        AgentToolDefinition(
            name = "ask_user",
            description =
                "Show the user a question card with tappable answers, then wait. Use this instead of " +
                    "asking a yes/no or small-choice question in a normal message. Ask one question per call " +
                    "with 2-6 short option labels; set multiple=true only when more than one option may apply. " +
                    "Returns the selected labels, or cancelled=true with reason=dismissed, timeout, stopped or " +
                    "busy when the card was not answered. Use normal text for open-ended questions.",
            permission = AgentPermission.USER_INTERACTION,
            parameters =
                listOf(
                    AgentParameterSpec(
                        name = "question",
                        type = AgentParameterType.STRING,
                        required = true,
                        description = "One short question shown on the card.",
                    ),
                    AgentParameterSpec(
                        name = "options",
                        type = AgentParameterType.STRING_ARRAY,
                        required = true,
                        description = "2-6 short answer labels. Each becomes a tappable option.",
                    ),
                    AgentParameterSpec(
                        name = "multiple",
                        type = AgentParameterType.BOOLEAN,
                        required = false,
                        description = "Allow selecting more than one option. Defaults to false.",
                    ),
                    AgentParameterSpec(
                        name = "header",
                        type = AgentParameterType.STRING,
                        required = false,
                        description = "Optional short title above the question.",
                        allowBlank = true,
                    ),
                ),
            confirmationPolicy = ConfirmationPolicy.NEVER,
            category = "conversation",
            core = true,
            aliases = listOf("ask user", "multiple choice", "yes or no", "question card"),
            timeoutMs = ASK_USER_TIMEOUT_MS,
        )

    override suspend fun execute(
        args: JsonObject,
        cancellation: ToolCancellation,
    ): ToolExecution {
        val port = port
            ?: return ToolExecution(
                errorCode = "unavailable",
                errorMessage = "The question card is not available in this build.",
            )
        val question = AgentJson.string(args, "question")?.trim().orEmpty()
        if (question.isEmpty()) {
            return invalid("question is required")
        }
        if (question.length > MAX_QUESTION_CHARS) {
            return invalid("question must be at most $MAX_QUESTION_CHARS characters")
        }
        val options =
            AgentJson.stringList(args, "options")
                .orEmpty()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        if (options.size !in MIN_OPTIONS..MAX_OPTIONS) {
            return invalid("options must contain $MIN_OPTIONS to $MAX_OPTIONS labels")
        }
        if (options.any { it.length > MAX_OPTION_CHARS }) {
            return invalid("each option must be at most $MAX_OPTION_CHARS characters")
        }
        if (options.map { it.lowercase() }.distinct().size != options.size) {
            return invalid("options must be distinct")
        }
        val header =
            AgentJson.string(args, "header")
                ?.trim()
                ?.take(MAX_HEADER_CHARS)
                ?.takeIf { it.isNotEmpty() }
        val multiple = AgentJson.boolean(args, "multiple") ?: false
        return try {
            val result = port.ask(AskUserRequest(question, header, options, multiple), cancellation)
            ToolExecution(
                output =
                    buildJsonObject {
                        put("answered", !result.cancelled)
                        put("cancelled", result.cancelled)
                        result.message?.let { put("message", it) }
                        result.reason?.let { put("reason", it) }
                        put(
                            "selected",
                            buildJsonArray {
                                result.selected.forEach { add(it) }
                            },
                        )
                    },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolExecution(
                errorCode = "ask_user_failed",
                errorMessage = AgentSanitizer.sanitizeText(e.message ?: "The question card failed."),
            )
        }
    }

    private fun invalid(message: String): ToolExecution =
        ToolExecution(errorCode = "invalid_arguments", errorMessage = message)

    companion object {
        const val MIN_OPTIONS = 2
        const val MAX_OPTIONS = 6
        const val MAX_QUESTION_CHARS = 500
        const val MAX_OPTION_CHARS = 80
        const val MAX_HEADER_CHARS = 60
        const val ASK_USER_TIMEOUT_MS = 10L * 60L * 1000L
    }
}

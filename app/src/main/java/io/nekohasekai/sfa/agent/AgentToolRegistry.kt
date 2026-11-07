package io.nekohasekai.sfa.agent

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Name-based runtime tool dispatch. Validation happens before execution, and
 * tools marked needs_confirmation never run until the conversation layer passes
 * confirmed = true.
 */
class AgentToolRegistry(
    tools: List<AgentTool>,
) {
    private val toolsByName = tools.associateBy { it.definition.name }

    fun definitions(): List<AgentToolDefinition> = toolsByName.values.map { it.definition }

    fun invoke(
        name: String,
        argsJson: String = "{}",
        confirmed: Boolean = false,
    ): AgentInvocationResult {
        val tool =
            toolsByName[name]
                ?: return failure(
                    name,
                    "unknown_tool",
                    "Unknown tool '$name'. Registered tools: ${toolsByName.keys.joinToString()}.",
                )
        val trimmed = argsJson.trim()
        val args =
            if (trimmed.isEmpty()) {
                JsonObject(emptyMap())
            } else {
                AgentJson.parseObject(trimmed)
                    ?: return failure(name, "invalid_arguments", "Arguments must be a JSON object.")
            }

        for (spec in tool.definition.parameters) {
            val element = args[spec.name]
            if (element == null || element is JsonNull) {
                if (spec.required) {
                    return failure(name, "missing_parameter", "Missing required parameter '${spec.name}'.")
                }
                continue
            }
            val primitive = element as? JsonPrimitive
            if (primitive == null || !primitive.isString) {
                return failure(
                    name,
                    "invalid_parameter_type",
                    "Parameter '${spec.name}' must be a ${spec.type.schemaType}.",
                )
            }
            if (primitive.content.isBlank()) {
                return failure(name, "invalid_parameter", "Parameter '${spec.name}' must not be blank.")
            }
        }

        val allowedUrls = setOfNotNull(AgentJson.string(args, "url"))
        val definition = tool.definition
        if (definition.needsConfirmation && !confirmed) {
            return AgentInvocationResult(
                tool = name,
                ok = false,
                errorCode = "confirmation_required",
                errorMessage = "Tool '$name' needs explicit confirmation before it runs.",
                confirmation =
                    AgentConfirmation(
                        tool = name,
                        // Raw arguments are kept for execution after confirmation.
                        argsJson = trimmed.ifEmpty { "{}" },
                        displayArgsJson =
                            AgentSanitizer.sanitizeArgsJson(trimmed.ifEmpty { "{}" }, allowedUrls),
                        reason = definition.confirmationReason ?: "This tool can change network state.",
                    ),
            )
        }

        return try {
            val execution = tool.execute(args)
            if (execution.ok) {
                val output = execution.output ?: JsonObject(emptyMap())
                AgentInvocationResult(
                    tool = name,
                    ok = true,
                    output = AgentSanitizer.sanitizeElement(output, allowedUrls) as JsonObject,
                )
            } else {
                AgentInvocationResult(
                    tool = name,
                    ok = false,
                    output =
                        execution.output?.let {
                            AgentSanitizer.sanitizeElement(it, allowedUrls) as JsonObject
                        },
                    errorCode = execution.errorCode ?: "tool_failed",
                    errorMessage =
                        AgentSanitizer.sanitizeText(
                            execution.errorMessage ?: "Tool failed.",
                            allowedUrls,
                        ),
                )
            }
        } catch (t: Throwable) {
            failure(
                name,
                "internal_error",
                AgentSanitizer.sanitizeText(t.message ?: t.toString(), allowedUrls),
            )
        }
    }

    private fun failure(
        name: String,
        code: String,
        message: String,
    ): AgentInvocationResult =
        AgentInvocationResult(
            tool = name,
            ok = false,
            errorCode = code,
            errorMessage = message,
        )
}

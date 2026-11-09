package io.nekohasekai.sfa.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Name-based runtime tool dispatch. Validation happens before execution, and
 * tools marked AGENT_ONLY / ALWAYS never run for the Agent until the
 * conversation layer passes humanApproved = true.
 */
class AgentToolRegistry(
    tools: List<AgentTool>,
) : AgentToolExecutor {
    private val toolsByName = tools.associateBy { it.definition.name }
    private val activeCancellations = CopyOnWriteArrayList<ToolCancellationImpl>()

    override fun definitions(): List<AgentToolDefinition> = toolsByName.values.map { it.definition }

    override suspend fun invokeUserCommand(
        name: String,
        argsJson: String,
    ): AgentInvocationResult = invoke(name, argsJson, InvocationSource.COMMAND, humanApproved = false)

    override suspend fun confirmUserCommand(
        name: String,
        argsJson: String,
    ): AgentInvocationResult = invoke(name, argsJson, InvocationSource.COMMAND, humanApproved = true)

    override suspend fun invokeAgent(
        call: ToolCall,
        humanApproved: Boolean,
    ): AgentInvocationResult = invoke(call.name, call.argumentsJson, InvocationSource.AGENT, humanApproved)

    override fun cancelActive() {
        activeCancellations.forEach { it.cancel() }
    }

    private suspend fun invoke(
        name: String,
        argsJson: String,
        source: InvocationSource,
        humanApproved: Boolean,
    ): AgentInvocationResult {
        val tool =
            toolsByName[name]
                ?: return failure(
                    name,
                    "unknown_tool",
                    "Unknown tool. Registered tools: ${toolsByName.keys.joinToString()}.",
                )
        val definition = tool.definition
        val trimmed = argsJson.trim()
        val args =
            if (trimmed.isEmpty()) {
                JsonObject(emptyMap())
            } else {
                AgentJson.parseObject(trimmed)
                    ?: return failure(name, "invalid_arguments", "Arguments must be a JSON object.")
            }

        for (spec in definition.parameters) {
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
        val knownNames = definition.parameters.map { it.name }.toSet()
        val unknown = args.keys.firstOrNull { it !in knownNames }
        if (unknown != null) {
            return failure(name, "unknown_parameter", "Unknown parameter '$unknown'.")
        }

        val allowedUrls =
            definition.allowedUrlParameter
                ?.let { AgentJson.string(args, it) }
                ?.let { setOf(it) }
                .orEmpty()

        val confirmationRequired =
            when (definition.confirmationPolicy) {
                ConfirmationPolicy.NEVER -> false
                ConfirmationPolicy.AGENT_ONLY -> source == InvocationSource.AGENT && !humanApproved
                ConfirmationPolicy.ALWAYS -> !humanApproved
            }
        if (confirmationRequired) {
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

        val cancellation = ToolCancellationImpl()
        activeCancellations.add(cancellation)
        return try {
            val execution =
                withContext(Dispatchers.IO) {
                    tool.execute(args, cancellation)
                }
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failure(
                name,
                "internal_error",
                AgentSanitizer.sanitizeText(e.message ?: e.toString(), allowedUrls),
            )
        } finally {
            activeCancellations.remove(cancellation)
        }
    }

    private fun failure(
        name: String,
        code: String,
        message: String,
    ): AgentInvocationResult =
        AgentInvocationResult(
            tool = AgentSanitizer.sanitizeText(name),
            ok = false,
            errorCode = code,
            errorMessage = AgentSanitizer.sanitizeText(message),
        )
}

private class ToolCancellationImpl : ToolCancellation {
    @Volatile
    private var cancelled = false
    private val actions = CopyOnWriteArrayList<() -> Unit>()

    override fun isCancelled(): Boolean = cancelled

    override fun onCancel(action: () -> Unit) {
        if (cancelled) {
            runCatching { action() }
        } else {
            actions.add(action)
        }
    }

    fun cancel() {
        if (cancelled) return
        cancelled = true
        actions.forEach { runCatching { it() } }
        actions.clear()
    }
}

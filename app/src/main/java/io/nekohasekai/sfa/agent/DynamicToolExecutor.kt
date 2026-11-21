package io.nekohasekai.sfa.agent

import java.util.ArrayDeque

/**
 * Built-in tools stay fixed. Extras (MCP) are swapped in before a turn.
 * cancelActive still reaches a registry that started an in-flight call.
 */
class DynamicToolExecutor(
    private val builtin: List<AgentTool>,
    private val toolTimeoutMs: Long = 120_000,
) : AgentToolExecutor {
    private val lock = Any()
    private var current = AgentToolRegistry(builtin, toolTimeoutMs)
    private val previous = ArrayDeque<AgentToolRegistry>()
    private val active = mutableSetOf<AgentToolRegistry>()

    fun setExtras(extras: List<AgentTool>) {
        val builtinNames = builtin.map { it.definition.name }.toSet()
        val filtered =
            extras.filter { tool ->
                val name = tool.definition.name
                name !in builtinNames && name != "route_call" && name != "search_tools"
            }
        val next = AgentToolRegistry(this.builtin + filtered, toolTimeoutMs)
        synchronized(lock) {
            previous.addLast(current)
            while (previous.size > 3) previous.removeFirst()
            current = next
        }
    }

    override fun definitions(): List<AgentToolDefinition> = synchronized(lock) { current.definitions() }

    override fun cancelActive() {
        val registries = synchronized(lock) { listOf(current) + active + previous }
        registries.distinct().forEach { it.cancelActive() }
    }

    override suspend fun invokeUserCommand(
        name: String,
        argsJson: String,
    ): AgentInvocationResult =
        withActiveRegistry { it.invokeUserCommand(name, argsJson) }

    override suspend fun confirmUserCommand(
        name: String,
        argsJson: String,
    ): AgentInvocationResult =
        withActiveRegistry { it.confirmUserCommand(name, argsJson) }

    override suspend fun invokeAgent(
        call: ToolCall,
        humanApproved: Boolean,
    ): AgentInvocationResult =
        withActiveRegistry { it.invokeAgent(call, humanApproved) }

    private suspend fun <T> withActiveRegistry(block: suspend (AgentToolRegistry) -> T): T {
        val registry = registry()
        synchronized(lock) { active += registry }
        return try {
            block(registry)
        } finally {
            synchronized(lock) { active -= registry }
        }
    }

    private fun registry(): AgentToolRegistry = synchronized(lock) { current }
}

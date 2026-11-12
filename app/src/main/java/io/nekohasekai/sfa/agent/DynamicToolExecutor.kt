package io.nekohasekai.sfa.agent

import java.util.ArrayDeque

/**
 * Built-in tools stay fixed. Extras (MCP) are swapped in before a turn.
 * cancelActive still reaches a registry that started an in-flight call.
 */
class DynamicToolExecutor(
    private val builtin: List<AgentTool>,
) : AgentToolExecutor {
    private val lock = Any()
    private var current = AgentToolRegistry(builtin)
    private val previous = ArrayDeque<AgentToolRegistry>()

    fun setExtras(extras: List<AgentTool>) {
        val builtinNames = builtin.map { it.definition.name }.toSet()
        val filtered =
            extras.filter { tool ->
                val name = tool.definition.name
                name !in builtinNames && name != "route_call" && name != "search_tools"
            }
        val next = AgentToolRegistry(this.builtin + filtered)
        synchronized(lock) {
            previous.addLast(current)
            while (previous.size > 3) previous.removeFirst()
            current = next
        }
    }

    override fun definitions(): List<AgentToolDefinition> = synchronized(lock) { current.definitions() }

    override fun cancelActive() {
        val registries = synchronized(lock) { listOf(current) + previous }
        registries.forEach { it.cancelActive() }
    }

    override suspend fun invokeUserCommand(
        name: String,
        argsJson: String,
    ): AgentInvocationResult = registry().invokeUserCommand(name, argsJson)

    override suspend fun confirmUserCommand(
        name: String,
        argsJson: String,
    ): AgentInvocationResult = registry().confirmUserCommand(name, argsJson)

    override suspend fun invokeAgent(
        call: ToolCall,
        humanApproved: Boolean,
    ): AgentInvocationResult = registry().invokeAgent(call, humanApproved)

    private fun registry(): AgentToolRegistry = synchronized(lock) { current }
}

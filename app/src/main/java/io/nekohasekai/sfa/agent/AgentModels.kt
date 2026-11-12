package io.nekohasekai.sfa.agent

import kotlinx.serialization.json.JsonObject

/**
 * Stable tool metadata. The registry exposes the name, description and JSON
 * schema so the conversation layer (manual chips or a future model hook) can
 * decide what to call at runtime.
 */
enum class AgentPermission(
    val label: String,
) {
    READ_NETWORK_STATE("read_network_state"),
    NETWORK_PROBE("network_probe"),
    NETWORK_CHANGE("network_change"),
    DATA_EGRESS("data_egress"),
}

enum class ConfirmationPolicy {
    NEVER,
    AGENT_ONLY,
    ALWAYS,
}

enum class InvocationSource {
    COMMAND,
    AGENT,
}

interface ToolCancellation {
    fun isCancelled(): Boolean

    fun onCancel(action: () -> Unit)
}

enum class AgentParameterType(
    val schemaType: String,
) {
    STRING("string"),
}

data class AgentParameterSpec(
    val name: String,
    val type: AgentParameterType,
    val required: Boolean,
    val description: String,
)

data class AgentToolDefinition(
    val name: String,
    val description: String,
    val permission: AgentPermission,
    val parameters: List<AgentParameterSpec>,
    val confirmationPolicy: ConfirmationPolicy,
    val category: String,
    val aliases: List<String> = emptyList(),
    val core: Boolean = false,
    val confirmationReason: String? = null,
    val allowedUrlParameter: String? = null,
    val parametersSchemaOverride: JsonObject? = null,
    val passthroughArguments: Boolean = false,
) {
    val parametersSchema: JsonObject
        get() = parametersSchemaOverride ?: AgentJson.schema(parameters)
}

interface AgentToolExecutor {
    fun definitions(): List<AgentToolDefinition>

    fun cancelActive()

    suspend fun invokeUserCommand(
        name: String,
        argsJson: String,
    ): AgentInvocationResult

    suspend fun confirmUserCommand(
        name: String,
        argsJson: String,
    ): AgentInvocationResult

    suspend fun invokeAgent(
        call: ToolCall,
        humanApproved: Boolean,
    ): AgentInvocationResult
}

interface TailscaleStatusSource {
    suspend fun status(cancellation: ToolCancellation? = null): AgentTailscaleSnapshot
}

/**
 * Structured Tailscale view for the agent. It deliberately has no account email
 * or peer IP fields; those stay in the user-facing `/tailscale` output only.
 */
data class AgentTailscaleSnapshot(
    val endpoints: List<AgentTailscaleEndpoint>,
)

data class AgentTailscaleEndpoint(
    val tag: String,
    val state: String,
    val self: AgentTailscalePeer? = null,
    val peers: List<AgentTailscalePeer> = emptyList(),
)

data class AgentTailscalePeer(
    val name: String,
    val online: Boolean,
    val exitNode: Boolean = false,
    val exitNodeOption: Boolean = false,
    val lastSeen: String? = null,
)

interface TelemetryUploadPort {
    suspend fun upload(cancellation: ToolCancellation? = null): String
}

/**
 * Plain snapshot types. Keeping them independent of libbox lets the tool layer
 * stay JVM-testable; the Android-side adapter fills them from CommandClient.
 */
data class AgentNode(
    val tag: String,
    val type: String,
    val displayType: String,
    val selected: Boolean,
    val urlTestDelayMs: Int?,
)

data class AgentGroup(
    val tag: String,
    val type: String,
    val displayType: String,
    val selectable: Boolean,
    val selected: String,
    val items: List<AgentNode>,
)

data class NodeSnapshot(
    val coreRunning: Boolean,
    val remoteControl: Boolean,
    val groups: List<AgentGroup> = emptyList(),
    val outbounds: List<AgentNode> = emptyList(),
    val error: String? = null,
)

/**
 * Elevated to an interface so tests can feed fake command-interface data.
 * The production implementation only reads the cached CommandClient state.
 */
interface NodeSnapshotSource {
    fun snapshot(): NodeSnapshot
}

/**
 * Device-local signals (wifi/cellular, battery optimisation, ...). These are
 * not part of the Remote Control command interface; callers must label them
 * as local observations.
 */
data class DeviceSnapshot(
    val networkType: String = "unknown",
    val localTime: String? = null,
    val metered: Boolean? = null,
    val vpnActive: Boolean? = null,
    val serviceMode: String? = null,
    val batteryOptimizationIgnored: Boolean? = null,
    val powerSave: Boolean? = null,
    val doze: Boolean? = null,
    val error: String? = null,
)

interface DeviceSnapshotSource {
    fun snapshot(): DeviceSnapshot
}

data class ProbeOutcome(
    val statusCode: Int? = null,
    val latencyMs: Long? = null,
    val error: String? = null,
)

/**
 * Outbound-bound HTTP probe. Production uses the existing command client to
 * select the node and the existing libbox HTTP client; tests use a mock.
 */
interface ProbePort {
    fun probe(
        outbound: String,
        url: String,
        cancellation: ToolCancellation? = null,
    ): ProbeOutcome
}

interface NodeSelectionPort {
    suspend fun select(
        group: String,
        outbound: String,
    ): NodeSelectionResult
}

data class NodeSelectionResult(
    val ok: Boolean,
    val group: String,
    val selected: String,
    val previous: String? = null,
    val error: String? = null,
)

data class AgentConfirmation(
    val tool: String,
    val argsJson: String,
    val displayArgsJson: String,
    val reason: String,
)

data class AgentInvocationResult(
    val tool: String,
    val ok: Boolean,
    val output: JsonObject? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val confirmation: AgentConfirmation? = null,
    val degraded: Boolean = false,
)

data class ToolExecution(
    val output: JsonObject? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val degraded: Boolean = false,
) {
    val ok: Boolean
        get() = errorCode == null
}

interface AgentTool {
    val definition: AgentToolDefinition

    suspend fun execute(
        args: JsonObject,
        cancellation: ToolCancellation,
    ): ToolExecution
}

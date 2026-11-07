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
    val needsConfirmation: Boolean = false,
    val confirmationReason: String? = null,
) {
    val parametersSchema: JsonObject
        get() = AgentJson.schema(parameters)
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
    ): ProbeOutcome
}

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
)

data class ToolExecution(
    val output: JsonObject? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
) {
    val ok: Boolean
        get() = errorCode == null
}

interface AgentTool {
    val definition: AgentToolDefinition

    fun execute(args: JsonObject): ToolExecution
}

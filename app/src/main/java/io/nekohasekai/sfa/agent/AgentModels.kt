package io.nekohasekai.sfa.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.io.File

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
    READ_FILES("read_files"),
    WRITE_FILES("write_files"),
    FILE_TRANSFER("file_transfer"),
    DATA_EGRESS("data_egress"),
    USER_INTERACTION("user_interaction"),
    READ_APP_STATE("read_app_state"),
    UTILITY("utility"),
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
    INTEGER("integer"),
    BOOLEAN("boolean"),
    STRING_ARRAY("array"),
}

data class AgentParameterSpec(
    val name: String,
    val type: AgentParameterType,
    val required: Boolean,
    val description: String,
    /** Some text parameters legitimately accept an empty string (for example, a path for the root). */
    val allowBlank: Boolean = false,
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
    /** MCP server display name; null for built-in tools. */
    val serverName: String? = null,
    /** MCP tool prefix chosen by the user; null for built-in tools. */
    val namespace: String? = null,
    /** Per-tool execution budget; null uses the registry default. */
    val timeoutMs: Long? = null,
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

/** Current conversation and API trace, rendered as Markdown for diagnostics. */
data class TraceExport(
    val text: String,
    val count: Int,
    val firstTimestamp: Long?,
    val lastTimestamp: Long?,
    val availableFrom: Long?,
    val missingBefore: Boolean,
)

interface DiagnosticsSource {
    fun conversationMarkdown(): String

    fun traceMarkdown(
        lastEntries: Int? = null,
        since: Long? = null,
    ): TraceExport
}

data class TaildropSendResult(
    val peer: String,
    val files: List<String>,
    val ok: Boolean,
    val message: String? = null,
)

interface ReleaseNotesSource {
    suspend fun current(): io.nekohasekai.sfa.update.ReleaseNotes
}

interface TaildropPort {
    suspend fun send(
        peer: String,
        files: List<File>,
        cancellation: ToolCancellation? = null,
    ): TaildropSendResult

    suspend fun sendSelected(
        peer: String,
        ids: List<String>,
        cancellation: ToolCancellation? = null,
    ): TaildropSendResult
}

data class SelectedFileInfo(
    val id: String,
    val name: String,
    val size: Long,
    val mimeType: String? = null,
    /** False when the content URI does not expose a reliable size and cannot back a VFS entry. */
    val inboxReady: Boolean = true,
)

data class SelectedFileRead(
    val id: String,
    val name: String,
    val size: Long,
    val text: String,
    val truncated: Boolean,
)

data class FileSelectionResult(
    val files: List<SelectedFileInfo> = emptyList(),
    val cancelled: Boolean = false,
    val message: String? = null,
)

data class SelectedImage(
    val id: String,
    val name: String,
    val mimeType: String,
    val bytes: ByteArray,
)

/** Persistent reference to an image stored under the app's private Conductor directory. */
@Serializable
data class AgentImageRef(
    val id: String,
    val name: String,
    val mimeType: String = "image/jpeg",
    val width: Int = 0,
    val height: Int = 0,
    val size: Long = 0,
)

/** Bytes loaded for the next model request; never persisted in the session JSON. */
data class AgentImageData(
    val bytes: ByteArray,
    val mimeType: String,
)

interface FileSelectionPort {
    suspend fun request(
        reason: String,
        multiple: Boolean,
        mimeTypes: List<String>?,
        cancellation: ToolCancellation? = null,
    ): FileSelectionResult

    suspend fun read(
        id: String,
        maxBytes: Int,
    ): SelectedFileRead

    /** Binary read for image analysis; only the vision tool should use it. */
    suspend fun readBytes(
        id: String,
        maxBytes: Int,
    ): SelectedImage = throw UnsupportedOperationException("Binary selection is not supported.")
}

/** One vision answer, retaining the provider's completion signal for the caller. */
data class VisionAnalysis(
    val text: String,
    val finishReason: String? = null,
    val truncated: Boolean = false,
    val reasoningFallback: Boolean = false,
)

/** Sends one selected image to the configured model provider and returns text. */
interface VisionPort {
    suspend fun analyze(
        image: ByteArray,
        mimeType: String,
        question: String,
    ): VisionAnalysis
}

/**
 * A small set of answers the model offers as a card instead of a plain-text
 * question. Labels are both the UI text and the value returned to the model.
 */
data class AskUserRequest(
    val question: String,
    val header: String? = null,
    val options: List<String>,
    val multiple: Boolean = false,
)

data class AskUserResult(
    val selected: List<String> = emptyList(),
    val cancelled: Boolean = false,
    val message: String? = null,
    /** Machine-readable cancel reason: dismissed, timeout, stopped or busy. */
    val reason: String? = null,
)

interface AskUserPort {
    suspend fun ask(
        request: AskUserRequest,
        cancellation: ToolCancellation? = null,
    ): AskUserResult
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
    /** True when the CommandClient is connected to the core, not a process-liveness signal. */
    val commandClientConnected: Boolean,
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
    val serviceActive: Boolean? = null,
    val vpnServiceActive: Boolean? = null,
    val serviceMode: String? = null,
    val batteryOptimizationExempt: Boolean? = null,
    val batterySaver: Boolean? = null,
    val dozeMode: Boolean? = null,
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

data class HttpFetchOutcome(
    val statusCode: Int? = null,
    val statusText: String? = null,
    val latencyMs: Long? = null,
    val content: String? = null,
    val contentType: String? = null,
    val headers: Map<String, List<String>> = emptyMap(),
    val bytes: Int = 0,
    val truncated: Boolean = false,
    val binary: Boolean = false,
    val error: String? = null,
)

data class HttpDownloadOutcome(
    val statusCode: Int? = null,
    val statusText: String? = null,
    val latencyMs: Long? = null,
    val contentType: String? = null,
    val bytes: ByteArray = ByteArray(0),
    val truncated: Boolean = false,
    val error: String? = null,
)

/**
 * Standard HTTP fetch over the device's current network. It deliberately has
 * no outbound parameter: node-bound probes stay in ProbePort, and the fetch
 * tool must not change the running core's global selection.
 */
interface HttpFetchPort {
    fun fetch(
        url: String,
        method: String,
        headers: List<Pair<String, String>>,
        body: String?,
        maxBytes: Int,
        cancellation: ToolCancellation? = null,
    ): HttpFetchOutcome

    fun download(
        url: String,
        method: String,
        headers: List<Pair<String, String>>,
        body: String?,
        maxBytes: Int,
        cancellation: ToolCancellation? = null,
    ): HttpDownloadOutcome = HttpDownloadOutcome(error = "download is not supported by this HttpFetchPort")
}

data class WebSearchSource(
    val url: String,
    val title: String? = null,
    val pageAge: String? = null,
    val citedText: String? = null,
)

data class WebSearchOutcome(
    val answer: String? = null,
    val sources: List<WebSearchSource> = emptyList(),
    val searchQueries: List<String> = emptyList(),
    val searchErrors: List<String> = emptyList(),
    val latencyMs: Long? = null,
    val error: String? = null,
)

/**
 * One web search through a server-side search tool. Implementations own the
 * provider request and must not expose their API key to the model.
 */
interface WebSearchPort {
    suspend fun search(
        query: String,
        maxResults: Int,
        cancellation: ToolCancellation? = null,
    ): WebSearchOutcome
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
    /** Source URLs this tool intentionally returns to the model. */
    val allowedUrls: Set<String> = emptySet(),
) {
    val ok: Boolean
        get() = errorCode == null
}

interface AgentTool {
    val definition: AgentToolDefinition

    /**
     * False hides the tool from definitions, search and dispatch. Use this for
     * provider/build capabilities that must not be advertised at all.
     */
    fun isAvailable(): Boolean = true

    suspend fun execute(
        args: JsonObject,
        cancellation: ToolCancellation,
    ): ToolExecution
}

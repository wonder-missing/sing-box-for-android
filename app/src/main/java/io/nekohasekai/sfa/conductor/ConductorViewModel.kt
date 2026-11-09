package io.nekohasekai.sfa.conductor

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.nekohasekai.libbox.OutboundGroup
import io.nekohasekai.libbox.OutboundGroupItem
import io.nekohasekai.libbox.TailscalePeer
import io.nekohasekai.libbox.TailscaleStatusHandler
import io.nekohasekai.libbox.TailscaleStatusUpdate
import io.nekohasekai.sfa.agent.AgentApiKeyProviderFactory
import io.nekohasekai.sfa.agent.AgentConfig
import io.nekohasekai.sfa.agent.AgentError
import io.nekohasekai.sfa.agent.AgentEvent
import io.nekohasekai.sfa.agent.AgentInvocationResult
import io.nekohasekai.sfa.agent.AgentJson
import io.nekohasekai.sfa.agent.AgentMessage
import io.nekohasekai.sfa.agent.AgentRuntime
import io.nekohasekai.sfa.agent.AgentSanitizer
import io.nekohasekai.sfa.agent.AgentToolRegistry
import io.nekohasekai.sfa.agent.AgentTools
import io.nekohasekai.sfa.agent.AgentTurnErrorCode
import io.nekohasekai.sfa.agent.AndroidDeviceSnapshotSource
import io.nekohasekai.sfa.agent.CommandNodeSnapshotSource
import io.nekohasekai.sfa.agent.CommandNodeState
import io.nekohasekai.sfa.agent.ConfirmationRequest
import io.nekohasekai.sfa.agent.DeepSeekModelClient
import io.nekohasekai.sfa.agent.DefaultAgentInputGuard
import io.nekohasekai.sfa.agent.InputGuardResult
import io.nekohasekai.sfa.agent.LibboxProbePort
import io.nekohasekai.sfa.agent.TailscaleStatusSource
import io.nekohasekai.sfa.agent.TelemetryUploadPort
import io.nekohasekai.sfa.agent.ToolCallView
import io.nekohasekai.sfa.agent.ToolResultView
import io.nekohasekai.sfa.agent.ToolStatus
import io.nekohasekai.sfa.agent.TurnOutcome
import io.nekohasekai.sfa.agent.TurnResult
import io.nekohasekai.sfa.agent.buildCommandNodeState
import io.nekohasekai.sfa.constant.ServiceMode
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.telemetry.TelemetryRuntime
import io.nekohasekai.sfa.utils.CommandClient
import io.nekohasekai.sfa.utils.CommandTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicLong

class ConductorViewModel(
    application: Application,
) : AndroidViewModel(application),
    CommandClient.Handler {
    data class UiState(
        val items: List<ConductorItem> = emptyList(),
        val isRunning: Boolean = false,
        val coreConnected: Boolean = false,
        val agentSupported: Boolean = true,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val stateLock = Any()

    @Volatile
    private var nodeState = CommandNodeState()

    private var rawGroups: List<OutboundGroup> = emptyList()
    private var rawOutbounds: List<OutboundGroupItem> = emptyList()

    @Volatile
    private var localServiceStarted: Boolean? = null

    @Volatile
    private var remoteControl: Boolean = false

    private var wantConnected = false
    private val commandConfirmationIds = AtomicLong(0)

    private val apiKeyProvider = AgentApiKeyProviderFactory.create(application)

    private val commandClient =
        CommandClient(
            viewModelScope,
            listOf(CommandClient.ConnectionType.Groups, CommandClient.ConnectionType.Outbounds),
            this,
        )

    private val nodeSource =
        CommandNodeSnapshotSource(
            state = { nodeState },
            remoteControl = { CommandTarget.isRemote },
        )

    private val deviceSource =
        AndroidDeviceSnapshotSource(
            application,
            serviceMode = { Settings.serviceMode },
            serviceStarted = { localServiceStarted },
        )

    private val probePort =
        LibboxProbePort(
            nodeSource = nodeSource,
            appTrafficUsesVpn = { appTrafficUsesVpn() },
        )

    private val tailscaleSource =
        object : TailscaleStatusSource {
            override suspend fun status(): String = tailscaleText()
        }

    private val telemetryPort =
        object : TelemetryUploadPort {
            override suspend fun upload(): String = TelemetryRuntime.uploadNow(application)
        }

    private val registry =
        AgentToolRegistry(
            AgentTools(
                nodeSource = nodeSource,
                deviceSource = deviceSource,
                probePort = probePort,
                tailscaleStatusSource = tailscaleSource,
                telemetryUploadPort = telemetryPort,
            ).all,
        )

    private val runtime =
        AgentRuntime(
            inputGuard = DefaultAgentInputGuard,
            model = DeepSeekModelClient(),
            tools = registry,
            apiKeys = apiKeyProvider,
            config = AgentConfig(),
        )

    private var transcript: List<AgentMessage> = emptyList()
    private var agentJob: Job? = null

    @Volatile
    private var pendingAgentConfirmation: CompletableDeferred<Boolean>? = null

    private var pendingCommandConfirmation: Pair<String, String>? = null

    init {
        _uiState.update { it.copy(agentSupported = apiKeyProvider.supported) }
    }

    fun onServiceState(
        serviceStatus: Status,
        remoteControl: Boolean,
        remoteConnected: Boolean,
    ) {
        this.remoteControl = remoteControl
        localServiceStarted = !remoteControl && serviceStatus == Status.Started
        val shouldConnect = if (remoteControl) remoteConnected else serviceStatus == Status.Started
        val previous =
            synchronized(stateLock) {
                val previous = wantConnected
                wantConnected = shouldConnect
                previous
            }
        if (previous == shouldConnect) return
        if (shouldConnect) {
            append(ConductorItem.Notice("core", "Connecting to the core…"))
            commandClient.connect()
        } else {
            commandClient.disconnect()
            publishNodeState(CommandNodeState())
        }
    }

    fun submit(raw: String) {
        val text = raw.trim()
        if (text.isEmpty()) return
        if (_uiState.value.isRunning) {
            append(ConductorItem.Notice("busy", "Wait for the current command to finish."))
            return
        }

        if (!text.startsWith("/")) {
            if (DefaultAgentInputGuard.check(text) is InputGuardResult.Blocked) {
                append(
                    ConductorItem.Notice(
                        "conductor",
                        "This message may contain a subscription link, token or log excerpt. " +
                            "It was not sent. Remove the sensitive part and try again.",
                    ),
                )
                return
            }
            append(ConductorItem.User(text))
            submitAgent(text)
            return
        }

        append(ConductorItem.User(text))

        val parsed =
            ConductorCommands.parse(text)
                ?: run {
                    append(ConductorItem.Error("command", ConductorCommands.unknownMessage(text)))
                    return
                }
        val command =
            parsed.command
                ?: run {
                    append(ConductorItem.Error("command", ConductorCommands.unknownMessage(text)))
                    return
                }

        when (command.action) {
            ConductorCommandKind.HELP -> {
                val topic = parsed.args.firstOrNull()?.let(ConductorCommands::find)
                append(ConductorItem.Notice("help", ConductorCommands.helpText(topic)))
            }

            ConductorCommandKind.CAPABILITIES ->
                append(
                    ConductorItem.Notice(
                        "capabilities",
                        ConductorCommands.capabilitiesText(registry.definitions()),
                    ),
                )

            ConductorCommandKind.CLEAR -> {
                if (_uiState.value.isRunning) {
                    append(ConductorItem.Notice("clear", "Wait for the current command to finish."))
                } else {
                    updateUi { copy(items = emptyList()) }
                    transcript = emptyList()
                    pendingCommandConfirmation = null
                }
            }

            ConductorCommandKind.NODES -> runTool("list_nodes")
            ConductorCommandKind.NETWORK -> runTool("network_snapshot")
            ConductorCommandKind.TAILSCALE -> runTailscale()
            ConductorCommandKind.TELEMETRY_UPLOAD -> runTelemetryUpload()
            ConductorCommandKind.PROBE -> runProbe(parsed.args)
        }
    }

    fun confirmAgent() {
        val pending = pendingAgentConfirmation ?: return
        removeConfirmationItems()
        pending.complete(true)
    }

    fun cancelAgent() {
        val pending = pendingAgentConfirmation ?: return
        removeConfirmationItems()
        pending.complete(false)
    }

    fun confirmConfirmation() {
        if (pendingAgentConfirmation != null) {
            confirmAgent()
        } else {
            confirmCommand()
        }
    }

    fun cancelConfirmation() {
        if (pendingAgentConfirmation != null) {
            cancelAgent()
        } else {
            cancelCommand()
        }
    }

    fun confirmCommand() {
        val pending = pendingCommandConfirmation ?: return
        pendingCommandConfirmation = null
        removeConfirmationItems()
        invokeCommandTool(pending.first, pending.second, pending.first)
    }

    fun cancelCommand() {
        pendingCommandConfirmation = null
        removeConfirmationItems()
        append(ConductorItem.Notice("command", "Cancelled."))
    }

    fun disconnect() {
        synchronized(stateLock) {
            wantConnected = false
        }
        commandClient.disconnect()
        publishNodeState(CommandNodeState())
        runtime.cancel()
        agentJob?.cancel()
    }

    override fun onCleared() {
        runtime.cancel()
        agentJob?.cancel()
        commandClient.disconnect()
        super.onCleared()
    }

    private fun submitAgent(text: String) {
        if (!apiKeyProvider.supported) {
            append(ConductorItem.Error("agent", "The Conductor agent requires Android 7.0 or newer."))
            return
        }
        updateUi { copy(isRunning = true) }
        agentJob =
            viewModelScope.launch {
                val result =
                    try {
                        runtime.runTurn(
                            userText = text,
                            history = transcript,
                            deviceSummary = deviceSummary(),
                            onEvent = ::handleAgentEvent,
                            confirm = { awaitAgentConfirmation() },
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        TurnResult(
                            messages = emptyList(),
                            outcome =
                                TurnOutcome.Failed(
                                    AgentError(
                                        AgentTurnErrorCode.UNKNOWN,
                                        e.message ?: "Agent failed.",
                                    ),
                                ),
                        )
                    }
                withContext(Dispatchers.Main) {
                    transcript = transcript + result.messages
                    updateUi { copy(isRunning = false) }
                    pendingAgentConfirmation = null
                    when (val outcome = result.outcome) {
                        is TurnOutcome.Failed ->
                            append(
                                ConductorItem.Error(
                                    "agent",
                                    outcome.error.message,
                                    outcome.error.retryable,
                                ),
                            )

                        is TurnOutcome.Cancelled -> Unit
                        is TurnOutcome.Completed -> Unit
                    }
                }
            }
    }

    private fun handleAgentEvent(event: AgentEvent) {
        when (event) {
            is AgentEvent.AssistantMessage -> append(ConductorItem.Assistant(event.text))
            is AgentEvent.ToolStarted -> append(ConductorItem.Tool(event.call, ToolStatus.RUNNING))
            is AgentEvent.ToolFinished -> updateToolResult(event.result)
            is AgentEvent.ConfirmationRequested -> {
                pendingAgentConfirmation = CompletableDeferred()
                append(ConductorItem.Confirmation(event.request))
            }

            is AgentEvent.Notice -> append(ConductorItem.Notice("conductor", event.text))
            is AgentEvent.AssistantDelta -> Unit
            is AgentEvent.StateChanged -> Unit
            is AgentEvent.TurnStarted -> Unit
            is AgentEvent.TurnFinished -> Unit
        }
    }

    private suspend fun awaitAgentConfirmation(): Boolean {
        val pending = pendingAgentConfirmation ?: return false
        return try {
            pending.await()
        } finally {
            if (pendingAgentConfirmation === pending) {
                pendingAgentConfirmation = null
            }
        }
    }

    private fun updateToolResult(result: ToolResultView) {
        val status =
            when {
                result.ok -> ToolStatus.SUCCEEDED
                result.errorCode == "cancelled" -> ToolStatus.CANCELLED
                else -> ToolStatus.FAILED
            }
        val found =
            _uiState.value.items.any {
                it is ConductorItem.Tool && it.call.id == result.callId
            }
        if (!found) {
            append(
                ConductorItem.Tool(
                    ToolCallView(result.callId, result.name, ""),
                    status,
                    result,
                ),
            )
            return
        }
        updateUi {
            copy(
                items =
                    items.map { item ->
                        if (item is ConductorItem.Tool && item.call.id == result.callId) {
                            item.copy(status = status, result = result)
                        } else {
                            item
                        }
                    },
            )
        }
    }

    private fun runTool(
        name: String,
        argsJson: String = "{}",
        title: String = name,
    ) {
        if (_uiState.value.isRunning) {
            append(ConductorItem.Notice("busy", "Wait for the current command to finish."))
            return
        }
        updateUi { copy(isRunning = true) }
        viewModelScope.launch {
            val result = invokeRegistryUserCommand(name, argsJson)
            withContext(Dispatchers.Main) {
                updateUi { copy(isRunning = false) }
                if (result.confirmation != null) {
                    showCommandConfirmation(name, result.confirmation!!)
                } else {
                    appendToolResult(title, result)
                }
            }
        }
    }

    private fun invokeCommandTool(
        name: String,
        argsJson: String,
        title: String,
    ) {
        updateUi { copy(isRunning = true) }
        viewModelScope.launch {
            val result = invokeRegistryConfirmCommand(name, argsJson)
            withContext(Dispatchers.Main) {
                updateUi { copy(isRunning = false) }
                appendToolResult(title, result)
            }
        }
    }

    private suspend fun invokeRegistryUserCommand(
        name: String,
        argsJson: String,
    ): AgentInvocationResult =
        try {
            registry.invokeUserCommand(name, argsJson)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AgentInvocationResult(
                tool = name,
                ok = false,
                errorCode = "internal_error",
                errorMessage = e.message ?: "Tool failed.",
            )
        }

    private suspend fun invokeRegistryConfirmCommand(
        name: String,
        argsJson: String,
    ): AgentInvocationResult =
        try {
            registry.confirmUserCommand(name, argsJson)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AgentInvocationResult(
                tool = name,
                ok = false,
                errorCode = "internal_error",
                errorMessage = e.message ?: "Tool failed.",
            )
        }

    private fun showCommandConfirmation(
        name: String,
        confirmation: io.nekohasekai.sfa.agent.AgentConfirmation,
    ) {
        pendingCommandConfirmation = name to confirmation.argsJson
        append(
            ConductorItem.Confirmation(
                ConfirmationRequest(
                    id = commandConfirmationIds.incrementAndGet(),
                    toolName = name,
                    displayArgsJson = confirmation.displayArgsJson,
                    reason = confirmation.reason,
                ),
            ),
        )
    }

    private fun appendToolResult(
        title: String,
        result: AgentInvocationResult,
    ) {
        if (result.ok) {
            append(
                ConductorItem.Notice(
                    title,
                    AgentJson.pretty(result.output ?: JsonObject(emptyMap())),
                ),
            )
        } else {
            append(
                ConductorItem.Error(
                    title,
                    AgentSanitizer.sanitizeText(result.errorMessage ?: "Tool failed."),
                ),
            )
        }
    }

    private fun runProbe(args: List<String>) {
        if (args.size < 2) {
            append(
                ConductorItem.Notice(
                    "probe",
                    "Usage: /probe <outbound> <url>\nUse /nodes to list outbound tags.",
                ),
            )
            return
        }
        val outbound = args.first()
        val url = args.drop(1).joinToString(" ")
        val argsJson =
            buildJsonObject {
                put("outbound", JsonPrimitive(outbound))
                put("url", JsonPrimitive(url))
            }.toString()
        runTool("probe_via_node", argsJson, "probe")
    }

    private fun runTailscale() {
        updateUi { copy(isRunning = true) }
        append(ConductorItem.Notice("tailscale", "Reading Tailscale status…"))
        viewModelScope.launch {
            val text = runCatching { tailscaleText() }.getOrElse { "Tailscale unavailable: ${it.message ?: it}" }
            withContext(Dispatchers.Main) {
                updateUi { copy(isRunning = false) }
                append(ConductorItem.Notice("tailscale", text))
            }
        }
    }

    private fun runTelemetryUpload() {
        updateUi { copy(isRunning = true) }
        append(ConductorItem.Notice("telemetry-upload", "Uploading pending telemetry…"))
        viewModelScope.launch {
            val result =
                runCatching { TelemetryRuntime.uploadNow(getApplication<Application>()) }
                    .getOrElse { it.message ?: it.toString() }
            withContext(Dispatchers.Main) {
                updateUi { copy(isRunning = false) }
                append(ConductorItem.Notice("telemetry-upload", result))
            }
        }
    }

    private suspend fun tailscaleText(): String {
        val deferred = CompletableDeferred<String>()
        val subscription =
            CommandTarget.standaloneClient().subscribeTailscaleStatus(
                object : TailscaleStatusHandler {
                    override fun onStatusUpdate(status: TailscaleStatusUpdate) {
                        if (!deferred.isCompleted) {
                            deferred.complete(formatTailscale(status))
                        }
                    }

                    override fun onError(message: String) {
                        if (!deferred.isCompleted) {
                            deferred.complete("Tailscale error: $message")
                        }
                    }
                },
            )
        return try {
            withTimeoutOrNull(5_000) { deferred.await() } ?: "Timed out waiting for Tailscale status."
        } finally {
            runCatching { subscription.close() }
        }
    }

    private fun formatTailscale(update: TailscaleStatusUpdate): String =
        buildString {
            var endpointCount = 0
            val endpoints = update.endpoints()
            while (endpoints.hasNext()) {
                endpointCount += 1
                val endpoint = endpoints.next()
                appendLine("Endpoint ${endpoint.endpointTag} [${endpoint.backendState}]")
                val self = endpoint.getSelf()
                if (self != null) {
                    appendLine("  self: ${peerName(self)} ${if (self.online) "online" else "offline"}")
                }
                val groups = endpoint.userGroups()
                while (groups.hasNext()) {
                    val group = groups.next()
                    appendLine("  ${group.loginName.ifBlank { group.displayName }}")
                    val peers = group.peers()
                    while (peers.hasNext()) {
                        val peer = peers.next()
                        if (self != null && peer.stableID == self.stableID) continue
                        val flags =
                            buildList {
                                add(if (peer.online) "online" else "offline")
                                if (peer.exitNode) add("exit-node")
                                if (peer.exitNodeOption) add("exit-node-option")
                            }
                        appendLine("    - ${peerName(peer)} ${flags.joinToString(", ")} ${peerIps(peer)}".trimEnd())
                    }
                }
            }
            if (endpointCount == 0) {
                append("No Tailscale endpoints. Start Tailscale in a profile first.")
            }
        }

    private fun peerName(peer: TailscalePeer): String {
        val dns = peer.getDNSName()
        return dns.substringBefore(".").ifEmpty { peer.hostName }
    }

    private fun peerIps(peer: TailscalePeer): String {
        val ips = mutableListOf<String>()
        val iterator = peer.tailscaleIPs()
        while (iterator.hasNext()) {
            ips.add(iterator.next())
        }
        return ips.joinToString(", ")
    }

    private fun deviceSummary(): String {
        val service = if (localServiceStarted == true) "started" else "stopped"
        return "service=$service; remoteControl=$remoteControl; coreConnected=${_uiState.value.coreConnected}"
    }

    private fun append(item: ConductorItem) {
        updateUi { copy(items = (items + item).takeLast(MAX_ITEMS)) }
    }

    private fun removeConfirmationItems() {
        updateUi { copy(items = items.filterNot { it is ConductorItem.Confirmation }) }
    }

    private fun updateUi(transform: UiState.() -> UiState) {
        _uiState.update { it.transform() }
    }

    private fun publishNodeState(state: CommandNodeState) {
        synchronized(stateLock) {
            nodeState = state
        }
        updateUi { copy(coreConnected = state.connected) }
    }

    private fun rebuildNodeState(
        connected: Boolean,
        error: String? = null,
        reset: Boolean = false,
    ) {
        val state =
            synchronized(stateLock) {
                if (reset) {
                    rawGroups = emptyList()
                    rawOutbounds = emptyList()
                }
                buildCommandNodeState(connected, rawGroups, rawOutbounds, error).also {
                    nodeState = it
                }
            }
        updateUi { copy(coreConnected = state.connected) }
    }

    override fun onConnected() {
        rebuildNodeState(connected = true)
    }

    override fun onDisconnected() {
        rebuildNodeState(connected = false, reset = true)
    }

    override fun onConnectionError(message: String) {
        rebuildNodeState(connected = false, error = message, reset = true)
    }

    override fun updateGroups(newGroups: MutableList<OutboundGroup>) {
        synchronized(stateLock) {
            rawGroups = newGroups.toList()
        }
        rebuildNodeState(connected = true)
    }

    override fun updateOutbounds(outbounds: List<OutboundGroupItem>) {
        synchronized(stateLock) {
            rawOutbounds = outbounds.toList()
        }
        rebuildNodeState(connected = true)
    }

    private fun appTrafficUsesVpn(): Boolean? =
        runCatching {
            localServiceStarted == true && Settings.serviceMode == ServiceMode.VPN
        }.getOrNull()

    companion object {
        private const val MAX_ITEMS = 200
    }
}

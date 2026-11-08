package io.nekohasekai.sfa.conductor

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.nekohasekai.libbox.OutboundGroup
import io.nekohasekai.libbox.OutboundGroupItem
import io.nekohasekai.libbox.TailscalePeer
import io.nekohasekai.libbox.TailscaleStatusHandler
import io.nekohasekai.libbox.TailscaleStatusUpdate
import io.nekohasekai.sfa.agent.AgentJson
import io.nekohasekai.sfa.agent.AgentToolRegistry
import io.nekohasekai.sfa.agent.AgentTools
import io.nekohasekai.sfa.agent.AndroidDeviceSnapshotSource
import io.nekohasekai.sfa.agent.CommandNodeSnapshotSource
import io.nekohasekai.sfa.agent.CommandNodeState
import io.nekohasekai.sfa.agent.LibboxProbePort
import io.nekohasekai.sfa.agent.buildCommandNodeState
import io.nekohasekai.sfa.constant.ServiceMode
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.telemetry.TelemetryRuntime
import io.nekohasekai.sfa.utils.CommandClient
import io.nekohasekai.sfa.utils.CommandTarget
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
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
import java.util.concurrent.atomic.AtomicLong

class ConductorViewModel(
    application: Application,
) : AndroidViewModel(application),
    CommandClient.Handler {
    data class UiState(
        val messages: List<ConductorMessage> = emptyList(),
        val isRunning: Boolean = false,
        val coreConnected: Boolean = false,
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

    private var wantConnected = false
    private val confirmationIds = AtomicLong(0)

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

    private val registry =
        AgentToolRegistry(
            AgentTools(
                nodeSource = nodeSource,
                deviceSource = deviceSource,
                probePort = probePort,
            ).all,
        )

    fun onServiceState(
        serviceStatus: Status,
        remoteControl: Boolean,
        remoteConnected: Boolean,
    ) {
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
            append(ConductorMessageKind.SYSTEM, "core", "Connecting to the core…")
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
            append(ConductorMessageKind.SYSTEM, "busy", "Wait for the current command to finish.")
            return
        }

        append(ConductorMessageKind.USER, "you", text)

        if (!text.startsWith("/")) {
            append(
                ConductorMessageKind.SYSTEM,
                "conductor",
                "Natural-language agent is not connected yet. Type / for commands.",
            )
            return
        }

        val parsed =
            ConductorCommands.parse(text)
                ?: run {
                    append(ConductorMessageKind.ERROR, "command", ConductorCommands.unknownMessage(text))
                    return
                }
        val command =
            parsed.command
                ?: run {
                    append(ConductorMessageKind.ERROR, "command", ConductorCommands.unknownMessage(text))
                    return
                }

        when (command.action) {
            ConductorAction.HELP -> {
                val topic = parsed.args.firstOrNull()?.let(ConductorCommands::find)
                append(ConductorMessageKind.RESULT, "help", ConductorCommands.helpText(topic))
            }

            ConductorAction.CAPABILITIES ->
                append(
                    ConductorMessageKind.RESULT,
                    "capabilities",
                    ConductorCommands.capabilitiesText(registry.definitions()),
                )

            ConductorAction.CLEAR -> updateUi { copy(messages = emptyList()) }

            ConductorAction.NODES -> runTool("list_nodes")

            ConductorAction.NETWORK -> runTool("network_snapshot")

            ConductorAction.TAILSCALE -> runTailscale()

            ConductorAction.TELEMETRY_UPLOAD -> runTelemetryUpload()

            ConductorAction.PROBE -> runProbe(parsed.args)
        }
    }

    fun disconnect() {
        synchronized(stateLock) {
            wantConnected = false
        }
        commandClient.disconnect()
        publishNodeState(CommandNodeState())
    }

    override fun onCleared() {
        commandClient.disconnect()
        super.onCleared()
    }

    private fun runTool(
        name: String,
        argsJson: String = "{}",
        confirmed: Boolean = false,
    ) {
        updateUi { copy(isRunning = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val result =
                runCatching { registry.invoke(name, argsJson, confirmed) }
                    .getOrElse { throwable ->
                        io.nekohasekai.sfa.agent.AgentInvocationResult(
                            tool = name,
                            ok = false,
                            errorCode = "internal_error",
                            errorMessage = throwable.message ?: throwable.toString(),
                        )
                    }
            withContext(Dispatchers.Main) {
                updateUi { copy(isRunning = false) }
                if (result.ok) {
                    append(
                        ConductorMessageKind.RESULT,
                        name,
                        AgentJson.pretty(result.output ?: JsonObject(emptyMap())),
                    )
                } else {
                    append(
                        ConductorMessageKind.ERROR,
                        name,
                        result.errorMessage ?: "Tool failed.",
                    )
                }
            }
        }
    }

    private fun runProbe(args: List<String>) {
        if (args.size < 2) {
            append(
                ConductorMessageKind.SYSTEM,
                "probe",
                "Usage: /probe <outbound> <url>\nUse /nodes to list outbound tags.",
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
        val result = registry.invoke("probe_via_node", argsJson, confirmed = false)
        val confirmation = result.confirmation
        if (confirmation == null) {
            if (result.ok) {
                append(
                    ConductorMessageKind.RESULT,
                    "probe",
                    AgentJson.pretty(result.output ?: JsonObject(emptyMap())),
                )
            } else {
                append(
                    ConductorMessageKind.ERROR,
                    "probe",
                    result.errorMessage ?: "Probe failed.",
                )
            }
            return
        }
        appendMessage(
            ConductorMessage(
                kind = ConductorMessageKind.SYSTEM,
                title = "probe",
                body = "Outbound: $outbound\nURL: $url\n\n${confirmation.reason}",
                confirmation = confirmation,
                confirmationId = confirmationIds.incrementAndGet(),
            ),
        )
    }

    fun confirmProbe(id: Long) {
        val state = _uiState.value
        val index = state.messages.indexOfFirst { it.confirmationId == id }
        if (index < 0) return
        val argsJson = state.messages[index].confirmation?.argsJson ?: return
        clearConfirmation(id)
        append(ConductorMessageKind.SYSTEM, "probe", "Running probe…")
        runTool("probe_via_node", argsJson, confirmed = true)
    }

    fun cancelProbe(id: Long) {
        clearConfirmation(id)
        append(ConductorMessageKind.SYSTEM, "probe", "Probe cancelled.")
    }

    private fun clearConfirmation(id: Long) {
        updateUi {
            val index = messages.indexOfFirst { it.confirmationId == id }
            if (index >= 0) {
                val updated = messages.toMutableList()
                updated[index] = updated[index].copy(confirmation = null)
                copy(messages = updated)
            } else {
                this
            }
        }
    }

    private fun runTailscale() {
        updateUi { copy(isRunning = true) }
        append(ConductorMessageKind.SYSTEM, "tailscale", "Reading Tailscale status…")
        viewModelScope.launch(Dispatchers.IO) {
            val text =
                runCatching { fetchTailscale() }
                    .getOrElse { "Tailscale unavailable: ${it.message ?: it}" }
            withContext(Dispatchers.Main) {
                updateUi { copy(isRunning = false) }
                append(ConductorMessageKind.RESULT, "tailscale", text)
            }
        }
    }

    private fun runTelemetryUpload() {
        updateUi { copy(isRunning = true) }
        append(ConductorMessageKind.SYSTEM, "telemetry-upload", "Uploading pending telemetry…")
        viewModelScope.launch(Dispatchers.IO) {
            val result =
                runCatching { TelemetryRuntime.uploadNow(getApplication<Application>()) }
                    .getOrElse { it.message ?: it.toString() }
            withContext(Dispatchers.Main) {
                updateUi { copy(isRunning = false) }
                append(ConductorMessageKind.RESULT, "telemetry-upload", result)
            }
        }
    }

    private suspend fun fetchTailscale(): String {
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

    private fun append(
        kind: ConductorMessageKind,
        title: String,
        body: String,
    ) {
        appendMessage(ConductorMessage(kind, title, body))
    }

    private fun appendMessage(message: ConductorMessage) {
        updateUi {
            copy(messages = (messages + message).takeLast(MAX_MESSAGES))
        }
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
        private const val MAX_MESSAGES = 200
    }
}

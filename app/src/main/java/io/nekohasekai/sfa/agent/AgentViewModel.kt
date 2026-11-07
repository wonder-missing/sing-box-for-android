package io.nekohasekai.sfa.agent

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.nekohasekai.libbox.OutboundGroup
import io.nekohasekai.libbox.OutboundGroupItem
import io.nekohasekai.sfa.constant.ServiceMode
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.utils.CommandClient
import io.nekohasekai.sfa.utils.CommandTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class AgentViewModel(
    application: Application,
) : AndroidViewModel(application),
    CommandClient.Handler {
    enum class EntryKind {
        USER,
        DECISION,
        CALL,
        RESULT,
        INFO,
    }

    data class Entry(
        val kind: EntryKind,
        val title: String,
        val body: String,
    )

    data class PendingConfirmation(
        val tool: String,
        val argsJson: String,
        val displayArgsJson: String,
        val reason: String,
    )

    data class UiState(
        val entries: List<Entry> = emptyList(),
        val isRunning: Boolean = false,
        val coreConnected: Boolean = false,
        val remoteControl: Boolean = false,
        val pendingConfirmation: PendingConfirmation? = null,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState = _uiState.asStateFlow()

    private val stateLock = Any()
    private val uiLock = Any()

    @Volatile
    private var nodeState = CommandNodeState()

    private var rawGroups: List<OutboundGroup> = emptyList()
    private var rawOutbounds: List<OutboundGroupItem> = emptyList()

    @Volatile
    private var localServiceStarted: Boolean? = null

    private var wantConnected = false

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

    /**
     * Called from the screen as service/Remote Control state changes. Connecting
     * the same CommandClient as the rest of the app keeps local and remote
     * sessions on one command path.
     */
    fun onServiceState(
        serviceStatus: Status,
        remoteControl: Boolean,
        remoteConnected: Boolean,
    ) {
        localServiceStarted = !remoteControl && serviceStatus == Status.Started
        val shouldConnect = if (remoteControl) remoteConnected else serviceStatus == Status.Started
        val previousWant =
            synchronized(stateLock) {
                val previous = wantConnected
                wantConnected = shouldConnect
                previous
            }
        updateUi { copy(remoteControl = remoteControl) }
        if (previousWant == shouldConnect) {
            return
        }
        if (shouldConnect) {
            append(Entry(EntryKind.INFO, "连接", "正在通过 CommandClient 连接核心…"))
            commandClient.connect()
        } else {
            commandClient.disconnect()
            publishNodeState(CommandNodeState())
        }
    }

    fun addUserMessage(text: String) {
        append(Entry(EntryKind.USER, "你", text))
    }

    fun addInfo(text: String) {
        append(Entry(EntryKind.INFO, "提示", text))
    }

    fun invokeTool(
        name: String,
        argsJson: String = "{}",
        confirmed: Boolean = false,
        decision: String? = null,
    ) {
        if (_uiState.value.isRunning) {
            addInfo("上一个工具调用仍在进行，请稍候。")
            return
        }
        if (decision != null) {
            append(Entry(EntryKind.DECISION, "决定", decision))
        }
        val allowedUrls = allowedUrls(argsJson)
        append(Entry(EntryKind.CALL, "调用 $name", AgentSanitizer.sanitizeArgsJson(argsJson, allowedUrls)))
        updateUi { copy(isRunning = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val result =
                runCatching {
                    registry.invoke(name, argsJson, confirmed)
                }.getOrElse { throwable ->
                    AgentInvocationResult(
                        tool = name,
                        ok = false,
                        errorCode = "internal_error",
                        errorMessage = throwable.message ?: throwable.toString(),
                    )
                }
            withContext(Dispatchers.Main) {
                finishInvocation(result)
            }
        }
    }

    fun confirmPending() {
        val pending = _uiState.value.pendingConfirmation ?: return
        updateUi { copy(pendingConfirmation = null) }
        append(Entry(EntryKind.INFO, "已确认", pending.tool))
        invokeTool(pending.tool, pending.argsJson, confirmed = true)
    }

    fun dismissConfirmation() {
        updateUi { copy(pendingConfirmation = null) }
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

    private fun finishInvocation(result: AgentInvocationResult) {
        updateUi { copy(isRunning = false) }
        val confirmation = result.confirmation
        if (confirmation != null) {
            append(
                Entry(
                    EntryKind.INFO,
                    "需要确认",
                    "${confirmation.reason}\n${confirmation.displayArgsJson}",
                ),
            )
            updateUi {
                copy(
                    pendingConfirmation =
                        PendingConfirmation(
                            tool = confirmation.tool,
                            argsJson = confirmation.argsJson,
                            displayArgsJson = confirmation.displayArgsJson,
                            reason = confirmation.reason,
                        ),
                )
            }
            return
        }
        val body =
            if (result.ok) {
                AgentJson.pretty(result.output ?: JsonObject(emptyMap()))
            } else {
                AgentJson.pretty(
                    buildJsonObject {
                        put("errorCode", result.errorCode ?: "tool_failed")
                        put("message", result.errorMessage ?: "unknown error")
                        result.output?.let { put("details", it) }
                    },
                )
            }
        append(
            Entry(
                kind = EntryKind.RESULT,
                title = if (result.ok) "返回 ${result.tool}" else "失败 ${result.tool}",
                body = body,
            ),
        )
    }

    private fun allowedUrls(argsJson: String): Set<String> {
        val args = AgentJson.parseObject(argsJson) ?: return emptySet()
        return setOfNotNull(AgentJson.string(args, "url"))
    }

    private fun append(entry: Entry) {
        updateUi {
            copy(entries = (entries + entry).takeLast(MAX_ENTRIES))
        }
    }

    private fun updateUi(transform: UiState.() -> UiState) {
        synchronized(uiLock) {
            _uiState.value = _uiState.value.transform()
        }
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

    /**
     * The select-then-HTTP probe only reaches the requested node when this
     * process' sockets traverse the local TUN. BoxService always keeps SFA in
     * the per-app VPN package set, so VPN mode + a started service is enough.
     * Report an explicit error instead of a false "direct" result otherwise.
     */
    private fun appTrafficUsesVpn(): Boolean? =
        runCatching {
            localServiceStarted == true && Settings.serviceMode == ServiceMode.VPN
        }.getOrNull()

    companion object {
        private const val MAX_ENTRIES = 120
    }
}

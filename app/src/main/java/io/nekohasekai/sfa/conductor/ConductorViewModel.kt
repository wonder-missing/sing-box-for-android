package io.nekohasekai.sfa.conductor

import android.app.Application
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.nekohasekai.libbox.OutboundGroup
import io.nekohasekai.libbox.OutboundGroupItem
import io.nekohasekai.libbox.TailscalePeer
import io.nekohasekai.libbox.TailscaleStatusHandler
import io.nekohasekai.libbox.TailscaleStatusUpdate
import io.nekohasekai.sfa.agent.AgentApiKeyProviderFactory
import io.nekohasekai.sfa.agent.AgentApiTrace
import io.nekohasekai.sfa.agent.AgentConfig
import io.nekohasekai.sfa.agent.AgentDataTools
import io.nekohasekai.sfa.agent.AgentError
import io.nekohasekai.sfa.agent.AgentEvent
import io.nekohasekai.sfa.agent.AgentInvocationResult
import io.nekohasekai.sfa.agent.AgentJson
import io.nekohasekai.sfa.agent.AgentMessage
import io.nekohasekai.sfa.agent.AgentRuntime
import io.nekohasekai.sfa.agent.AgentSanitizer
import io.nekohasekai.sfa.agent.AgentTools
import io.nekohasekai.sfa.agent.DynamicToolExecutor
import io.nekohasekai.sfa.agent.mcp.McpListedTool
import io.nekohasekai.sfa.agent.mcp.McpRuntimes
import io.nekohasekai.sfa.agent.AgentTailscaleEndpoint
import io.nekohasekai.sfa.agent.AgentTailscalePeer
import io.nekohasekai.sfa.agent.AgentTailscaleSnapshot
import io.nekohasekai.sfa.agent.AgentTurnErrorCode
import io.nekohasekai.sfa.agent.AndroidDeviceSnapshotSource
import io.nekohasekai.sfa.agent.CommandNodeSnapshotSource
import io.nekohasekai.sfa.agent.CommandNodeState
import io.nekohasekai.sfa.agent.ConfirmationRequest
import io.nekohasekai.sfa.agent.ConductorFileStore
import io.nekohasekai.sfa.agent.DiagnosticsSource
import io.nekohasekai.sfa.agent.DefaultAgentInputGuard
import io.nekohasekai.sfa.agent.FileSelectionPort
import io.nekohasekai.sfa.agent.FileSelectionResult
import io.nekohasekai.sfa.agent.InputGuardResult
import io.nekohasekai.sfa.agent.LibboxNodeSelectionPort
import io.nekohasekai.sfa.agent.LibboxProbePort
import io.nekohasekai.sfa.agent.ModelProviderSettings
import io.nekohasekai.sfa.agent.OpenAiCompatibleModelClient
import io.nekohasekai.sfa.agent.ReasoningEffort
import io.nekohasekai.sfa.agent.SelectedFileInfo
import io.nekohasekai.sfa.agent.SelectedFileRead
import io.nekohasekai.sfa.agent.TaildropPeerInfo
import io.nekohasekai.sfa.agent.TaildropPort
import io.nekohasekai.sfa.agent.TaildropSendResult
import io.nekohasekai.sfa.agent.TailscaleStatusSource
import io.nekohasekai.sfa.agent.TelemetryUploadPort
import io.nekohasekai.sfa.agent.ToolCallView
import io.nekohasekai.sfa.agent.ToolCancellation
import io.nekohasekai.sfa.agent.ToolResultView
import io.nekohasekai.sfa.agent.ToolStatus
import io.nekohasekai.sfa.agent.TurnEvent
import io.nekohasekai.sfa.agent.TurnOutcome
import io.nekohasekai.sfa.agent.TurnResult
import io.nekohasekai.sfa.agent.TurnState
import io.nekohasekai.sfa.agent.buildCommandNodeState
import io.nekohasekai.sfa.agent.validateTranscriptPairing
import io.nekohasekai.sfa.BuildConfig
import io.nekohasekai.sfa.constant.ServiceMode
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.compose.screen.tools.TaildropSendFile
import io.nekohasekai.sfa.compose.screen.tools.TaildropSendManager
import io.nekohasekai.sfa.log.AppLog
import io.nekohasekai.sfa.telemetry.TelemetryEventLog
import io.nekohasekai.sfa.telemetry.TelemetryRuntime
import io.nekohasekai.sfa.utils.CommandClient
import io.nekohasekai.sfa.utils.CommandTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** A pending request_files call, shown above the composer until the user picks or cancels. */
data class FileRequestUi(
    val id: String,
    val reason: String,
    val multiple: Boolean,
    val mimeTypes: List<String>,
)

class ConductorViewModel(
    application: Application,
) : AndroidViewModel(application),
    CommandClient.Handler {
    data class UiState(
        val items: List<ConductorItem> = emptyList(),
        val isRunning: Boolean = false,
        val coreConnected: Boolean = false,
        val agentSupported: Boolean = true,
        val canRetryLastTurn: Boolean = false,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()
    val mcpListed: StateFlow<List<McpListedTool>> = McpRuntimes.get(application).listed

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
    private val sessionStore = ConductorSessionStore(application)

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

    private val selectionPort = LibboxNodeSelectionPort(nodeSource)

    private val tailscaleSource =
        object : TailscaleStatusSource {
            override suspend fun status(cancellation: ToolCancellation?): AgentTailscaleSnapshot =
                tailscaleSnapshot(cancellation)
        }

    private val telemetryPort =
        object : TelemetryUploadPort {
            override suspend fun upload(cancellation: ToolCancellation?): String =
                TelemetryRuntime.uploadNow(application, cancellation)
        }

    private val workingDir: File = application.getExternalFilesDir(null) ?: application.filesDir
    private val fileStore = ConductorFileStore(workingDir)

    private val diagnosticsSource =
        object : DiagnosticsSource {
            override fun conversationMarkdown(): String = conversationTranscript()

            override fun traceMarkdown(lastEntries: Int?): String = apiTraceMarkdown(lastEntries)
        }

    private val taildropPort =
        object : TaildropPort {
            override suspend fun peers(): List<TaildropPeerInfo> {
                val update = awaitTailscaleStatus(null, "taildrop")
                val peers = LinkedHashMap<String, TaildropPeerInfo>()
                val endpoints = update.endpoints()
                while (endpoints.hasNext()) {
                    val endpoint = endpoints.next()
                    val self = endpoint.getSelf()
                    val groups = endpoint.userGroups()
                    while (groups.hasNext()) {
                        val group = groups.next()
                        val iterator = group.peers()
                        while (iterator.hasNext()) {
                            val peer = iterator.next()
                            if (self != null && peer.stableID == self.stableID) continue
                            val name = peerName(peer)
                            if (!peers.containsKey(name)) {
                                peers[name] =
                                    TaildropPeerInfo(
                                        name = name,
                                        online = peer.online,
                                        lastSeen =
                                            if (!peer.online && peer.lastSeen > 0) {
                                                formatLastSeen(peer.lastSeen)
                                            } else {
                                                null
                                            },
                                    )
                            }
                        }
                    }
                }
                return peers.values.toList()
            }

            override suspend fun send(
                targetPeer: String,
                files: List<File>,
                cancellation: ToolCancellation?,
            ): TaildropSendResult =
                sendTaildropFiles(
                    targetPeer = targetPeer,
                    sendFiles = files.map { TaildropSendFile(it.name, null, null, it) },
                    names = files.map { it.name },
                    cancellation = cancellation,
                )

            override suspend fun sendSelected(
                peer: String,
                ids: List<String>,
                cancellation: ToolCancellation?,
            ): TaildropSendResult {
                val picked =
                    synchronized(selectedFilesLock) {
                        ids.map { id ->
                            selectedFiles[id]
                                ?: return TaildropSendResult(
                                    peer,
                                    ids,
                                    ok = false,
                                    message = "Unknown file handle '$id'. Call request_files again.",
                                )
                        }
                    }
                val names = picked.map { it.info.name }
                val declaredTotal = picked.sumOf { it.info.size }
                if (declaredTotal > TAILDROP_MAX_BYTES) {
                    return TaildropSendResult(
                        peer,
                        names,
                        ok = false,
                        message = "Total size is larger than $TAILDROP_MAX_BYTES bytes.",
                    )
                }
                val sendFiles =
                    withContext(Dispatchers.IO) {
                        TaildropSendManager.openURIs(picked.map { it.uri })
                    }
                if (sendFiles.sumOf { it.size } > TAILDROP_MAX_BYTES) {
                    sendFiles.forEach { runCatching { it.close() } }
                    return TaildropSendResult(
                        peer,
                        names,
                        ok = false,
                        message = "Total size is larger than $TAILDROP_MAX_BYTES bytes.",
                    )
                }
                return sendTaildropFiles(peer, sendFiles, names, cancellation)
            }
        }

    /**
     * Shared Taildrop transfer used by the working-directory tool and by
     * user-selected files. The send manager closes [sendFiles] once the
     * session ends; early failures close them here instead.
     */
    private suspend fun sendTaildropFiles(
        targetPeer: String,
        sendFiles: List<TaildropSendFile>,
        names: List<String>,
        cancellation: ToolCancellation?,
    ): TaildropSendResult {
        fun fail(message: String): TaildropSendResult {
            sendFiles.forEach { runCatching { it.close() } }
            return TaildropSendResult(targetPeer, names, ok = false, message = message)
        }
        val update = awaitTailscaleStatus(cancellation, "taildrop")
        var endpointTag: String? = null
        var stableID: String? = null
        var online = false
        val endpoints = update.endpoints()
        while (endpoints.hasNext() && stableID == null) {
            val endpoint = endpoints.next()
            val self = endpoint.getSelf()
            val groups = endpoint.userGroups()
            while (groups.hasNext() && stableID == null) {
                val group = groups.next()
                val iterator = group.peers()
                while (iterator.hasNext()) {
                    val peer = iterator.next()
                    if (self != null && peer.stableID == self.stableID) continue
                    if (peerName(peer) == targetPeer) {
                        endpointTag = endpoint.endpointTag
                        stableID = peer.stableID
                        online = peer.online
                        break
                    }
                }
            }
        }
        val endpoint = endpointTag
        val stable = stableID
        if (endpoint == null || stable == null) {
            return fail("Unknown peer: $targetPeer")
        }
        if (!online) {
            return fail("Peer is offline: $targetPeer")
        }
        val id = TaildropSendManager.send(endpoint, stable, targetPeer, sendFiles)
        if (id < 0) {
            return fail("Nothing to send.")
        }
        cancellation?.onCancel { TaildropSendManager.cancel(id) }
        val state =
            withTimeoutOrNull(TAILDROP_SEND_TIMEOUT_MS) {
                TaildropSendManager.sessions
                    .first { sessions -> sessions.any { it.id == id && it.finished } }
                    .firstOrNull { it.id == id }
            }
        if (state == null) {
            TaildropSendManager.cancel(id)
            return TaildropSendResult(
                targetPeer,
                names,
                ok = false,
                message = "Timed out waiting for the transfer to finish.",
            )
        }
        state.errorMessage?.let { message ->
            return TaildropSendResult(targetPeer, names, ok = false, message = message)
        }
        if (state.files.any { !it.completed }) {
            return TaildropSendResult(
                targetPeer,
                names,
                ok = false,
                message = "Some files were not completed.",
            )
        }
        AppLog.info("conductor taildrop send peer=$targetPeer files=${names.joinToString(",")}")
        return TaildropSendResult(targetPeer, names, ok = true)
    }

    /**
     * Bridges request_files to the system picker. Files are kept as content
     * URIs under the activity's temporary read grant: nothing is copied,
     * renamed or modified, and no persistable grant is taken.
     */
    private val fileSelectionPort =
        object : FileSelectionPort {
            override suspend fun request(
                reason: String,
                multiple: Boolean,
                mimeTypes: List<String>?,
                cancellation: ToolCancellation?,
            ): FileSelectionResult {
                val request =
                    PendingFileRequest(
                        id = UUID.randomUUID().toString().take(8),
                        multiple = multiple,
                        deferred = CompletableDeferred(),
                    )
                synchronized(selectedFilesLock) {
                    if (pendingFileRequest != null) {
                        return FileSelectionResult(
                            cancelled = true,
                            message = "Another file request is already waiting for the user.",
                        )
                    }
                    pendingFileRequest = request
                }
                _fileRequest.value =
                    FileRequestUi(
                        id = request.id,
                        reason = reason.take(MAX_FILE_REQUEST_REASON_CHARS),
                        multiple = multiple,
                        mimeTypes = mimeTypes.orEmpty(),
                    )
                cancellation?.onCancel {
                    completeFileRequest(
                        request,
                        FileSelectionResult(cancelled = true, message = "The request was cancelled."),
                    )
                }
                return try {
                    withTimeoutOrNull(FILE_PICK_TIMEOUT_MS) { request.deferred.await() }
                        ?: FileSelectionResult(
                            cancelled = true,
                            message = "The user did not choose files in time.",
                        )
                } finally {
                    clearFileRequest(request.id)
                }
            }

            override suspend fun read(
                id: String,
                maxBytes: Int,
            ): SelectedFileRead {
                val file =
                    synchronized(selectedFilesLock) { selectedFiles[id] }
                        ?: throw IllegalArgumentException("Unknown file handle '$id'. Call request_files again.")
                return withContext(Dispatchers.IO) {
                    val cap = maxBytes.coerceIn(1, ConductorFileStore.MAX_READ_BYTES)
                    val resolver = getApplication<Application>().contentResolver
                    val stream =
                        resolver.openInputStream(file.uri)
                            ?: throw IllegalStateException("The selected file is no longer available.")
                    stream.use {
                        val buffer = ByteArray(8 * 1024)
                        val collected = java.io.ByteArrayOutputStream(cap + 1)
                        var remaining = cap + 1
                        while (remaining > 0) {
                            val read = it.read(buffer, 0, minOf(buffer.size, remaining))
                            if (read <= 0) break
                            collected.write(buffer, 0, read)
                            remaining -= read
                        }
                        val bytes = collected.toByteArray()
                        if (bytes.any { byte -> byte.toInt() == 0 }) {
                            throw IllegalArgumentException("The selected file is binary.")
                        }
                        SelectedFileRead(
                            id = id,
                            name = file.info.name,
                            size = file.info.size,
                            text = bytes.copyOf(minOf(bytes.size, cap)).decodeToString(),
                            truncated = bytes.size > cap || file.info.size > cap,
                        )
                    }
                }
            }
        }

    private val agentConfig = AgentConfig()

    private val toolExecutor =
        DynamicToolExecutor(
            AgentTools(
                nodeSource = nodeSource,
                deviceSource = deviceSource,
                probePort = probePort,
                selectionPort = selectionPort,
                tailscaleStatusSource = tailscaleSource,
                telemetryUploadPort = telemetryPort,
            ).all +
                AgentDataTools(
                    fileStore = fileStore,
                    diagnostics = diagnosticsSource,
                    taildrop = taildropPort,
                    fileSelection = fileSelectionPort,
                ).all,
            toolTimeoutMs = agentConfig.toolTimeoutMs,
        )

    private val modelClient = OpenAiCompatibleModelClient(endpointProvider = ModelProviderSettings::endpoint)

    private val runtime =
        AgentRuntime(
            inputGuard = DefaultAgentInputGuard,
            model = modelClient,
            tools = toolExecutor,
            apiKeys = apiKeyProvider,
            config = agentConfig,
            streamingModel = modelClient,
            toolRoutingEnabled = { Settings.conductorToolRouting },
            reasoningEffort = { ReasoningEffort.wireValue(Settings.conductorReasoningEffort) },
        )

    private var transcript: List<AgentMessage> = emptyList()
    private var pendingTurnEvent: TurnEvent? = null
    private val toolStartedAt = mutableMapOf<String, Long>()
    private var sessionStartVersion: String = BuildConfig.VERSION_NAME
    private var agentJob: Job? = null
    private var stopRequested = false

    @Volatile
    private var pendingAgentConfirmation: CompletableDeferred<Boolean>? = null

    @Volatile
    private var autoApproveAgentToolName: String? = null

    private var pendingCommandConfirmation: Pair<String, String>? = null

    private data class PendingFileRequest(
        val id: String,
        val multiple: Boolean,
        val deferred: CompletableDeferred<FileSelectionResult>,
    )

    private data class SelectedFile(
        val info: SelectedFileInfo,
        val uri: Uri,
    )

    private val selectedFilesLock = Any()
    private val selectedFiles = LinkedHashMap<String, SelectedFile>()
    private var pendingFileRequest: PendingFileRequest? = null

    private val _fileRequest = MutableStateFlow<FileRequestUi?>(null)
    val fileRequest: StateFlow<FileRequestUi?> = _fileRequest.asStateFlow()

    init {
        AgentApiTrace.setAppVersion(BuildConfig.VERSION_NAME)
        runCatching { fileStore.ensureWorkspace() }
        restoreSession()
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
            commandClient.connect()
        } else {
            commandClient.disconnect()
            publishNodeState(CommandNodeState())
        }
    }

    fun currentDraft(): String = sessionStore.loadDraft()

    fun saveDraft(text: String) {
        sessionStore.saveDraft(text)
    }

    fun submit(raw: String) {
        val text = raw.trim()
        if (text.isEmpty()) return
        val isClear = text == "/clear" || text.startsWith("/clear ")
        if (_uiState.value.isRunning) {
            if (isClear) {
                clearConversation()
                return
            }
            append(ConductorItem.Notice("busy", "Wait for the current command to finish."))
            return
        }
        updateUi { copy(canRetryLastTurn = false) }

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
            // Persist before the model call: a process death mid-turn must not
            // lose the user message. Restore reconciles it into the transcript.
            persistSession()
            submitAgent(text)
            return
        }

        append(ConductorItem.User(text))
        persistSession()

        val parsed =
            ConductorCommands.parse(text)
                ?: run {
                    append(ConductorItem.Error("command", ConductorCommands.unknownMessage(text)))
                    persistSession()
                    return
                }
        val command =
            parsed.command
                ?: run {
                    append(ConductorItem.Error("command", ConductorCommands.unknownMessage(text)))
                    persistSession()
                    return
                }

        when (command.action) {
            ConductorCommandKind.HELP -> {
                val topic = parsed.args.firstOrNull()?.let(ConductorCommands::find)
                append(ConductorItem.Notice("help", ConductorCommands.helpText(topic)))
                persistSession()
            }

            ConductorCommandKind.CAPABILITIES -> {
                append(
                    ConductorItem.Notice(
                        "capabilities",
                        ConductorCommands.capabilitiesText(toolExecutor.definitions()),
                    ),
                )
                persistSession()
            }

            ConductorCommandKind.MCP -> {
                if (ConductorMcpUse.isUse(parsed.args)) {
                    append(
                        ConductorItem.Notice(
                            "mcp",
                            "Type /mcp use and a tool name. /mcp lists them.",
                        ),
                    )
                    persistSession()
                } else {
                    showMcp(parsed.args.joinToString(" "))
                }
            }

            ConductorCommandKind.CLEAR -> clearConversation()

            ConductorCommandKind.REASONING -> {
                val outcome =
                    ConductorReasoningCommand.apply(
                        parsed.args,
                        Settings.conductorReasoningEffort,
                    )
                when (outcome) {
                    is ConductorReasoningCommand.Outcome.Updated -> {
                        Settings.conductorReasoningEffort = outcome.value
                        append(ConductorItem.Notice("reasoning", outcome.message))
                    }
                    is ConductorReasoningCommand.Outcome.Shown -> {
                        append(ConductorItem.Notice("reasoning", outcome.message))
                    }
                    is ConductorReasoningCommand.Outcome.Invalid -> {
                        append(ConductorItem.Error("reasoning", outcome.message))
                    }
                }
                persistSession()
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

    /**
     * Approve this tool for the rest of the turn. The runtime still asks once
     * per call; the ViewModel answers prompts for the same tool without drawing
     * another card. A different tool asks again.
     */
    fun confirmAllAgent() {
        if (pendingAgentConfirmation == null) return
        val toolName =
            _uiState.value.items
                .filterIsInstance<ConductorItem.Confirmation>()
                .lastOrNull()
                ?.request
                ?.toolName
                ?: return
        autoApproveAgentToolName = toolName
        confirmAgent()
    }

    fun cancelAgent() {
        autoApproveAgentToolName = null
        val pending = pendingAgentConfirmation ?: return
        removeConfirmationItems()
        pending.complete(false)
    }

    fun stopAgent() {
        if (!_uiState.value.isRunning || stopRequested) return
        stopRequested = true
        autoApproveAgentToolName = null
        cancelPendingFileRequest("The turn was stopped.")
        pendingAgentConfirmation?.complete(false)
        removeConfirmationItems()
        runtime.cancel()
        McpRuntimes.get(getApplication()).cancelActive()
        viewModelScope.launch {
            delay(1_000)
            if (_uiState.value.isRunning) {
                append(
                    ConductorItem.Notice(
                        "conductor",
                        "Stopping… the current tool has not confirmed cancellation yet.",
                    ),
                )
            }
        }
    }

    /** The system picker returned; empty [uris] means the user dismissed it. */
    fun onFilesSelected(
        requestId: String,
        uris: List<Uri>,
    ) {
        val request =
            synchronized(selectedFilesLock) {
                pendingFileRequest?.takeIf { it.id == requestId }
            } ?: return
        if (uris.isEmpty()) {
            completeFileRequest(request, FileSelectionResult(cancelled = true))
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val resolver = getApplication<Application>().contentResolver
            val picked = mutableListOf<SelectedFile>()
            for (uri in uris.take(if (request.multiple) MAX_SELECTED_FILES else 1)) {
                val described = describeUri(resolver, uri) ?: continue
                picked +=
                    SelectedFile(
                        info =
                            SelectedFileInfo(
                                id = UUID.randomUUID().toString().take(8),
                                name = described.first,
                                size = described.second,
                                mimeType = described.third,
                            ),
                        uri = uri,
                    )
            }
            if (picked.isEmpty()) {
                completeFileRequest(
                    request,
                    FileSelectionResult(cancelled = true, message = "The selected files could not be read."),
                )
                return@launch
            }
            val stillPending =
                synchronized(selectedFilesLock) {
                    if (pendingFileRequest !== request) {
                        false
                    } else {
                        picked.forEach { selectedFiles[it.info.id] = it }
                        while (selectedFiles.size > MAX_RETAINED_SELECTED_FILES) {
                            selectedFiles.remove(selectedFiles.keys.first())
                        }
                        true
                    }
                }
            if (!stillPending) return@launch
            val kept = picked.size
            val omitted = uris.size - kept
            val result =
                FileSelectionResult(
                    files = picked.map { it.info },
                    message =
                        if (omitted > 0) {
                            "Only the first $kept files were kept; $omitted were ignored."
                        } else {
                            null
                        },
                )
            completeFileRequest(request, result)
        }
    }

    /** The user dismissed the in-app card without opening the picker. */
    fun cancelFileRequest(requestId: String) {
        val request =
            synchronized(selectedFilesLock) {
                pendingFileRequest?.takeIf { it.id == requestId }
            } ?: return
        completeFileRequest(
            request,
            FileSelectionResult(cancelled = true, message = "The user dismissed the file request."),
        )
    }

    private fun cancelPendingFileRequest(message: String) {
        val request = synchronized(selectedFilesLock) { pendingFileRequest } ?: return
        completeFileRequest(request, FileSelectionResult(cancelled = true, message = message))
    }

    private fun completeFileRequest(
        request: PendingFileRequest,
        result: FileSelectionResult,
    ) {
        synchronized(selectedFilesLock) {
            if (pendingFileRequest !== request) return
            pendingFileRequest = null
        }
        _fileRequest.value = null
        request.deferred.complete(result)
    }

    private fun clearFileRequest(id: String) {
        synchronized(selectedFilesLock) {
            if (pendingFileRequest?.id == id) pendingFileRequest = null
        }
        _fileRequest.update { current -> if (current?.id == id) null else current }
    }

    private fun describeUri(
        resolver: android.content.ContentResolver,
        uri: Uri,
    ): Triple<String, Long, String?>? {
        var name: String? = null
        var size = -1L
        runCatching {
            resolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                        name = cursor.getString(nameIndex)
                    }
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                        size = cursor.getLong(sizeIndex)
                    }
                }
            }
        }
        if (size < 0) {
            size =
                runCatching {
                    resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
                }.getOrDefault(-1L)
        }
        val displayName =
            (name ?: uri.lastPathSegment?.substringAfterLast('/'))
                ?.take(MAX_FILE_NAME_CHARS)
                ?.takeIf { it.isNotBlank() }
                ?: return null
        val mimeType = runCatching { resolver.getType(uri) }.getOrNull()
        return Triple(displayName, size.coerceAtLeast(0L), mimeType)
    }

    /**
     * Re-runs the user message whose turn failed before producing an answer.
     * The failed message is still in the model transcript, so drop it there and
     * let [submit] add it back as the new turn; the UI keeps a single copy.
     */
    fun retryLastTurn() {
        if (_uiState.value.isRunning || !_uiState.value.canRetryLastTurn) return
        val items = _uiState.value.items
        val lastUserIndex = items.indexOfLast { it is ConductorItem.User }
        if (lastUserIndex < 0) return
        if (transcript.lastOrNull() !is AgentMessage.User) return
        val text = (items[lastUserIndex] as ConductorItem.User).text
        transcript = transcript.dropLast(1)
        updateUi {
            copy(
                items = items.take(lastUserIndex),
                canRetryLastTurn = false,
            )
        }
        submit(text)
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
        updateUi { copy(isRunning = false) }
        append(ConductorItem.Notice("command", "Cancelled."))
        persistSession()
    }

    private fun clearConversation() {
        runtime.cancel()
        McpRuntimes.get(getApplication()).cancelActive()
        agentJob?.cancel()
        agentJob = null
        stopRequested = false
        autoApproveAgentToolName = null
        pendingAgentConfirmation = null
        pendingTurnEvent = null
        pendingCommandConfirmation = null
        cancelPendingFileRequest("The conversation was cleared.")
        synchronized(selectedFilesLock) { selectedFiles.clear() }
        sessionStartVersion = BuildConfig.VERSION_NAME
        toolStartedAt.clear()
        transcript = emptyList()
        updateUi { copy(items = emptyList(), isRunning = false, canRetryLastTurn = false) }
        sessionStore.clear()
        AgentApiTrace.clear()
    }

    override fun onCleared() {
        if (!_uiState.value.isRunning) {
            persistSession()
        }
        runtime.cancel()
        McpRuntimes.get(getApplication()).cancelActive()
        cancelPendingFileRequest("The conversation was closed.")
        synchronized(selectedFilesLock) { selectedFiles.clear() }
        agentJob?.cancel()
        commandClient.disconnect()
        ModelProviderSettings.unpin()
        super.onCleared()
    }

    private fun submitAgent(text: String) {
        if (!apiKeyProvider.supported) {
            append(ConductorItem.Error("agent", "The Conductor agent requires Android 7.0 or newer."))
            return
        }
        stopRequested = false
        autoApproveAgentToolName = null
        updateUi { copy(isRunning = true, canRetryLastTurn = false) }
        val initialEvent = pendingTurnEvent
        agentJob =
            viewModelScope.launch {
                refreshMcpTools()
                if (stopRequested) {
                    withContext(Dispatchers.Main) {
                        updateUi { copy(isRunning = false) }
                        append(ConductorItem.Notice("conductor", "Stopped."))
                        persistSession()
                    }
                    return@launch
                }
                ModelProviderSettings.pinForTurn()
                val result =
                    try {
                        runtime.runTurn(
                            userText = text,
                            history = transcript,
                            deviceSummary = deviceSummary(),
                            onEvent = ::handleAgentEvent,
                            confirm = { awaitAgentConfirmation() },
                            initialEvent = initialEvent,
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
                            pendingEvent = initialEvent,
                        )
                    }
                withContext(Dispatchers.Main) {
                    finishReasoning()
                    finishStreamingAssistant()
                    transcript = (result.compactedHistory ?: transcript) + result.messages
                    pendingTurnEvent = result.pendingEvent
                    val outcome = result.outcome
                    val canRetry =
                        outcome is TurnOutcome.Failed &&
                            transcript.lastOrNull() is AgentMessage.User &&
                            pendingTurnEvent != null
                    AppLog.info(
                        "conductor turn=${outcome::class.simpleName} messages=${result.messages.size} retry=$canRetry",
                    )
                    updateUi { copy(isRunning = false, canRetryLastTurn = canRetry) }
                    pendingAgentConfirmation = null
                    stopRequested = false
                    autoApproveAgentToolName = null
                    ModelProviderSettings.unpin()
                    when (outcome) {
                        is TurnOutcome.Failed ->
                            append(
                                ConductorItem.Error(
                                    "agent",
                                    outcome.error.message,
                                    outcome.error.retryable,
                                ),
                            )

                        is TurnOutcome.Cancelled ->
                            append(ConductorItem.Notice("conductor", "Stopped."))

                        is TurnOutcome.Completed -> Unit
                    }
                    if (result.messages.isNotEmpty()) {
                        AgentApiTrace.lastUsage()?.let { usage ->
                            append(
                                ConductorItem.Notice(
                                    "usage",
                                    "in=${usage.inputTokens ?: "?"} out=${usage.outputTokens ?: "?"}" +
                                        " cache=${usage.cacheHitTokens ?: "?"}" +
                                        " miss=${usage.cacheMissTokens ?: "?"}",
                                ),
                            )
                        }
                    }
                    persistSession()
                }
            }
    }

    internal fun refreshMcpQuietly() {
        viewModelScope.launch {
            val mcp = McpRuntimes.get(getApplication())
            try {
                mcp.refreshIfStale()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Suggestions keep the last list. Sending /mcp use still reports a miss.
            }
        }
    }

    internal suspend fun resolveMcpUse(raw: String): ConductorMcpUse.Outcome {
        if (_uiState.value.isRunning) {
            append(ConductorItem.Notice("busy", "Wait for the current command to finish."))
            persistSession()
            return ConductorMcpUse.Outcome.Rejected("Wait for the current command to finish.")
        }
        val args = ConductorCommands.parse(raw.trim())?.args.orEmpty()
        val mcp = McpRuntimes.get(getApplication())
        try {
            mcp.refreshIfStale()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Match against the last list. The settings row keeps the error.
        }
        return when (val outcome = ConductorMcpUse.resolve(args, mcp.listedTools())) {
            is ConductorMcpUse.Outcome.Draft -> outcome
            is ConductorMcpUse.Outcome.Rejected -> {
                append(ConductorItem.User(raw.trim()))
                append(ConductorItem.Notice("mcp", outcome.message))
                persistSession()
                outcome
            }
        }
    }

    private fun showMcp(query: String) {
        viewModelScope.launch {
            val mcp = McpRuntimes.get(getApplication())
            try {
                mcp.refreshIfStale()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The list still shows the last known servers and the row error.
            }
            append(
                ConductorItem.Notice(
                    "mcp",
                    ConductorCommands.mcpText(mcp.views.value, mcp.listedTools(), query),
                ),
            )
            persistSession()
        }
    }

    private suspend fun refreshMcpTools() {
        val mcp = McpRuntimes.get(getApplication())
        try {
            mcp.refreshIfStale()
        } catch (e: CancellationException) {
            if (!stopRequested) throw e
        } catch (_: Exception) {
            // A stale refresh must not fail the turn. The settings row keeps the error.
        }
        if (!stopRequested) toolExecutor.setExtras(mcp.tools())
    }

    private fun handleAgentEvent(event: AgentEvent) {
        when (event) {
            is AgentEvent.AssistantMessage -> {
                finishReasoning()
                appendAssistantMessage(event.text)
            }

            is AgentEvent.AssistantDelta -> appendAssistantDelta(event.text)
            is AgentEvent.ReasoningDelta -> appendReasoningDelta(event.text)
            is AgentEvent.StreamReset -> {
                updateUi { copy(items = items.dropStreamingOutput()) }
            }
            is AgentEvent.ToolStarted -> {
                finishReasoning()
                finishStreamingAssistant()
                toolStartedAt[event.call.id] = SystemClock.uptimeMillis()
                AppLog.info("conductor tool=${event.call.name} started")
                append(ConductorItem.Tool(event.call, ToolStatus.RUNNING))
            }

            is AgentEvent.ToolFinished -> {
                AppLog.info(
                    "conductor tool=${event.result.name} ok=${event.result.ok} code=${event.result.errorCode ?: "-"}",
                )
                toolStartedAt.remove(event.result.callId)?.let { startedAt ->
                    TelemetryEventLog.recordAsync(
                        getApplication<Application>(),
                        "conductor_tool",
                        mapOf(
                            "tool" to telemetryToolName(event.result.name),
                            "ms" to SystemClock.uptimeMillis() - startedAt,
                            "ok" to event.result.ok,
                            "code" to (event.result.errorCode ?: "-"),
                        ),
                    )
                }
                updateToolResult(event.result)
            }
            is AgentEvent.ConfirmationRequested -> {
                AppLog.info("conductor confirm=${event.request.toolName}")
                if (autoApproveAgentToolName == event.request.toolName) {
                    pendingAgentConfirmation =
                        CompletableDeferred<Boolean>().apply { complete(true) }
                } else {
                    finishReasoning()
                    finishStreamingAssistant()
                    pendingAgentConfirmation = CompletableDeferred()
                    append(ConductorItem.Confirmation(event.request))
                }
            }

            is AgentEvent.Notice -> {
                finishReasoning()
                finishStreamingAssistant()
                append(ConductorItem.Notice("conductor", event.text))
            }

            is AgentEvent.StateChanged ->
                if (event.state is TurnState.CallingModel) {
                    appendReasoningPlaceholder()
                }

            is AgentEvent.TurnStarted -> Unit
            is AgentEvent.TurnFinished -> {
                finishReasoning()
                finishStreamingAssistant()
                toolStartedAt.clear()
            }
        }
    }

    private fun appendReasoningPlaceholder() {
        updateUi {
            copy(items = this.items.withReasoningPlaceholder().takeLast(MAX_ITEMS))
        }
    }

    private fun appendReasoningDelta(text: String) {
        if (text.isEmpty()) return
        updateUi {
            copy(items = this.items.withReasoningDelta(text).takeLast(MAX_ITEMS))
        }
    }

    private fun finishReasoning() {
        updateUi {
            copy(items = this.items.settleReasoning())
        }
    }

    private fun appendAssistantDelta(text: String) {
        if (text.isEmpty()) return
        updateUi {
            copy(items = this.items.withAssistantDelta(text).takeLast(MAX_ITEMS))
        }
    }

    private fun appendAssistantMessage(text: String) {
        updateUi {
            copy(items = this.items.withAssistantFinal(text).takeLast(MAX_ITEMS))
        }
    }

    private fun finishStreamingAssistant() {
        updateUi {
            copy(items = this.items.settleStreamingAssistants())
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
                persistSession()
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
                persistSession()
            }
        }
    }

    private suspend fun invokeRegistryUserCommand(
        name: String,
        argsJson: String,
    ): AgentInvocationResult =
        try {
            toolExecutor.invokeUserCommand(name, argsJson)
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
            toolExecutor.confirmUserCommand(name, argsJson)
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
        updateUi { copy(isRunning = true) }
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
            val text =
                runCatching { tailscaleText() }
                    .getOrElse { friendlyTailscaleError(it.message ?: it.toString()) }
            withContext(Dispatchers.Main) {
                updateUi { copy(isRunning = false) }
                append(ConductorItem.Notice("tailscale", text))
                persistSession()
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
                persistSession()
            }
        }
    }

    private suspend fun tailscaleText(
        cancellation: ToolCancellation? = null,
        includeDetails: Boolean = true,
    ): String =
        try {
            formatTailscale(awaitTailscaleStatus(cancellation, "command"), includeDetails)
        } catch (e: CancellationException) {
            "cancelled"
        } catch (e: Exception) {
            e.message ?: "Tailscale error"
        }

    private suspend fun tailscaleSnapshot(cancellation: ToolCancellation?): AgentTailscaleSnapshot =
        buildTailscaleSnapshot(awaitTailscaleStatus(cancellation, "agent"))

    private suspend fun awaitTailscaleStatus(
        cancellation: ToolCancellation?,
        source: String,
    ): TailscaleStatusUpdate {
        if (cancellation?.isCancelled() == true) throw CancellationException("cancelled")
        val startedAt = SystemClock.uptimeMillis()
        val deferred = CompletableDeferred<TailscaleStatusUpdate>()
        val subscription =
            try {
                CommandTarget.standaloneClient().subscribeTailscaleStatus(
                    object : TailscaleStatusHandler {
                        override fun onStatusUpdate(status: TailscaleStatusUpdate) {
                            if (!deferred.isCompleted) {
                                deferred.complete(status)
                            }
                        }

                        override fun onError(message: String) {
                            if (!deferred.isCompleted) {
                                deferred.completeExceptionally(
                                    IllegalStateException(friendlyTailscaleError(message)),
                                )
                            }
                        }
                    },
                )
            } catch (e: Exception) {
                val message = friendlyTailscaleError(e.message ?: e.toString())
                recordTailscaleStatus(source, startedAt, "error", tailscaleErrorClass(message))
                throw IllegalStateException(message)
            }
        cancellation?.onCancel {
            runCatching { subscription.close() }
            deferred.cancel()
        }
        return try {
            val update = withTimeout(5_000) { deferred.await() }
            recordTailscaleStatus(source, startedAt, "ok")
            update
        } catch (e: TimeoutCancellationException) {
            recordTailscaleStatus(source, startedAt, "timeout")
            throw IllegalStateException("Timed out waiting for Tailscale status.")
        } catch (e: CancellationException) {
            recordTailscaleStatus(source, startedAt, "cancelled")
            throw e
        } catch (e: Exception) {
            recordTailscaleStatus(source, startedAt, "error", tailscaleErrorClass(e.message))
            throw e
        } finally {
            runCatching { subscription.close() }
        }
    }

    private fun recordTailscaleStatus(
        source: String,
        startedAt: Long,
        result: String,
        errorClass: String? = null,
    ) {
        TelemetryEventLog.recordAsync(
            getApplication<Application>(),
            "tailscale_status",
            buildMap {
                put("source", source)
                put("ms", SystemClock.uptimeMillis() - startedAt)
                put("result", result)
                errorClass?.let { put("error_class", it) }
            },
        )
    }

    private fun telemetryToolName(name: String): String = if (name.startsWith("mcp_")) "mcp" else name

    private fun tailscaleErrorClass(message: String?): String {
        val lower = message.orEmpty().lowercase(Locale.US)
        return when {
            "not running" in lower ||
                "connection refused" in lower ||
                "unavailable" in lower ||
                "no such file" in lower -> "service_down"

            "timed out" in lower -> "timeout"
            else -> "other"
        }
    }

    private fun buildTailscaleSnapshot(update: TailscaleStatusUpdate): AgentTailscaleSnapshot {
        val endpoints = mutableListOf<AgentTailscaleEndpoint>()
        val iterator = update.endpoints()
        while (iterator.hasNext()) {
            val endpoint = iterator.next()
            val self = endpoint.getSelf()
            val peers = mutableListOf<AgentTailscalePeer>()
            val groups = endpoint.userGroups()
            while (groups.hasNext()) {
                val group = groups.next()
                val groupPeers = group.peers()
                while (groupPeers.hasNext()) {
                    val peer = groupPeers.next()
                    if (self != null && peer.stableID == self.stableID) continue
                    peers +=
                        AgentTailscalePeer(
                            name = peerName(peer),
                            online = peer.online,
                            exitNode = peer.exitNode,
                            exitNodeOption = peer.exitNodeOption,
                            lastSeen = if (!peer.online && peer.lastSeen > 0) formatLastSeen(peer.lastSeen) else null,
                        )
                }
            }
            endpoints +=
                AgentTailscaleEndpoint(
                    tag = endpoint.endpointTag,
                    state = endpoint.backendState,
                    self =
                        self?.let {
                            AgentTailscalePeer(
                                name = peerName(it),
                                online = it.online,
                                exitNode = it.exitNode,
                                exitNodeOption = it.exitNodeOption,
                            )
                        },
                    peers = peers,
                )
        }
        return AgentTailscaleSnapshot(endpoints)
    }

    private fun formatTailscale(
        update: TailscaleStatusUpdate,
        includeDetails: Boolean = true,
    ): String =
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
                    if (includeDetails) {
                        appendLine("  ${group.loginName.ifBlank { group.displayName }}")
                    }
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
                        val addresses = if (includeDetails) " ${peerIps(peer)}" else ""
                        val lastSeen =
                            if (!peer.online && peer.lastSeen > 0) {
                                " last-seen=${formatLastSeen(peer.lastSeen)}"
                            } else {
                                ""
                            }
                        appendLine("    - ${peerName(peer)} ${flags.joinToString(", ")}$addresses$lastSeen".trimEnd())
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

    private fun formatLastSeen(seconds: Long): String =
        runCatching {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(seconds * 1_000))
        }.getOrDefault("unknown")

    private fun peerIps(peer: TailscalePeer): String {
        val ips = mutableListOf<String>()
        val iterator = peer.tailscaleIPs()
        while (iterator.hasNext()) {
            ips.add(iterator.next())
        }
        return ips.joinToString(", ")
    }

    private fun friendlyTailscaleError(message: String): String {
        val lower = message.lowercase()
        return if (
            "command.sock" in lower ||
            "no such file" in lower ||
            "connection refused" in lower ||
            "unavailable" in lower
        ) {
            "Service is not running. Start the service to read Tailscale status."
        } else {
            "Tailscale error: ${AgentSanitizer.sanitizeText(message)}"
        }
    }

    fun conversationTranscript(): String =
        buildString {
            appendLine("# Conductor conversation")
            appendLine()
            appendLine("- app version: ${BuildConfig.VERSION_NAME}")
            if (sessionStartVersion.isNotBlank() && sessionStartVersion != BuildConfig.VERSION_NAME) {
                appendLine("- session started on: $sessionStartVersion")
            }
            appendLine()
            _uiState.value.items.forEach { item ->
                when (item) {
                    is ConductorItem.User -> {
                        appendLine("## User")
                        appendLine()
                        appendLine(item.text)
                        appendLine()
                    }

                    is ConductorItem.Assistant -> {
                        appendLine("## Conductor")
                        appendLine()
                        appendLine(item.text)
                        appendLine()
                    }

                    is ConductorItem.Reasoning -> {
                        appendLine("## Thinking")
                        appendLine()
                        appendLine(item.text)
                        appendLine()
                    }

                    is ConductorItem.Tool -> {
                        appendLine("## Tool: ${item.call.name}")
                        appendLine()
                        appendLine("- status: ${item.status.name.lowercase()}")
                        if (item.result?.degraded == true) appendLine("- degraded: true")
                        appendLine()
                        val args = item.call.displayArgsJson
                        if (args.isNotBlank() && args != "{}") {
                            appendLine("~~~json")
                            appendLine(AgentJson.parseObject(args)?.let { AgentJson.pretty(it) } ?: args)
                            appendLine("~~~")
                            appendLine()
                        }
                        item.result?.let { result ->
                            result.output?.let { output ->
                                appendLine("~~~json")
                                appendLine(AgentJson.pretty(output))
                                appendLine("~~~")
                                appendLine()
                            }
                            result.message?.takeIf { it.isNotBlank() }?.let { message ->
                                appendLine("~~~text")
                                appendLine(message)
                                appendLine("~~~")
                                appendLine()
                            }
                            result.errorCode
                                ?.takeIf { result.output == null && result.message.isNullOrBlank() }
                                ?.let {
                                    appendLine("- errorCode: $it")
                                    appendLine()
                                }
                            if (result.truncated) {
                                appendLine("(truncated)")
                                appendLine()
                            }
                        }
                    }

                    is ConductorItem.Notice -> {
                        appendLine("## ${item.title}")
                        appendLine()
                        appendLine(item.body)
                        appendLine()
                    }

                    is ConductorItem.Error -> {
                        appendLine("## Error: ${item.title}")
                        appendLine()
                        appendLine(item.message)
                        appendLine()
                    }

                    is ConductorItem.Confirmation -> {
                        appendLine("## Pending confirmation: ${item.request.toolName}")
                        appendLine()
                        appendLine(item.request.reason)
                        appendLine()
                        appendLine("~~~json")
                        appendLine(
                            AgentJson.parseObject(item.request.displayArgsJson)
                                ?.let { AgentJson.pretty(it) }
                                ?: item.request.displayArgsJson,
                        )
                        appendLine("~~~")
                        appendLine()
                    }
                }
            }
        }.trim()

    fun apiTraceMarkdown(lastEntries: Int? = null): String = AgentApiTrace.asMarkdown(lastEntries)

    fun hasApiTrace(): Boolean = AgentApiTrace.hasEntries()

    private fun restoreSession() {
        val session = sessionStore.load() ?: return
        sessionStartVersion = session.appVersion.ifBlank { BuildConfig.VERSION_NAME }
        val loadedTranscript = session.transcript.takeIf { validateTranscriptPairing(it) }.orEmpty()
        val repaired = repairRestoredTranscript(loadedTranscript, session.items)
        transcript = repaired.takeIf { validateTranscriptPairing(it) } ?: loadedTranscript
        pendingTurnEvent = session.pendingEvent
        AgentApiTrace.restoreSummaries(session.trace)
        val restoredItems =
            session.items.mapNotNull { item ->
                when (item) {
                    is ConductorItem.Confirmation -> null
                    is ConductorItem.Assistant ->
                        if (item.streaming) item.copy(streaming = false) else item

                    is ConductorItem.Reasoning ->
                        if (item.streaming) item.copy(streaming = false) else item

                    is ConductorItem.Tool ->
                        if (item.status == ToolStatus.RUNNING) {
                            item.copy(
                                status = ToolStatus.CANCELLED,
                                result =
                                    ToolResultView(
                                        callId = item.call.id,
                                        name = item.call.name,
                                        ok = false,
                                        output = null,
                                        errorCode = "interrupted",
                                        message = "Session resumed after the page was left.",
                                        truncated = false,
                                    ),
                            )
                        } else {
                            item
                        }

                    else -> item
                }
            }
        val lastError = restoredItems.lastOrNull() as? ConductorItem.Error
        val canRetry =
            transcript.lastOrNull() is AgentMessage.User &&
                pendingTurnEvent != null &&
                lastError?.title == "agent"
        val versionChanged =
            sessionStartVersion.isNotBlank() && sessionStartVersion != BuildConfig.VERSION_NAME
        if (versionChanged && pendingTurnEvent == null) {
            pendingTurnEvent =
                TurnEvent(
                    "app_updated",
                    "${sessionStartVersion}->${BuildConfig.VERSION_NAME}",
                )
        }
        val displayItems =
            if (versionChanged) {
                restoredItems +
                    ConductorItem.Notice(
                        "version",
                        "App updated from $sessionStartVersion to ${BuildConfig.VERSION_NAME} " +
                            "during this session. Earlier tool results and events may use older semantics.",
                    )
            } else {
                restoredItems
            }
        if (displayItems.isNotEmpty()) {
            _uiState.update { it.copy(items = displayItems, canRetryLastTurn = canRetry) }
        }
    }

    private fun persistSession() {
        val items = _uiState.value.items.filterNot { it is ConductorItem.Confirmation }
        if (transcript.isEmpty() && items.isEmpty() && pendingTurnEvent == null) {
            sessionStore.clear()
            return
        }
        if (!validateTranscriptPairing(transcript)) return
        sessionStore.save(
            transcript = transcript,
            items = items,
            pendingEvent = pendingTurnEvent,
            appVersion = BuildConfig.VERSION_NAME,
            trace = AgentApiTrace.summaries(),
        )
    }

    private fun deviceSummary(): String {
        val configuration = getApplication<Application>().resources.configuration
        val language =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && configuration.locales.size() > 0) {
                configuration.locales[0].toLanguageTag()
            } else {
                Locale.getDefault().toLanguageTag()
            }
        return buildString {
            append("preferredLanguage=$language; ")
            append("timeZone=${TimeZone.getDefault().id}; ")
            append("appVersion=${BuildConfig.VERSION_NAME}; ")
            append("android=${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}); ")
            append("device=${Build.MANUFACTURER} ${Build.MODEL}")
        }
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
        private const val TAILDROP_SEND_TIMEOUT_MS = 120_000L
        private const val TAILDROP_MAX_BYTES = 8L * 1024 * 1024

        // Keep the picker deadline below the tool's 10-minute registry budget
        // so a timeout comes back as a structured cancelled result.
        private const val FILE_PICK_TIMEOUT_MS = 9L * 60L * 1000L
        private const val MAX_SELECTED_FILES = 10
        private const val MAX_RETAINED_SELECTED_FILES = 30
        private const val MAX_FILE_NAME_CHARS = 200
        private const val MAX_FILE_REQUEST_REASON_CHARS = 240
    }
}

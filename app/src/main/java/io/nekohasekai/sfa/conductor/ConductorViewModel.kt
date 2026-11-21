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
import io.nekohasekai.sfa.agent.AgentImageRef
import io.nekohasekai.sfa.agent.AgentJson
import io.nekohasekai.sfa.agent.AgentMessage
import io.nekohasekai.sfa.agent.AgentMetaTools
import io.nekohasekai.sfa.agent.AgentRuntime
import io.nekohasekai.sfa.agent.AgentSanitizer
import io.nekohasekai.sfa.agent.AgentToolCatalog
import io.nekohasekai.sfa.agent.AgentToolDefinition
import io.nekohasekai.sfa.agent.AgentTools
import io.nekohasekai.sfa.agent.DynamicToolExecutor
import io.nekohasekai.sfa.agent.mcp.McpListedTool
import io.nekohasekai.sfa.agent.mcp.McpRuntimes
import io.nekohasekai.sfa.agent.AskUserPort
import io.nekohasekai.sfa.agent.AskUserRequest
import io.nekohasekai.sfa.agent.AskUserResult
import io.nekohasekai.sfa.agent.AskUserTool
import io.nekohasekai.sfa.agent.AgentTailscaleEndpoint
import io.nekohasekai.sfa.agent.AgentTailscalePeer
import io.nekohasekai.sfa.agent.AgentTailscaleSnapshot
import io.nekohasekai.sfa.agent.AgentTurnErrorCode
import io.nekohasekai.sfa.agent.AndroidDeviceSnapshotSource
import io.nekohasekai.sfa.agent.CommandNodeSnapshotSource
import io.nekohasekai.sfa.agent.CommandNodeState
import io.nekohasekai.sfa.agent.ConfirmationRequest
import io.nekohasekai.sfa.agent.ConductorFileStore
import io.nekohasekai.sfa.agent.DeepSeekWebSearchPort
import io.nekohasekai.sfa.agent.DiagnosticsSource
import io.nekohasekai.sfa.agent.DefaultAgentInputGuard
import io.nekohasekai.sfa.agent.FileSelectionPort
import io.nekohasekai.sfa.agent.FileSelectionResult
import io.nekohasekai.sfa.agent.InboxPaths
import io.nekohasekai.sfa.agent.InboxRegistry
import io.nekohasekai.sfa.agent.InputGuardResult
import io.nekohasekai.sfa.agent.LibboxNodeSelectionPort
import io.nekohasekai.sfa.agent.ObservationOffload
import io.nekohasekai.sfa.agent.ObservationOffloadStore
import io.nekohasekai.sfa.agent.PLAN_UPDATE_TOOL
import io.nekohasekai.sfa.agent.PlanClearedBy
import io.nekohasekai.sfa.agent.PlanItem
import io.nekohasekai.sfa.agent.PlanState
import io.nekohasekai.sfa.agent.PlanStore
import io.nekohasekai.sfa.agent.LibboxProbePort
import io.nekohasekai.sfa.agent.ModelProviderSettings
import io.nekohasekai.sfa.agent.OkHttpHttpFetchPort
import io.nekohasekai.sfa.agent.OpenAiCompatibleModelClient
import io.nekohasekai.sfa.agent.ReasoningEffort
import io.nekohasekai.sfa.agent.ReleaseNotesSource
import io.nekohasekai.sfa.agent.SelectedFileInfo
import io.nekohasekai.sfa.agent.SelectedFileRead
import io.nekohasekai.sfa.agent.SelectedImage
import io.nekohasekai.sfa.agent.TaildropPort
import io.nekohasekai.sfa.agent.TaildropSendResult
import io.nekohasekai.sfa.agent.TailscaleStatusSource
import io.nekohasekai.sfa.agent.TelemetryUploadPort
import io.nekohasekai.sfa.agent.ToolCallView
import io.nekohasekai.sfa.agent.ToolCancellation
import io.nekohasekai.sfa.agent.ToolResultView
import io.nekohasekai.sfa.agent.ToolStatus
import io.nekohasekai.sfa.agent.TraceExport
import io.nekohasekai.sfa.agent.VisionAnalysis
import io.nekohasekai.sfa.agent.markdownFence
import io.nekohasekai.sfa.agent.TurnEvent
import io.nekohasekai.sfa.agent.TurnOutcome
import io.nekohasekai.sfa.agent.TurnResult
import io.nekohasekai.sfa.agent.TurnState
import io.nekohasekai.sfa.agent.VisionPort
import io.nekohasekai.sfa.agent.buildCommandNodeState
import io.nekohasekai.sfa.agent.formatUserMessageWithAttachments
import io.nekohasekai.sfa.agent.validateTranscriptPairing
import io.nekohasekai.sfa.BuildConfig
import io.nekohasekai.sfa.constant.ServiceMode
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.compose.screen.tools.TaildropSendFile
import io.nekohasekai.sfa.compose.screen.tools.TaildropSendManager
import io.nekohasekai.sfa.log.AppLog
import io.nekohasekai.sfa.telemetry.TelemetryEventLog
import io.nekohasekai.sfa.telemetry.TelemetryEvents
import io.nekohasekai.sfa.telemetry.TelemetryRuntime
import io.nekohasekai.sfa.utils.CommandClient
import io.nekohasekai.sfa.utils.CommandTarget
import io.nekohasekai.sfa.vendor.Vendor
import io.nekohasekai.sfa.vfs.VfsConsentDecision
import io.nekohasekai.sfa.vfs.VfsConsentUi
import io.nekohasekai.sfa.vfs.VfsConnectTool
import io.nekohasekai.sfa.vfs.VfsRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
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
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** A pending request_files call, shown above the composer until the user picks or cancels. */
data class FileRequestUi(
    val id: String,
    val reason: String,
    val multiple: Boolean,
    val mimeTypes: List<String>,
)

/** Developer-only tool output. It is UI state and is never saved or exported. */
data class ToolConsoleState(
    val title: String,
    val body: String,
    val running: Boolean = false,
)

/** A stale card must not approve the confirmation that replaced it. */
internal fun confirmationMatchesCurrent(
    requestId: Long,
    agentId: Long?,
    commandId: Long?,
): Boolean = (agentId ?: commandId) == requestId

/** Retry only the last real prompt, never a slash command that landed later. */
internal fun retryableUserIndex(items: List<ConductorItem>): Int =
    items.indexOfLast { it is ConductorItem.User && !it.text.startsWith("/") }

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
        val contextUsedTokens: Int = 0,
        val contextLimitTokens: Int = 0,
        val contextPercent: Int = 0,
        /** Transient status shown next to the composer while a turn is active. */
        val turnStatus: String? = null,
        val turnStatusSince: Long = 0L,
        /** An image picked in the composer and waiting for the next sent message. */
        val pendingImage: AgentImageRef? = null,
        /** True until the persisted session has been applied after process start. */
        val sessionRestoring: Boolean = true,
        /** Transient developer tool output; never persisted into the session. */
        val toolConsole: ToolConsoleState? = null,
        /** The current task plan; persisted with the session, not a message. */
        val plan: PlanState? = null,
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

    private val commandConfirmationIds = AtomicLong(0)

    private val apiKeyProvider = AgentApiKeyProviderFactory.create(application)
    private val sessionStore = ConductorSessionStore(application)
    private val sessionWrites =
        Executors.newSingleThreadExecutor { task ->
            Thread(task, "conductor-session").apply { isDaemon = true }
        }

    /** Completes after the persisted session has been applied (or found absent). */
    private val sessionReady = CompletableDeferred<Unit>()

    private val commandClient =
        CommandClient(
            viewModelScope,
            listOf(CommandClient.ConnectionType.Groups, CommandClient.ConnectionType.Outbounds),
            this,
        )

    private val coreConnection =
        CoreConnectionState(
            connect = { commandClient.connect() },
            disconnect = {
                commandClient.disconnect()
                publishNodeState(CommandNodeState())
            },
            isConnected = { nodeState.connected },
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

    private val httpFetchPort = OkHttpHttpFetchPort()

    private val webSearchPort =
        DeepSeekWebSearchPort(
            endpointProvider = ModelProviderSettings::endpoint,
            apiKeyProvider = { apiKeyProvider.apiKey() },
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
    private val imageStore = ConductorImageStore(File(application.filesDir, "conductor/images"))
    private val observationOffloadStore =
        object : ObservationOffloadStore {
            override suspend fun save(
                tool: String,
                json: String,
            ): ObservationOffload? =
                withContext(Dispatchers.IO) {
                    runCatching {
                        val toolName = InboxPaths.fileName(tool, "text/plain").removeSuffix(".txt")
                        val name = "tool-output-$toolName-${System.currentTimeMillis()}.txt"
                        val stored =
                            fileStore.writeResourceBytes(
                                "conductor/resources/artifacts/$name",
                                json.toByteArray(Charsets.UTF_8),
                                overwrite = true,
                            )
                        ObservationOffload(stored.path, stored.size)
                    }.getOrNull()
                }
        }

    private val diagnosticsSource =
        object : DiagnosticsSource {
            override fun conversationMarkdown(): String = conversationTranscript()

            override fun traceMarkdown(
                lastEntries: Int?,
                since: Long?,
            ): TraceExport = AgentApiTrace.export(lastEntries, since)
        }

    private val taildropPort =
        object : TaildropPort {
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
                return try {
                    sendTaildropFiles(peer, sendFiles, names, cancellation)
                } catch (e: Exception) {
                    sendFiles.forEach { runCatching { it.close() } }
                    throw e
                }
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
                        ?: throw IllegalArgumentException(
                            "Unknown file handle '$id'. Use the `id` from request_files, or call request_files again.",
                        )
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
                            throw IllegalArgumentException(
                                "The selected file is binary. Use analyze_image with file_id for images; file_read_selected only reads text.",
                            )
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

            override suspend fun readBytes(
                id: String,
                maxBytes: Int,
            ): SelectedImage {
                val file =
                    synchronized(selectedFilesLock) { selectedFiles[id] }
                        ?: throw IllegalArgumentException(
                            "Unknown file handle '$id'. Use the `id` from request_files, or call request_files again.",
                        )
                return withContext(Dispatchers.IO) {
                    val cap = maxBytes.coerceIn(1, MAX_IMAGE_READ_BYTES)
                    val resolver = getApplication<Application>().contentResolver
                    val stream =
                        resolver.openInputStream(file.uri)
                            ?: throw IllegalStateException("The selected file is no longer available.")
                    stream.use {
                        val buffer = ByteArray(8 * 1024)
                        val collected = java.io.ByteArrayOutputStream()
                        var total = 0
                        while (true) {
                            val read = it.read(buffer)
                            if (read <= 0) break
                            total += read
                            if (total > cap) {
                                throw IllegalArgumentException(
                                    "The selected image is larger than ${cap / (1024 * 1024)} MB.",
                                )
                            }
                            collected.write(buffer, 0, read)
                        }
                        SelectedImage(
                            id = id,
                            name = file.info.name,
                            mimeType = file.info.mimeType ?: resolver.getType(file.uri) ?: "image/jpeg",
                            bytes = collected.toByteArray(),
                        )
                    }
                }
            }
        }

    /**
     * Bridges ask_user to an inline question card. The card stays in the
     * conversation after it is answered, so the selected labels remain
     * readable and are also what the model receives.
     */
    private val askUserPort =
        object : AskUserPort {
            override suspend fun ask(
                request: AskUserRequest,
                cancellation: ToolCancellation?,
            ): AskUserResult {
                val pending =
                    PendingAskUser(
                        id = UUID.randomUUID().toString().take(8),
                        deferred = CompletableDeferred(),
                    )
                synchronized(askUserLock) {
                    if (pendingAskUser != null) {
                        return AskUserResult(
                            cancelled = true,
                            message = "Another question is already waiting for the user.",
                            reason = "busy",
                        )
                    }
                    pendingAskUser = pending
                }
                append(
                    ConductorItem.Question(
                        id = pending.id,
                        question = request.question,
                        header = request.header.orEmpty(),
                        options = request.options,
                        multiple = request.multiple,
                    ),
                )
                persistSession()
                cancellation?.onCancel {
                    completeAskUser(
                        pending,
                        AskUserResult(cancelled = true, message = "The request was cancelled.", reason = "stopped"),
                    )
                }
                return try {
                    val result =
                        withTimeoutOrNull(AskUserTool.ASK_USER_TIMEOUT_MS) { pending.deferred.await() }
                            ?: AskUserResult(
                                cancelled = true,
                                message = "The user did not answer in time.",
                                reason = "timeout",
                            )
                    settleQuestion(pending.id, result)
                    result
                } finally {
                    synchronized(askUserLock) {
                        if (pendingAskUser === pending) pendingAskUser = null
                    }
                }
            }
        }

    private data class PendingAskUser(
        val id: String,
        val deferred: CompletableDeferred<AskUserResult>,
    )

    private val askUserLock = Any()
    private var pendingAskUser: PendingAskUser? = null

    fun answerAskUser(
        id: String,
        selected: List<String>,
    ) {
        val pending = synchronized(askUserLock) { pendingAskUser?.takeIf { it.id == id } } ?: return
        val question =
            _uiState.value.items
                .filterIsInstance<ConductorItem.Question>()
                .lastOrNull { it.id == id } ?: return
        val allowed = selected.filter { it in question.options }
        if (allowed.isEmpty()) return
        val normalized = if (question.multiple) allowed.distinct() else allowed.take(1)
        completeAskUser(pending, AskUserResult(selected = normalized))
    }

    fun cancelAskUser(id: String) {
        val pending = synchronized(askUserLock) { pendingAskUser?.takeIf { it.id == id } } ?: return
        completeAskUser(
            pending,
            AskUserResult(
                cancelled = true,
                message = "The user dismissed the question.",
                reason = "dismissed",
            ),
        )
    }

    private fun completeAskUser(
        pending: PendingAskUser,
        result: AskUserResult,
    ) {
        synchronized(askUserLock) {
            if (pendingAskUser === pending) pendingAskUser = null
        }
        settleQuestion(pending.id, result)
        pending.deferred.complete(result)
    }

    private fun cancelPendingAskUser(
        message: String,
        reason: String = "stopped",
    ) {
        val pending = synchronized(askUserLock) { pendingAskUser } ?: return
        completeAskUser(pending, AskUserResult(cancelled = true, message = message, reason = reason))
    }

    /** Marks the card answered or cancelled; later calls cannot overwrite a settled card. */
    private fun settleQuestion(
        id: String,
        result: AskUserResult,
    ) {
        updateUi {
            val settled =
                items.map { item ->
                    if (item is ConductorItem.Question && item.id == id && !item.answered && !item.cancelled) {
                        if (result.cancelled) {
                            item.copy(cancelled = true)
                        } else {
                            item.copy(answered = true, selected = result.selected)
                        }
                    } else {
                        item
                    }
                }
            copy(items = settled)
        }
        persistSession()
    }

    private val releaseNotesSource =
        object : ReleaseNotesSource {
            override suspend fun current() =
                Vendor.releaseNotes()
                    ?: throw IllegalStateException("Release notes for this version are not available.")
        }

    private val agentConfig = AgentConfig()

    private val vfsConnectionManager = VfsRuntime.manager(getApplication())

    val vfsConsent: StateFlow<VfsConsentUi?> = VfsRuntime.consent

    fun resolveVfsConsent(decision: VfsConsentDecision) {
        VfsRuntime.resolveVfsConsent(decision)
    }

    private val visionPort: VisionPort by lazy {
        object : VisionPort {
            override suspend fun analyze(
                image: ByteArray,
                mimeType: String,
                question: String,
            ): VisionAnalysis {
                val key =
                    apiKeyProvider.apiKey()
                        ?: throw IllegalStateException("API key is not configured for the selected provider.")
                return modelClient.analyzeImage(image, mimeType, question, key)
            }
        }
    }

    private val planStore = PlanStore()

    private val toolExecutor =
        DynamicToolExecutor(
            AgentTools(
                nodeSource = nodeSource,
                deviceSource = deviceSource,
                probePort = probePort,
                selectionPort = selectionPort,
                tailscaleStatusSource = tailscaleSource,
                telemetryUploadPort = telemetryPort,
                httpFetchPort = httpFetchPort,
                fileStore = fileStore,
                webSearchPort = webSearchPort,
                webSearchAvailable = ModelProviderSettings::supportsWebSearch,
            ).all +
                AgentDataTools(
                    fileStore = fileStore,
                    diagnostics = diagnosticsSource,
                    taildrop = taildropPort,
                    fileSelection = fileSelectionPort,
                    releaseNotes = releaseNotesSource,
                    vision = visionPort,
                    chatImage = { imageStore.load(it) },
                    planStore = planStore,
                ).all +
                listOf(VfsConnectTool(vfsConnectionManager), AskUserTool(askUserPort)),
            toolTimeoutMs = agentConfig.toolTimeoutMs,
        )

    private fun consoleToolDefinitions(): List<AgentToolDefinition> =
        toolExecutor.definitions() + AgentMetaTools.consoleDefinitions()

    private val _toolDefinitions = MutableStateFlow(consoleToolDefinitions())
    val toolDefinitions: StateFlow<List<AgentToolDefinition>> = _toolDefinitions.asStateFlow()

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
            brief = { Settings.conductorBrief },
            modelLimits = { ModelProviderSettings.limitsFor(ModelProviderSettings.endpoint()) },
            imageLoader = { id -> imageStore.load(id) },
            offloadStore = observationOffloadStore,
            planBlock = { planStore.state.value?.recitation() },
        )

    private var transcript: List<AgentMessage> = emptyList()
    private var pendingTurnEvent: TurnEvent? = null
    private val toolStartedAt = mutableMapOf<String, Long>()
    private var sessionStartVersion: String = BuildConfig.VERSION_NAME
    var transcriptScrollIndex: Int = 0
    var transcriptScrollOffset: Int = 0
    var transcriptStickToBottom: Boolean = true
    private var agentJob: Job? = null
    private var stopRequested = false

    @Volatile
    private var pendingAgentConfirmation: CompletableDeferred<Boolean>? = null
    private var pendingAgentConfirmationId: Long? = null

    @Volatile
    private var autoApproveAgentSafeTools: Boolean = false

    @Volatile
    private var autoApproveAgentBatchId: String? = null

    private var pendingCommandConfirmation: Pair<String, String>? = null
    private var pendingCommandConfirmationId: Long? = null
    private var pendingCommandAsTool = false

    private data class PendingFileRequest(
        val id: String,
        val multiple: Boolean,
        val deferred: CompletableDeferred<FileSelectionResult>,
    )

    private data class SelectedFile(
        val info: SelectedFileInfo,
        val uri: Uri,
        val inboxEligible: Boolean = true,
    )

    private val selectedFilesLock = Any()
    private val selectedFiles = LinkedHashMap<String, SelectedFile>()
    private var pendingFileRequest: PendingFileRequest? = null

    private val _fileRequest = MutableStateFlow<FileRequestUi?>(null)
    val fileRequest: StateFlow<FileRequestUi?> = _fileRequest.asStateFlow()

    private val _draft = MutableStateFlow("")
    val draft: StateFlow<String> = _draft.asStateFlow()

    @Volatile
    private var draftTouched = false

    init {
        AgentApiTrace.setAppVersion(BuildConfig.VERSION_NAME)
        // Bind before any record can be written; the file read itself stays on
        // IO so a large persisted trace cannot jank the first frame.
        AgentApiTrace.bindPersistence(
            File(getApplication<Application>().filesDir, "conductor/api-trace.json"),
        )
        _uiState.update { it.copy(agentSupported = apiKeyProvider.supported) }
        viewModelScope.launch {
            planStore.state.collect { plan ->
                updateUi { copy(plan = plan) }
                persistSession()
            }
        }
        viewModelScope.launch {
            // A cancelled restore leaves this false, so onCleared never saves
            // the empty startup state over the stored session.
            var applied = false
            var draft: String? = null
            try {
                val restored =
                    withContext(Dispatchers.IO) {
                        AgentApiTrace.loadPersisted()
                        runCatching { fileStore.ensureWorkspace() }
                        sessionStore.load() to sessionStore.loadDraft()
                    }
                applyRestoredSession(restored.first)
                draft = restored.second
                applied = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.warning("conductor restore failed: ${e.javaClass.simpleName}")
                applied = true
            } finally {
                if (!draftTouched) _draft.value = draft.orEmpty()
                _uiState.update { it.copy(sessionRestoring = false) }
                if (applied) sessionReady.complete(Unit)
            }
        }
    }

    fun onServiceState(
        serviceStatus: Status,
        remoteControl: Boolean,
        remoteConnected: Boolean,
    ) {
        this.remoteControl = remoteControl
        localServiceStarted = !remoteControl && serviceStatus == Status.Started
        val shouldConnect = if (remoteControl) remoteConnected else serviceStatus == Status.Started
        coreConnection.onServiceState(shouldConnect)
    }

    fun saveDraft(text: String) {
        val normalized = text.take(ConductorSessionStore.MAX_DRAFT)
        draftTouched = true
        if (_draft.value != normalized) _draft.value = normalized
        sessionStore.saveDraft(normalized)
    }

    fun submit(raw: String) {
        val images = listOfNotNull(_uiState.value.pendingImage)
        if (sessionReady.isCompleted) {
            submitInternal(raw = raw, images = images, clearPending = true)
            return
        }
        // The stored conversation is still loading. Starting a turn now would
        // use an empty history and persist over the stored session.
        viewModelScope.launch {
            sessionReady.await()
            submitInternal(raw = raw, images = images, clearPending = true)
        }
    }

    /**
     * Imports one picked image and holds it in the composer until the next
     * message is sent. Import and compression run off the main thread.
     */
    suspend fun importImage(uri: Uri): AgentImageRef? =
        try {
            val ref = ConductorImageImporter.import(getApplication(), uri, imageStore)
            updateUi { copy(pendingImage = ref) }
            ref
        } catch (e: Exception) {
            TelemetryEventLog.recordAsync(
                getApplication(),
                "conductor_image",
                mapOf(
                    "stage" to "import",
                    "error" to e.javaClass.simpleName,
                    "detail" to TelemetryEvents.scrub(e.message.orEmpty(), 160),
                ),
            )
            append(
                ConductorItem.Notice(
                    "image",
                    e.message ?: "Could not import the selected image.",
                ),
            )
            null
        }

    fun clearPendingImage() {
        updateUi { copy(pendingImage = null) }
    }

    fun imageFile(id: String): File? = imageStore.file(id)

    fun resourceFile(path: String): File? =
        runCatching {
            val normalized = conductorLocalResourcePath(path) ?: return null
            fileStore.resolveResource(normalized)
        }.getOrNull()

    /** Reads a Markdown local link without executing it; link paths stay inside conductor/resources/. */
    fun previewLocalFile(path: String): LocalFilePreview? {
        val normalized = conductorLocalResourcePath(path) ?: return null
        return runCatching {
            val result = fileStore.readText(normalized, maxBytes = 256 * 1024)
            LocalFilePreview(path = result.path, text = result.text, truncated = result.truncated)
        }.getOrElse { error ->
            LocalFilePreview(
                path = normalized,
                text = "",
                truncated = false,
                error = error.message ?: "Could not read the file.",
            )
        }
    }

    private fun submitInternal(
        raw: String,
        images: List<AgentImageRef>,
        clearPending: Boolean,
    ) {
        val text = raw.trim()
        if (text.isEmpty() && images.isEmpty()) return
        val isClear = text == "/clear" || text.startsWith("/clear ")
        if (_uiState.value.isRunning) {
            if (isClear) {
                clearConversation()
                return
            }
            append(ConductorItem.Notice("busy", "Wait for the current command to finish."))
            return
        }
        if (isClear) {
            clearConversation()
            return
        }
        images.forEach { ref ->
            imageStore.file(ref.id)?.let { file ->
                InboxRegistry.shared.registerFile(ref.id, ref.name, ref.mimeType, file)
            }
        }
        val slash = if (text.startsWith("/")) ConductorCommands.parse(text) else null
        if (slash?.command?.action == ConductorCommandKind.TOOL) {
            if (images.isNotEmpty()) {
                updateUi {
                    copy(toolConsole = ToolConsoleState("Tool", "Remove the image before running /tool."))
                }
            } else if (_uiState.value.isRunning) {
                updateUi {
                    copy(
                        toolConsole =
                            ToolConsoleState("Tool", "Wait for the current command to finish."),
                    )
                }
            } else {
                runToolCommand(slash.tail)
            }
            return
        }
        if (text.startsWith("/") && images.isNotEmpty()) {
            append(ConductorItem.Notice("conductor", "Remove the image before running a command."))
            return
        }
        updateUi { copy(canRetryLastTurn = false) }

        if (!text.startsWith("/")) {
            if (text.isNotEmpty() && DefaultAgentInputGuard.check(text) is InputGuardResult.Blocked) {
                append(
                    ConductorItem.Notice(
                        "conductor",
                        "This message may contain a subscription link, token or log excerpt. " +
                            "It was not sent. Remove the sensitive part and try again.",
                    ),
                )
                return
            }
            append(ConductorItem.User(text, System.currentTimeMillis(), images))
            if (clearPending) updateUi { copy(pendingImage = null) }
            // Persist before the model call: a process death mid-turn must not
            // lose the user message. Restore reconciles it into the transcript.
            persistSession()
            submitAgent(text, images)
            return
        }

        append(ConductorItem.User(text, System.currentTimeMillis()))
        persistSession()

        val parsed =
            slash
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

            ConductorCommandKind.TOOL -> runToolCommand(parsed.tail)

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

            ConductorCommandKind.BRIEF -> {
                when (
                    val outcome =
                        ConductorBriefCommand.apply(parsed.args, Settings.conductorBrief)
                ) {
                    is ConductorBriefCommand.Outcome.Updated -> {
                        Settings.conductorBrief = outcome.value
                        append(ConductorItem.Notice("brief", outcome.message))
                    }
                    is ConductorBriefCommand.Outcome.Shown -> {
                        append(ConductorItem.Notice("brief", outcome.message))
                    }
                    is ConductorBriefCommand.Outcome.Invalid -> {
                        append(ConductorItem.Error("brief", outcome.message))
                    }
                }
                persistSession()
            }

            ConductorCommandKind.ROUTING -> {
                when (
                    val outcome =
                        ConductorRoutingCommand.apply(parsed.args, Settings.conductorToolRouting)
                ) {
                    is ConductorRoutingCommand.Outcome.Updated -> {
                        Settings.conductorToolRouting = outcome.value
                        append(ConductorItem.Notice("routing", outcome.message))
                    }
                    is ConductorRoutingCommand.Outcome.Shown -> {
                        append(ConductorItem.Notice("routing", outcome.message))
                    }
                    is ConductorRoutingCommand.Outcome.Invalid -> {
                        append(ConductorItem.Error("routing", outcome.message))
                    }
                }
                persistSession()
            }

            ConductorCommandKind.NODES -> runTool("list_nodes")
            ConductorCommandKind.NETWORK -> runTool("system_snapshot")
            ConductorCommandKind.TAILSCALE -> runTailscale()
            ConductorCommandKind.TELEMETRY_UPLOAD -> runTelemetryUpload()
            ConductorCommandKind.PROBE -> runProbe(parsed.args)
        }
    }

    fun confirmAgent() {
        val pending = pendingAgentConfirmation ?: return
        pendingAgentConfirmationId = null
        removeConfirmationItems()
        pending.complete(true)
    }

    /**
     * Approve all safe tools for the rest of the turn. The runtime still asks
     * for ALWAYS tools (probes, network changes, Taildrop); ViewModel answers
     * later safe confirmations without drawing another card.
     */
    fun confirmAllSafeAgent(requestId: Long) {
        if (!isCurrentConfirmation(requestId) || pendingAgentConfirmation == null) return
        val request =
            _uiState.value.items
                .filterIsInstance<ConductorItem.Confirmation>()
                .lastOrNull()
                ?.request
                ?: return
        if (!request.allowTurnApproval) {
            confirmAgent()
            return
        }
        autoApproveAgentSafeTools = true
        confirmAgent()
    }

    /**
     * Approve the safe calls remaining in this assistant batch. Destructive
     * ALWAYS tools are marked non-approvable by the runtime and still draw an
     * individual card.
     */
    fun confirmBatchAgent(requestId: Long) {
        if (!isCurrentConfirmation(requestId) || pendingAgentConfirmation == null) return
        val request =
            _uiState.value.items
                .filterIsInstance<ConductorItem.Confirmation>()
                .lastOrNull()
                ?.request
                ?: return
        if (!request.allowBatchApproval || request.batchApprovableRemaining <= 1) {
            confirmAgent()
            return
        }
        autoApproveAgentBatchId = request.batchId
        confirmAgent()
    }

    fun cancelAgent() {
        autoApproveAgentSafeTools = false
        autoApproveAgentBatchId = null
        val pending = pendingAgentConfirmation ?: return
        pendingAgentConfirmationId = null
        removeConfirmationItems()
        pending.complete(false)
    }

    fun stopAgent() {
        if (!_uiState.value.isRunning || stopRequested) return
        stopRequested = true
        autoApproveAgentSafeTools = false
        autoApproveAgentBatchId = null
        cancelPendingFileRequest("The turn was stopped.")
        cancelPendingAskUser("The turn was stopped.")
        pendingAgentConfirmation?.complete(false)
        pendingAgentConfirmationId = null
        pendingCommandConfirmationId = null
        removeConfirmationItems()
        runtime.cancel()
        McpRuntimes.get(getApplication()).cancelActive()
        viewModelScope.launch {
            delay(STOP_CONFIRM_TIMEOUT_MS)
            if (!_uiState.value.isRunning) return@launch
            // A blocked provider or tool call may never observe the flag. Do not
            // cancel the job or persist a half-turn: the UI items are ahead of
            // the model transcript. Keep isRunning until the worker actually
            // exits, otherwise the next turn loses the user/tool history.
            AppLog.info("conductor stop timeout waiting for tool")
            setTurnStatus("Stopping…")
            append(
                ConductorItem.Notice(
                    "conductor",
                    "Stop requested; the current tool has not confirmed cancellation yet. " +
                        "Waiting for it to exit.",
                ),
            )
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
                val measured =
                    described.second.takeIf { it >= 0L }
                        ?: measureUriSize(resolver, uri)
                picked +=
                    SelectedFile(
                        info =
                            SelectedFileInfo(
                                id = UUID.randomUUID().toString().take(8),
                                name = described.first,
                                size = measured.coerceAtLeast(0L),
                                mimeType = described.third,
                                inboxReady = measured >= 0L,
                            ),
                        uri = uri,
                        inboxEligible = measured >= 0L,
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
                        picked.forEach { file ->
                            selectedFiles[file.info.id] = file
                            if (file.inboxEligible) {
                                InboxRegistry.shared.register(
                                    id = file.info.id,
                                    originalName = file.info.name,
                                    mimeType = file.info.mimeType ?: "application/octet-stream",
                                    size = file.info.size,
                                    source = UriInboxSource(resolver, file.uri),
                                )
                            }
                        }
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
    /** The host activity is finishing; a picker result can no longer arrive. */
    fun onHostFinishing() {
        cancelPendingFileRequest("The app was closed while waiting for file selection.")
        cancelPendingAskUser("The app was closed while waiting for an answer.")
        VfsRuntime.cancelPendingConsent("The app was closed while waiting for VFS confirmation.")
    }

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
        _fileRequest.update { current -> if (current?.id == request.id) null else current }
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
        return Triple(displayName, size, mimeType)
    }

    private fun measureUriSize(
        resolver: android.content.ContentResolver,
        uri: Uri,
    ): Long {
        runCatching {
            resolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                if (descriptor.length >= 0) return descriptor.length
            }
        }
        runCatching {
            resolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) return total
                    total += read
                    if (total > MAX_MEASURE_BYTES) return -1L
                }
            }
        }
        return -1L
    }

    /**
     * Re-runs the user message whose turn failed before producing an answer.
     * The failed message is still in the model transcript, so drop it there and
     * let [submit] add it back as the new turn; the UI keeps a single copy.
     */
    fun retryLastTurn() {
        if (_uiState.value.isRunning || !_uiState.value.canRetryLastTurn) return
        val items = _uiState.value.items
        val lastUserIndex = retryableUserIndex(items)
        if (lastUserIndex < 0) return
        if (transcript.lastOrNull() !is AgentMessage.User) return
        val user = items[lastUserIndex] as ConductorItem.User
        transcript = transcript.dropLast(1)
        updateUi {
            copy(
                items = items.take(lastUserIndex),
                canRetryLastTurn = false,
            )
        }
        submitInternal(
            raw = user.text,
            images = user.images,
            clearPending = false,
        )
    }

    private fun isCurrentConfirmation(requestId: Long): Boolean {
        if (confirmationMatchesCurrent(requestId, pendingAgentConfirmationId, pendingCommandConfirmationId)) {
            return true
        }
        removeConfirmationItem(requestId)
        return false
    }

    private fun removeConfirmationItem(requestId: Long) {
        updateUi {
            copy(
                items =
                    items.filterNot {
                        it is ConductorItem.Confirmation && it.request.id == requestId
                    },
            )
        }
    }

    fun confirmConfirmation(requestId: Long) {
        if (!isCurrentConfirmation(requestId)) return
        if (pendingAgentConfirmation != null) {
            confirmAgent()
        } else {
            confirmCommand()
        }
    }

    fun cancelConfirmation(requestId: Long) {
        if (!isCurrentConfirmation(requestId)) return
        if (pendingAgentConfirmation != null) {
            cancelAgent()
        } else {
            cancelCommand()
        }
    }

    fun confirmCommand() {
        val pending = pendingCommandConfirmation ?: return
        val asTool = pendingCommandAsTool
        pendingCommandConfirmation = null
        pendingCommandConfirmationId = null
        pendingCommandAsTool = false
        removeConfirmationItems()
        invokeCommandTool(pending.first, pending.second, pending.first, asTool)
    }

    fun cancelCommand() {
        val asTool = pendingCommandAsTool
        pendingCommandConfirmation = null
        pendingCommandConfirmationId = null
        pendingCommandAsTool = false
        removeConfirmationItems()
        updateUi { copy(isRunning = false, toolConsole = if (asTool) null else toolConsole) }
        if (!asTool) {
            append(ConductorItem.Notice("command", "Cancelled."))
            persistSession()
        }
    }

    private fun clearConversation() {
        runtime.cancel()
        McpRuntimes.get(getApplication()).cancelActive()
        agentJob?.cancel()
        agentJob = null
        stopRequested = false
        runtime.resetCancel()
        autoApproveAgentSafeTools = false
        autoApproveAgentBatchId = null
        pendingAgentConfirmation = null
        pendingAgentConfirmationId = null
        pendingTurnEvent = null
        pendingCommandConfirmation = null
        pendingCommandConfirmationId = null
        pendingCommandAsTool = false
        updateUi { copy(toolConsole = null) }
        cancelPendingFileRequest("The conversation was cleared.")
        cancelPendingAskUser("The conversation was cleared.")
        synchronized(selectedFilesLock) { selectedFiles.clear() }
        InboxRegistry.shared.clear()
        sessionStartVersion = BuildConfig.VERSION_NAME
        toolStartedAt.clear()
        transcript = emptyList()
        planStore.restore(null)
        updateUi {
            copy(
                items = emptyList(),
                plan = null,
                isRunning = false,
                canRetryLastTurn = false,
                contextUsedTokens = 0,
                contextLimitTokens = 0,
                contextPercent = 0,
                turnStatus = null,
                turnStatusSince = 0L,
                pendingImage = null,
            )
        }
        sessionWrites.execute { sessionStore.clear() }
        imageStore.clear()
        AgentApiTrace.clear()
    }

    override fun onCleared() {
        if (!_uiState.value.isRunning) {
            persistSession()
        }
        runtime.cancel()
        McpRuntimes.get(getApplication()).cancelActive()
        cancelPendingFileRequest("The conversation was closed.")
        cancelPendingAskUser("The conversation was closed.")
        synchronized(selectedFilesLock) { selectedFiles.clear() }
        agentJob?.cancel()
        commandClient.disconnect()
        ModelProviderSettings.unpin()
        sessionWrites.shutdown()
        super.onCleared()
    }

    private fun submitAgent(
        text: String,
        images: List<AgentImageRef>,
    ) {
        if (!apiKeyProvider.supported) {
            append(ConductorItem.Error("agent", "The Conductor agent requires Android 7.0 or newer."))
            return
        }
        stopRequested = false
        runtime.resetCancel()
        autoApproveAgentSafeTools = false
        autoApproveAgentBatchId = null
        updateUi {
            copy(
                isRunning = true,
                canRetryLastTurn = false,
                turnStatus = "Preparing tools",
                turnStatusSince = SystemClock.uptimeMillis(),
            )
        }
        val initialEvent = pendingTurnEvent
        val assistantCountBefore = _uiState.value.items.count { it is ConductorItem.Assistant }
        agentJob =
            viewModelScope.launch {
                refreshMcpTools()
                if (stopRequested) {
                    withContext(Dispatchers.Main) {
                        updateUi {
                            copy(
                                isRunning = false,
                                turnStatus = null,
                                turnStatusSince = 0L,
                            )
                        }
                        append(ConductorItem.Notice("conductor", "Stopped by user."))
                        persistSession()
                    }
                    return@launch
                }
                ModelProviderSettings.pinForTurn()
                AgentApiTrace.beginTurn()
                val result =
                    try {
                        runtime.runTurn(
                            userText = text,
                            history = transcript,
                            deviceSummary = deviceSummary(),
                            onEvent = ::handleAgentEvent,
                            confirm = { awaitAgentConfirmation() },
                            initialEvent = initialEvent,
                            images = images,
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        val message = AgentSanitizer.sanitizeText(e.message ?: "Agent failed.")
                        TurnResult(
                            messages =
                                listOf(
                                    AgentMessage.User(
                                        formatUserMessageWithAttachments(text, initialEvent, images),
                                        images = images,
                                    ),
                                ),
                            outcome =
                                TurnOutcome.Failed(
                                    AgentError(
                                        AgentTurnErrorCode.UNKNOWN,
                                        message,
                                    ),
                                ),
                            pendingEvent = initialEvent ?: TurnEvent("turn_failed", "exception", message),
                        )
                    } finally {
                        withContext(NonCancellable + Dispatchers.IO) {
                            AgentApiTrace.endTurn()
                        }
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
                    updateUi {
                        copy(
                            isRunning = false,
                            canRetryLastTurn = canRetry,
                            turnStatus = null,
                            turnStatusSince = 0L,
                        )
                    }
                    pendingAgentConfirmation = null
                    pendingAgentConfirmationId = null
                    pendingCommandConfirmationId = null
                    removeConfirmationItems()
                    stopRequested = false
                    autoApproveAgentSafeTools = false
                    autoApproveAgentBatchId = null
                    ModelProviderSettings.unpin()
                    if (outcome is TurnOutcome.Failed) {
                        TelemetryEventLog.recordAsync(
                            getApplication(),
                            "conductor_turn",
                            mapOf(
                                "result" to "failed",
                                "code" to outcome.error.code.name.lowercase(Locale.US),
                                "detail" to TelemetryEvents.scrub(outcome.error.message, 160),
                            ),
                        )
                    }
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
                            append(ConductorItem.Notice("conductor", "Stopped by user."))

                        is TurnOutcome.Completed -> Unit
                    }
                    if (result.messages.isNotEmpty()) {
                        AgentApiTrace.lastUsage()?.let { usage ->
                            val contextPercent =
                                _uiState.value.contextPercent
                                    .takeIf { _uiState.value.contextLimitTokens > 0 }
                            updateUi {
                                copy(
                                    items =
                                        items.attachUsage(
                                            usage,
                                            contextPercent,
                                            assistantCountBefore,
                                        ),
                                )
                            }
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
        // This path appends items without going through submit; wait so a
        // restore cannot replace them with the stored conversation.
        sessionReady.await()
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
                append(ConductorItem.User(raw.trim(), System.currentTimeMillis()))
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
        if (!stopRequested) {
            toolExecutor.setExtras(mcp.tools())
            _toolDefinitions.value = consoleToolDefinitions()
        }
    }

    internal fun refreshToolCatalog() {
        viewModelScope.launch {
            refreshMcpTools()
            _toolDefinitions.value = consoleToolDefinitions()
        }
    }

    private fun handleAgentEvent(event: AgentEvent) {
        when (event) {
            is AgentEvent.AssistantMessage -> {
                finishReasoning()
                appendAssistantMessage(event.text)
                clearTurnStatus()
            }

            is AgentEvent.AssistantDelta -> {
                appendAssistantDelta(event.text)
                if (event.text.isNotBlank()) setTurnStatus("Receiving response")
            }

            is AgentEvent.ReasoningDelta -> appendReasoningDelta(event.text)
            is AgentEvent.StreamReset -> {
                updateUi { copy(items = items.dropStreamingOutput()) }
            }
            is AgentEvent.ToolStarted -> {
                finishReasoning()
                finishStreamingAssistant()
                toolStartedAt[event.call.id] = SystemClock.uptimeMillis()
                AppLog.info("conductor tool=${event.call.name} started")
                if (event.call.name == PLAN_UPDATE_TOOL) {
                    setTurnStatus("Updating plan")
                } else {
                    append(ConductorItem.Tool(event.call, ToolStatus.RUNNING))
                    setTurnStatus("Running ${event.call.name}")
                }
            }

            is AgentEvent.ToolFinished -> {
                val elapsedMs =
                    toolStartedAt.remove(event.result.callId)?.let { startedAt ->
                        SystemClock.uptimeMillis() - startedAt
                    }
                val waitMs = event.result.confirmationMs
                val workMs =
                    if (elapsedMs != null && waitMs != null) {
                        (elapsedMs - waitMs).coerceAtLeast(0L)
                    } else {
                        null
                    }
                AppLog.info(
                    "conductor tool=${event.result.name} ok=${event.result.ok} " +
                        "code=${event.result.errorCode ?: "-"} ms=${elapsedMs ?: "-"} " +
                        "wait=${waitMs ?: "-"} work=${workMs ?: "-"}",
                )
                if (elapsedMs != null) {
                    TelemetryEventLog.recordAsync(
                        getApplication<Application>(),
                        "conductor_tool",
                        buildMap {
                            put("tool", telemetryToolName(event.result.name))
                            put("ms", elapsedMs)
                            waitMs?.let { put("confirmMs", it) }
                            workMs?.let { put("workMs", it) }
                            put("ok", event.result.ok)
                            put("code", event.result.errorCode ?: "-")
                            if (!event.result.ok) {
                                put(
                                    "detail",
                                    TelemetryEvents.scrub(event.result.message.orEmpty(), 160),
                                )
                            }
                        },
                    )
                }
                updateToolResult(event.result)
            }
            is AgentEvent.ConfirmationRequested -> {
                AppLog.info("conductor confirm=${event.request.toolName}")
                val batchApproved =
                    event.request.allowBatchApproval &&
                        autoApproveAgentBatchId != null &&
                        event.request.batchId == autoApproveAgentBatchId
                if ((autoApproveAgentSafeTools && event.request.allowTurnApproval) || batchApproved) {
                    pendingAgentConfirmationId = event.request.id
                    pendingAgentConfirmation =
                        CompletableDeferred<Boolean>().apply { complete(true) }
                } else {
                    finishReasoning()
                    finishStreamingAssistant()
                    pendingAgentConfirmationId = event.request.id
                    pendingAgentConfirmation = CompletableDeferred()
                    removeConfirmationItems()
                    append(ConductorItem.Confirmation(event.request))
                    setTurnStatus("Waiting for your approval")
                }
            }

            is AgentEvent.Notice -> {
                finishReasoning()
                finishStreamingAssistant()
                append(ConductorItem.Notice("conductor", event.text))
                if (event.text.startsWith("The provider request failed; retrying")) {
                    setTurnStatus("Provider request failed; retrying")
                }
            }

            is AgentEvent.ContextUsage ->
                updateUi {
                    copy(
                        contextUsedTokens = event.usedTokens,
                        contextLimitTokens = event.limitTokens,
                        contextPercent = event.percent,
                    )
                }

            is AgentEvent.StateChanged ->
                when (val state = event.state) {
                    is TurnState.CallingModel -> {
                        appendReasoningPlaceholder()
                        setTurnStatus("Waiting for the provider")
                    }

                    is TurnState.RunningTool -> setTurnStatus("Running ${state.call.name}")
                    is TurnState.AwaitingConfirmation -> setTurnStatus("Waiting for your approval")
                    TurnState.Idle, is TurnState.Cancelling -> Unit
                    is TurnState.Completed,
                    is TurnState.Cancelled,
                    is TurnState.Failed,
                    -> clearTurnStatus()
                }

            is AgentEvent.TurnStarted -> Unit
            is AgentEvent.TurnFinished -> {
                finishReasoning()
                finishStreamingAssistant()
                toolStartedAt.clear()
                clearTurnStatus()
            }
        }
    }

    private fun setTurnStatus(text: String) {
        updateUi {
            if (turnStatus == text && turnStatusSince != 0L) {
                this
            } else {
                copy(turnStatus = text, turnStatusSince = SystemClock.uptimeMillis())
            }
        }
    }

    private fun clearTurnStatus() {
        updateUi {
            if (turnStatus == null) this else copy(turnStatus = null, turnStatusSince = 0L)
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
        if (result.name == PLAN_UPDATE_TOOL) {
            if (!result.ok) {
                append(ConductorItem.Notice("plan", result.message ?: "The plan could not be updated."))
            }
            return
        }
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
        asTool: Boolean = false,
        refreshMcp: Boolean = false,
    ) {
        if (_uiState.value.isRunning) {
            append(ConductorItem.Notice("busy", "Wait for the current command to finish."))
            return
        }
        updateUi { copy(isRunning = true) }
        viewModelScope.launch {
            if (refreshMcp) refreshMcpTools()
            val result = invokeRegistryUserCommand(name, argsJson)
            withContext(Dispatchers.Main) {
                updateUi { copy(isRunning = false) }
                if (result.confirmation != null) {
                    showCommandConfirmation(name, result.confirmation!!, asTool)
                } else if (asTool) {
                    appendDirectToolResult(name, argsJson, result)
                } else {
                    appendToolResult(title, result)
                    persistSession()
                }
            }
        }
    }

    private fun runToolCommand(tail: String) {
        if (_uiState.value.isRunning) {
            updateUi {
                copy(
                    toolConsole =
                        ToolConsoleState("Tool", "Wait for the current command to finish."),
                )
            }
            return
        }
        updateUi {
            copy(
                isRunning = true,
                toolConsole = ToolConsoleState("Tool", "Reading tools…", running = true),
            )
        }
        viewModelScope.launch {
            refreshMcpTools()
            val definitions = consoleToolDefinitions()
            val agentDefinitions = toolExecutor.definitions()
            val text = tail.trim()
            val name = text.takeWhile { !it.isWhitespace() }
            val argsJson = text.drop(name.length).trim()
            withContext(Dispatchers.Main) {
                when {
                    text.isEmpty() -> {
                        updateUi {
                            copy(
                                isRunning = false,
                                toolConsole =
                                    ToolConsoleState(
                                        "Tools",
                                        ConductorCommands.toolListText(definitions),
                                    ),
                            )
                        }
                    }

                    argsJson.isEmpty() -> {
                        updateUi { copy(isRunning = false) }
                        when (val resolution = resolveToolDefinition(name, definitions)) {
                            is ToolResolution.Found ->
                                updateUi {
                                    copy(
                                        toolConsole =
                                            ToolConsoleState(
                                                "Tool",
                                                ConductorCommands.toolSchemaText(
                                                    resolution.definition,
                                                ),
                                            ),
                                    )
                                }

                            is ToolResolution.Ambiguous ->
                                updateUi {
                                    copy(
                                        toolConsole =
                                            ToolConsoleState(
                                                "Tool",
                                                "More than one tool matches \"$name\": " +
                                                    resolution.definitions.joinToString { it.name } +
                                                    ". Use the exact name.",
                                            ),
                                    )
                                }

                            ToolResolution.NotFound ->
                                updateUi {
                                    copy(
                                        toolConsole =
                                            ToolConsoleState(
                                                "Tool",
                                                "No registered tool matches \"$name\". " +
                                                    "Type /tool to list tools.",
                                            ),
                                    )
                                }
                        }
                    }

                    else -> {
                        when (val resolution = resolveToolDefinition(name, definitions)) {
                            is ToolResolution.Found -> {
                                if (resolution.definition.name == AgentMetaTools.SEARCH_TOOLS) {
                                    val result = runSearchToolsCommand(argsJson, agentDefinitions)
                                    updateUi { copy(isRunning = false) }
                                    appendDirectToolResult(
                                        AgentMetaTools.SEARCH_TOOLS,
                                        argsJson,
                                        result,
                                    )
                                    return@withContext
                                }
                                updateUi {
                                    copy(
                                        isRunning = true,
                                        toolConsole =
                                            ToolConsoleState(
                                                "Tool",
                                                "Running ${resolution.definition.name}…",
                                                running = true,
                                            ),
                                    )
                                }
                                val result =
                                    invokeRegistryUserCommand(resolution.definition.name, argsJson)
                                if (result.confirmation != null) {
                                    updateUi {
                                        copy(
                                            isRunning = false,
                                            toolConsole =
                                                ToolConsoleState(
                                                    "Tool",
                                                    "Waiting for approval…",
                                                    running = true,
                                                ),
                                        )
                                    }
                                    showCommandConfirmation(
                                        resolution.definition.name,
                                        result.confirmation!!,
                                        asTool = true,
                                    )
                                } else {
                                    updateUi { copy(isRunning = false) }
                                    appendDirectToolResult(
                                        resolution.definition.name,
                                        argsJson,
                                        result,
                                    )
                                }
                            }

                            is ToolResolution.Ambiguous ->
                                updateUi {
                                    copy(
                                        isRunning = false,
                                        toolConsole =
                                            ToolConsoleState(
                                                "Tool",
                                                "More than one tool matches \"$name\": " +
                                                    resolution.definitions.joinToString { it.name } +
                                                    ". Use the exact name.",
                                            ),
                                    )
                                }

                            ToolResolution.NotFound ->
                                updateUi {
                                    copy(
                                        isRunning = false,
                                        toolConsole =
                                            ToolConsoleState(
                                                "Tool",
                                                "No registered tool matches \"$name\". " +
                                                    "Type /tool to list tools.",
                                            ),
                                    )
                                }
                        }
                    }
                }
            }
        }
    }

    /**
     * `/tool` is the developer console, so its two routing primitives are
     * available even when routing mode is off. The catalog itself still only
     * contains real capabilities; meta tools never enter it.
     */
    private fun runSearchToolsCommand(
        argsJson: String,
        definitions: List<AgentToolDefinition>,
    ): AgentInvocationResult {
        val args =
            AgentJson.parseObject(argsJson)
                ?: return AgentInvocationResult(
                    tool = AgentMetaTools.SEARCH_TOOLS,
                    ok = false,
                    errorCode = "invalid_arguments",
                    errorMessage = "search_tools arguments must be a JSON object.",
                )
        val query =
            AgentJson.string(args, "query")
                ?: return AgentInvocationResult(
                    tool = AgentMetaTools.SEARCH_TOOLS,
                    ok = false,
                    errorCode = "missing_parameter",
                    errorMessage = "search_tools requires a string query; use an empty string to list the catalog.",
                )
        val catalog = AgentToolCatalog(definitions)
        val output =
            if (query.isBlank()) {
                catalog.catalogOutput(agentConfig.maxObservationChars)
            } else {
                catalog.searchOutput(query, agentConfig.searchLimit, agentConfig.maxObservationChars)
            }
        return AgentInvocationResult(
            tool = AgentMetaTools.SEARCH_TOOLS,
            ok = true,
            output = output,
        )
    }

    private fun resolveToolDefinition(
        name: String,
        definitions: List<AgentToolDefinition>,
    ): ToolResolution {
        val exact = definitions.filter { it.name.equals(name, ignoreCase = true) }
        if (exact.size == 1) return ToolResolution.Found(exact.single())
        if (exact.size > 1) return ToolResolution.Ambiguous(exact)
        val aliases =
            definitions.filter { definition ->
                definition.aliases.any { it.equals(name, ignoreCase = true) }
            }
        return when {
            aliases.size == 1 -> ToolResolution.Found(aliases.single())
            aliases.size > 1 -> ToolResolution.Ambiguous(aliases)
            else -> ToolResolution.NotFound
        }
    }

    private sealed interface ToolResolution {
        data class Found(
            val definition: AgentToolDefinition,
        ) : ToolResolution

        data class Ambiguous(
            val definitions: List<AgentToolDefinition>,
        ) : ToolResolution

        data object NotFound : ToolResolution
    }

    private fun appendDirectToolResult(
        name: String,
        argsJson: String,
        result: AgentInvocationResult,
    ) {
        val body =
            buildString {
                appendLine("Tool `${name}`")
                appendLine()
                appendLine(if (result.ok) "status: ok" else "status: failed")
                appendLine()
                appendLine("args:")
                appendLine(
                    markdownFence(
                        AgentSanitizer.sanitizeArgsForDisplay(argsJson),
                        "json",
                    ),
                )
                result.output?.let { output ->
                    appendLine()
                    appendLine("output:")
                    appendLine(markdownFence(AgentJson.pretty(output), "json"))
                }
                result.errorCode?.let { code ->
                    appendLine()
                    appendLine("error code: `$code`")
                }
                result.errorMessage?.takeIf { it.isNotBlank() }?.let { message ->
                    appendLine()
                    appendLine("error:")
                    appendLine(markdownFence(AgentSanitizer.sanitizeText(message), "text"))
                }
                if (result.degraded) {
                    appendLine()
                    appendLine("degraded: true")
                }
            }.trim()
        updateUi { copy(toolConsole = ToolConsoleState("Tool", body)) }
        TelemetryEventLog.recordAsync(
            getApplication<Application>(),
            "conductor_tool",
            buildMap {
                put("tool", telemetryToolName(name))
                put("source", "command")
                put("ok", result.ok)
                put("code", result.errorCode ?: "-")
                if (!result.ok) {
                    put("detail", TelemetryEvents.scrub(result.errorMessage.orEmpty(), 160))
                }
            },
        )
    }

    fun dismissToolConsole() {
        updateUi { copy(toolConsole = null) }
    }

    private fun invokeCommandTool(
        name: String,
        argsJson: String,
        title: String,
        asTool: Boolean = false,
    ) {
        updateUi { copy(isRunning = true) }
        viewModelScope.launch {
            val result = invokeRegistryConfirmCommand(name, argsJson)
            withContext(Dispatchers.Main) {
                updateUi { copy(isRunning = false) }
                if (asTool) {
                    appendDirectToolResult(name, argsJson, result)
                } else {
                    appendToolResult(title, result)
                    persistSession()
                }
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
        asTool: Boolean = false,
    ) {
        pendingCommandConfirmation = name to confirmation.argsJson
        pendingCommandConfirmationId = commandConfirmationIds.incrementAndGet()
        pendingCommandAsTool = asTool
        updateUi { copy(isRunning = true) }
        removeConfirmationItems()
        append(
            ConductorItem.Confirmation(
                ConfirmationRequest(
                    id = pendingCommandConfirmationId!!,
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

    fun savePlan(
        goal: String,
        items: List<PlanItem>,
        note: String,
    ) {
        if (_uiState.value.isRunning) return
        val text =
            buildString {
                appendLine(goal)
                items.forEach { appendLine(it.step) }
                appendLine(note)
            }
        if (DefaultAgentInputGuard.check(text) is InputGuardResult.Blocked) {
            append(
                ConductorItem.Notice(
                    "plan",
                    "This plan may contain a subscription link, token or log excerpt. " +
                        "It was not saved. Remove the sensitive part and try again.",
                ),
            )
            return
        }
        planStore.update(goal, items, note, clearedBy = PlanClearedBy.USER)
    }

    fun clearPlan() {
        if (_uiState.value.isRunning) return
        planStore.clear()
    }

    fun conversationTranscript(): String =
        conductorConversationMarkdown(
            items = _uiState.value.items,
            appVersion = BuildConfig.VERSION_NAME,
            sessionStartVersion = sessionStartVersion,
            plan = _uiState.value.plan,
        )

    fun apiTraceMarkdown(lastEntries: Int? = null): String = AgentApiTrace.asMarkdown(lastEntries)

    fun hasApiTrace(): Boolean = AgentApiTrace.hasEntries()

    /**
     * Applies a session decoded on IO. Runs on the main thread, before any
     * queued [submit] resumes, so it cannot overwrite user-visible state.
     */
    private fun applyRestoredSession(session: ConductorSession?) {
        if (session == null) return
        sessionStartVersion = session.appVersion.ifBlank { BuildConfig.VERSION_NAME }
        planStore.restore(session.plan)
        val loadedTranscript = session.transcript.takeIf { validateTranscriptPairing(it) }.orEmpty()
        val repaired = repairRestoredTranscript(loadedTranscript, session.items)
        transcript = repaired.takeIf { validateTranscriptPairing(it) } ?: loadedTranscript
        transcript
            .filterIsInstance<AgentMessage.User>()
            .flatMap { it.images }
            .forEach { ref ->
                imageStore.file(ref.id)?.let { file ->
                    InboxRegistry.shared.registerFile(ref.id, ref.name, ref.mimeType, file)
                }
            }
        pendingTurnEvent = session.pendingEvent
        AgentApiTrace.restoreSummaries(session.trace)
        val restored = restoreConductorItems(session.items)
        val restoredItems = restored.items
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
        val finalItems =
            if (restored.interrupted) displayItems + interruptedConductorNotice() else displayItems
        if (finalItems.isNotEmpty()) {
            _uiState.update { it.copy(items = finalItems, canRetryLastTurn = canRetry) }
        }
    }

    private fun persistSession() {
        // Mid-turn the UI items are ahead of the model transcript. Writing that
        // pair would make a process-death restore show tool cards the model
        // never saw. The turn's final persist below writes both together.
        if (_uiState.value.isRunning) return
        // The restored session must be applied before any write: a save during
        // startup would replace the stored conversation with the empty state.
        if (!sessionReady.isCompleted) return
        val items = _uiState.value.items.filterNot { it is ConductorItem.Confirmation }
        val plan = _uiState.value.plan?.takeIf { it.items.isNotEmpty() || it.cleared }
        if (transcript.isEmpty() && items.isEmpty() && pendingTurnEvent == null && plan == null) {
            runCatching { sessionWrites.execute { sessionStore.clear() } }
            return
        }
        if (!validateTranscriptPairing(transcript)) return
        val transcriptSnapshot = transcript.toList()
        val pendingEvent = pendingTurnEvent
        val trace = AgentApiTrace.summaries()
        runCatching {
            sessionWrites.execute {
                sessionStore.save(
                    transcript = transcriptSnapshot,
                    items = items,
                    pendingEvent = pendingEvent,
                    appVersion = BuildConfig.VERSION_NAME,
                    trace = trace,
                    plan = plan,
                )
            }
        }
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
        private const val STOP_CONFIRM_TIMEOUT_MS = 1_000L
        private const val TAILDROP_SEND_TIMEOUT_MS = 120_000L
        private const val TAILDROP_MAX_BYTES = 8L * 1024 * 1024

        // Keep the picker deadline below the tool's 10-minute registry budget
        // so a timeout comes back as a structured cancelled result.
        private const val FILE_PICK_TIMEOUT_MS = 9L * 60L * 1000L
        private const val MAX_SELECTED_FILES = 10
        private const val MAX_RETAINED_SELECTED_FILES = 30
        private const val MAX_FILE_NAME_CHARS = 200
        private const val MAX_FILE_REQUEST_REASON_CHARS = 240
        private const val MAX_IMAGE_READ_BYTES = 5 * 1024 * 1024
        private const val MAX_MEASURE_BYTES = 512L * 1024 * 1024
    }
}

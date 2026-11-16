package io.nekohasekai.sfa.conductor

import android.graphics.BitmapFactory
import android.net.Uri
import android.app.Application
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.agent.AgentImageRef
import io.nekohasekai.sfa.log.AppLog
import io.nekohasekai.sfa.telemetry.TelemetryEventLog
import io.nekohasekai.sfa.telemetry.TelemetryEvents
import io.nekohasekai.sfa.agent.AgentJson
import io.nekohasekai.sfa.compat.CodeEditorSyntax
import io.nekohasekai.sfa.compat.ProfileCodeEditor
import io.nekohasekai.sfa.compat.ProfileEditorColors
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import io.nekohasekai.sfa.agent.ToolStatus
import io.nekohasekai.sfa.compose.topbar.LocalScaffoldPadding
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.utils.RemoteControlManager
import io.nekohasekai.sfa.vfs.VfsConsentDecision
import io.nekohasekai.sfa.vfs.VfsScope

private val InputPanelTopPadding = 18.dp
private val InputPanelBottomPadding = 60.dp
private val InputPanelMinHeight = 120.dp
private const val InputPanelMaxLines = 5
private const val MaxInputChars = ConductorSessionStore.MAX_DRAFT
private const val ContextWarningPercent = 80

private val InputFieldSaver =
    listSaver<TextFieldValue, Any>(
        save = { value ->
            listOf(value.text.take(MaxInputChars), value.selection.start, value.selection.end)
        },
        restore = { saved ->
            val text = (saved[0] as String).take(MaxInputChars)
            val start = (saved[1] as Int).coerceIn(0, text.length)
            val end = (saved[2] as Int).coerceIn(0, text.length)
            TextFieldValue(text, TextRange(start, end))
        },
    )

private enum class ConductorExport { Conversation, ApiTrace }

private data class ConductorViewer(
    val title: String,
    val fileName: String,
    val text: String,
    val kind: ConductorExport,
)

/**
 * The Conductor session is process-scoped. An in-flight turn survives leaving
 * the screen, finishing the activity, and coming back. Only process death ends
 * it; that case is restored from the session store as an interrupted turn.
 */
private object ConductorViewModelOwner : ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
}

@Composable
private fun conductorViewModel(): ConductorViewModel {
    val application = LocalContext.current.applicationContext as Application
    return viewModel(
        viewModelStoreOwner = ConductorViewModelOwner,
        factory = ViewModelProvider.AndroidViewModelFactory.getInstance(application),
    )
}

@Composable
fun ConductorScreen(
    serviceStatus: Status = Status.Stopped,
    viewModel: ConductorViewModel = conductorViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val fileRequest by viewModel.fileRequest.collectAsState()
    val vfsConsent by viewModel.vfsConsent.collectAsState()
    val remoteServer by RemoteControlManager.remoteServer.collectAsState()
    val remoteConnected by RemoteControlManager.isConnected.collectAsState()
    val hostActivity = LocalActivity.current as? ComponentActivity

    // A file picker cannot return to a finishing activity. The process-scoped
    // ViewModel keeps the turn alive, so cancel only this UI-bound request.
    DisposableEffect(hostActivity) {
        onDispose {
            if (hostActivity?.isFinishing == true) {
                viewModel.onHostFinishing()
            }
        }
    }

    var pendingFilePickId by rememberSaveable { mutableStateOf<String?>(null) }
    var textViewer by remember { mutableStateOf<ConductorViewer?>(null) }
    fun deliverPickedFiles(uris: List<Uri>) {
        val requestId = pendingFilePickId
        pendingFilePickId = null
        if (requestId != null) viewModel.onFilesSelected(requestId, uris)
    }
    val multiFilePicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
            deliverPickedFiles(it)
        }
    val singleFilePicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            deliverPickedFiles(listOfNotNull(uri))
        }

    LaunchedEffect(serviceStatus, remoteServer, remoteConnected) {
        viewModel.onServiceState(
            serviceStatus = serviceStatus,
            remoteControl = remoteServer != null,
            remoteConnected = remoteConnected,
        )
    }

    var inputField by rememberSaveable(stateSaver = InputFieldSaver) {
        val draft = viewModel.currentDraft().take(MaxInputChars)
        mutableStateOf(TextFieldValue(draft, TextRange(draft.length)))
    }
    val input = inputField.text

    fun setField(next: TextFieldValue) {
        val text = next.text.take(MaxInputChars)
        inputField =
            if (next.text.length <= MaxInputChars) {
                next
            } else {
                TextFieldValue(
                    text,
                    TextRange(
                        next.selection.start.coerceIn(0, text.length),
                        next.selection.end.coerceIn(0, text.length),
                    ),
                )
            }
        viewModel.saveDraft(text)
    }

    fun replaceInput(
        text: String,
        cursor: Int = text.length,
    ) {
        val clipped = text.take(MaxInputChars)
        setField(TextFieldValue(clipped, TextRange(cursor.coerceIn(0, clipped.length))))
    }

    fun applyEdit(edit: ConductorInputEdit) {
        if (edit.cursor == null && edit.text == inputField.text) return
        val cursor = edit.cursor ?: edit.text.length
        replaceInput(edit.text, cursor)
    }

    LaunchedEffect(Unit) {
        viewModel.saveDraft(input)
    }
    var inputExpanded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(fileRequest?.id) {
        if (fileRequest != null) inputExpanded = false
    }
    var inputLineCount by remember { mutableStateOf(1) }
    var inputTooLongWarned by remember { mutableStateOf(false) }
    var pendingExport by remember { mutableStateOf<ConductorExport?>(null) }
    val focusRequester = remember { FocusRequester() }
    val listState =
        rememberLazyListState(
            initialFirstVisibleItemIndex = viewModel.transcriptScrollIndex,
            initialFirstVisibleItemScrollOffset = viewModel.transcriptScrollOffset,
        )
    var stickToBottom by remember { mutableStateOf(viewModel.transcriptStickToBottom) }
    LaunchedEffect(stickToBottom) {
        viewModel.transcriptStickToBottom = stickToBottom
    }
    DisposableEffect(listState) {
        onDispose {
            viewModel.transcriptScrollIndex = listState.firstVisibleItemIndex
            viewModel.transcriptScrollOffset = listState.firstVisibleItemScrollOffset
        }
    }
    val isDragged by listState.interactionSource.collectIsDraggedAsState()
    val atBottom by remember {
        derivedStateOf {
            if (uiState.items.isEmpty()) return@derivedStateOf true
            val lastVisible =
                listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
                    ?: return@derivedStateOf true
            lastVisible >= uiState.items.size
        }
    }
    val lastContentVersion = uiState.items.lastOrNull()?.contentVersion() ?: 0
    val focusManager = LocalFocusManager.current
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var importingImage by remember { mutableStateOf(false) }
    fun reportImagePicker(
        stage: String,
        error: Throwable? = null,
        hasUri: Boolean? = null,
    ) {
        TelemetryEventLog.recordAsync(
            context,
            "conductor_image",
            buildMap {
                put("stage", stage)
                hasUri?.let { put("has_uri", it) }
                error?.let {
                    put("error", it.javaClass.simpleName)
                    put("detail", TelemetryEvents.scrub(it.message.orEmpty(), 160))
                }
            },
        )
        error?.let {
            AppLog.error(
                "conductor image picker $stage ${it.javaClass.simpleName}: " +
                    TelemetryEvents.scrub(it.message.orEmpty(), 160),
            )
        }
    }
    val imagePicker =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            reportImagePicker("result", hasUri = uri != null)
            if (uri != null) {
                coroutineScope.launch {
                    importingImage = true
                    try {
                        viewModel.importImage(uri)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        reportImagePicker("import", e)
                    } finally {
                        importingImage = false
                    }
                }
            }
        }
    val lifecycleOwner = LocalLifecycleOwner.current
    val density = LocalDensity.current
    val scaffoldPadding = LocalScaffoldPadding.current

    // If the IME is dismissed while the field still has focus, clear focus so the
    // cursor stops blinking and the IME does not pop back when the app resumes.
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    var wasImeVisible by remember { mutableStateOf(false) }
    LaunchedEffect(imeVisible) {
        if (wasImeVisible && !imeVisible) {
            focusManager.clearFocus()
        }
        wasImeVisible = imeVisible
    }
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_PAUSE) {
                    focusManager.clearFocus()
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val suggestions = ConductorCommands.suggestions(input)
    val effortSuggestions = ConductorReasoningCommand.suggestions(input)
    val briefSuggestions = ConductorBriefCommand.suggestions(input)
    val routingSuggestions = ConductorRoutingCommand.suggestions(input)
    val mcpTools by viewModel.mcpListed.collectAsState()
    val offerMcpVerb = ConductorMcpUse.offerVerb(input)
    val mcpToolSuggestions = ConductorMcpUse.toolSuggestions(input, mcpTools)
    var slashMenuHidden by remember { mutableStateOf(false) }
    val slashMenuVisible =
        !inputExpanded &&
            !slashMenuHidden &&
            (
                effortSuggestions.isNotEmpty() ||
                    briefSuggestions.isNotEmpty() ||
                    routingSuggestions.isNotEmpty() ||
                    offerMcpVerb ||
                    mcpToolSuggestions.isNotEmpty() ||
                    suggestions.isNotEmpty()
            )
    val mcpCommand = input.trimStart().startsWith("/mcp")
    LaunchedEffect(mcpCommand) {
        if (mcpCommand) viewModel.refreshMcpQuietly()
    }

    fun focusInput() {
        runCatching { focusRequester.requestFocus() }
    }

    fun send() {
        val text = input.trim()
        val hasImage = uiState.pendingImage != null
        if (text.isEmpty() && !hasImage) return
        if (text.length > MaxInputChars) {
            Toast.makeText(
                context,
                context.getString(R.string.conductor_input_too_long, MaxInputChars),
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        if (hasImage && text.startsWith("/")) {
            // Let the ViewModel explain that a command cannot carry an image;
            // keep both the draft and the pending image.
            viewModel.submit(text)
            return
        }
        if (ConductorMcpUse.isCommand(text)) {
            coroutineScope.launch {
                when (val outcome = viewModel.resolveMcpUse(text)) {
                    is ConductorMcpUse.Outcome.Draft -> {
                        replaceInput(outcome.text, outcome.text.length)
                        focusInput()
                    }
                    is ConductorMcpUse.Outcome.Rejected -> {
                        replaceInput("")
                        inputExpanded = false
                        focusManager.clearFocus()
                    }
                }
            }
            return
        }
        viewModel.submit(text)
        replaceInput("")
        inputExpanded = false
        focusManager.clearFocus()
    }

    val exportLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument("text/plain"),
            onResult = { uri ->
                val export = pendingExport
                pendingExport = null
                if (uri != null && export != null) {
                    val empty =
                        if (export == ConductorExport.ApiTrace) {
                            !viewModel.hasApiTrace()
                        } else {
                            uiState.items.isEmpty()
                        }
                    if (empty) {
                        Toast.makeText(
                            context,
                            context.getString(
                                if (export == ConductorExport.ApiTrace) {
                                    R.string.conductor_api_trace_empty
                                } else {
                                    R.string.conductor_conversation_empty
                                },
                            ),
                            Toast.LENGTH_LONG,
                        ).show()
                    } else {
                        coroutineScope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    val text =
                                        when (export) {
                                            ConductorExport.Conversation -> viewModel.conversationTranscript()
                                            ConductorExport.ApiTrace -> viewModel.apiTraceMarkdown()
                                        }
                                    val output = checkNotNull(context.contentResolver.openOutputStream(uri))
                                    output.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                                }
                                val saved =
                                    when (export) {
                                        ConductorExport.Conversation -> R.string.conductor_conversation_saved
                                        ConductorExport.ApiTrace -> R.string.conductor_api_trace_saved
                                    }
                                Toast.makeText(context, context.getString(saved), Toast.LENGTH_SHORT).show()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                val failed =
                                    when (export) {
                                        ConductorExport.Conversation -> R.string.conductor_conversation_save_failed
                                        ConductorExport.ApiTrace -> R.string.conductor_api_trace_save_failed
                                    }
                                Toast.makeText(
                                    context,
                                    context.getString(failed, e.message.orEmpty()),
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        }
                    }
                }
            },
        )

    fun export(
        kind: ConductorExport,
        fileName: String,
    ) {
        pendingExport = kind
        runCatching { exportLauncher.launch(fileName) }
            .onFailure {
                pendingExport = null
                val failed =
                    when (kind) {
                        ConductorExport.Conversation -> R.string.conductor_conversation_save_failed
                        ConductorExport.ApiTrace -> R.string.conductor_api_trace_save_failed
                    }
                Toast.makeText(
                    context,
                    context.getString(failed, it.message.orEmpty()),
                    Toast.LENGTH_LONG,
                ).show()
            }
    }

    BackHandler(enabled = inputExpanded) {
        inputExpanded = false
    }

    BoxWithConstraints(
        modifier =
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .imePadding(),
    ) {
        val panelDensity = LocalDensity.current
        val lineHeightDp = with(panelDensity) {
            MaterialTheme.typography.bodyLarge.lineHeight.toDp()
        }
        val defaultPanelHeight =
            (InputPanelTopPadding +
                lineHeightDp * inputLineCount.coerceIn(1, InputPanelMaxLines).toFloat() +
                InputPanelBottomPadding)
                .coerceAtLeast(InputPanelMinHeight)

        val rawTarget = if (inputExpanded) maxHeight * 0.85f else defaultPanelHeight
        // Whole-pixel heights avoid subpixel shimmer while the panel moves.
        val targetPanelHeight = with(panelDensity) { rawTarget.roundToPx().toDp() }

        // During and just after an IME inset change, snap the panel height.
        // Otherwise the IME animation and the 200ms height animation can
        // disagree for one frame at the top, which looks like a vibration.
        var lastMaxHeight by remember { mutableStateOf(maxHeight) }
        var lastImeChangeAt by remember { mutableStateOf(0L) }
        val maxHeightChanged = maxHeight != lastMaxHeight
        LaunchedEffect(maxHeight) {
            lastMaxHeight = maxHeight
            lastImeChangeAt = System.currentTimeMillis()
        }
        val imeSettling =
            maxHeightChanged ||
                System.currentTimeMillis() - lastImeChangeAt < 300L
        val panelHeight by animateDpAsState(
            targetValue = targetPanelHeight,
            animationSpec = if (imeSettling) snap() else tween(200),
            label = "conductorInputPanelHeight",
        )
        val layoutDirection = LocalLayoutDirection.current

        LaunchedEffect(atBottom) {
            if (atBottom) stickToBottom = true
        }
        LaunchedEffect(isDragged) {
            if (isDragged) {
                delay(100)
                if (listState.canScrollForward) stickToBottom = false
            }
        }
        LaunchedEffect(uiState.items.size, lastContentVersion) {
            if (uiState.items.isNotEmpty() && stickToBottom) {
                listState.animateScrollToItem(uiState.items.size)
            }
        }

        Column(
            modifier =
            Modifier
                .fillMaxSize()
                .padding(
                    top = scaffoldPadding.calculateTopPadding(),
                    start = scaffoldPadding.calculateStartPadding(layoutDirection),
                    end = scaffoldPadding.calculateEndPadding(layoutDirection),
                )
                .padding(bottom = panelHeight),
        ) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                if (uiState.items.isEmpty()) {
                    item(key = "empty") {
                        ConductorEmptyState(
                            onCommand = { command ->
                                replaceInput("")
                                inputExpanded = false
                                viewModel.submit(command)
                            },
                        )
                    }
                } else {
                    items(
                        count = uiState.items.size,
                        key = { index -> uiState.items[index].stableKey(index) },
                    ) { index ->
                        ConductorItemView(
                            item = uiState.items[index],
                            onConfirm = viewModel::confirmConfirmation,
                            onConfirmAll = viewModel::confirmAllAgent,
                            onCancel = viewModel::cancelConfirmation,
                            onAnswerQuestion = viewModel::answerAskUser,
                            onCancelQuestion = viewModel::cancelAskUser,
                            canRetry = uiState.canRetryLastTurn && index == uiState.items.lastIndex,
                            onRetry = viewModel::retryLastTurn,
                            imageFile = viewModel::imageFile,
                        )
                    }
                    item(key = "conversation-actions") {
                        ConversationActions(
                            onPlainTranscript = {
                                textViewer =
                                    ConductorViewer(
                                        title = context.getString(R.string.conductor_plain_transcript),
                                        fileName = ConductorExportNames.transcript(),
                                        text = viewModel.conversationTranscript(),
                                        kind = ConductorExport.Conversation,
                                    )
                            },
                            onApiTrace = {
                                textViewer =
                                    ConductorViewer(
                                        title = context.getString(R.string.conductor_api_trace),
                                        fileName = ConductorExportNames.apiTrace(),
                                        text = viewModel.apiTraceMarkdown(),
                                        kind = ConductorExport.ApiTrace,
                                    )
                            },
                        )
                    }
                }
                }
                if (!stickToBottom && uiState.items.isNotEmpty()) {
                    SmallFloatingActionButton(
                        onClick = {
                            stickToBottom = true
                            coroutineScope.launch { listState.animateScrollToItem(uiState.items.size) }
                        },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = stringResource(R.string.conductor_back_to_latest),
                        )
                    }
                }
            }

            if (slashMenuVisible && effortSuggestions.isNotEmpty()) {
                EffortSuggestions(
                    levels = effortSuggestions,
                    onPick = { level ->
                        replaceInput("")
                        inputExpanded = false
                        viewModel.submit("/reasoning $level")
                        focusManager.clearFocus()
                    },
                    hint = ConductorReasoningCommand::hint,
                )
            } else if (slashMenuVisible && briefSuggestions.isNotEmpty()) {
                EffortSuggestions(
                    levels = briefSuggestions,
                    onPick = { value ->
                        replaceInput("")
                        inputExpanded = false
                        viewModel.submit("/brief $value")
                        focusManager.clearFocus()
                    },
                    hint = ConductorBriefCommand::hint,
                )
            } else if (slashMenuVisible && routingSuggestions.isNotEmpty()) {
                EffortSuggestions(
                    levels = routingSuggestions,
                    onPick = { value ->
                        replaceInput("")
                        inputExpanded = false
                        viewModel.submit("/routing $value")
                        focusManager.clearFocus()
                    },
                    hint = ConductorRoutingCommand::hint,
                )
            } else if (slashMenuVisible && offerMcpVerb) {
                McpUseSuggestions(
                    rows = listOf(McpUseRow(key = "use", title = "use", detail = "Put one MCP tool in the input")),
                    onPick = { _ ->
                        val next = "/mcp use "
                        replaceInput(next, next.length)
                        focusInput()
                    },
                )
            } else if (slashMenuVisible && mcpToolSuggestions.isNotEmpty()) {
                McpUseSuggestions(
                    rows =
                        mcpToolSuggestions.map { tool ->
                            McpUseRow(
                                key = tool.localName,
                                title = tool.remoteName,
                                detail = tool.serverName,
                            )
                        },
                    onPick = { index ->
                        val draft = ConductorMcpUse.draft(mcpToolSuggestions[index])
                        replaceInput(draft, draft.length)
                        focusInput()
                    },
                )
            } else if (slashMenuVisible && suggestions.isNotEmpty()) {
                CommandSuggestions(
                    commands = suggestions,
                    onPick = { command ->
                        if (command.takesArgs) {
                            val next = "/${command.name} "
                            replaceInput(next, next.length)
                            focusInput()
                        } else {
                            replaceInput("")
                            inputExpanded = false
                            viewModel.submit("/${command.name}")
                            focusManager.clearFocus()
                        }
                    },
                )
            }
        }

        fileRequest?.let { request ->
            FileRequestCard(
                request = request,
                onChoose = {
                    pendingFilePickId = request.id
                    val mimeTypes = request.mimeTypes.ifEmpty { listOf("*/*") }.toTypedArray()
                    if (request.multiple) {
                        multiFilePicker.launch(mimeTypes)
                    } else {
                        singleFilePicker.launch(mimeTypes)
                    }
                },
                onCancel = { viewModel.cancelFileRequest(request.id) },
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(start = 16.dp, end = 16.dp, bottom = panelHeight + 8.dp),
            )
        }

        vfsConsent?.let { request ->
            VfsConsentDialog(
                request = request,
                onDecision = viewModel::resolveVfsConsent,
                onDismiss = { viewModel.resolveVfsConsent(VfsConsentDecision.DENY) },
            )
        }

        textViewer?.let { viewer ->
            ConductorTextViewerDialog(
                viewer = viewer,
                onDismiss = { textViewer = null },
                onSave = { export(viewer.kind, viewer.fileName) },
            )
        }

        ConductorInputBar(
            modifier = Modifier.align(Alignment.BottomCenter),
            height = panelHeight,
            expanded = inputExpanded,
            onToggleExpand = { inputExpanded = !inputExpanded },
            input = inputField,
            onInputChange = { next ->
                if (next.text != input) slashMenuHidden = false
                if (next.text.length > MaxInputChars) {
                    if (!inputTooLongWarned) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.conductor_input_too_long, MaxInputChars),
                            Toast.LENGTH_LONG,
                        ).show()
                        inputTooLongWarned = true
                    }
                    setField(next)
                } else {
                    setField(next)
                    if (next.text.length < MaxInputChars - 256) inputTooLongWarned = false
                }
            },
            onLineCountChanged = { inputLineCount = it },
            onSend = { send() },
            onStop = viewModel::stopAgent,
            isRunning = uiState.isRunning,
            pendingImage = uiState.pendingImage,
            importingImage = importingImage,
            onAddImage = {
                reportImagePicker("launch")
                runCatching {
                    imagePicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                }.onFailure { reportImagePicker("launch_failed", it) }
            },
            onRemoveImage = viewModel::clearPendingImage,
            imageFile = viewModel::imageFile,
            statusText = uiState.turnStatus,
            contextPercent = uiState.contextPercent,
            contextUsedTokens = uiState.contextUsedTokens,
            contextLimitTokens = uiState.contextLimitTokens,
            onSlash = {
                val press = ConductorInputEdits.slashPress(slashMenuVisible, input, MaxInputChars)
                if (press.hideMenu) {
                    slashMenuHidden = true
                } else {
                    slashMenuHidden = false
                    press.edit?.let(::applyEdit)
                    focusInput()
                }
            },
            focusRequester = focusRequester,
        )
    }
}

@Composable
private fun FileRequestCard(
    request: FileRequestUi,
    onChoose: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_baseline_file_open_24),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = stringResource(R.string.conductor_file_request_title),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            Text(
                text = request.reason,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onCancel) {
                    Text(stringResource(R.string.cancel))
                }
                Spacer(Modifier.weight(1f))
                Button(onClick = onChoose) {
                    Text(
                        stringResource(
                            if (request.multiple) {
                                R.string.conductor_file_request_choose
                            } else {
                                R.string.conductor_file_request_choose_one
                            },
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun VfsConsentDialog(
    request: VfsConsentUi,
    onDecision: (VfsConsentDecision) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.vfs_consent_title, request.serverName)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.vfs_consent_server, request.server),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.vfs_consent_scope),
                    style = MaterialTheme.typography.titleSmall,
                )
                request.scope.forEach { scope ->
                    Text(
                        text = "• ${vfsScopeLabel(scope)}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (request.scope.contains(VfsScope.CONVERSATION)) {
                    Text(
                        text = stringResource(R.string.vfs_consent_conversation),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    text = stringResource(R.string.vfs_consent_detail),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onDecision(VfsConsentDecision.TRUST_15_DAYS) }) {
                Text(stringResource(R.string.vfs_consent_trust))
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { onDecision(VfsConsentDecision.DENY) }) {
                    Text(stringResource(R.string.vfs_consent_deny))
                }
                TextButton(onClick = { onDecision(VfsConsentDecision.ONCE) }) {
                    Text(stringResource(R.string.vfs_consent_once))
                }
            }
        },
    )
}

@Composable
private fun vfsScopeLabel(scope: VfsScope): String =
    stringResource(
        when (scope) {
            VfsScope.CONDUCTOR -> R.string.vfs_scope_conductor
            VfsScope.TRACE -> R.string.vfs_scope_trace
            VfsScope.SYSTEM_PROMPT -> R.string.vfs_scope_system_prompt
            VfsScope.CONVERSATION -> R.string.vfs_scope_conversation
            VfsScope.WORKDIR -> R.string.vfs_scope_workdir
        },
    )

@Composable
private fun ConversationActions(
    onPlainTranscript: () -> Unit,
    onApiTrace: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
    ) {
        Box(modifier = Modifier.size(48.dp)) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .offset(x = (-12).dp)
                        .clip(CircleShape)
                        .clickable { expanded = true },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.MoreHoriz,
                    contentDescription = stringResource(R.string.conductor_conversation_actions),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.conductor_plain_transcript)) },
                    onClick = {
                        expanded = false
                        onPlainTranscript()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.conductor_api_trace)) },
                    onClick = {
                        expanded = false
                        onApiTrace()
                    },
                )
            }
        }
    }
}

@Composable
private fun ConductorTextViewerDialog(
    viewer: ConductorViewer,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var rich by remember(viewer.title) { mutableStateOf(false) }
    var autoWrap by remember(viewer.title) { mutableStateOf(false) }
    var optionsOpen by remember { mutableStateOf(false) }
    val editorRef = remember { mutableStateOf<ProfileCodeEditor?>(null) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(start = 16.dp, end = 8.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box {
                        IconButton(onClick = { optionsOpen = true }) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = stringResource(R.string.conductor_viewer_options),
                            )
                        }
                        DropdownMenu(
                            expanded = optionsOpen,
                            onDismissRequest = { optionsOpen = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.conductor_viewer_auto_wrap)) },
                                onClick = {
                                    autoWrap = !autoWrap
                                    optionsOpen = false
                                },
                                trailingIcon = {
                                    if (autoWrap) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = null,
                                        )
                                    }
                                },
                            )
                        }
                    }
                    Text(
                        text = viewer.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f).padding(start = 4.dp),
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.close),
                        )
                    }
                }

                val colorScheme = MaterialTheme.colorScheme
                val editorColors =
                    remember(colorScheme) {
                        ProfileEditorColors(
                            background = colorScheme.background.toArgb(),
                            foreground = colorScheme.onSurface.toArgb(),
                            lineNumber = colorScheme.onSurfaceVariant.toArgb(),
                            lineNumberBackground = colorScheme.background.toArgb(),
                            selectionBackground = colorScheme.primary.copy(alpha = 0.35f).toArgb(),
                            currentLineBackground = colorScheme.surfaceContainer.toArgb(),
                            cursor = colorScheme.primary.toArgb(),
                            matchedTextBackground = colorScheme.tertiary.copy(alpha = 0.35f).toArgb(),
                            comment = colorScheme.onSurfaceVariant.toArgb(),
                            key = colorScheme.primary.toArgb(),
                            string = colorScheme.tertiary.toArgb(),
                            number = colorScheme.secondary.toArgb(),
                            literal = colorScheme.primary.toArgb(),
                        )
                    }

                if (rich) {
                    ConductorMarkdownViewer(
                        text = viewer.text,
                        viewerName = viewer.title,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                } else {
                    AndroidView(
                        factory = { ctx ->
                            ProfileCodeEditor(ctx, CodeEditorSyntax.MARKDOWN).apply {
                                setReadOnly(true)
                                setText(viewer.text)
                                applyColors(editorColors)
                                setWordWrap(autoWrap)
                            }.also { editorRef.value = it }.view
                        },
                        update = { editorRef.value?.setWordWrap(autoWrap) },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .padding(horizontal = 12.dp),
                    )
                }

                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val compactButtonPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    OutlinedButton(
                        onClick = { rich = !rich },
                        contentPadding = compactButtonPadding,
                    ) {
                        Text(
                            stringResource(
                                if (rich) R.string.conductor_viewer_raw else R.string.conductor_viewer_rich,
                            ),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(viewer.text))
                            Toast.makeText(
                                context,
                                context.getString(R.string.conductor_text_copied),
                                Toast.LENGTH_SHORT,
                            ).show()
                        },
                        contentPadding = compactButtonPadding,
                    ) {
                        Text(stringResource(R.string.conductor_copy_text))
                    }
                    Button(
                        onClick = onSave,
                        contentPadding = compactButtonPadding,
                    ) {
                        Text(stringResource(R.string.conductor_save_to_file))
                    }
                }
            }
        }
    }
}

@Composable
private fun ConductorEmptyState(onCommand: (String) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 48.dp, start = 8.dp, end = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.conductor_empty_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(R.string.conductor_empty_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { AssistChip(onClick = { onCommand("/help") }, label = { Text("/help") }) }
            item { AssistChip(onClick = { onCommand("/capabilities") }, label = { Text("/capabilities") }) }
            item { AssistChip(onClick = { onCommand("/tailscale") }, label = { Text("/tailscale") }) }
        }
    }
}

@Composable
private fun ReasoningItem(item: ConductorItem.Reasoning) {
    var expanded by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    val lineHeight = 18.sp
    val miniText = remember(item.text) { item.text.replace(Regex("""\s*\r?\n\s*"""), " ").trim() }
    val viewportHeight = with(LocalDensity.current) { lineHeight.toDp() * 2 }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (item.streaming) "Thinking" else "Thought",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (item.streaming) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(start = 6.dp).size(10.dp),
                    strokeWidth = 1.5.dp,
                )
            }
            Spacer(Modifier.weight(1f))
            if (!item.streaming && item.text.isNotBlank()) {
                TextButton(
                    onClick = { expanded = !expanded },
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) {
                    Text(
                        text = if (expanded) "Hide" else "Show",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
        if (item.text.isNotBlank()) {
            if (expanded) {
                SelectionContainer {
                    Text(
                        text = item.text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                    )
                }
            } else {
                val surface = MaterialTheme.colorScheme.surfaceContainerHigh
                Box(
                    modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp)
                        .height(viewportHeight)
                        .clip(RoundedCornerShape(8.dp))
                        .background(surface)
                        .padding(horizontal = 8.dp)
                        .clickable { expanded = true },
                ) {
                    Text(
                        text = miniText,
                        style = MaterialTheme.typography.bodySmall.copy(lineHeight = lineHeight),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxSize().verticalScroll(scroll),
                    )
                    Box(
                        modifier =
                        Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .height(10.dp)
                            .background(Brush.verticalGradient(listOf(surface, Color.Transparent))),
                    )
                    Box(
                        modifier =
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(10.dp)
                            .background(Brush.verticalGradient(listOf(Color.Transparent, surface))),
                    )
                }
                LaunchedEffect(item.text) {
                    snapshotFlow { scroll.maxValue }.collect { maxValue ->
                        if (maxValue > 0) scroll.scrollTo(maxValue)
                    }
                }
            }
        }
    }
}

@Composable
private fun ConductorItemView(
    item: ConductorItem,
    onConfirm: () -> Unit,
    onConfirmAll: () -> Unit,
    onCancel: () -> Unit,
    onAnswerQuestion: (String, List<String>) -> Unit,
    onCancelQuestion: (String) -> Unit,
    canRetry: Boolean,
    onRetry: () -> Unit,
    imageFile: (String) -> File?,
) {
    when (item) {
        is ConductorItem.User ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                UserMessage(item.text, item.sentAt, item.images, imageFile)
            }

        is ConductorItem.Assistant ->
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "conductor",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (item.streaming) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(start = 6.dp).size(10.dp),
                            strokeWidth = 1.5.dp,
                        )
                    }
                }
                SelectionContainer {
                    Text(
                        text = markdownAnnotated(item.text),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

        is ConductorItem.Reasoning -> ReasoningItem(item)

        is ConductorItem.Notice ->
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
                SelectionContainer {
                    Text(
                        text = item.body,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

        is ConductorItem.Error ->
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
                Text(
                    text = item.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (canRetry) {
                    TextButton(
                        onClick = onRetry,
                        modifier = Modifier.padding(top = 4.dp),
                    ) {
                        Text(stringResource(R.string.conductor_retry))
                    }
                }
            }

        is ConductorItem.Tool -> ToolItem(item)

        is ConductorItem.Question ->
            QuestionCard(
                item = item,
                onAnswer = { onAnswerQuestion(item.id, it) },
                onDismiss = { onCancelQuestion(item.id) },
            )

        is ConductorItem.Confirmation ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = stringResource(R.string.agent_confirm_title),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = item.request.toolName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    SelectionContainer {
                        Text(
                            text = item.request.displayArgsJson,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        )
                    }
                    Text(
                        text = item.request.reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    if (item.request.allowTurnApproval) {
                        Text(
                            text =
                                if (item.request.batchRemaining > 1) {
                                    stringResource(
                                        R.string.agent_confirm_batch,
                                        item.request.batchRemaining,
                                    )
                                } else {
                                    stringResource(R.string.agent_confirm_turn)
                                },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    if (item.request.allowTurnApproval) {
                        TextButton(
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            onClick = onConfirmAll,
                        ) {
                            Text(stringResource(R.string.agent_confirm_run_all))
                        }
                    }
                    Row(
                        modifier = Modifier.padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TextButton(onClick = onConfirm) {
                            Text(stringResource(R.string.agent_confirm_run))
                        }
                        TextButton(onClick = onCancel) {
                            Text(stringResource(R.string.conductor_probe_cancel))
                        }
                    }
                }
            }
    }
}

@Composable
private fun OptionIndicator(
    checked: Boolean,
    multiple: Boolean,
    enabled: Boolean,
) {
    val active = MaterialTheme.colorScheme.primary
    val inactive =
        if (enabled) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
        }
    val borderColor = if (checked) active else inactive
    val shape = if (multiple) RoundedCornerShape(5.dp) else CircleShape
    Box(
        modifier =
            Modifier
                .size(20.dp)
                .border(2.dp, borderColor, shape)
                .then(
                    if (multiple && checked) {
                        Modifier.background(active, shape)
                    } else {
                        Modifier
                    },
                ),
        contentAlignment = Alignment.Center,
    ) {
        when {
            multiple && checked ->
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(14.dp),
                )

            !multiple && checked ->
                Box(
                    modifier =
                        Modifier
                            .size(10.dp)
                            .background(active, CircleShape),
                )
        }
    }
}

@Composable
private fun QuestionCard(
    item: ConductorItem.Question,
    onAnswer: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val settled = item.answered || item.cancelled
    var picked by rememberSaveable(item.id) { mutableStateOf(item.selected) }
    val choices = if (item.answered) item.selected else picked
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = item.header.ifBlank { "Conductor asks" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = item.question,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item.options.forEach { option ->
                    val checked = option in choices
                    Row(
                        modifier =
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .selectable(
                                selected = checked,
                                enabled = !settled,
                                role = if (item.multiple) Role.Checkbox else Role.RadioButton,
                                onClick = {
                                    picked =
                                        if (item.multiple) {
                                            if (checked) picked - option else picked + option
                                        } else {
                                            listOf(option)
                                        }
                                },
                            )
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OptionIndicator(
                            checked = checked,
                            multiple = item.multiple,
                            enabled = !settled,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = option,
                            style = MaterialTheme.typography.bodyMedium,
                            color =
                                if (checked) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            modifier = Modifier.weight(1f).padding(end = 8.dp),
                        )
                    }
                }
            }
            when {
                item.answered ->
                    Text(
                        text = "Answered",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )

                item.cancelled ->
                    Text(
                        text = "Dismissed",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                else -> {
                    Text(
                        text = if (item.multiple) "Select any that apply." else "Select one.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onDismiss) {
                            Text("Dismiss")
                        }
                        Spacer(Modifier.weight(1f))
                        Button(
                            onClick = { onAnswer(picked) },
                            enabled = picked.isNotEmpty(),
                        ) {
                            Text("Answer")
                        }
                    }
                }
            }
        }
    }
}

private val InlineMarkdown = Regex("""\*\*(.+?)\*\*|`([^`]+)`""")

private fun ConductorItem.contentVersion(): Int =
    when (this) {
        is ConductorItem.Assistant -> text.length
        is ConductorItem.Reasoning -> text.length
        is ConductorItem.Tool -> (result?.output?.toString()?.length ?: 0) + (result?.message?.length ?: 0)
        is ConductorItem.Question -> if (answered || cancelled) 1 else 0
        else -> 0
    }

private fun ConductorItem.stableKey(index: Int): String =
    when (this) {
        is ConductorItem.User -> "user:$index"
        is ConductorItem.Assistant -> "assistant:$index"
        is ConductorItem.Reasoning -> "reasoning:$index"
        is ConductorItem.Tool -> "tool:${call.id}"
        is ConductorItem.Notice -> "notice:$index"
        is ConductorItem.Error -> "error:$index"
        is ConductorItem.Confirmation -> "confirm:${request.id}"
        is ConductorItem.Question -> "question:${id}"
    }

private fun markdownAnnotated(text: String): AnnotatedString =
    buildAnnotatedString {
        var cursor = 0
        InlineMarkdown.findAll(text).forEach { match ->
            if (match.range.first > cursor) {
                append(text.substring(cursor, match.range.first))
            }
            val bold = match.groups[1]?.value
            val code = match.groups[2]?.value
            when {
                bold != null ->
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                        append(bold)
                    }

                code != null ->
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) {
                        append(code)
                    }
            }
            cursor = match.range.last + 1
        }
        if (cursor < text.length) {
            append(text.substring(cursor))
        }
    }

@Composable
private fun UserImage(
    ref: AgentImageRef,
    imageFile: (String) -> File?,
) {
    var fullscreen by remember { mutableStateOf(false) }
    var loaded by remember(ref.id) { mutableStateOf(false) }
    val bitmap by produceState<ImageBitmap?>(initialValue = null, ref.id) {
        value =
            withContext(Dispatchers.IO) {
                imageFile(ref.id)?.let { file ->
                    BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
                }
            }
        loaded = true
    }
    val preview = bitmap
    if (preview != null) {
        Image(
            bitmap = preview,
            contentDescription = ref.name,
            contentScale = ContentScale.Crop,
            modifier =
                Modifier
                    .size(160.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { fullscreen = true },
        )
        if (fullscreen) {
            Dialog(
                onDismissRequest = { fullscreen = false },
                properties = DialogProperties(usePlatformDefaultWidth = false),
            ) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .background(Color.Black)
                            .clickable { fullscreen = false },
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        bitmap = preview,
                        contentDescription = ref.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    } else {
        Box(
            modifier =
                Modifier
                    .size(160.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            if (!loaded) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Text(
                    text = stringResource(R.string.conductor_image_unavailable),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun UserMessage(
    text: String,
    sentAt: Long,
    images: List<AgentImageRef>,
    imageFile: (String) -> File?,
) {
    var showTime by remember { mutableStateOf(false) }
    Column(horizontalAlignment = Alignment.End) {
        Surface(
            modifier =
            Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = sentAt > 0L,
            ) { showTime = !showTime },
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Column {
                images.forEach { ref ->
                    Box(modifier = Modifier.padding(start = 8.dp, top = 8.dp, end = 8.dp)) {
                        UserImage(ref, imageFile)
                    }
                }
                if (text.isNotBlank()) {
                    SelectionContainer {
                        Text(
                            text = text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
        if (showTime && sentAt > 0L) {
            Text(
                text = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(sentAt)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, end = 4.dp),
            )
        }
    }
}

@Composable
private fun ToolItem(item: ConductorItem.Tool) {
    var expanded by remember { mutableStateOf(false) }
    val result = item.result
    val statusColor =
        when (item.status) {
            ToolStatus.SUCCEEDED -> MaterialTheme.colorScheme.primary
            ToolStatus.FAILED -> MaterialTheme.colorScheme.error
            ToolStatus.CANCELLED -> MaterialTheme.colorScheme.onSurfaceVariant
            ToolStatus.RUNNING -> MaterialTheme.colorScheme.onSurfaceVariant
        }
    val degradedLabel = stringResource(R.string.conductor_tool_degraded)
    val statusText =
        if (result?.degraded == true) {
            "${item.status.name.lowercase()} · $degradedLabel"
        } else {
            item.status.name.lowercase()
        }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = item.call.name,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = statusText,
                style = MaterialTheme.typography.labelSmall,
                color = statusColor,
            )
            if (result != null) {
                TextButton(
                    onClick = { expanded = !expanded },
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) {
                    Text(
                        stringResource(
                            if (expanded) R.string.conductor_tool_hide_details else R.string.conductor_tool_details,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
        if (item.call.displayArgsJson.isNotBlank() && item.call.displayArgsJson != "{}") {
            SelectionContainer {
                Text(
                    text = item.call.displayArgsJson,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                )
            }
        }
        if (result != null) {
            val details =
                result.output?.let { AgentJson.pretty(it) }
                    ?: result.message
                    ?: result.errorCode
                    ?: ""
            if (expanded) {
                SelectionContainer {
                    Box(
                        modifier =
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                    ) {
                        Text(
                            text = if (result.truncated) "$details\n(truncated)" else details,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = if (result.ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                            softWrap = false,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            } else {
                val outputMessage =
                    (result.output?.get("message") as? JsonPrimitive)?.contentOrNull
                        ?: ((result.output?.get("core") as? JsonObject)?.get("message") as? JsonPrimitive)?.contentOrNull
                val summary =
                    result.message
                        ?: outputMessage
                        ?: result.errorCode
                        ?: stringResource(R.string.conductor_tool_details_hint)
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (result.ok) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
        }
    }
}

private data class McpUseRow(
    val key: String,
    val title: String,
    val detail: String,
)

@Composable
private fun McpUseSuggestions(
    rows: List<McpUseRow>,
    onPick: (Int) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        tonalElevation = 2.dp,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
    ) {
        LazyColumn(modifier = Modifier.heightIn(max = 220.dp)) {
            items(rows.size, key = { index -> rows[index].key }) { index ->
                val row = rows[index]
                Row(
                    modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPick(index) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = row.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = row.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun EffortSuggestions(
    levels: List<String>,
    onPick: (String) -> Unit,
    hint: (String) -> String,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        tonalElevation = 2.dp,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
    ) {
        LazyColumn(modifier = Modifier.heightIn(max = 220.dp)) {
            items(levels, key = { it }) { level ->
                Row(
                    modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPick(level) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = level,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = hint(level),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun CommandSuggestions(
    commands: List<ConductorCommand>,
    onPick: (ConductorCommand) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        tonalElevation = 2.dp,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
    ) {
        LazyColumn(modifier = Modifier.heightIn(max = 220.dp)) {
            items(commands, key = { it.name }) { command ->
                Row(
                    modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPick(command) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "/${command.name}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = command.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

private fun formatTokenCount(tokens: Int): String =
    when {
        tokens >= 1_000_000 -> {
            val value = tokens / 1_000_000.0
            if (value % 1.0 == 0.0 || value >= 10) {
                "${value.toInt()}M"
            } else {
                String.format(Locale.US, "%.1fM", value)
            }
        }

        tokens >= 1_000 -> {
            val value = tokens / 1_000.0
            if (value % 1.0 == 0.0 || value >= 100) {
                "${value.toInt()}k"
            } else {
                String.format(Locale.US, "%.1fk", value)
            }
        }

        else -> tokens.toString()
    }

@Composable
private fun TurnStatus(
    text: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(10.dp),
            strokeWidth = 1.5.dp,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

@Composable
private fun ConductorInputBar(
    modifier: Modifier = Modifier,
    height: Dp,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    input: TextFieldValue,
    onInputChange: (TextFieldValue) -> Unit,
    onLineCountChanged: (Int) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    isRunning: Boolean,
    pendingImage: AgentImageRef?,
    importingImage: Boolean,
    onAddImage: () -> Unit,
    onRemoveImage: () -> Unit,
    imageFile: (String) -> File?,
    statusText: String?,
    contextPercent: Int,
    contextUsedTokens: Int,
    contextLimitTokens: Int,
    onSlash: () -> Unit,
    focusRequester: FocusRequester,
) {
    val sendLabel = stringResource(R.string.conductor_send)
    val stopLabel = stringResource(R.string.stop)
    val expandLabel = stringResource(if (expanded) R.string.conductor_collapse else R.string.conductor_expand)
    Surface(
        modifier = modifier.fillMaxWidth().height(height),
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Box(Modifier.fillMaxSize()) {
            // The placeholder is a sibling of the text field, not part of its
            // decoration. BasicTextField relayouts its decoration while the
            // panel resizes, which can briefly blur the hint during the IME
            // animation; a plain Text stays put.
            if (input.text.isEmpty()) {
                Text(
                    text = stringResource(R.string.conductor_input_hint),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier =
                    Modifier.padding(
                        start = 16.dp,
                        top = InputPanelTopPadding,
                        end = 60.dp,
                    ),
                )
            }

            BasicTextField(
                value = input,
                onValueChange = onInputChange,
                modifier =
                Modifier
                    .fillMaxSize()
                    .padding(
                        start = 16.dp,
                        top = InputPanelTopPadding,
                        end = 60.dp,
                        bottom = InputPanelBottomPadding,
                    )
                    .focusRequester(focusRequester),
                textStyle =
                MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                maxLines = if (expanded) Int.MAX_VALUE else InputPanelMaxLines,
                onTextLayout = { result -> onLineCountChanged(result.lineCount) },
            )

            Box(
                modifier =
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 6.dp, end = 6.dp)
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onToggleExpand)
                    .semantics { contentDescription = expandLabel },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter =
                    painterResource(
                        if (expanded) {
                            R.drawable.ic_conductor_collapse
                        } else {
                            R.drawable.ic_conductor_expand
                        },
                    ),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(
                // 48dp touch targets overhang the 36dp visuals by 6dp on each
                // side; 6dp row padding keeps the visual circles 12dp from the
                // panel edges. The earlier 24dp inset was based on an edge-
                // gesture theory that the stale cancel flag has since explained;
                // the tighter visual margin is the intended composer layout.
                modifier =
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(InputPanelBottomPadding)
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                InputGlyphButton(
                    icon = R.drawable.ic_conductor_slash,
                    label = stringResource(R.string.conductor_commands),
                    onClick = onSlash,
                )
                if (importingImage) {
                    Box(
                        modifier = Modifier.size(48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                } else {
                    InputGlyphButton(
                        icon = R.drawable.ic_conductor_add,
                        label = stringResource(R.string.conductor_image_add),
                        onClick = onAddImage,
                    )
                }
                pendingImage?.let { ref ->
                    PendingImageChip(
                        ref = ref,
                        imageFile = imageFile,
                        onRemove = onRemoveImage,
                    )
                }
                Box(
                    modifier =
                    Modifier
                        .weight(1f)
                        .padding(start = 8.dp, end = 10.dp),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    if (statusText == null) {
                        if (contextLimitTokens > 0) {
                            val usedText = formatTokenCount(contextUsedTokens)
                            val limitText = formatTokenCount(contextLimitTokens)
                            val contextDescription =
                                stringResource(
                                    R.string.conductor_context_usage_description,
                                    usedText,
                                    limitText,
                                )
                            Text(
                                text =
                                    stringResource(
                                        R.string.conductor_context_usage,
                                        usedText,
                                        limitText,
                                    ),
                                style = MaterialTheme.typography.labelSmall,
                                color =
                                    if (contextPercent >= ContextWarningPercent) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.End,
                                modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .semantics { contentDescription = contextDescription },
                            )
                        }
                    } else {
                        val statusLabel =
                            stringResource(
                                when {
                                    statusText == "Receiving response" -> R.string.conductor_status_thinking
                                    statusText.startsWith("Running ") -> R.string.conductor_status_running
                                    else -> R.string.conductor_status_waiting
                                },
                            )
                        TurnStatus(
                            text = statusLabel,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                if (isRunning) {
                    Box(
                        modifier =
                        Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .clickable(onClick = onStop)
                            .semantics { contentDescription = stopLabel },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier =
                            Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.errorContainer),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_stop_24),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                } else {
                    val enabled = !importingImage && (input.text.isNotBlank() || pendingImage != null)
                    Box(
                        modifier =
                        Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .clickable(enabled = enabled, onClick = onSend)
                            .semantics { contentDescription = sendLabel },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier =
                            Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(
                                    if (enabled) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainerHighest
                                    },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_conductor_send),
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint =
                                if (enabled) {
                                    MaterialTheme.colorScheme.onPrimary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PendingImageChip(
    ref: AgentImageRef,
    imageFile: (String) -> File?,
    onRemove: () -> Unit,
) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, ref.id) {
        value =
            withContext(Dispatchers.IO) {
                imageFile(ref.id)?.let { file ->
                    BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
                }
            }
    }
    val removeLabel = stringResource(R.string.conductor_image_remove)
    Box(
        modifier = Modifier.size(48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            val preview = bitmap
            if (preview != null) {
                Image(
                    bitmap = preview,
                    contentDescription = ref.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    text = "IMG",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Box(
            modifier =
                Modifier
                    .align(Alignment.TopEnd)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.error)
                    .clickable(onClick = onRemove)
                    .semantics { contentDescription = removeLabel },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onError,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

@Composable
private fun InputGlyphButton(
    @DrawableRes icon: Int,
    label: String,
    onClick: () -> Unit,
) {
    Box(
        modifier =
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

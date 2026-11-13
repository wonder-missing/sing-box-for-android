package io.nekohasekai.sfa.conductor

import android.net.Uri
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.agent.AgentJson
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

/**
 * The chat outlives this navigation destination. Scoping the ViewModel to the
 * activity keeps an in-flight turn alive when the user leaves the screen and
 * comes back; it is only cleared when the activity finishes.
 */
@Composable
private fun activityConductorViewModel(): ConductorViewModel {
    val activity =
        LocalActivity.current as? ComponentActivity
            ?: error("ConductorScreen requires a ComponentActivity host")
    return viewModel(viewModelStoreOwner = activity)
}

@Composable
fun ConductorScreen(
    serviceStatus: Status = Status.Stopped,
    viewModel: ConductorViewModel = activityConductorViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val fileRequest by viewModel.fileRequest.collectAsState()
    val remoteServer by RemoteControlManager.remoteServer.collectAsState()
    val remoteConnected by RemoteControlManager.isConnected.collectAsState()

    var pendingFilePickId by rememberSaveable { mutableStateOf<String?>(null) }
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
    val listState = rememberLazyListState()
    var stickToBottom by remember { mutableStateOf(true) }
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
    val mcpTools by viewModel.mcpListed.collectAsState()
    val offerMcpVerb = ConductorMcpUse.offerVerb(input)
    val mcpToolSuggestions = ConductorMcpUse.toolSuggestions(input, mcpTools)
    var slashMenuHidden by remember { mutableStateOf(false) }
    val slashMenuVisible =
        !inputExpanded &&
            !slashMenuHidden &&
            (
                effortSuggestions.isNotEmpty() ||
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
        if (text.isEmpty()) return
        if (text.length > MaxInputChars) {
            Toast.makeText(
                context,
                context.getString(R.string.conductor_input_too_long, MaxInputChars),
                Toast.LENGTH_LONG,
            ).show()
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

    fun copyConversation() {
        clipboard.setText(AnnotatedString(viewModel.conversationTranscript()))
        Toast.makeText(context, context.getString(R.string.conductor_conversation_copied), Toast.LENGTH_SHORT).show()
    }

    fun copyApiTrace() {
        if (!viewModel.hasApiTrace()) {
            Toast.makeText(context, context.getString(R.string.conductor_api_trace_empty), Toast.LENGTH_LONG).show()
            return
        }
        clipboard.setText(AnnotatedString(viewModel.apiTraceMarkdown()))
        Toast.makeText(context, context.getString(R.string.conductor_api_trace_copied), Toast.LENGTH_SHORT).show()
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

    fun exportConversation() {
        if (uiState.items.isEmpty()) {
            Toast.makeText(context, context.getString(R.string.conductor_conversation_empty), Toast.LENGTH_LONG).show()
            return
        }
        export(ConductorExport.Conversation, ConductorExportNames.conversation())
    }

    fun exportApiTrace() {
        if (!viewModel.hasApiTrace()) {
            Toast.makeText(context, context.getString(R.string.conductor_api_trace_empty), Toast.LENGTH_LONG).show()
            return
        }
        export(ConductorExport.ApiTrace, ConductorExportNames.apiTrace())
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
                            canRetry = uiState.canRetryLastTurn && index == uiState.items.lastIndex,
                            onRetry = viewModel::retryLastTurn,
                        )
                    }
                    item(key = "conversation-actions") {
                        ConversationActions(
                            onCopyConversation = ::copyConversation,
                            onExportConversation = ::exportConversation,
                            onCopyApiTrace = ::copyApiTrace,
                            onExportApiTrace = ::exportApiTrace,
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
            onAt = {
                applyEdit(ConductorInputEdits.mention(input, MaxInputChars))
                focusInput()
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
private fun ConversationActions(
    onCopyConversation: () -> Unit,
    onExportConversation: () -> Unit,
    onCopyApiTrace: () -> Unit,
    onExportApiTrace: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
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
                text = { Text(stringResource(R.string.conductor_copy_conversation)) },
                onClick = {
                    expanded = false
                    onCopyConversation()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.conductor_export_conversation)) },
                onClick = {
                    expanded = false
                    onExportConversation()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.conductor_copy_api_trace)) },
                onClick = {
                    expanded = false
                    onCopyApiTrace()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.conductor_export_api_trace)) },
                onClick = {
                    expanded = false
                    onExportApiTrace()
                },
            )
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
                        .padding(horizontal = 8.dp),
                ) {
                    Text(
                        text = item.text,
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
    canRetry: Boolean,
    onRetry: () -> Unit,
) {
    when (item) {
        is ConductorItem.User ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                UserMessage(item.text)
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

private val InlineMarkdown = Regex("""\*\*(.+?)\*\*|`([^`]+)`""")

private fun ConductorItem.contentVersion(): Int =
    when (this) {
        is ConductorItem.Assistant -> text.length
        is ConductorItem.Reasoning -> text.length
        is ConductorItem.Tool -> (result?.output?.toString()?.length ?: 0) + (result?.message?.length ?: 0)
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
private fun UserMessage(text: String) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
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
            )
            Text(
                text = statusText,
                style = MaterialTheme.typography.labelSmall,
                color = statusColor,
            )
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
            TextButton(onClick = { expanded = !expanded }) {
                Text(
                    stringResource(
                        if (expanded) R.string.conductor_tool_hide_details else R.string.conductor_tool_details,
                    ),
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
                        text = ConductorReasoningCommand.hint(level),
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
    contextPercent: Int,
    contextUsedTokens: Int,
    contextLimitTokens: Int,
    onSlash: () -> Unit,
    onAt: () -> Unit,
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
                // panel edges. The row centers them 30dp above the bottom.
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
                InputGlyphButton(
                    icon = R.drawable.ic_conductor_at,
                    label = stringResource(R.string.conductor_mention),
                    onClick = onAt,
                )
                Spacer(modifier = Modifier.weight(1f))
                if (contextLimitTokens > 0) {
                    val contextDescription =
                        stringResource(
                            R.string.conductor_context_usage_description,
                            contextPercent,
                            contextUsedTokens,
                            contextLimitTokens,
                        )
                    Text(
                        text =
                            stringResource(
                                R.string.conductor_context_usage,
                                contextPercent,
                                formatTokenCount(contextUsedTokens),
                                formatTokenCount(contextLimitTokens),
                            ),
                        style = MaterialTheme.typography.labelSmall,
                        color =
                            if (contextPercent >= ContextWarningPercent) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        maxLines = 1,
                        modifier =
                        Modifier
                            .padding(end = 4.dp)
                            .semantics { contentDescription = contextDescription },
                    )
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
                    val enabled = input.text.isNotBlank()
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

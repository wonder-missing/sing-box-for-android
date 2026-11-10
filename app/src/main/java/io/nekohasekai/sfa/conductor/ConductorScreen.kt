package io.nekohasekai.sfa.conductor

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.agent.AgentJson
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

@Composable
fun ConductorScreen(
    serviceStatus: Status = Status.Stopped,
    viewModel: ConductorViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val remoteServer by RemoteControlManager.remoteServer.collectAsState()
    val remoteConnected by RemoteControlManager.isConnected.collectAsState()

    LaunchedEffect(serviceStatus, remoteServer, remoteConnected) {
        viewModel.onServiceState(
            serviceStatus = serviceStatus,
            remoteControl = remoteServer != null,
            remoteConnected = remoteConnected,
        )
    }
    DisposableEffect(Unit) {
        onDispose { viewModel.disconnect() }
    }

    var input by rememberSaveable { mutableStateOf("") }
    var inputExpanded by rememberSaveable { mutableStateOf(false) }
    var inputLineCount by remember { mutableStateOf(1) }
    val focusRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val scaffoldPadding = LocalScaffoldPadding.current
    val suggestions = ConductorCommands.suggestions(input)

    fun focusInput() {
        runCatching { focusRequester.requestFocus() }
    }

    fun send() {
        val text = input.trim()
        if (text.isEmpty()) return
        viewModel.submit(text)
        input = ""
        inputExpanded = false
        focusManager.clearFocus()
    }

    fun copyConversation() {
        clipboard.setText(AnnotatedString(viewModel.conversationTranscript()))
        Toast.makeText(context, context.getString(R.string.conductor_conversation_copied), Toast.LENGTH_SHORT).show()
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
        val density = LocalDensity.current
        val lineHeightDp = with(density) {
            MaterialTheme.typography.bodyLarge.lineHeight.toDp()
        }
        val defaultPanelHeight =
            (InputPanelTopPadding +
                lineHeightDp * inputLineCount.coerceIn(1, InputPanelMaxLines).toFloat() +
                InputPanelBottomPadding)
                .coerceAtLeast(InputPanelMinHeight)

        val rawTarget = if (inputExpanded) maxHeight * 0.85f else defaultPanelHeight
        // Whole-pixel heights avoid subpixel shimmer while the panel moves.
        val targetPanelHeight = with(density) { rawTarget.roundToPx().toDp() }

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
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (uiState.items.isEmpty()) {
                    item(key = "empty") {
                        ConductorEmptyState(
                            onCommand = { command ->
                                input = ""
                                inputExpanded = false
                                viewModel.submit(command)
                            },
                        )
                    }
                } else {
                    items(uiState.items.size, key = { index -> index }) { index ->
                        ConductorItemView(
                            item = uiState.items[index],
                            onConfirm = viewModel::confirmConfirmation,
                            onCancel = viewModel::cancelConfirmation,
                        )
                    }
                    item(key = "conversation-actions") {
                        ConversationActions(onCopy = ::copyConversation)
                    }
                }
            }

            LaunchedEffect(uiState.items.size) {
                if (uiState.items.isNotEmpty()) {
                    listState.animateScrollToItem(uiState.items.lastIndex)
                }
            }

            if (!inputExpanded && suggestions.isNotEmpty()) {
                CommandSuggestions(
                    commands = suggestions,
                    onPick = { command ->
                        if (command.takesArgs) {
                            input = "/${command.name} "
                            focusInput()
                        } else {
                            input = ""
                            inputExpanded = false
                            viewModel.submit("/${command.name}")
                            focusManager.clearFocus()
                        }
                    },
                )
            }
        }

        ConductorInputBar(
            modifier = Modifier.align(Alignment.BottomCenter),
            height = panelHeight,
            expanded = inputExpanded,
            onToggleExpand = { inputExpanded = !inputExpanded },
            input = input,
            onInputChange = { input = it },
            onLineCountChanged = { inputLineCount = it },
            onSend = { send() },
            isRunning = uiState.isRunning,
            onSlash = {
                input = if (input.startsWith("/")) input else "/" + input
                focusInput()
            },
            onAt = {
                input =
                    when {
                        input.isBlank() -> "@"
                        input.last().isWhitespace() -> input + "@"
                        else -> "$input @"
                    }
                focusInput()
            },
            focusRequester = focusRequester,
        )
    }
}

@Composable
private fun ConversationActions(onCopy: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                imageVector = Icons.Default.MoreVert,
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
                    onCopy()
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
private fun ConductorItemView(
    item: ConductorItem,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
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
                Text(
                    text = "conductor",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
                SelectionContainer {
                    Text(
                        text = markdownAnnotated(item.text),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UserMessage(text: String) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var menuExpanded by remember { mutableStateOf(false) }

    Box {
        Surface(
            modifier =
            Modifier.combinedClickable(
                onClick = {},
                onLongClick = { menuExpanded = true },
            ),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.conductor_copy)) },
                onClick = {
                    clipboard.setText(AnnotatedString(text))
                    menuExpanded = false
                    Toast.makeText(context, context.getString(R.string.conductor_copied), Toast.LENGTH_SHORT).show()
                },
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

@Composable
private fun ConductorInputBar(
    modifier: Modifier = Modifier,
    height: Dp,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    input: String,
    onInputChange: (String) -> Unit,
    onLineCountChanged: (Int) -> Unit,
    onSend: () -> Unit,
    isRunning: Boolean,
    onSlash: () -> Unit,
    onAt: () -> Unit,
    focusRequester: FocusRequester,
) {
    val sendLabel = stringResource(R.string.conductor_send)
    val runningLabel = stringResource(R.string.conductor_running)
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
            if (input.isEmpty()) {
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
                if (isRunning) {
                    Box(
                        modifier = Modifier.size(48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            modifier =
                            Modifier
                                .size(20.dp)
                                .semantics { contentDescription = runningLabel },
                            strokeWidth = 2.dp,
                        )
                    }
                } else {
                    val enabled = input.isNotBlank()
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

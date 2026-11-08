package io.nekohasekai.sfa.conductor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.topbar.LocalScaffoldPadding
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.utils.RemoteControlManager

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
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current
    val scaffoldPadding = LocalScaffoldPadding.current
    val suggestions = ConductorCommands.suggestions(input)

    fun send() {
        val text = input.trim()
        if (text.isEmpty()) return
        viewModel.submit(text)
        input = ""
        focusManager.clearFocus()
    }

    Column(
        modifier =
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .padding(scaffoldPadding)
            .imePadding(),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (uiState.messages.isEmpty()) {
                item(key = "empty") {
                    ConductorEmptyState(
                        onCommand = { command ->
                            input = ""
                            viewModel.submit(command)
                        },
                    )
                }
            } else {
                items(uiState.messages.size, key = { index -> index }) { index ->
                    ConductorMessageItem(uiState.messages[index])
                }
            }
        }

        LaunchedEffect(uiState.messages.size) {
            if (uiState.messages.isNotEmpty()) {
                listState.animateScrollToItem(uiState.messages.lastIndex)
            }
        }

        if (suggestions.isNotEmpty()) {
            CommandSuggestions(
                commands = suggestions,
                onPick = { command ->
                    if (command.takesArgs) {
                        input = "/${command.name} "
                    } else {
                        input = ""
                        viewModel.submit("/${command.name}")
                        focusManager.clearFocus()
                    }
                },
            )
        }

        ConductorInputBar(
            input = input,
            onInputChange = { input = it },
            onSend = { send() },
            isRunning = uiState.isRunning,
            onSlash = { input = "/" },
        )
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
private fun ConductorMessageItem(message: ConductorMessage) {
    when (message.kind) {
        ConductorMessageKind.USER ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Text(
                        text = message.body,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }

        ConductorMessageKind.SYSTEM,
        ConductorMessageKind.RESULT,
        ConductorMessageKind.ERROR -> {
            val isError = message.kind == ConductorMessageKind.ERROR
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = message.title,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
                )
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color =
                    if (isError) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerLow
                    },
                ) {
                    SelectionContainer {
                        Text(
                            text = message.body,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily =
                            if (message.kind == ConductorMessageKind.RESULT) {
                                FontFamily.Monospace
                            } else {
                                FontFamily.Default
                            },
                            color =
                            if (isError) {
                                MaterialTheme.colorScheme.onErrorContainer
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
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
        shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
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
    input: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    isRunning: Boolean,
    onSlash: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            TextButton(onClick = onSlash) {
                Text("/", style = MaterialTheme.typography.titleLarge)
            }
            OutlinedTextField(
                value = input,
                onValueChange = onInputChange,
                modifier = Modifier.weight(1f),
                minLines = 1,
                maxLines = 3,
                shape = RoundedCornerShape(20.dp),
                placeholder = { Text(stringResource(R.string.conductor_input_hint)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
            )
            Box(
                modifier = Modifier.size(48.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (isRunning) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = onSend, enabled = input.isNotBlank()) {
                        Icon(
                            imageVector = Icons.Default.Send,
                            contentDescription = stringResource(R.string.conductor_send),
                        )
                    }
                }
            }
        }
    }
}

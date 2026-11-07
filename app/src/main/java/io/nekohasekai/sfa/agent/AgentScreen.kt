package io.nekohasekai.sfa.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.topbar.LocalScaffoldPadding
import io.nekohasekai.sfa.compose.topbar.OverrideTopBar
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.utils.RemoteControlManager
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentScreen(
    navController: NavController,
    serviceStatus: Status = Status.Stopped,
    viewModel: AgentViewModel = viewModel(),
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
    var showProbeDialog by rememberSaveable { mutableStateOf(false) }
    var probeOutbound by rememberSaveable { mutableStateOf("") }
    var probeUrl by rememberSaveable { mutableStateOf("https://example.com/") }
    val listState = rememberLazyListState()

    val parseFailedHint = stringResource(R.string.agent_parse_failed)
    val manualListDecision = stringResource(R.string.agent_manual_list)
    val manualSnapshotDecision = stringResource(R.string.agent_manual_snapshot)
    val manualProbeDecision = stringResource(R.string.agent_manual_probe)
    val statusText =
        when {
            uiState.remoteControl && uiState.coreConnected -> stringResource(R.string.agent_status_remote_connected)
            uiState.remoteControl -> stringResource(R.string.agent_status_remote_disconnected)
            uiState.coreConnected -> stringResource(R.string.agent_status_connected)
            else -> stringResource(R.string.agent_status_disconnected)
        }

    OverrideTopBar {
        TopAppBar(
            title = { Text(stringResource(R.string.agent)) },
            navigationIcon = {
                IconButton(onClick = { navController.navigateUp() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                }
            },
        )
    }

    LaunchedEffect(uiState.entries.lastOrNull()) {
        if (uiState.entries.isNotEmpty()) {
            listState.animateScrollToItem(uiState.entries.lastIndex)
        }
    }

    val scaffoldPadding = LocalScaffoldPadding.current
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .padding(scaffoldPadding),
    ) {
        Text(
            text = statusText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        Text(
            text = stringResource(R.string.agent_no_model_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (uiState.entries.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.agent_empty_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(uiState.entries) { entry ->
                AgentEntryCard(entry)
            }
        }

        if (uiState.isRunning) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.agent_input_hint)) },
                maxLines = 3,
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    val text = input.trim()
                    if (text.isEmpty() || uiState.isRunning) {
                        return@Button
                    }
                    input = ""
                    viewModel.addUserMessage(text)
                    val planned = AgentIntentParser.parse(text)
                    if (planned == null) {
                        viewModel.addInfo(parseFailedHint)
                    } else {
                        viewModel.invokeTool(
                            name = planned.tool,
                            argsJson = planned.argsJson,
                            decision = planned.rationale,
                        )
                    }
                },
                enabled = !uiState.isRunning && input.isNotBlank(),
            ) {
                Text(stringResource(R.string.agent_send))
            }
        }

        LazyRow(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                AssistChip(
                    onClick = {
                        viewModel.invokeTool("list_nodes", "{}", decision = manualListDecision)
                    },
                    enabled = !uiState.isRunning,
                    label = { Text("list_nodes") },
                )
            }
            item {
                AssistChip(
                    onClick = {
                        viewModel.invokeTool("network_snapshot", "{}", decision = manualSnapshotDecision)
                    },
                    enabled = !uiState.isRunning,
                    label = { Text("network_snapshot") },
                )
            }
            item {
                AssistChip(
                    onClick = { showProbeDialog = true },
                    enabled = !uiState.isRunning,
                    label = { Text("probe_via_node") },
                )
            }
        }
    }

    if (showProbeDialog) {
        AlertDialog(
            onDismissRequest = { showProbeDialog = false },
            title = { Text(stringResource(R.string.agent_probe_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = probeOutbound,
                        onValueChange = { probeOutbound = it },
                        label = { Text(stringResource(R.string.agent_probe_outbound)) },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = probeUrl,
                        onValueChange = { probeUrl = it },
                        label = { Text(stringResource(R.string.agent_probe_url)) },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = probeOutbound.isNotBlank() && probeUrl.isNotBlank(),
                    onClick = {
                        showProbeDialog = false
                        val args =
                            buildJsonObject {
                                put("outbound", probeOutbound.trim())
                                put("url", probeUrl.trim())
                            }
                        viewModel.invokeTool(
                            name = "probe_via_node",
                            argsJson = args.toString(),
                            decision = manualProbeDecision,
                        )
                    },
                ) {
                    Text(stringResource(R.string.agent_probe_run))
                }
            },
            dismissButton = {
                TextButton(onClick = { showProbeDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    uiState.pendingConfirmation?.let { pending ->
        AlertDialog(
            onDismissRequest = viewModel::dismissConfirmation,
            title = { Text(stringResource(R.string.agent_confirm_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(pending.reason)
                    Text(
                        text = pending.displayArgsJson,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmPending) {
                    Text(stringResource(R.string.agent_confirm_run))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissConfirmation) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun AgentEntryCard(entry: AgentViewModel.Entry) {
    val colorScheme = MaterialTheme.colorScheme
    val containerColor =
        when (entry.kind) {
            AgentViewModel.EntryKind.USER -> colorScheme.primaryContainer.copy(alpha = 0.35f)
            AgentViewModel.EntryKind.CALL -> colorScheme.tertiaryContainer.copy(alpha = 0.35f)
            AgentViewModel.EntryKind.RESULT -> colorScheme.surfaceVariant.copy(alpha = 0.5f)
            else -> colorScheme.surfaceVariant.copy(alpha = 0.3f)
        }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = containerColor,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = entry.title,
                style = MaterialTheme.typography.labelMedium,
                color = colorScheme.primary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = entry.body,
                style = MaterialTheme.typography.bodySmall,
                fontFamily =
                    if (entry.kind == AgentViewModel.EntryKind.CALL ||
                        entry.kind == AgentViewModel.EntryKind.RESULT
                    ) {
                        FontFamily.Monospace
                    } else {
                        null
                    },
            )
        }
    }
}

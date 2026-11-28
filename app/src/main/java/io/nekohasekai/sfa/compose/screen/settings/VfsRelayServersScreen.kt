package io.nekohasekai.sfa.compose.screen.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.topbar.LocalScaffoldPadding
import io.nekohasekai.sfa.compose.topbar.OverrideTopBar
import io.nekohasekai.sfa.vfs.relay.VfsRelayRuntime
import io.nekohasekai.sfa.vfs.relay.VfsRelayServerView
import io.nekohasekai.sfa.vfs.relay.VfsRelayState
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VfsRelayServersScreen(navController: NavController) {
    OverrideTopBar {
        TopAppBar(
            title = { Text(stringResource(R.string.vfs_relay_servers_title)) },
            navigationIcon = {
                IconButton(onClick = { navController.navigateUp() }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.content_description_back),
                    )
                }
            },
        )
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scaffoldPadding = LocalScaffoldPadding.current
    val supported = android.os.Build.VERSION.SDK_INT >= 24

    if (!supported) {
        Column(
            modifier = Modifier.fillMaxSize().padding(scaffoldPadding).padding(16.dp),
        ) {
            Text(stringResource(R.string.share_files_unsupported), style = MaterialTheme.typography.bodyLarge)
        }
        return
    }

    VfsRelayRuntime.ensure(context)
    val serverViews by VfsRelayRuntime.servers.collectAsState()
    val selectedServerId by VfsRelayRuntime.selectedServerId.collectAsState()
    val relayState by VfsRelayRuntime.state.collectAsState()
    val shares by VfsRelayRuntime.shares.collectAsState()
    val active =
        relayState is VfsRelayState.Connected ||
            relayState is VfsRelayState.Connecting ||
            relayState is VfsRelayState.Backoff
    val selectedServer = serverViews.firstOrNull { it.selected }
    val selectedHasShares =
        selectedServer?.let { server ->
            shares.any { it.serverId == server.id && it.status != "revoked" }
        } == true

    var busy by remember { mutableStateOf(false) }
    var showAddServer by remember { mutableStateOf(false) }
    var editServer by remember { mutableStateOf<VfsRelayServerView?>(null) }
    var deleteServer by remember { mutableStateOf<VfsRelayServerView?>(null) }
    var serverDialogError by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var testMessage by remember { mutableStateOf<String?>(null) }
    var testSucceeded by remember { mutableStateOf(false) }
    var testingServerId by remember { mutableStateOf<String?>(null) }
    var resetServer by remember { mutableStateOf<VfsRelayServerView?>(null) }

    Column(
        modifier =
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .verticalScroll(rememberScrollState())
            .padding(
                top = scaffoldPadding.calculateTopPadding() + 8.dp,
                bottom = scaffoldPadding.calculateBottomPadding() + 32.dp,
                start = 16.dp,
                end = 16.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (serverViews.isEmpty()) {
            Text(
                text = stringResource(R.string.share_files_no_server),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            serverViews.forEach { server ->
                val hasShares = shares.any { it.serverId == server.id && it.status != "revoked" }
                val canSelect = !busy && !active && !selectedHasShares
                var menuOpen by remember(server.id) { mutableStateOf(false) }
                Card(
                    colors =
                    CardDefaults.cardColors(
                        containerColor =
                        if (server.selected) {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        } else {
                            MaterialTheme.colorScheme.surfaceContainer
                        },
                    ),
                    modifier =
                    Modifier
                        .fillMaxWidth()
                        .then(
                            if (canSelect && !server.selected) {
                                Modifier.clickable {
                                    scope.launch {
                                        busy = true
                                        message = null
                                        testMessage = null
                                        VfsRelayRuntime.selectServer(context, server.id)
                                            .onFailure { message = it.message }
                                        busy = false
                                    }
                                }
                            } else {
                                Modifier
                            },
                        ),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = server.name,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (server.selected) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                    Text(
                                        text = stringResource(R.string.vfs_selected),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                            Box {
                                IconButton(onClick = { menuOpen = true }) {
                                    Icon(
                                        imageVector = Icons.Default.MoreVert,
                                        contentDescription = stringResource(R.string.more_options),
                                    )
                                }
                                DropdownMenu(
                                    expanded = menuOpen,
                                    onDismissRequest = { menuOpen = false },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.share_files_edit)) },
                                        enabled = !busy && !active && !hasShares,
                                        onClick = {
                                            menuOpen = false
                                            serverDialogError = null
                                            editServer = server
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.share_files_delete)) },
                                        enabled = !busy,
                                        colors =
                                        MenuDefaults.itemColors(
                                            textColor = MaterialTheme.colorScheme.error,
                                        ),
                                        onClick = {
                                            menuOpen = false
                                            if (active) {
                                                message = context.getString(R.string.vfs_server_busy)
                                            } else if (hasShares) {
                                                message = context.getString(R.string.share_files_server_has_shares)
                                            } else {
                                                deleteServer = server
                                            }
                                        },
                                    )
                                    if (hasShares) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.share_files_reset)) },
                                            enabled = !busy,
                                            colors =
                                            MenuDefaults.itemColors(
                                                textColor = MaterialTheme.colorScheme.error,
                                            ),
                                            onClick = {
                                                menuOpen = false
                                                resetServer = server
                                            },
                                        )
                                    }
                                }
                            }
                        }
                        Text(
                            text = server.endpoint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        testingServerId = server.id
                                        testMessage = null
                                        testSucceeded = false
                                        VfsRelayRuntime.testServer(context, server.id)
                                            .onSuccess {
                                                testSucceeded = true
                                                testMessage = "${server.name}: ${context.getString(R.string.vfs_test_ok)}"
                                            }
                                            .onFailure {
                                                testSucceeded = false
                                                testMessage = "${server.name}: ${it.message}"
                                            }
                                        testingServerId = null
                                    }
                                },
                                enabled = !busy && testingServerId == null,
                                contentPadding = PaddingValues(horizontal = 0.dp),
                            ) {
                                Text(
                                    stringResource(
                                        if (testingServerId == server.id) R.string.vfs_testing else R.string.vfs_test_short,
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }

        if (selectedServerId.isBlank() && serverViews.isNotEmpty()) {
            Text(
                text = stringResource(R.string.share_files_choose_server),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (selectedHasShares) {
            Text(
                text = stringResource(R.string.vfs_switch_requires_revoke),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        testMessage?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color =
                    if (testSucceeded) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
            )
        }
        message?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        Button(onClick = { showAddServer = true }, enabled = !busy) {
            Text(stringResource(R.string.share_files_add_server))
        }
    }

    if (showAddServer) {
        ServerEditorDialog(
            initial = null,
            error = serverDialogError,
            busy = busy,
            onDismiss = {
                showAddServer = false
                serverDialogError = null
            },
            onSubmit = { name, url, token ->
                scope.launch {
                    busy = true
                    serverDialogError = null
                    VfsRelayRuntime.addServer(context, name, url, token)
                        .onSuccess {
                            showAddServer = false
                            message = null
                            testMessage = null
                        }
                        .onFailure { serverDialogError = it.message }
                    busy = false
                }
            },
        )
    }

    editServer?.let { server ->
        ServerEditorDialog(
            initial = server,
            error = serverDialogError,
            busy = busy,
            onDismiss = {
                editServer = null
                serverDialogError = null
            },
            onSubmit = { name, url, token ->
                scope.launch {
                    busy = true
                    serverDialogError = null
                    VfsRelayRuntime.updateServer(context, server.id, name, url, token.takeIf { it.isNotBlank() })
                        .onSuccess {
                            editServer = null
                            message = null
                            testMessage = null
                        }
                        .onFailure { serverDialogError = it.message }
                    busy = false
                }
            },
        )
    }

    deleteServer?.let { server ->
        AlertDialog(
            onDismissRequest = { deleteServer = null },
            title = { Text(stringResource(R.string.share_files_server_delete_title)) },
            text = { Text(stringResource(R.string.share_files_server_delete_help)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteServer = null
                        scope.launch {
                            busy = true
                            message = null
                            testMessage = null
                            VfsRelayRuntime.removeServer(context, server.id)
                                .onFailure { message = it.message }
                            busy = false
                        }
                    },
                ) {
                    Text(stringResource(R.string.share_files_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteServer = null }) {
                    Text(stringResource(R.string.share_files_cancel))
                }
            },
        )
    }

    resetServer?.let { server ->
        AlertDialog(
            onDismissRequest = { resetServer = null },
            title = { Text(stringResource(R.string.share_files_reset_title)) },
            text = { Text(stringResource(R.string.share_files_reset_help)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        resetServer = null
                        scope.launch {
                            busy = true
                            message = null
                            testMessage = null
                            VfsRelayRuntime.resetLinksForServer(context, server.id)
                                .onFailure { message = it.message }
                            busy = false
                        }
                    },
                ) {
                    Text(stringResource(R.string.share_files_reset_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { resetServer = null }) {
                    Text(stringResource(R.string.share_files_cancel))
                }
            },
        )
    }
}

@Composable
private fun ServerEditorDialog(
    initial: VfsRelayServerView?,
    error: String?,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (name: String, url: String, token: String) -> Unit,
) {
    val editing = initial != null
    var name by remember(initial) { mutableStateOf(initial?.name.orEmpty()) }
    var url by remember(initial) { mutableStateOf(initial?.endpoint.orEmpty()) }
    var token by remember(initial) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (editing) R.string.share_files_edit_server_title else R.string.share_files_add_server_title,
                ),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(64) },
                    label = { Text(stringResource(R.string.share_files_server_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it.trim() },
                    label = { Text(stringResource(R.string.share_files_server_url)) },
                    supportingText = {
                        Text(
                            stringResource(R.string.share_files_server_url_help),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it.trim() },
                    label = { Text(stringResource(R.string.share_files_server_token)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (editing) {
                    Text(
                        text = stringResource(R.string.share_files_server_token_keep),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSubmit(name, url, token) },
                enabled = !busy && name.isNotBlank() && url.isNotBlank() && (editing || token.isNotBlank()),
            ) {
                Text(stringResource(if (editing) R.string.share_files_save else R.string.share_files_add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text(stringResource(R.string.share_files_cancel))
            }
        },
    )
}

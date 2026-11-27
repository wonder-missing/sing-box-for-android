package io.nekohasekai.sfa.compose.screen.settings

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import io.nekohasekai.sfa.BuildConfig
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.topbar.LocalScaffoldPadding
import io.nekohasekai.sfa.compose.topbar.OverrideTopBar
import io.nekohasekai.sfa.vfs.relay.RelayScope
import io.nekohasekai.sfa.vfs.relay.VfsPairingUi
import io.nekohasekai.sfa.vfs.relay.VfsRelayRuntime
import io.nekohasekai.sfa.vfs.relay.VfsRelayServerView
import io.nekohasekai.sfa.vfs.relay.VfsRelayShareView
import io.nekohasekai.sfa.vfs.relay.VfsRelayState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

private enum class SharePreset {
    RECOMMENDED,
    EVERYTHING,
    CUSTOMIZE,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareFilesScreen(navController: NavController) {
    OverrideTopBar {
        TopAppBar(
            title = { Text(stringResource(R.string.share_files_title)) },
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
    val supported = BuildConfig.SHARE_KEY_VFS && android.os.Build.VERSION.SDK_INT >= 24
    val activity = context as? Activity
    DisposableEffect(activity) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    if (!supported) {
        Column(
            modifier = Modifier.fillMaxSize().padding(scaffoldPadding).padding(16.dp),
        ) {
            Text(stringResource(R.string.share_files_unsupported), style = MaterialTheme.typography.bodyLarge)
        }
        return
    }

    VfsRelayRuntime.ensure(context)
    val relayState by VfsRelayRuntime.state.collectAsState()
    val relayError = relayState as? VfsRelayState.Error
    val shares by VfsRelayRuntime.shares.collectAsState()
    val serverViews by VfsRelayRuntime.servers.collectAsState()
    val selectedServerId by VfsRelayRuntime.selectedServerId.collectAsState()

    var busy by remember { mutableStateOf(false) }
    var showAddServer by remember { mutableStateOf(false) }
    var editServer by remember { mutableStateOf<VfsRelayServerView?>(null) }
    var deleteServer by remember { mutableStateOf<VfsRelayServerView?>(null) }
    var serverDialogError by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var showCreate by remember { mutableStateOf(false) }
    var preset by remember { mutableStateOf(SharePreset.RECOMMENDED) }
    var selectedScopes by remember { mutableStateOf(RelayScope.recommended()) }
    var selectedUris by remember { mutableStateOf<List<String>>(emptyList()) }
    var label by remember { mutableStateOf("") }
    var pairingShareId by remember { mutableStateOf<String?>(null) }
    var revokeShareId by remember { mutableStateOf<String?>(null) }
    var showReset by remember { mutableStateOf(false) }
    var tick by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(pairingShareId) {
        while (pairingShareId != null) {
            delay(1_000L)
            tick = System.currentTimeMillis()
        }
    }
    LaunchedEffect(tick, pairingShareId, shares) {
        val id = pairingShareId ?: return@LaunchedEffect
        val share = shares.firstOrNull { it.shareId == id }
        if (VfsRelayRuntime.pairingUi(id) == null &&
            (share == null || share.status in setOf("expired", "revoked", "paused", "failed", "pending", "offline"))
        ) {
            pairingShareId = null
        }
    }

    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            uris.forEach { uri ->
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
            }
            selectedUris = (selectedUris + uris.map { it.toString() }).distinct()
        }

    val connected = relayState is VfsRelayState.Connected
    val active = connected || relayState is VfsRelayState.Connecting || relayState is VfsRelayState.Backoff

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
        Text(
            text = stringResource(R.string.share_files_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text(
            text = stringResource(R.string.share_files_portal),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = stringResource(R.string.share_files_portal_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (serverViews.isEmpty()) {
            Text(
                text = stringResource(R.string.share_files_no_server),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Button(onClick = { showAddServer = true }, enabled = !busy) {
                Text(stringResource(R.string.share_files_add_server))
            }
        } else {
            serverViews.forEach { server ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    ListItem(
                        headlineContent = { Text(server.name) },
                        supportingContent = {
                            Text(server.endpoint, style = MaterialTheme.typography.bodySmall)
                        },
                        leadingContent = {
                            RadioButton(
                                selected = server.selected,
                                onClick = {
                                    if (!server.selected) {
                                        scope.launch {
                                            busy = true
                                            message = null
                                            VfsRelayRuntime.selectServer(context, server.id)
                                                .onFailure { message = it.message }
                                            busy = false
                                        }
                                    }
                                },
                                enabled = !active && !busy,
                            )
                        },
                        trailingContent = {
                            Row {
                                TextButton(
                                    onClick = {
                                        serverDialogError = null
                                        editServer = server
                                    },
                                    enabled = !active && !busy,
                                ) {
                                    Text(stringResource(R.string.share_files_edit))
                                }
                                TextButton(
                                    onClick = {
                                        if (active) {
                                            message = context.getString(R.string.share_files_disconnect_first)
                                        } else if (shares.any { it.serverId == server.id && it.status != "revoked" }) {
                                            message = context.getString(R.string.share_files_server_has_shares)
                                        } else {
                                            deleteServer = server
                                        }
                                    },
                                    enabled = !busy,
                                ) {
                                    Text(stringResource(R.string.share_files_delete))
                                }
                            }
                        },
                    )
                }
            }
            OutlinedButton(onClick = { showAddServer = true }, enabled = !busy && !active) {
                Text(stringResource(R.string.share_files_add_server))
            }
            if (selectedServerId.isBlank()) {
                Text(
                    text = stringResource(R.string.share_files_choose_server),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        StatusLine(relayState)
        if (relayError != null && relayError.resetRecommended) {
            OutlinedButton(onClick = { showReset = true }, enabled = !busy) {
                Text(stringResource(R.string.share_files_reset))
            }
        }
        message?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    if (active) {
                        scope.launch {
                            busy = true
                            VfsRelayRuntime.disconnect(context)
                            pairingShareId = null
                            busy = false
                        }
                    } else {
                        scope.launch {
                            busy = true
                            message = null
                            VfsRelayRuntime.connect(context).onFailure { message = it.message }
                            busy = false
                        }
                    }
                },
                enabled = !busy && (active || selectedServerId.isNotBlank()),
            ) {
                Text(
                    stringResource(
                        if (active) R.string.share_files_disconnect else R.string.share_files_connect,
                    ),
                )
            }
            if (active) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            busy = true
                            VfsRelayRuntime.stopSharing(context)
                            pairingShareId = null
                            busy = false
                        }
                    },
                    enabled = !busy,
                ) {
                    Text(stringResource(R.string.share_files_stop))
                }
            }
        }

        if (connected) {
            Text(
                text = stringResource(R.string.share_files_shares),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            if (shares.isEmpty()) {
                Text(
                    text = stringResource(R.string.share_files_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            shares.forEach { share ->
                ShareCard(
                    share = share,
                    onNewCode = {
                        scope.launch {
                            busy = true
                            message = null
                            VfsRelayRuntime.newCode(context, share.shareId)
                                .onSuccess { pairingShareId = share.shareId }
                                .onFailure { message = it.message }
                            busy = false
                        }
                    },
                    onRevoke = { revokeShareId = share.shareId },
                    enabled = !busy,
                )
            }
            if (!showCreate) {
                Button(onClick = { showCreate = true }, enabled = !busy) {
                    Text(stringResource(R.string.share_files_new))
                }
            } else {
                CreateShareCard(
                    label = label,
                    onLabel = { label = it },
                    preset = preset,
                    onPreset = { chosen ->
                        preset = chosen
                        selectedScopes =
                            when (chosen) {
                                SharePreset.RECOMMENDED -> RelayScope.recommended()
                                SharePreset.EVERYTHING -> RelayScope.everything()
                                SharePreset.CUSTOMIZE -> selectedScopes.ifEmpty { RelayScope.recommended() }
                            }
                    },
                    selectedScopes = selectedScopes,
                    onToggleScope = { relayScope, checked ->
                        selectedScopes =
                            if (checked) selectedScopes + relayScope else selectedScopes - relayScope
                    },
                    selectedUris = selectedUris,
                    onPickFiles = { picker.launch(arrayOf("*/*")) },
                    onCancel = { showCreate = false },
                    busy = busy,
                    onCreate = {
                        if (RelayScope.USER_SELECTED in selectedScopes && selectedUris.isEmpty()) {
                            message = "Pick at least one file for the selected-files scope."
                        } else {
                            scope.launch {
                                busy = true
                                message = null
                                VfsRelayRuntime
                                    .createShare(context, label, selectedScopes, selectedUris)
                                    .onSuccess { created ->
                                        showCreate = false
                                        label = ""
                                        selectedUris = emptyList()
                                        VfsRelayRuntime.newCode(context, created.shareId)
                                            .onSuccess { pairingShareId = created.shareId }
                                            .onFailure { message = it.message }
                                    }
                                    .onFailure { message = it.message }
                                busy = false
                            }
                        }
                    },
                )
            }
        }

        pairingShareId?.let { shareId ->
            val pairing = VfsRelayRuntime.pairingUi(shareId)
            PairingCard(
                pairing = pairing,
                now = tick,
                onCopy = { command ->
                    copyToClipboard(context, command)
                    scope.launch {
                        delay(60_000L)
                        clearClipboardIfMatches(context, command)
                    }
                },
            )
        }
    }

    if (showReset) {
        AlertDialog(
            onDismissRequest = { showReset = false },
            title = { Text(stringResource(R.string.share_files_reset_title)) },
            text = { Text(stringResource(R.string.share_files_reset_help)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showReset = false
                        scope.launch {
                            busy = true
                            VfsRelayRuntime.resetShareFilesData(context)
                            pairingShareId = null
                            message = null
                            busy = false
                        }
                    },
                ) {
                    Text(stringResource(R.string.share_files_reset_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showReset = false }) {
                    Text(stringResource(R.string.share_files_cancel))
                }
            },
        )
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

    revokeShareId?.let { shareId ->
        AlertDialog(
            onDismissRequest = { revokeShareId = null },
            title = { Text(stringResource(R.string.share_files_revoke_title)) },
            text = { Text(stringResource(R.string.share_files_revoke_help)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        revokeShareId = null
                        scope.launch {
                            busy = true
                            VfsRelayRuntime.revokeShare(context, shareId)
                            if (pairingShareId == shareId) pairingShareId = null
                            busy = false
                        }
                    },
                ) {
                    Text(stringResource(R.string.share_files_revoke))
                }
            },
            dismissButton = {
                TextButton(onClick = { revokeShareId = null }) {
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

@Composable
private fun StatusLine(state: VfsRelayState) {
    val text =
        when (state) {
            is VfsRelayState.Idle -> stringResource(R.string.share_files_idle)
            is VfsRelayState.Connecting -> stringResource(R.string.share_files_connecting)
            is VfsRelayState.Connected ->
                stringResource(R.string.share_files_connected, state.shares.size)
            is VfsRelayState.Backoff -> stringResource(R.string.share_files_backoff)
            is VfsRelayState.Error -> state.message
        }
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (state is VfsRelayState.Error) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurface
        },
    )
    if (state is VfsRelayState.Error && state.actionable != null) {
        Text(
            text = state.actionable,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ShareCard(
    share: VfsRelayShareView,
    onNewCode: () -> Unit,
    onRevoke: () -> Unit,
    enabled: Boolean,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = share.label.ifBlank { share.shareId.take(8) },
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = shareStatusLabel(share.status),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = share.scopes.map { scopeLabel(it) }.joinToString(", "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            share.error?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (share.status != "revoked") {
                    TextButton(onClick = onNewCode, enabled = enabled) {
                        Text(stringResource(R.string.share_files_new_code))
                    }
                    TextButton(onClick = onRevoke, enabled = enabled) {
                        Text(stringResource(R.string.share_files_revoke))
                    }
                }
            }
        }
    }
}

@Composable
private fun CreateShareCard(
    label: String,
    onLabel: (String) -> Unit,
    preset: SharePreset,
    onPreset: (SharePreset) -> Unit,
    selectedScopes: Set<RelayScope>,
    onToggleScope: (RelayScope, Boolean) -> Unit,
    selectedUris: List<String>,
    onPickFiles: () -> Unit,
    onCancel: () -> Unit,
    onCreate: () -> Unit,
    busy: Boolean,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = label,
                onValueChange = { onLabel(it.take(64)) },
                label = { Text(stringResource(R.string.share_files_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = preset == SharePreset.RECOMMENDED,
                    onClick = { onPreset(SharePreset.RECOMMENDED) },
                    label = { Text(stringResource(R.string.share_files_preset_recommended)) },
                )
                FilterChip(
                    selected = preset == SharePreset.EVERYTHING,
                    onClick = { onPreset(SharePreset.EVERYTHING) },
                    label = { Text(stringResource(R.string.share_files_preset_everything)) },
                )
                FilterChip(
                    selected = preset == SharePreset.CUSTOMIZE,
                    onClick = { onPreset(SharePreset.CUSTOMIZE) },
                    label = { Text(stringResource(R.string.share_files_preset_customize)) },
                )
            }
            Text(
                text =
                    stringResource(
                        when (preset) {
                            SharePreset.RECOMMENDED -> R.string.share_files_preset_recommended_help
                            SharePreset.EVERYTHING -> R.string.share_files_preset_everything_help
                            SharePreset.CUSTOMIZE -> R.string.share_files_customize_help
                        },
                    ),
                style = MaterialTheme.typography.bodySmall,
                color =
                    if (preset == SharePreset.EVERYTHING) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
            if (preset == SharePreset.RECOMMENDED) {
                selectedScopes
                    .sortedBy { it.wire }
                    .forEach { relayScope ->
                        Text(
                            text = scopeLabel(relayScope.wire) + " · " +
                                stringResource(R.string.share_files_warning_amber),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
            }
            if (preset != SharePreset.RECOMMENDED) {
                val scopes = RelayScope.fixed + RelayScope.USER_SELECTED
                scopes.forEach { relayScope ->
                    val checked = relayScope in selectedScopes
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = { onToggleScope(relayScope, it) },
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(scopeLabel(relayScope.wire), style = MaterialTheme.typography.bodyMedium)
                            val warning =
                                when (relayScope.warning) {
                                    io.nekohasekai.sfa.vfs.relay.RelayWarning.AMBER ->
                                        R.string.share_files_warning_amber
                                    io.nekohasekai.sfa.vfs.relay.RelayWarning.RED ->
                                        R.string.share_files_warning_red
                                    io.nekohasekai.sfa.vfs.relay.RelayWarning.PICKER ->
                                        R.string.share_files_warning_picker
                                }
                            Text(
                                text = stringResource(warning),
                                style = MaterialTheme.typography.bodySmall,
                                color =
                                    if (relayScope.warning == io.nekohasekai.sfa.vfs.relay.RelayWarning.AMBER) {
                                        MaterialTheme.colorScheme.tertiary
                                    } else {
                                        MaterialTheme.colorScheme.error
                                    },
                            )
                        }
                    }
                }
                if (RelayScope.USER_SELECTED in selectedScopes) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onPickFiles) {
                            Text(stringResource(R.string.share_files_pick_files))
                        }
                        Text(
                            text = stringResource(R.string.share_files_selected_count, selectedUris.size),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onCreate, enabled = !busy) {
                    Text(stringResource(R.string.share_files_create))
                }
                TextButton(onClick = onCancel, enabled = !busy) {
                    Text(stringResource(R.string.share_files_cancel))
                }
            }
        }
    }
}

@Composable
private fun PairingCard(
    pairing: VfsPairingUi?,
    now: Long,
    onCopy: (String) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = stringResource(R.string.share_files_pairing_title),
                style = MaterialTheme.typography.titleSmall,
            )
            if (pairing == null) {
                Text(
                    text = stringResource(R.string.share_files_pairing_expired),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                val remaining = (pairing.expiresAt - now).coerceAtLeast(0)
                val display = String.format(Locale.US, "%d:%02d", remaining / 60_000, (remaining / 1_000) % 60)
                Text(
                    text = pairing.code.chunked(4).joinToString(" "),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    text = stringResource(R.string.share_files_pairing_help, display),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SelectionContainer {
                    Text(
                        text = pairing.command,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Button(onClick = { onCopy(pairing.command) }) {
                    Text(stringResource(R.string.share_files_copy_command))
                }
                Text(
                    text = stringResource(R.string.share_files_copy_stdin_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun copyToClipboard(
    context: Context,
    text: String,
) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("wonder-vfs pair", text))
}

private fun clearClipboardIfMatches(
    context: Context,
    text: String,
) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    val current = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
    if (current == text) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                clipboard.clearPrimaryClip()
            } else {
                clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
            }
        }
    }
}

@Composable
private fun shareStatusLabel(status: String): String =
    stringResource(
        when (status) {
            "waiting" -> R.string.share_files_status_waiting
            "active" -> R.string.share_files_status_active
            "paired" -> R.string.share_files_status_paired
            "paused" -> R.string.share_files_status_paused
            "expired" -> R.string.share_files_status_expired
            "pending" -> R.string.share_files_status_pending
            "failed" -> R.string.share_files_status_failed
            "revoked" -> R.string.share_files_status_revoked
            "offline" -> R.string.share_files_status_offline
            else -> R.string.share_files_status_pending
        },
    )

@Composable
private fun scopeLabel(wire: String): String =
    stringResource(
        when (wire) {
            "app.data.activity" -> R.string.share_files_scope_activity
            "app.data.groups" -> R.string.share_files_scope_groups
            "app.data.logs" -> R.string.share_files_scope_logs
            "app.data.telemetry" -> R.string.share_files_scope_telemetry
            "app.data.diagnostics" -> R.string.share_files_scope_diagnostics
            "app.files" -> R.string.share_files_scope_app_files
            "app.workspace" -> R.string.share_files_scope_workspace
            "conductor.files" -> R.string.share_files_scope_conductor
            "conductor.trace" -> R.string.share_files_scope_trace
            "conductor.system_prompt" -> R.string.share_files_scope_system_prompt
            "conductor.conversation" -> R.string.share_files_scope_conversation
            "user.selected" -> R.string.share_files_scope_selected
            else -> R.string.share_files_scope_diagnostics
        },
    )

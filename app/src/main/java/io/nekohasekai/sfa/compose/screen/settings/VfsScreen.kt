package io.nekohasekai.sfa.compose.screen.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.navigation.NavController
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.topbar.LocalScaffoldPadding
import io.nekohasekai.sfa.compose.topbar.OverrideTopBar
import io.nekohasekai.sfa.vfs.relay.RelayScope
import io.nekohasekai.sfa.vfs.relay.VfsPairingUi
import io.nekohasekai.sfa.vfs.relay.VfsRelayRuntime
import io.nekohasekai.sfa.vfs.relay.VfsRelayShareView
import io.nekohasekai.sfa.vfs.relay.VfsRelayState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.Locale

private enum class SharePreset {
    RECOMMENDED,
    EVERYTHING,
    CUSTOMIZE,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VfsScreen(navController: NavController) {
    OverrideTopBar {
        TopAppBar(
            title = { Text(stringResource(R.string.vfs_links_title)) },
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
    val relayState by VfsRelayRuntime.state.collectAsState()
    val relayError = relayState as? VfsRelayState.Error
    val shares by VfsRelayRuntime.shares.collectAsState()
    val serverViews by VfsRelayRuntime.servers.collectAsState()
    val selectedServerId by VfsRelayRuntime.selectedServerId.collectAsState()
    val selectedServer = serverViews.firstOrNull { it.selected }

    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var showCreate by remember { mutableStateOf(false) }
    var preset by remember { mutableStateOf(SharePreset.RECOMMENDED) }
    var selectedScopes by remember { mutableStateOf(RelayScope.recommended()) }
    var selectedUris by remember { mutableStateOf<List<String>>(emptyList()) }
    var label by remember { mutableStateOf("") }
    var pairingShareId by remember { mutableStateOf<String?>(null) }
    var revokeShareId by remember { mutableStateOf<String?>(null) }
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
            (share == null || share.status in setOf("expired", "revoked", "paused", "stopped", "failed", "pending", "offline"))
        ) {
            pairingShareId = null
        }
    }
    LaunchedEffect(Unit) {
        VfsRelayRuntime.syncServing(context)
    }
    LaunchedEffect(selectedServerId) {
        if (selectedServerId.isNotBlank()) {
            VfsRelayRuntime.syncServing(context)
        }
    }

    val openPairing: (String) -> Unit = { shareId ->
        scope.launch {
            busy = true
            message = null
            if (VfsRelayRuntime.pairingUi(shareId) != null) {
                pairingShareId = shareId
            } else {
                VfsRelayRuntime.newCode(context, shareId)
                    .onSuccess { pairingShareId = shareId }
                    .onFailure { message = it.message }
            }
            busy = false
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
        if (selectedServer == null) {
            Text(
                text = stringResource(R.string.vfs_no_server),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Button(onClick = { navController.navigate("settings/vfs_servers") }) {
                Text(stringResource(R.string.vfs_configure_server))
            }
            return@Column
        }

        if (relayError != null) {
            Text(
                text = relayError.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            relayError.actionable?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = { navController.navigate("settings/vfs_servers") }) {
                Text(stringResource(R.string.vfs_check_relay_servers))
            }
        }
        message?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        if (shares.isNotEmpty()) {
            shares.forEach { share ->
                val relayName =
                    serverViews.firstOrNull { it.id == share.serverId }?.name
                        ?: selectedServer?.name.orEmpty()
                ShareCard(
                    share = share,
                    relayName = relayName,
                    onPairing = { openPairing(share.shareId) },
                    onToggleStop = {
                        scope.launch {
                            busy = true
                            message = null
                            VfsRelayRuntime.setShareStopped(
                                context,
                                share.shareId,
                                stopped = share.status != "stopped",
                            ).onFailure { message = it.message }
                            busy = false
                        }
                    },
                    onRevoke = { revokeShareId = share.shareId },
                    enabled = !busy,
                )
            }
        }

        if (shares.isNotEmpty() && !showCreate) {
            Button(onClick = { showCreate = true }, enabled = !busy) {
                Text(stringResource(R.string.share_files_new))
            }
        }
        if (shares.isEmpty() || showCreate) {
            Text(
                text = stringResource(R.string.share_files_new),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
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
                    preset = SharePreset.CUSTOMIZE
                    selectedScopes =
                        if (checked) selectedScopes + relayScope else selectedScopes - relayScope
                },
                selectedUris = selectedUris,
                onPickFiles = { picker.launch(arrayOf("*/*")) },
                onCancel =
                    if (shares.isEmpty()) {
                        null
                    } else {
                        { showCreate = false }
                    },
                busy = busy,
                onCreate = {
                    if (RelayScope.USER_SELECTED in selectedScopes && selectedUris.isEmpty()) {
                        message = context.getString(R.string.share_files_pick_files_required)
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
                                    busy = false
                                    scope.launch {
                                        VfsRelayRuntime.newCode(context, created.shareId)
                                            .onSuccess { pairingShareId = created.shareId }
                                            .onFailure { message = it.message }
                                    }
                                }
                                .onFailure {
                                    message = it.message
                                    busy = false
                                }
                        }
                    }
                },
            )
        }

        pairingShareId?.let { shareId ->
            PairingDialog(
                pairing = VfsRelayRuntime.pairingUi(shareId),
                now = tick,
                onCopy = { command ->
                    copyToClipboard(context, command)
                    scope.launch {
                        delay(60_000L)
                        clearClipboardIfMatches(context, command)
                    }
                },
                onDismiss = { pairingShareId = null },
            )
        }
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
private fun ShareCard(
    share: VfsRelayShareView,
    relayName: String,
    onPairing: () -> Unit,
    onToggleStop: () -> Unit,
    onRevoke: () -> Unit,
    enabled: Boolean,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
                text = stringResource(R.string.vfs_share_relay, relayName.ifBlank { "—" }),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = share.scopes.map { scopeLabel(it) }.joinToString(", "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            share.lastUsedAt?.let { lastUsed ->
                Text(
                    text = stringResource(
                        R.string.share_files_last_read,
                        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(lastUsed)),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            share.error?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (share.status != "revoked") {
                    if (!share.stopped) {
                        TextButton(
                            onClick = onPairing,
                            enabled = enabled,
                            contentPadding = PaddingValues(horizontal = 0.dp),
                        ) {
                            Text(stringResource(R.string.share_files_show_pairing))
                        }
                    }
                    TextButton(
                        onClick = onToggleStop,
                        enabled = enabled,
                        contentPadding = PaddingValues(horizontal = 0.dp),
                    ) {
                        Text(
                            stringResource(
                                if (share.stopped) R.string.share_files_start else R.string.share_files_stop,
                            ),
                        )
                    }
                    TextButton(
                        onClick = onRevoke,
                        enabled = enabled,
                        contentPadding = PaddingValues(horizontal = 0.dp),
                        colors =
                        ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        Text(stringResource(R.string.share_files_revoke))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
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
    onCancel: (() -> Unit)?,
    onCreate: () -> Unit,
    busy: Boolean,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = label,
                onValueChange = { onLabel(it.take(64)) },
                label = { Text(stringResource(R.string.share_files_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            val presets = SharePreset.entries
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                presets.forEachIndexed { index, item ->
                    SegmentedButton(
                        selected = preset == item,
                        onClick = { onPreset(item) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = presets.size),
                        modifier = Modifier.weight(1f),
                        icon = {},
                        contentPadding = PaddingValues(horizontal = 4.dp),
                        label = {
                            Text(
                                text =
                                    stringResource(
                                        when (item) {
                                            SharePreset.RECOMMENDED -> R.string.share_files_preset_recommended
                                            SharePreset.EVERYTHING -> R.string.share_files_preset_everything
                                            SharePreset.CUSTOMIZE -> R.string.share_files_preset_customize
                                        },
                                    ),
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                }
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
            Text(
                text = stringResource(R.string.share_files_scope_heading),
                style = MaterialTheme.typography.titleSmall,
            )
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
                                    R.string.share_files_warning_amber_short
                                io.nekohasekai.sfa.vfs.relay.RelayWarning.RED ->
                                    R.string.share_files_warning_red_short
                                io.nekohasekai.sfa.vfs.relay.RelayWarning.PICKER ->
                                    R.string.share_files_warning_picker_short
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
                        text = pluralStringResource(R.plurals.share_files_selected_count, selectedUris.size, selectedUris.size),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (selectedScopes.isEmpty()) {
                Text(
                    text = stringResource(R.string.share_files_scope_required),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onCreate, enabled = !busy && selectedScopes.isNotEmpty()) {
                    Text(stringResource(R.string.share_files_create))
                }
                onCancel?.let { cancel ->
                    TextButton(onClick = cancel, enabled = !busy) {
                        Text(stringResource(R.string.share_files_cancel))
                    }
                }
            }
        }
    }
}

@Composable
private fun PairingDialog(
    pairing: VfsPairingUi?,
    now: Long,
    onCopy: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var copied by remember(pairing?.code) { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Text(
                    text = stringResource(R.string.share_files_pairing_title),
                    style = MaterialTheme.typography.headlineSmall,
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
                    val pairingLine =
                        stringResource(
                            R.string.share_files_pairing_line,
                            pairing.linkId,
                            pairing.code,
                        )
                    SelectionContainer {
                        Text(
                            text = pairingLine,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Button(
                        onClick = {
                            onCopy(pairingLine)
                            copied = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            stringResource(
                                if (copied) R.string.share_files_copied_time else R.string.share_files_copy_pairing_time,
                                display,
                            ),
                        )
                    }
                }
            }
        }
    }
}

private fun copyToClipboard(
    context: Context,
    text: String,
) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("vfsc pair", text))
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
            "stopped" -> R.string.share_files_status_stopped
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
            "user.selected" -> R.string.share_files_scope_selected
            else -> R.string.share_files_scope_diagnostics
        },
    )

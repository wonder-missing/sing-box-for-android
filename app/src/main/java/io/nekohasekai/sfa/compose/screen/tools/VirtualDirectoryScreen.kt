package io.nekohasekai.sfa.compose.screen.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.agent.mcp.McpTokenCipherFactory
import io.nekohasekai.sfa.compose.topbar.LocalScaffoldPadding
import io.nekohasekai.sfa.compose.topbar.OverrideTopBar
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.vfs.VirtualDirectoryActivity
import io.nekohasekai.sfa.vfs.VirtualDirectoryRuntime
import io.nekohasekai.sfa.vfs.VirtualDirectoryState
import io.nekohasekai.sfa.vfs.normalizeVirtualDirectoryUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VirtualDirectoryScreen(navController: NavController) {
    OverrideTopBar {
        TopAppBar(
            title = { Text(stringResource(R.string.virtual_directory_title)) },
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

    val scope = rememberCoroutineScope()
    val scaffoldPadding = LocalScaffoldPadding.current
    var enabled by remember { mutableStateOf(Settings.virtualDirectoryEnabled) }
    var endpoint by remember { mutableStateOf(Settings.virtualDirectoryUrl) }
    var token by remember { mutableStateOf("") }
    var revealToken by remember { mutableStateOf(false) }
    var tokenSaved by remember { mutableStateOf(Settings.virtualDirectoryTokenBlob.isNotBlank()) }
    var clearToken by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var urlError by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var feedbackIsError by remember { mutableStateOf(false) }
    val runtimeState by VirtualDirectoryRuntime.state.collectAsState()
    val activity by VirtualDirectoryRuntime.activity.collectAsState()

    val invalidUrlText = stringResource(R.string.virtual_directory_error_url)
    val savedText = stringResource(R.string.virtual_directory_saved)
    val saveFailedText = stringResource(R.string.virtual_directory_error_save)

    fun saveConfig(requestedEnabled: Boolean) {
        if (busy) return
        val candidate = endpoint.trim()
        val normalized = normalizeVirtualDirectoryUrl(candidate)
        if (requestedEnabled && normalized == null) {
            urlError = true
            feedbackIsError = true
            feedback = invalidUrlText
            return
        }
        urlError = false
        val tokenToSave = token.trim()
        val removeToken = clearToken
        scope.launch {
            busy = true
            val result =
                withContext(Dispatchers.IO) {
                    runCatching {
                        val tokenBlob =
                            when {
                                removeToken -> ""
                                tokenToSave.isNotBlank() ->
                                    McpTokenCipherFactory.create().encrypt(
                                        VirtualDirectoryRuntime.TOKEN_ID,
                                        tokenToSave,
                                    )

                                else -> null
                            }
                        Settings.virtualDirectoryUrl = normalized ?: candidate
                        Settings.virtualDirectoryEnabled = requestedEnabled
                        tokenBlob?.let { Settings.virtualDirectoryTokenBlob = it }
                        VirtualDirectoryRuntime.reload()
                    }
                }
            busy = false
            result
                .onSuccess {
                    enabled = requestedEnabled
                    if (removeToken) {
                        tokenSaved = false
                        clearToken = false
                    }
                    if (tokenToSave.isNotBlank()) {
                        tokenSaved = true
                        token = ""
                        revealToken = false
                    }
                    feedbackIsError = false
                    feedback = savedText
                }.onFailure {
                    feedbackIsError = true
                    feedback = saveFailedText
                }
        }
    }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .verticalScroll(rememberScrollState())
                .padding(
                    top = scaffoldPadding.calculateTopPadding() + 8.dp,
                    bottom = scaffoldPadding.calculateBottomPadding() + 24.dp,
                    start = 16.dp,
                    end = 16.dp,
                ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.virtual_directory_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.virtual_directory_enable),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = enabled,
                        onCheckedChange = { requested -> saveConfig(requested) },
                        enabled = !busy,
                    )
                }
                OutlinedTextField(
                    value = endpoint,
                    onValueChange = {
                        endpoint = it
                        urlError = false
                    },
                    label = { Text(stringResource(R.string.virtual_directory_endpoint)) },
                    placeholder = { Text(stringResource(R.string.virtual_directory_endpoint_placeholder)) },
                    singleLine = true,
                    enabled = !busy,
                    isError = urlError,
                    supportingText = {
                        Text(
                            if (urlError) {
                                invalidUrlText
                            } else {
                                stringResource(R.string.virtual_directory_endpoint_help)
                            },
                        )
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text(stringResource(R.string.virtual_directory_token)) },
                    singleLine = true,
                    enabled = !busy,
                    visualTransformation =
                        if (revealToken) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { revealToken = !revealToken }) {
                            Icon(
                                imageVector =
                                    if (revealToken) {
                                        Icons.Filled.VisibilityOff
                                    } else {
                                        Icons.Filled.Visibility
                                    },
                                contentDescription =
                                    stringResource(
                                        if (revealToken) {
                                            R.string.virtual_directory_token_hide
                                        } else {
                                            R.string.virtual_directory_token_show
                                        },
                                    ),
                            )
                        }
                    },
                    supportingText = {
                        Text(
                            when {
                                clearToken -> stringResource(R.string.virtual_directory_token_will_clear)
                                tokenSaved -> stringResource(R.string.virtual_directory_token_saved)
                                else -> stringResource(R.string.virtual_directory_token_help)
                            },
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (tokenSaved && !clearToken) {
                    TextButton(
                        onClick = {
                            clearToken = true
                            token = ""
                        },
                        enabled = !busy,
                    ) {
                        Text(stringResource(R.string.virtual_directory_token_remove))
                    }
                } else if (clearToken) {
                    TextButton(
                        onClick = { clearToken = false },
                        enabled = !busy,
                    ) {
                        Text(stringResource(R.string.virtual_directory_token_undo_remove))
                    }
                }
                Button(
                    onClick = { saveConfig(enabled) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.virtual_directory_save))
                }
                feedback?.let { text ->
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodySmall,
                        color =
                            if (feedbackIsError) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                    )
                }
            }
        }

        StatusCard(state = runtimeState, activity = activity)

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.virtual_directory_scope),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(R.string.virtual_directory_scope_value),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StatusCard(
    state: VirtualDirectoryState,
    activity: VirtualDirectoryActivity,
) {
    val connected = state as? VirtualDirectoryState.Connected
    val sinceText =
        remember(connected?.since) {
            connected?.let { formatVirtualDirectoryTime(it.since) }
        }
    val lastRequest =
        remember(activity.lastKind, activity.lastPath) {
            val kind = activity.lastKind
            val path = activity.lastPath
            when {
                kind == null || path == null -> null
                path.isEmpty() -> "$kind /"
                else -> "$kind $path"
            }
        }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.virtual_directory_status),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            VirtualDirectoryStatusLabel(state = state)
            when (state) {
                is VirtualDirectoryState.Connected -> {
                    StatusRow(stringResource(R.string.virtual_directory_server), state.server)
                    sinceText?.let { StatusRow(stringResource(R.string.virtual_directory_since), it) }
                }

                is VirtualDirectoryState.Error -> {
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    if (state.retryInMs != null) {
                        Text(
                            text = stringResource(R.string.virtual_directory_retry_auto),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                else -> Unit
            }
            StatusRow(
                stringResource(R.string.virtual_directory_last_request),
                lastRequest ?: stringResource(R.string.virtual_directory_never),
            )
            StatusRow(stringResource(R.string.virtual_directory_requests), activity.requestCount.toString())
            StatusRow(
                stringResource(R.string.virtual_directory_bytes_read),
                formatByteCount(activity.bytesRead),
            )
            activity.lastError?.let { error ->
                StatusRow(stringResource(R.string.virtual_directory_last_error), error)
            }
        }
    }
}

@Composable
private fun StatusRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
internal fun VirtualDirectoryStatusLabel(
    state: VirtualDirectoryState,
    modifier: Modifier = Modifier,
) {
    val color = virtualDirectoryStatusColor(state)
    val text =
        when (state) {
            is VirtualDirectoryState.Connected -> stringResource(R.string.virtual_directory_summary_connected)
            is VirtualDirectoryState.Connecting -> stringResource(R.string.virtual_directory_summary_connecting)
            is VirtualDirectoryState.Error -> stringResource(R.string.virtual_directory_summary_error)
            VirtualDirectoryState.Disabled -> stringResource(R.string.virtual_directory_summary_off)
        }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(color),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = color,
        )
    }
}

@Composable
private fun virtualDirectoryStatusColor(state: VirtualDirectoryState): Color =
    when (state) {
        is VirtualDirectoryState.Connected -> MaterialTheme.colorScheme.primary
        is VirtualDirectoryState.Connecting -> MaterialTheme.colorScheme.tertiary
        is VirtualDirectoryState.Error -> MaterialTheme.colorScheme.error
        VirtualDirectoryState.Disabled -> MaterialTheme.colorScheme.onSurfaceVariant
    }

private fun formatVirtualDirectoryTime(timestamp: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))

private fun formatByteCount(bytes: Long): String =
    when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KiB", bytes / 1024.0)
        else -> String.format(Locale.US, "%.1f MiB", bytes / (1024.0 * 1024.0))
    }

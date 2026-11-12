package io.nekohasekai.sfa.compose.screen.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.agent.mcp.McpAddResult
import io.nekohasekai.sfa.agent.mcp.McpConfigError
import io.nekohasekai.sfa.agent.mcp.McpPhase
import io.nekohasekai.sfa.agent.mcp.McpRuntimes
import io.nekohasekai.sfa.agent.mcp.McpServerView
import io.nekohasekai.sfa.compose.topbar.LocalScaffoldPadding
import io.nekohasekai.sfa.compose.topbar.OverrideTopBar
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpServersScreen(navController: NavController) {
    OverrideTopBar {
        TopAppBar(
            title = { Text(stringResource(R.string.mcp_servers_title)) },
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
    val runtime = remember { McpRuntimes.get(context.applicationContext) }
    val servers by runtime.views.collectAsState()
    val scope = rememberCoroutineScope()
    val scaffoldPadding = LocalScaffoldPadding.current
    var showAdd by remember { mutableStateOf(false) }
    var pendingRemove by remember { mutableStateOf<McpServerView?>(null) }

    LaunchedEffect(runtime) {
        runtime.refreshIfStale()
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
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.mcp_servers_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (servers.isEmpty()) {
            Text(
                text = stringResource(R.string.mcp_servers_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        servers.forEach { server ->
            ServerCard(
                server = server,
                onEnabled = { enabled ->
                    scope.launch { runtime.setEnabled(server.id, enabled) }
                },
                onRefresh = { scope.launch { runtime.refresh(server.id) } },
                onRemove = { pendingRemove = server },
            )
        }
        Button(
            onClick = { showAdd = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.mcp_servers_add))
        }
    }

    if (showAdd) {
        AddServerDialog(
            onDismiss = { showAdd = false },
            onAdd = { name, url, token, onResult ->
                scope.launch {
                    onResult(runtime.add(name, url, token))
                }
            },
        )
    }
    pendingRemove?.let { server ->
        AlertDialog(
            onDismissRequest = { pendingRemove = null },
            title = { Text(stringResource(R.string.mcp_servers_remove_title)) },
            text = { Text(stringResource(R.string.mcp_servers_remove_body, server.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingRemove = null
                        scope.launch { runtime.remove(server.id) }
                    },
                ) {
                    Text(stringResource(R.string.mcp_servers_remove))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemove = null }) {
                    Text(stringResource(R.string.mcp_servers_cancel))
                }
            },
        )
    }
}

@Composable
private fun ServerCard(
    server: McpServerView,
    onEnabled: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onRemove: () -> Unit,
) {
    val connecting = server.phase == McpPhase.CONNECTING
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = server.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = server.url,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Switch(
                    checked = server.enabled || connecting,
                    onCheckedChange = onEnabled,
                    enabled = !connecting,
                )
            }
            Text(
                text = statusText(server),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!server.error.isNullOrBlank()) {
                Text(
                    text = server.error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (server.hasToken) {
                Text(
                    text = stringResource(R.string.mcp_servers_token_saved),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = onRefresh,
                    enabled = server.enabled && !connecting,
                ) {
                    Text(stringResource(R.string.mcp_servers_refresh))
                }
                TextButton(onClick = onRemove) {
                    Text(stringResource(R.string.mcp_servers_remove))
                }
            }
        }
    }
}

@Composable
private fun statusText(server: McpServerView): String {
    if (server.phase == McpPhase.CONNECTING) return stringResource(R.string.mcp_servers_status_connecting)
    if (server.toolCount > 0) return stringResource(R.string.mcp_servers_status_tools, server.toolCount)
    if (server.enabled && server.fetched) return stringResource(R.string.mcp_servers_status_empty)
    if (server.enabled) return stringResource(R.string.mcp_servers_status_enabled)
    return stringResource(R.string.mcp_servers_status_off)
}

@Composable
private fun AddServerDialog(
    onDismiss: () -> Unit,
    onAdd: (String, String, String, (McpAddResult) -> Unit) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var reveal by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var errorCode by remember { mutableStateOf<McpConfigError?>(null) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.mcp_servers_add)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !busy,
                    label = { Text(stringResource(R.string.mcp_servers_name)) },
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !busy,
                    label = { Text(stringResource(R.string.mcp_servers_url)) },
                    placeholder = { Text("https://host/mcp") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !busy,
                    label = { Text(stringResource(R.string.mcp_servers_token)) },
                    placeholder = { Text(stringResource(R.string.mcp_servers_token_help)) },
                    visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { reveal = !reveal }) {
                            Icon(
                                imageVector = if (reveal) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = null,
                            )
                        }
                    },
                )
                errorCode?.let { code ->
                    Text(
                        text = configErrorText(code),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    busy = true
                    errorCode = null
                    onAdd(name, url, token) { result ->
                        busy = false
                        when (result) {
                            McpAddResult.Saved -> onDismiss()
                            is McpAddResult.Invalid -> errorCode = result.error
                        }
                    }
                },
            ) {
                Text(
                    stringResource(
                        if (busy) R.string.mcp_servers_status_connecting else R.string.mcp_servers_save,
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text(stringResource(R.string.mcp_servers_cancel))
            }
        },
    )
}

@Composable
private fun configErrorText(error: McpConfigError): String =
    when (error) {
        McpConfigError.NAME_BLANK -> stringResource(R.string.mcp_error_name)
        McpConfigError.NAME_LENGTH -> stringResource(R.string.mcp_error_name_length)
        McpConfigError.URL -> stringResource(R.string.mcp_error_url)
        McpConfigError.URL_USERINFO -> stringResource(R.string.mcp_error_url_userinfo)
        McpConfigError.URL_HOST -> stringResource(R.string.mcp_error_url_host)
        McpConfigError.URL_LENGTH -> stringResource(R.string.mcp_error_url_length)
        McpConfigError.URL_QUERY_SECRET -> stringResource(R.string.mcp_error_url_query)
        McpConfigError.TOKEN -> stringResource(R.string.mcp_error_token)
        McpConfigError.LIMIT -> stringResource(R.string.mcp_error_limit)
    }

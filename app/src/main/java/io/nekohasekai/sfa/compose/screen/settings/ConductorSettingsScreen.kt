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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.agent.AgentApiKeyProviderFactory
import io.nekohasekai.sfa.agent.AgentModelException
import io.nekohasekai.sfa.agent.DeepSeekModelClient
import io.nekohasekai.sfa.compose.topbar.LocalScaffoldPadding
import io.nekohasekai.sfa.compose.topbar.OverrideTopBar
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConductorSettingsScreen(navController: NavController) {
    OverrideTopBar {
        TopAppBar(
            title = { Text(stringResource(R.string.conductor_settings_title)) },
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
    val provider = remember { AgentApiKeyProviderFactory.create(context) }
    val client = remember { DeepSeekModelClient() }
    val scaffoldPadding = LocalScaffoldPadding.current

    var apiKey by remember { mutableStateOf("") }
    var reveal by remember { mutableStateOf(false) }
    var configuredHint by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(provider) {
        configuredHint = provider.apiKey()?.takeLast(4)
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
        if (!provider.supported) {
            Text(
                text = stringResource(R.string.conductor_agent_unsupported),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        } else {

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(R.string.conductor_api_key_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text =
                        configuredHint?.let {
                            stringResource(R.string.conductor_api_key_saved, it)
                        } ?: stringResource(R.string.conductor_api_key_not_configured),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.conductor_api_key_title)) },
                    placeholder = { Text(stringResource(R.string.conductor_api_key_hint)) },
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = {
                            val key = apiKey.trim()
                            if (key.isEmpty() || busy) return@Button
                            busy = true
                            scope.launch {
                                try {
                                    provider.save(key)
                                    apiKey = ""
                                    configuredHint = provider.apiKey()?.takeLast(4)
                                    status = null
                                } catch (e: Exception) {
                                    status = e.message ?: "Could not save the key."
                                } finally {
                                    busy = false
                                }
                            }
                        },
                        enabled = apiKey.isNotBlank() && !busy,
                    ) {
                        Text(stringResource(R.string.conductor_api_key_save))
                    }
                    FilledTonalButton(
                        onClick = {
                            if (busy) return@FilledTonalButton
                            busy = true
                            status = null
                            scope.launch {
                                val key = apiKey.trim().ifBlank { provider.apiKey().orEmpty() }
                                if (key.isBlank()) {
                                    status = context.getString(R.string.conductor_api_key_not_configured)
                                    busy = false
                                    return@launch
                                }
                                status =
                                    runCatching { client.testConnection(key) }
                                        .fold(
                                            onSuccess = { it },
                                            onFailure = { it.message ?: "Connection failed." },
                                        )
                                busy = false
                            }
                        },
                        enabled = !busy,
                    ) {
                        Text(
                            stringResource(
                                if (busy) R.string.conductor_api_key_testing else R.string.conductor_api_key_test,
                            ),
                        )
                    }
                    TextButton(
                        onClick = {
                            if (busy) return@TextButton
                            busy = true
                            scope.launch {
                                provider.clear()
                                apiKey = ""
                                configuredHint = null
                                status = context.getString(R.string.conductor_api_key_cleared)
                                busy = false
                            }
                        },
                        enabled = !busy,
                    ) {
                        Text(stringResource(R.string.conductor_api_key_clear))
                    }
                }
                status?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Text(
            text = stringResource(R.string.conductor_agent_privacy),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        }
    }
}

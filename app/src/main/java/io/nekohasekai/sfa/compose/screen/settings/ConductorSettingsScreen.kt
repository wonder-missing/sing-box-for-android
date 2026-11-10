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
import androidx.compose.material3.Switch
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
import androidx.compose.ui.Alignment
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
import io.nekohasekai.sfa.database.Settings
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
    val scaffoldPadding = LocalScaffoldPadding.current

    var apiKey by remember { mutableStateOf("") }
    var reveal by remember { mutableStateOf(false) }
    var configuredHint by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var model by remember { mutableStateOf(Settings.conductorModel) }
    var savedModel by remember { mutableStateOf(Settings.conductorModel) }
    var toolRouting by remember { mutableStateOf(Settings.conductorToolRouting) }

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
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.conductor_model_title)) },
                    placeholder = { Text(stringResource(R.string.conductor_model_hint)) },
                    supportingText = { Text(stringResource(R.string.conductor_model_help)) },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.conductor_tool_routing_title),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = stringResource(R.string.conductor_tool_routing_help),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = toolRouting,
                        onCheckedChange = {
                            toolRouting = it
                            Settings.conductorToolRouting = it
                        },
                    )
                }
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            if (busy) return@Button
                            val key = apiKey.trim()
                            val nextModel = model.trim().ifBlank { DeepSeekModelClient.DEFAULT_MODEL }
                            if (key.isEmpty() && nextModel == savedModel) return@Button
                            busy = true
                            scope.launch {
                                try {
                                    if (key.isNotEmpty()) provider.save(key)
                                    Settings.conductorModel = nextModel
                                    model = nextModel
                                    savedModel = nextModel
                                    apiKey = ""
                                    configuredHint = provider.apiKey()?.takeLast(4)
                                    status = null
                                } catch (e: Exception) {
                                    status = e.message ?: "Could not save the settings."
                                } finally {
                                    busy = false
                                }
                            }
                        },
                        enabled = (apiKey.isNotBlank() || model.trim().ifBlank { DeepSeekModelClient.DEFAULT_MODEL } != savedModel) && !busy,
                    ) {
                        Text(stringResource(R.string.conductor_api_key_save))
                    }
                    FilledTonalButton(
                        modifier = Modifier.fillMaxWidth(),
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
                                val testModel = model.trim().ifBlank { DeepSeekModelClient.DEFAULT_MODEL }
                                status =
                                    runCatching {
                                        DeepSeekModelClient(modelProvider = { testModel }).testConnection(key)
                                    }.fold(
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
                        modifier = Modifier.fillMaxWidth(),
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

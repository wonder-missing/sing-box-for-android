package io.nekohasekai.sfa.compose.screen.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
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
import io.nekohasekai.sfa.agent.ModelEndpoint
import io.nekohasekai.sfa.agent.ModelProviderSettings
import io.nekohasekai.sfa.agent.ModelProviders
import io.nekohasekai.sfa.agent.ReasoningEffort
import io.nekohasekai.sfa.agent.OpenAiCompatibleModelClient
import io.nekohasekai.sfa.compat.menuAnchorCompat
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
    val scaffoldPadding = LocalScaffoldPadding.current

    var selectedProviderId by remember {
        mutableStateOf(ModelProviders.byId(Settings.conductorProvider).id)
    }
    val preset = remember(selectedProviderId) { ModelProviders.byId(selectedProviderId) }
    val keyStore =
        remember(selectedProviderId) {
            AgentApiKeyProviderFactory.create(context, selectedProviderId)
        }

    var apiKey by remember { mutableStateOf("") }
    var reveal by remember { mutableStateOf(false) }
    var configuredHint by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var providerMenuExpanded by remember { mutableStateOf(false) }
    var model by remember { mutableStateOf(ModelProviderSettings.modelFor(selectedProviderId)) }
    var savedModel by remember { mutableStateOf(model) }
    var baseUrl by remember { mutableStateOf(ModelProviderSettings.baseUrlFor(selectedProviderId)) }
    var savedBaseUrl by remember { mutableStateOf(baseUrl) }
    var toolRouting by remember { mutableStateOf(Settings.conductorToolRouting) }
    var reasoningEffort by remember {
        mutableStateOf(ReasoningEffort.normalize(Settings.conductorReasoningEffort))
    }
    var effortMenuExpanded by remember { mutableStateOf(false) }
    val effortLabels =
        mapOf(
            ReasoningEffort.DEFAULT to stringResource(R.string.conductor_reasoning_effort_default),
            ReasoningEffort.LOW to stringResource(R.string.conductor_reasoning_effort_low),
            ReasoningEffort.MEDIUM to stringResource(R.string.conductor_reasoning_effort_medium),
            ReasoningEffort.HIGH to stringResource(R.string.conductor_reasoning_effort_high),
        )

    LaunchedEffect(keyStore) {
        configuredHint = keyStore.apiKey()?.takeLast(4)
    }

    val customBaseUrl = preset.customBaseUrl
    val effectiveBaseUrl = if (customBaseUrl) baseUrl.trim() else preset.baseUrl
    val effectiveModel = model.trim().ifBlank { preset.defaultModel }
    val formValid = effectiveBaseUrl.isNotBlank() && effectiveModel.isNotBlank()
    val baseChanged = customBaseUrl && baseUrl.trim() != savedBaseUrl
    val changed = apiKey.isNotBlank() || effectiveModel != savedModel || baseChanged

    fun selectProvider(id: String) {
        val next = ModelProviders.byId(id)
        if (next.id == selectedProviderId) {
            providerMenuExpanded = false
            return
        }
        ModelProviderSettings.select(next.id)
        selectedProviderId = next.id
        apiKey = ""
        reveal = false
        status = null
        configuredHint = null
        model = ModelProviderSettings.modelFor(next.id)
        savedModel = model
        baseUrl = ModelProviderSettings.baseUrlFor(next.id)
        savedBaseUrl = baseUrl
        providerMenuExpanded = false
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
        if (!keyStore.supported) {
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
                        text = stringResource(R.string.conductor_provider_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )

                    ExposedDropdownMenuBox(
                        expanded = providerMenuExpanded,
                        onExpandedChange = {
                            if (!busy) providerMenuExpanded = it
                        },
                    ) {
                        OutlinedTextField(
                            value = preset.label,
                            onValueChange = {},
                            modifier =
                            Modifier
                                .then(menuAnchorCompat(!busy))
                                .fillMaxWidth(),
                            readOnly = true,
                            enabled = !busy,
                            label = { Text(stringResource(R.string.conductor_provider_title)) },
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = providerMenuExpanded)
                            },
                        )
                        ExposedDropdownMenu(
                            expanded = providerMenuExpanded,
                            onDismissRequest = { providerMenuExpanded = false },
                        ) {
                            ModelProviders.all.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option.label) },
                                    onClick = { selectProvider(option.id) },
                                )
                            }
                        }
                    }
                    Text(
                        text = stringResource(R.string.conductor_provider_help),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    OutlinedTextField(
                        value = if (customBaseUrl) baseUrl else preset.baseUrl,
                        onValueChange = { baseUrl = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        readOnly = !customBaseUrl,
                        enabled = !busy,
                        label = { Text(stringResource(R.string.conductor_base_url_title)) },
                        placeholder = { Text(stringResource(R.string.conductor_base_url_hint)) },
                        supportingText = {
                            Text(
                                if (customBaseUrl) {
                                    stringResource(R.string.conductor_base_url_help_custom)
                                } else {
                                    stringResource(R.string.conductor_base_url_help_fixed, preset.label)
                                },
                            )
                        },
                    )

                    OutlinedTextField(
                        value = model,
                        onValueChange = { model = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !busy,
                        label = { Text(stringResource(R.string.conductor_model_title)) },
                        placeholder = { Text(preset.defaultModel.ifBlank { "model ID" }) },
                        supportingText = { Text(stringResource(R.string.conductor_model_help)) },
                    )

                    ExposedDropdownMenuBox(
                        expanded = effortMenuExpanded,
                        onExpandedChange = {
                            if (!busy) effortMenuExpanded = it
                        },
                    ) {
                        OutlinedTextField(
                            value = effortLabels[reasoningEffort] ?: effortLabels.getValue(ReasoningEffort.DEFAULT),
                            onValueChange = {},
                            modifier =
                            Modifier
                                .then(menuAnchorCompat(!busy))
                                .fillMaxWidth(),
                            readOnly = true,
                            enabled = !busy,
                            label = { Text(stringResource(R.string.conductor_reasoning_effort_title)) },
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = effortMenuExpanded)
                            },
                        )
                        ExposedDropdownMenu(
                            expanded = effortMenuExpanded,
                            onDismissRequest = { effortMenuExpanded = false },
                        ) {
                            ReasoningEffort.options.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(effortLabels.getValue(option)) },
                                    onClick = {
                                        reasoningEffort = option
                                        Settings.conductorReasoningEffort = option
                                        effortMenuExpanded = false
                                    },
                                )
                            }
                        }
                    }
                    Text(
                        text = stringResource(R.string.conductor_reasoning_effort_help),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                        enabled = !busy,
                        label = { Text(stringResource(R.string.conductor_api_key_title)) },
                        placeholder = { Text(preset.keyHint) },
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

                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 48.dp)
                                .clickable { navController.navigate("settings/conductor/mcp") },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.mcp_servers_entry),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = stringResource(R.string.mcp_servers_entry_help),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
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
                                if (!formValid) {
                                    status = context.getString(R.string.conductor_provider_missing_fields)
                                    return@Button
                                }
                                busy = true
                                scope.launch {
                                    try {
                                        if (apiKey.isNotBlank()) keyStore.save(apiKey.trim())
                                        ModelProviderSettings.setModel(selectedProviderId, effectiveModel)
                                        if (customBaseUrl) {
                                            ModelProviderSettings.setBaseUrl(selectedProviderId, effectiveBaseUrl)
                                        }
                                        model = effectiveModel
                                        savedModel = effectiveModel
                                        if (customBaseUrl) {
                                            baseUrl = effectiveBaseUrl
                                            savedBaseUrl = effectiveBaseUrl
                                        }
                                        apiKey = ""
                                        configuredHint = keyStore.apiKey()?.takeLast(4)
                                        status = null
                                    } catch (e: Exception) {
                                        status = e.message ?: "Could not save the settings."
                                    } finally {
                                        busy = false
                                    }
                                }
                            },
                            enabled = !busy && formValid && changed,
                        ) {
                            Text(stringResource(R.string.conductor_api_key_save))
                        }
                        FilledTonalButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                if (busy) return@FilledTonalButton
                                if (!formValid) {
                                    status = context.getString(R.string.conductor_provider_missing_fields)
                                    return@FilledTonalButton
                                }
                                val testEndpoint = ModelEndpoint(
                                    providerId = preset.id,
                                    providerLabel = preset.label,
                                    baseUrl = effectiveBaseUrl,
                                    model = effectiveModel,
                                )
                                busy = true
                                status = null
                                scope.launch {
                                    val key = apiKey.trim().ifBlank { keyStore.apiKey().orEmpty() }
                                    if (key.isBlank()) {
                                        status = context.getString(R.string.conductor_api_key_not_configured)
                                        busy = false
                                        return@launch
                                    }
                                    status =
                                        runCatching {
                                            OpenAiCompatibleModelClient { testEndpoint }.testConnection(key)
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
                                    keyStore.clear()
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

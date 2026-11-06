package io.nekohasekai.sfa.telemetry

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.topbar.LocalScaffoldPadding
import io.nekohasekai.sfa.compose.topbar.OverrideTopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TelemetrySettingsScreen(navController: NavController) {
    OverrideTopBar {
        TopAppBar(
            title = { Text(stringResource(R.string.telemetry_title)) },
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
    var enabled by rememberSaveable { mutableStateOf(TelemetryPreferences.isEnabled(context)) }
    var endpoint by rememberSaveable { mutableStateOf(TelemetryPreferences.endpointUrl(context)) }
    var token by rememberSaveable { mutableStateOf(TelemetryPreferences.ingestToken(context)) }
    var tokenVisible by rememberSaveable { mutableStateOf(false) }
    var saved by rememberSaveable { mutableStateOf(false) }
    var helpOpen by rememberSaveable { mutableStateOf(false) }
    var testing by rememberSaveable { mutableStateOf(false) }
    var testResult by rememberSaveable { mutableStateOf<String?>(null) }
    var uploading by remember { mutableStateOf(false) }
    var uploadResult by rememberSaveable { mutableStateOf<String?>(null) }
    var lastUploadAt by remember { mutableStateOf(TelemetryPreferences.lastUploadOkAt(context)) }
    var lastTestAt by remember { mutableStateOf(TelemetryPreferences.lastProbeOkAt(context)) }
    val scaffoldPadding = LocalScaffoldPadding.current

    LaunchedEffect(Unit) {
        while (true) {
            lastUploadAt = TelemetryPreferences.lastUploadOkAt(context)
            lastTestAt = TelemetryPreferences.lastProbeOkAt(context)
            delay(2_000)
        }
    }

    fun persist() {
        TelemetryPreferences.save(context, enabled, endpoint, token)
        endpoint = TelemetryPreferences.endpointUrl(context)
        token = TelemetryPreferences.ingestToken(context)
        saved = true
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .verticalScroll(rememberScrollState())
            .padding(
                top = scaffoldPadding.calculateTopPadding() + 8.dp,
                bottom = scaffoldPadding.calculateBottomPadding() + 8.dp,
            ),
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
        ) {
            ListItem(
                headlineContent = {
                    Text(
                        stringResource(R.string.telemetry_title),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                },
                supportingContent = {
                    Text(stringResource(R.string.telemetry_enabled_summary))
                },
                trailingContent = {
                    Switch(
                        checked = enabled,
                        onCheckedChange = { checked ->
                            enabled = checked
                            saved = false
                        },
                    )
                },
                modifier = Modifier.clip(RoundedCornerShape(12.dp)),
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                OutlinedTextField(
                    value = endpoint,
                    onValueChange = {
                        endpoint = it
                        saved = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.telemetry_endpoint)) },
                    placeholder = { Text(stringResource(R.string.telemetry_endpoint_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = token,
                    onValueChange = {
                        token = it
                        saved = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.telemetry_token)) },
                    placeholder = { Text(stringResource(R.string.telemetry_token_hint)) },
                    singleLine = true,
                    visualTransformation = if (tokenVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { tokenVisible = !tokenVisible }) {
                            Icon(
                                imageVector = if (tokenVisible) {
                                    Icons.Filled.VisibilityOff
                                } else {
                                    Icons.Filled.Visibility
                                },
                                contentDescription = stringResource(
                                    if (tokenVisible) {
                                        R.string.telemetry_hide_token
                                    } else {
                                        R.string.telemetry_show_token
                                    },
                                ),
                            )
                        }
                    },
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = { persist() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.telemetry_save))
                }
                if (saved) {
                    Text(
                        text = stringResource(R.string.telemetry_saved),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        persist()
                        testing = true
                        testResult = null
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                TelemetryRuntime.probe(context)
                            }
                            testResult = result
                            lastTestAt = TelemetryPreferences.lastProbeOkAt(context)
                            testing = false
                        }
                    },
                    enabled = !testing,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(
                            if (testing) R.string.telemetry_testing else R.string.telemetry_test,
                        ),
                    )
                }
                if (!testResult.isNullOrBlank()) {
                    Text(
                        text = testResult!!,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        persist()
                        uploading = true
                        uploadResult = null
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                TelemetryRuntime.uploadNow(context)
                            }
                            uploadResult = result
                            lastUploadAt = TelemetryPreferences.lastUploadOkAt(context)
                            uploading = false
                        }
                    },
                    enabled = !uploading,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(
                            if (uploading) R.string.telemetry_uploading else R.string.telemetry_upload_now,
                        ),
                    )
                }
                if (!uploadResult.isNullOrBlank()) {
                    Text(
                        text = uploadResult!!,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(
                        R.string.telemetry_last_upload,
                        if (lastUploadAt > 0) {
                            formatTimestamp(lastUploadAt)
                        } else {
                            stringResource(R.string.telemetry_never)
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(
                        R.string.telemetry_last_test,
                        if (lastTestAt > 0) {
                            formatTimestamp(lastTestAt)
                        } else {
                            stringResource(R.string.telemetry_never)
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        TextButton(
            onClick = { helpOpen = !helpOpen },
            modifier = Modifier.padding(horizontal = 16.dp),
        ) {
            Text(stringResource(R.string.telemetry_help_title))
        }
        if (helpOpen) {
            Text(
                text = stringResource(R.string.telemetry_help_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
        }
    }
}

private fun formatTimestamp(timestamp: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))

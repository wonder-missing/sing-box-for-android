package io.nekohasekai.sfa.compose.component

import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.ktx.clipboardText
import io.nekohasekai.sfa.update.DownloadErrorDetails
import io.nekohasekai.sfa.update.UpdateCheckException
import io.nekohasekai.sfa.update.UpdateState
import io.nekohasekai.sfa.vendor.Vendor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Manual update control for the Settings root. Checks for an update, then
 * opens the same confirm-and-download dialog the App page uses.
 */
@Composable
fun AppUpdateButton() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val updateInfo by UpdateState.updateInfo
    val isChecking by UpdateState.isChecking
    var showErrorDialog by remember { mutableStateOf<String?>(null) }
    var showUpdateAvailableDialog by remember { mutableStateOf(false) }
    var showDownloadDialog by remember { mutableStateOf(false) }
    var downloadJob by remember { mutableStateOf<Job?>(null) }
    var downloadError by remember { mutableStateOf<String?>(null) }

    IconButton(
        onClick = {
            if (UpdateState.isChecking.value || showDownloadDialog) return@IconButton
            val known = UpdateState.updateInfo.value
            if (UpdateState.hasUpdate.value && known != null) {
                Settings.lastShownUpdateVersion = known.versionCode
                showUpdateAvailableDialog = true
                return@IconButton
            }
            scope.launch {
                UpdateState.isChecking.value = true
                try {
                    val result =
                        withContext(Dispatchers.IO) {
                            Vendor.checkUpdateAsync()
                        }
                    if (result != null) {
                        Settings.lastShownUpdateVersion = result.versionCode
                    }
                    UpdateState.setUpdate(result)
                    if (result == null) {
                        showErrorDialog = context.getString(R.string.no_updates_available)
                    } else {
                        showUpdateAvailableDialog = true
                    }
                } catch (e: UpdateCheckException.Unreachable) {
                    UpdateState.setUpdate(null)
                    showErrorDialog =
                        context.getString(
                            R.string.update_check_unreachable,
                            e.message ?: "",
                        )
                } catch (e: Exception) {
                    Log.e("AppUpdateButton", "checkUpdateAsync failed", e)
                    UpdateState.setUpdate(null)
                    showErrorDialog = e.message
                } finally {
                    UpdateState.isChecking.value = false
                }
            }
        },
        enabled = !isChecking && !showDownloadDialog,
    ) {
        if (isChecking) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.dp,
            )
        } else {
            Icon(
                imageVector = Icons.Outlined.Refresh,
                contentDescription = stringResource(R.string.check_update),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
        }
    }

    showErrorDialog?.let { message ->
        AlertDialog(
            onDismissRequest = { showErrorDialog = null },
            title = { Text(stringResource(R.string.check_update)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { showErrorDialog = null }) {
                    Text(stringResource(R.string.ok))
                }
            },
        )
    }

    val currentUpdate = updateInfo
    if (showUpdateAvailableDialog && currentUpdate != null) {
        UpdateAvailableDialog(
            updateInfo = currentUpdate,
            onDismiss = { showUpdateAvailableDialog = false },
            onUpdate = {
                showDownloadDialog = true
                downloadError = null
                downloadJob =
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                Vendor.downloadAndInstall(context, currentUpdate.downloadUrl)
                            }
                            showDownloadDialog = false
                        } catch (e: Exception) {
                            Log.e("AppUpdateButton", "Error downloading update", e)
                            downloadError = DownloadErrorDetails.format(currentUpdate.downloadUrl, e)
                        }
                    }
            },
        )
    }

    val downloadErrorMessage = downloadError
    if (showDownloadDialog) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.update)) },
            text = {
                Column {
                    if (downloadErrorMessage != null) {
                        Text(
                            downloadErrorMessage,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else {
                        val progress by UpdateState.downloadProgress
                        val progressValue = progress
                        Column {
                            if (progressValue != null) {
                                Text("${stringResource(R.string.downloading)} ${(progressValue * 100).toInt()}%")
                            } else {
                                Text(stringResource(R.string.downloading))
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            if (progressValue != null) {
                                LinearProgressIndicator(
                                    progress = { progressValue },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            } else {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        downloadJob?.cancel()
                        downloadJob = null
                        showDownloadDialog = false
                        downloadError = null
                        UpdateState.downloadProgress.value = null
                    },
                ) {
                    Text(stringResource(if (downloadErrorMessage != null) R.string.ok else android.R.string.cancel))
                }
            },
            dismissButton =
            if (downloadErrorMessage != null) {
                {
                    TextButton(
                        onClick = {
                            clipboardText = downloadErrorMessage
                            Toast.makeText(context, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
                        },
                    ) {
                        Text(stringResource(R.string.copy_details))
                    }
                }
            } else {
                null
            },
        )
    }
}

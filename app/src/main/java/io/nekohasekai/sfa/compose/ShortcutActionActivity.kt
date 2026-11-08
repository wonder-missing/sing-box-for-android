package io.nekohasekai.sfa.compose

import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.bg.BoxService
import io.nekohasekai.sfa.bg.LauncherShortcuts
import io.nekohasekai.sfa.bg.ServiceConnection
import io.nekohasekai.sfa.constant.Action
import io.nekohasekai.sfa.constant.Alert
import io.nekohasekai.sfa.constant.ServiceMode
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.telemetry.TelemetryRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Handles launcher shortcut actions without showing the main UI.
 *
 * Start and stop wait for the service status so the toast reflects the real
 * outcome. The activity itself uses a translucent theme and finishes as soon
 * as the action is done.
 */
class ShortcutActionActivity : AppCompatActivity(), ServiceConnection.Callback {
    private enum class Target {
        Start,
        Stop,
        Upload,
    }

    private val connection = ServiceConnection(this, this)
    private val vpnPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                sendStart()
            } else {
                toast(R.string.shortcut_service_start_failed)
                finishAction()
            }
        }
    private var target: Target? = null
    private var commandSent = false
    private var startFlowStarted = false
    private var finished = false
    private var timeoutJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (intent.action) {
            Action.SHORTCUT_START_SERVICE -> begin(Target.Start)
            Action.SHORTCUT_STOP_SERVICE -> begin(Target.Stop)
            Action.SHORTCUT_UPLOAD_TELEMETRY -> begin(Target.Upload)
            else -> finishAction()
        }
    }

    private fun begin(target: Target) {
        this.target = target
        if (target == Target.Upload) {
            uploadTelemetry()
        } else {
            connection.connect()
        }
    }

    override fun onServiceStatusChanged(status: Status) {
        runOnUiThread {
            when (target) {
                Target.Start -> handleStart(status)
                Target.Stop -> handleStop(status)
                else -> {}
            }
        }
    }

    private fun handleStart(status: Status) {
        if (commandSent) {
            if (status == Status.Started) {
                toast(R.string.shortcut_service_started)
                finishAction()
            }
            return
        }
        when (status) {
            Status.Started -> {
                toast(R.string.shortcut_service_already_running)
                finishAction()
            }

            Status.Stopped -> {
                if (!startFlowStarted) {
                    startFlowStarted = true
                    startService()
                }
            }

            Status.Starting, Status.Stopping -> {}
        }
    }

    private fun handleStop(status: Status) {
        if (commandSent) {
            if (status == Status.Stopped) {
                toast(R.string.shortcut_service_stopped)
                finishAction()
            }
            return
        }
        when (status) {
            Status.Stopped -> {
                toast(R.string.shortcut_service_already_stopped)
                finishAction()
            }

            Status.Started -> stopService()
            Status.Starting, Status.Stopping -> {}
        }
    }

    private fun startService() {
        val prepareIntent = if (Settings.serviceMode == ServiceMode.VPN) VpnService.prepare(this) else null
        if (prepareIntent != null) {
            vpnPermissionLauncher.launch(prepareIntent)
            return
        }
        sendStart()
    }

    private fun sendStart() {
        commandSent = true
        BoxService.start()
        toast(R.string.shortcut_service_starting)
        scheduleTimeout(R.string.shortcut_service_start_failed) { status -> status != Status.Started }
    }

    private fun stopService() {
        commandSent = true
        BoxService.stop()
        toast(R.string.shortcut_service_stopping)
        scheduleTimeout(R.string.shortcut_service_stop_failed) { status -> status != Status.Stopped }
    }

    private fun scheduleTimeout(
        message: Int,
        stillWaiting: (Status) -> Boolean,
    ) {
        timeoutJob?.cancel()
        timeoutJob =
            lifecycleScope.launch {
                delay(12_000)
                if (!finished && stillWaiting(connection.status)) {
                    toast(message)
                    finishAction()
                }
            }
    }

    private fun uploadTelemetry() {
        toast(R.string.telemetry_uploading)
        lifecycleScope.launch {
            val message =
                withContext(Dispatchers.IO) {
                    TelemetryRuntime.uploadNow(this@ShortcutActionActivity)
                }
            toast(message)
            finishAction()
        }
    }

    private fun toast(text: Int) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }

    private fun finishAction() {
        if (finished) return
        finished = true
        timeoutJob?.cancel()
        val finalTarget = target
        val finalStatus = connection.status
        connection.disconnect()
        if ((finalTarget == Target.Start || finalTarget == Target.Stop) &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1
        ) {
            LauncherShortcuts.publish(this, finalStatus)
        }
        finish()
    }

    override fun onServiceAlert(type: Alert, message: String?) {
        runOnUiThread {
            toast(message ?: getString(R.string.shortcut_service_start_failed))
            finishAction()
        }
    }

    override fun onDestroy() {
        timeoutJob?.cancel()
        connection.disconnect()
        super.onDestroy()
    }
}

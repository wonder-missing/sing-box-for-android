package io.nekohasekai.sfa.bg

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.nekohasekai.sfa.BuildConfig
import io.nekohasekai.sfa.activity.ActivityLog
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.log.AppLog
import io.nekohasekai.sfa.telemetry.TelemetryEventLog
import io.nekohasekai.sfa.telemetry.TelemetryEvents
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class CrashVersion(
    val code: Int?,
    val name: String,
)

internal object ServiceRestorePolicy {
    fun shouldStart(
        startedByUser: Boolean,
        restoreAfterUpdate: Boolean,
    ): Boolean = startedByUser || restoreAfterUpdate

    /**
     * Crash-loop protection only applies to a crash from the build that is
     * starting. An old unread report must not block every later update.
     */
    fun shouldBlockForCrash(
        unreadVersions: List<CrashVersion>,
        currentCode: Int,
        currentName: String,
    ): Boolean =
        unreadVersions.any { version ->
            version.code == currentCode || (version.name.isNotBlank() && version.name == currentName)
        }
}

class BootReceiver : BroadcastReceiver() {
    @OptIn(DelicateCoroutinesApi::class)
    override fun onReceive(context: Context, intent: Intent) {
        val action =
            when (intent.action) {
                Intent.ACTION_BOOT_COMPLETED -> "boot"
                Intent.ACTION_MY_PACKAGE_REPLACED -> "package_replaced"
                else -> return
            }
        val app = context.applicationContext
        val pendingResult = goAsync()
        GlobalScope.launch(Dispatchers.IO) {
            var startedByUser = false
            var restoreAfterUpdate = false
            var result = "skipped"
            var error: String? = null
            try {
                // Application.onCreate starts this asynchronously; force the
                // Room-backed preference cache before reading the flags.
                Settings.dataStore.initialize()
                startedByUser = Settings.startedByUser
                restoreAfterUpdate = Settings.restoreServiceAfterUpdate
                if (!ServiceRestorePolicy.shouldStart(startedByUser, restoreAfterUpdate)) {
                    result = "no_flags"
                    return@launch
                }
                CrashReportManager.refresh()
                if (
                    ServiceRestorePolicy.shouldBlockForCrash(
                        CrashReportManager.unreadVersions(),
                        BuildConfig.VERSION_CODE,
                        BuildConfig.VERSION_NAME,
                    )
                ) {
                    Settings.startedByUser = false
                    Settings.restoreServiceAfterUpdate = false
                    result = "blocked_crash"
                    return@launch
                }
                try {
                    withContext(Dispatchers.Main) {
                        BoxService.start().join()
                    }
                    // Keep the flag until the start command is accepted; a
                    // failure leaves it for the Activity launch fallback.
                    Settings.restoreServiceAfterUpdate = false
                    result = "started"
                } catch (e: Exception) {
                    result = "start_failed"
                    error = TelemetryEvents.scrub(e.message.orEmpty(), 120)
                }
            } catch (e: Exception) {
                result = "exception"
                error = TelemetryEvents.scrub(e.message.orEmpty(), 120)
            } finally {
                ActivityLog.recordServiceRestore(
                    app,
                    action,
                    result,
                    startedByUser,
                    restoreAfterUpdate,
                )
                TelemetryEventLog.record(
                    app,
                    "service_restore",
                    buildMap {
                        put("action", action)
                        put("started_by_user", startedByUser)
                        put("restore_after_update", restoreAfterUpdate)
                        put("result", result)
                        error?.takeIf { it.isNotBlank() }?.let { put("error", it) }
                    },
                )
                AppLog.info(
                    "Service restore action=$action result=$result " +
                        "startedByUser=$startedByUser restoreAfterUpdate=$restoreAfterUpdate",
                )
                runCatching { Settings.dataStore.flush() }
                pendingResult.finish()
            }
        }
    }
}

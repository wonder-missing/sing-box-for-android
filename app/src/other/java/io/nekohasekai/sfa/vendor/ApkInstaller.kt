package io.nekohasekai.sfa.vendor

import android.content.Context
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.bg.BoxService
import io.nekohasekai.sfa.bg.RootClient
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.telemetry.TelemetryEventLog
import io.nekohasekai.sfa.telemetry.TelemetryEvents
import io.nekohasekai.sfa.utils.HookStatusClient
import io.nekohasekai.sfa.xposed.XposedActivation
import kotlinx.coroutines.delay
import java.io.File

enum class InstallMethod {
    PACKAGE_INSTALLER,
    SHIZUKU,
    ROOT,
}

object ApkInstaller {

    private suspend fun stopServiceIfRunning() {
        val commandSocket = File(Application.application.filesDir, "command.sock")
        val socketRunning = commandSocket.exists()
        val userStarted = Settings.startedByUser
        if (!socketRunning && !userStarted) {
            TelemetryEventLog.record(
                Application.application,
                "update_install",
                mapOf(
                    "stage" to "prepare",
                    "running" to false,
                    "socket" to false,
                    "started_by_user" to false,
                ),
            )
            return
        }
        // The stop path clears startedByUser. Remember that the service was up
        // so BootReceiver can start it again after MY_PACKAGE_REPLACED.
        Settings.restoreServiceAfterUpdate = true
        Settings.dataStore.flush()
        TelemetryEventLog.record(
            Application.application,
            "update_install",
            mapOf(
                "stage" to "prepare",
                "running" to true,
                "socket" to socketRunning,
                "started_by_user" to userStarted,
            ),
        )
        BoxService.stop()
        repeat(20) {
            delay(100)
            if (!commandSocket.exists()) {
                return
            }
        }
    }

    fun getConfiguredMethod(): InstallMethod {
        if (HookStatusClient.status.value?.active == true ||
            XposedActivation.isActivated(Application.application)
        ) {
            return InstallMethod.ROOT
        }
        return if (Settings.silentInstallEnabled) {
            InstallMethod.valueOf(Settings.silentInstallMethod)
        } else {
            InstallMethod.PACKAGE_INSTALLER
        }
    }

    suspend fun install(context: Context, apkFile: File, method: InstallMethod = getConfiguredMethod()) {
        stopServiceIfRunning()
        TelemetryEventLog.record(
            Application.application,
            "update_install",
            mapOf("stage" to "install", "method" to method.name),
        )
        try {
            when (method) {
                InstallMethod.SHIZUKU -> ShizukuInstaller.install(apkFile)
                InstallMethod.ROOT -> RootInstaller.install(apkFile)
                InstallMethod.PACKAGE_INSTALLER -> SystemPackageInstaller.install(context, apkFile)
            }
        } catch (e: Exception) {
            TelemetryEventLog.record(
                Application.application,
                "update_install",
                mapOf(
                    "stage" to "error",
                    "method" to method.name,
                    "error" to e.javaClass.simpleName,
                    "detail" to TelemetryEvents.scrub(e.message.orEmpty(), 120),
                ),
            )
            throw e
        }
    }

    fun canSystemSilentInstall(): Boolean = SystemPackageInstaller.canSystemSilentInstall()

    suspend fun canSilentInstall(): Boolean {
        val method = getConfiguredMethod()
        return when (method) {
            InstallMethod.PACKAGE_INSTALLER -> canSystemSilentInstall()
            InstallMethod.SHIZUKU -> ShizukuInstaller.isAvailable() && ShizukuInstaller.checkPermission()
            InstallMethod.ROOT -> RootClient.checkRootAvailable()
        }
    }
}

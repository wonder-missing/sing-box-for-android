package io.nekohasekai.sfa.vendor

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

object SystemPackageInstaller {

    fun canSystemSilentInstall(): Boolean = false

    fun install(context: Context, apkFile: File) {
        val appContext = context.applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !appContext.packageManager.canRequestPackageInstalls()
        ) {
            val settings = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${appContext.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            Handler(Looper.getMainLooper()).post {
                appContext.startActivity(settings)
            }
            throw SecurityException(appContext.getString(io.nekohasekai.sfa.R.string.update_allow_installs))
        }
        val uri = FileProvider.getUriForFile(
            appContext,
            "${appContext.packageName}.cache",
            apkFile,
        )
        val install = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        Handler(Looper.getMainLooper()).post {
            appContext.startActivity(install)
        }
    }
}

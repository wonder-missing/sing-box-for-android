package io.nekohasekai.sfa.activity

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log

/**
 * Reads Android's own process-exit history so an ANR, native crash or kill is
 * visible even when the app did not get a chance to write a crash report.
 * Only the app's own package is queried; no permission is required.
 */
internal object ProcessExitRecorder {
    private const val MAX_REPORTS = 20

    private val abnormalReasons =
        setOf(
            "anr",
            "crash",
            "native_crash",
            "low_memory",
            "signaled",
            "init_failure",
            "resource_usage",
            "dependency_died",
        )

    fun recordRecent(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val am = context.getSystemService(ActivityManager::class.java) ?: return
        val reports =
            runCatching {
                am.getHistoricalProcessExitReasons(context.packageName, 0, MAX_REPORTS)
            }.onFailure {
                Log.w("ProcessExitRecorder", "read exit history: ${it.javaClass.simpleName}")
            }.getOrNull() ?: return
        val cutoff = System.currentTimeMillis() - ActivityReducer.RETENTION_MS
        for (report in reports) {
            if (report.timestamp < cutoff) continue
            val reason = reasonName(report.reason)
            if (reason !in abnormalReasons) continue
            ActivityLog.recordProcessExit(
                context,
                report.timestamp,
                reason,
                report.description?.take(160),
            )
        }
    }

    internal fun reasonName(reason: Int): String =
        when (reason) {
            ApplicationExitInfo.REASON_ANR -> "anr"
            ApplicationExitInfo.REASON_CRASH -> "crash"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "native_crash"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "low_memory"
            ApplicationExitInfo.REASON_SIGNALED -> "signaled"
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "init_failure"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "resource_usage"
            ApplicationExitInfo.REASON_EXIT_SELF -> "exit_self"
            ApplicationExitInfo.REASON_USER_REQUESTED -> "user_requested"
            ApplicationExitInfo.REASON_USER_STOPPED -> "user_stopped"
            ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "package_updated"
            ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "package_state_changed"
            ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission_changed"
            ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependency_died"
            ApplicationExitInfo.REASON_FREEZER -> "freezer"
            ApplicationExitInfo.REASON_OTHER -> "other"
            ApplicationExitInfo.REASON_UNKNOWN -> "unknown"
            else -> "reason_$reason"
        }
}

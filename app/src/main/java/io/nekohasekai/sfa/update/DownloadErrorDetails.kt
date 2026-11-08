package io.nekohasekai.sfa.update

import io.nekohasekai.sfa.BuildConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DownloadErrorDetails {
    fun format(
        url: String?,
        error: Throwable,
        timeMillis: Long = System.currentTimeMillis(),
    ): String =
        buildString {
            append(error.message ?: error.javaClass.simpleName)
            append("\nURL: ").append(url ?: "unknown")
            append("\nTime: ")
                .append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(timeMillis)))
            append("\nApp: ").append(BuildConfig.VERSION_NAME)
        }
}

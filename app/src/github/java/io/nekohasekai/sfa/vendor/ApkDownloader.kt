package io.nekohasekai.sfa.vendor

import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.update.UpdateState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.net.URL
import javax.net.ssl.HttpsURLConnection

class ApkDownloader : Closeable {
    suspend fun download(url: String): File = withContext(Dispatchers.IO) {
        val cacheDir = File(Application.application.cacheDir, "updates")
        cacheDir.mkdirs()
        val apkFile = File(cacheDir, "update.apk")

        if (apkFile.exists()) apkFile.delete()

        val connection = (URL(url).openConnection() as HttpsURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.android.package-archive,*/*")
            setRequestProperty("User-Agent", "WonderSing")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw Exception("Download failed: HTTP $code")
            }
            val total = connection.contentLength.toLong()
            connection.inputStream.use { input ->
                apkFile.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var readTotal = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        readTotal += count
                        UpdateState.downloadProgress.value =
                            if (total > 0) readTotal.toFloat() / total.toFloat() else null
                    }
                }
            }
        } finally {
            connection.disconnect()
        }

        if (!apkFile.exists() || apkFile.length() == 0L) {
            throw Exception("Download failed: empty file")
        }

        UpdateState.saveApkPath(apkFile)
        apkFile
    }

    override fun close() = Unit
}

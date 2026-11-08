package io.nekohasekai.sfa.update

import io.nekohasekai.sfa.BuildConfig
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadErrorDetailsTest {
    @Test
    fun `includes the url, http status, time and app version`() {
        val text =
            DownloadErrorDetails.format(
                url = "https://example.com/SFA-test.apk",
                error = Exception("Download failed: HTTP 504"),
                timeMillis = 0L,
            )

        assertTrue(text.contains("Download failed: HTTP 504"))
        assertTrue(text.contains("URL: https://example.com/SFA-test.apk"))
        assertTrue(text.contains("Time: "))
        assertTrue(text.contains("App: ${BuildConfig.VERSION_NAME}"))
    }

    @Test
    fun `uses unknown when the url is missing`() {
        val text = DownloadErrorDetails.format(url = null, error = Exception("Download failed"))

        assertTrue(text.contains("URL: unknown"))
    }
}

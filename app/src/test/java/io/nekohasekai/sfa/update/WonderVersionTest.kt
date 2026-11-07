package io.nekohasekai.sfa.update

import io.nekohasekai.sfa.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class WonderVersionTest {
    @Test
    fun `version code matches update checker base`() {
        val build =
            Regex("""wonder\.(\d+)""")
                .find(BuildConfig.VERSION_NAME)
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull()

        assertNotNull("VERSION_NAME must contain wonder.N: ${BuildConfig.VERSION_NAME}", build)
        assertEquals(
            BuildConfig.VERSION_CODE,
            GitHubReleaseFeed.WONDER_VERSION_CODE_BASE + build!!,
        )
    }
}

package io.nekohasekai.sfa.utils

import io.nekohasekai.sfa.database.TypedProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class RemoteProfileLastModifiedTest {
    @Test
    fun `updates the stored value when the endpoint responds`() {
        val profile = remoteProfile()
        val lastModified = Date(1_760_000_000_000L)

        val changed =
            profile.refreshRemoteLastModified("WonderSing-test") { _, _ ->
                RemoteProfileMetadata.Result(lastModified, true)
            }

        assertTrue(changed)
        assertEquals(lastModified, profile.remoteLastModified)
    }

    @Test
    fun `keeps the previous value when the request fails`() {
        val previous = Date(1_750_000_000_000L)
        val profile = remoteProfile().apply { remoteLastModified = previous }

        val changed =
            profile.refreshRemoteLastModified("WonderSing-test") { _, _ ->
                RemoteProfileMetadata.Result(null, false)
            }

        assertFalse(changed)
        assertEquals(previous, profile.remoteLastModified)
    }

    @Test
    fun `clears the value when the endpoint has no header`() {
        val profile = remoteProfile().apply { remoteLastModified = Date(1_750_000_000_000L) }

        val changed =
            profile.refreshRemoteLastModified("WonderSing-test") { _, _ ->
                RemoteProfileMetadata.Result(null, true)
            }

        assertTrue(changed)
        assertNull(profile.remoteLastModified)
    }

    @Test
    fun `ignores local profiles`() {
        val profile =
            TypedProfile().apply {
                type = TypedProfile.Type.Local
                remoteURL = "https://example.com/subscription"
            }

        val changed =
            profile.refreshRemoteLastModified("WonderSing-test") { _, _ ->
                RemoteProfileMetadata.Result(Date(), true)
            }

        assertFalse(changed)
        assertNull(profile.remoteLastModified)
    }

    private fun remoteProfile(): TypedProfile =
        TypedProfile().apply {
            type = TypedProfile.Type.Remote
            remoteURL = "https://example.com/subscription"
        }
}

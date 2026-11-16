package io.nekohasekai.sfa.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BundledReleaseNotesTest {
    private val version = "1.15.0-alpha.13-wonder.87"

    @Test
    fun `accepts the annotated tag message for the installed build`() {
        assertTrue(BundledReleaseNotes.looksLikeCurrentReleaseNotes("wonder.87\n\n- Fixes", version))
    }

    @Test
    fun `rejects a commit message or another version`() {
        assertFalse(BundledReleaseNotes.looksLikeCurrentReleaseNotes("chore: bump wonder build to 87", version))
        assertFalse(BundledReleaseNotes.looksLikeCurrentReleaseNotes("wonder.86\n\n- Old", version))
        assertFalse(BundledReleaseNotes.looksLikeCurrentReleaseNotes(null, version))
    }
}

package io.nekohasekai.sfa.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentSanitizerTest {
    @Test
    fun scrubPersonalDataMasksEmailAndTailscaleAddressesOnly() {
        val raw =
            "self: phone online\nwonder@example.com\n- mini online 100.106.21.44, fd7a:115c:a1e0::9b33:152d\n" +
                "- node online 104.25.59.157"

        val scrubbed = AgentSanitizer.scrubPersonalData(raw)

        assertFalse(scrubbed.contains("wonder@example.com"))
        assertFalse(scrubbed.contains("100.106.21.44"))
        assertFalse(scrubbed.contains("fd7a:115c:a1e0::9b33:152d"))
        assertTrue(scrubbed.contains("[redacted-email]"))
        assertTrue(scrubbed.contains("[redacted-ip]"))
        assertTrue(scrubbed.contains("104.25.59.157"))
    }

    @Test
    fun confirmationDisplayKeepsUrlHostAndMasksSecrets() {
        val raw =
            """{"url":"https://user:pass@example.com/path?token=SECRET&x=1","note":"token=abc"}"""

        val display = AgentSanitizer.sanitizeArgsForDisplay(raw)

        assertTrue(display.contains("example.com/path"))
        assertFalse(display.contains("SECRET"))
        assertFalse(display.contains("user:pass"))
        assertFalse(display.contains("abc"))
    }
}

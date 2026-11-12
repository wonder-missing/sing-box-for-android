package io.nekohasekai.sfa.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentSanitizerTest {
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

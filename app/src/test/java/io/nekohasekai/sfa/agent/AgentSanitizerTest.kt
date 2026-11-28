package io.nekohasekai.sfa.agent

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
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
    fun modelReadMasksSecretsButKeepsUrls() {
        val raw = "GET https://api.example.com/v1?token=abc 100.106.21.44 wonder@example.com"

        val scrubbed = AgentSanitizer.scrubForModelRead(raw)

        assertTrue(scrubbed.contains("https://api.example.com/v1?token=***"))
        assertFalse(scrubbed.contains("abc"))
        assertFalse(scrubbed.contains("100.106.21.44"))
        assertFalse(scrubbed.contains("wonder@example.com"))
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

    @Test
    fun aPlainServerAndScopeObjectIsStillRedacted() {
        val output =
            buildJsonObject {
                put("server", "https://example.com/secret")
                putJsonArray("scope") { add("x") }
            }

        val sanitized = AgentSanitizer.sanitizeElement(output).toString()

        assertTrue(sanitized.contains("[redacted-url]"))
        assertFalse(sanitized.contains("example.com"))
    }

    @Test
    fun nonVfsToolUrlsStayRedacted() {
        val output = buildJsonObject { put("url", "https://example.com/sub?token=SECRET") }

        val sanitized = AgentSanitizer.sanitizeElement(output).toString()

        assertTrue(sanitized.contains("[redacted-url]"))
        assertFalse(sanitized.contains("example.com"))
        assertFalse(sanitized.contains("SECRET"))
    }
}

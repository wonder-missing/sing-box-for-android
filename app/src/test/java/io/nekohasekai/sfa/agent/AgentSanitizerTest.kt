package io.nekohasekai.sfa.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
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
    fun vfsLinkKeepsTheIssuerLinkIdAndScope() {
        val output =
            buildJsonObject {
                put(
                    "link",
                    buildJsonObject {
                        put("type", "vfs-link")
                        put("version", 1)
                        put("issuer", "https://clawdy-mini.tailf665f5.ts.net")
                        put("linkId", "l_cbb79c6a9e92830cf0d22f577f059c88")
                        putJsonArray("scope") { add("conductor") }
                    },
                )
                put("expiresAt", 1_700_000_000_000L)
            }

        val sanitized = AgentSanitizer.sanitizeElement(output) as JsonObject
        val link = sanitized["link"] as JsonObject

        assertEquals(
            "https://clawdy-mini.tailf665f5.ts.net",
            (link["issuer"] as JsonPrimitive).content,
        )
        assertEquals(
            "l_cbb79c6a9e92830cf0d22f577f059c88",
            (link["linkId"] as JsonPrimitive).content,
        )
        assertEquals(
            "conductor",
            ((link["scope"] as JsonArray)[0] as JsonPrimitive).content,
        )
    }

    @Test
    fun vfsConnectOutputKeepsTheIssuerWithoutALinkId() {
        val output =
            buildJsonObject {
                put("connected", true)
                put("issuer", "https://clawdy-mini.tailf665f5.ts.net")
                putJsonArray("scope") { add("conductor") }
                putJsonArray("visibleRoots") { add("conductor/**") }
            }

        val sanitized = AgentSanitizer.sanitizeElement(output).toString()

        assertTrue(sanitized.contains("https://clawdy-mini.tailf665f5.ts.net"))
    }

    @Test
    fun aPlainIssuerAndScopeObjectIsStillRedacted() {
        val output =
            buildJsonObject {
                put("issuer", "https://example.com/secret")
                putJsonArray("scope") { add("x") }
            }

        val sanitized = AgentSanitizer.sanitizeElement(output).toString()

        assertTrue(sanitized.contains("[redacted-url]"))
        assertFalse(sanitized.contains("example.com"))
    }

    @Test
    fun aVfsLinkStillRedactsOtherUrls() {
        val output =
            buildJsonObject {
                put("type", "vfs-link")
                put("issuer", "https://clawdy-mini.tailf665f5.ts.net")
                put("linkId", "l_cbb79c6a9e92830cf0d22f577f059c88")
                putJsonArray("scope") { add("conductor") }
                put("note", "https://example.com/sub?token=SECRET")
            }

        val sanitized = AgentSanitizer.sanitizeElement(output).toString()

        assertTrue(sanitized.contains("clawdy-mini.tailf665f5.ts.net"))
        assertTrue(sanitized.contains("[redacted-url]"))
        assertFalse(sanitized.contains("example.com"))
        assertFalse(sanitized.contains("SECRET"))
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

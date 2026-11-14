package io.nekohasekai.sfa.vfs

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VfsLinkTest {
    @Test
    fun parsesAndNormalizesAValidLink() {
        val link =
            VfsLinkParser.parse(
                buildJsonObject {
                    put("type", "vfs-link")
                    put("version", 1)
                    put("issuer", "HTTPS://Example.com:443/base/")
                    put("linkId", " link_1 ")
                    put("scope", scopeArray("conductor", "trace"))
                    put("ignored", "ok")
                },
            )

        assertEquals("https://example.com/base", link.issuer)
        assertEquals("link_1", link.linkId)
        assertEquals(setOf(VfsScope.CONDUCTOR, VfsScope.TRACE), link.scope)
    }

    @Test
    fun rejectsWrongTypeOrVersion() {
        assertEquals("VFS_LINK_INVALID", failure { parse(link(type = "other")) }.code)
        assertEquals("VFS_LINK_INVALID", failure { parse(link(version = 2)) }.code)
    }

    @Test
    fun rejectsMissingOrInvalidFields() {
        assertEquals("VFS_LINK_INVALID", failure { parse(link(issuer = "")) }.code)
        assertEquals("VFS_LINK_INVALID", failure { parse(link(linkId = "")) }.code)
        assertEquals("VFS_LINK_INVALID", failure { parse(link(scope = null)) }.code)
        assertEquals("VFS_LINK_INVALID", failure { parse(link(scope = emptyList())) }.code)
    }

    @Test
    fun rejectsUnsupportedScopeSeparately() {
        val error = failure { parse(link(scope = listOf("conductor", "camera"))) }

        assertEquals("VFS_SCOPE_UNSUPPORTED", error.code)
        assertTrue(error.message.orEmpty().contains("camera"))
    }

    private fun parse(value: kotlinx.serialization.json.JsonObject): VfsLink = VfsLinkParser.parse(value)

    private fun link(
        type: String = "vfs-link",
        version: Int = 1,
        issuer: String = "https://example.com",
        linkId: String = "link_1",
        scope: List<String>? = listOf("conductor"),
    ) = buildJsonObject {
        put("type", type)
        put("version", version)
        put("issuer", issuer)
        put("linkId", linkId)
        scope?.let { put("scope", scopeArray(*it.toTypedArray())) }
    }

    private fun scopeArray(vararg values: String) =
        buildJsonArray {
            values.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
        }

    private fun failure(block: () -> Unit): VfsConnectException {
        val error = runCatching(block).exceptionOrNull()
        assertTrue("expected VfsConnectException, got $error", error is VfsConnectException)
        return error as VfsConnectException
    }
}

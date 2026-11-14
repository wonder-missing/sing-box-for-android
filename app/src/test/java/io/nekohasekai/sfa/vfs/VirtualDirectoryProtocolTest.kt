package io.nekohasekai.sfa.vfs

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualDirectoryProtocolTest {
    private fun encode(raw: String): JsonObject = Json.parseToJsonElement(raw).jsonObject

    @Test
    fun clientHelloCarriesVersionAndProvider() {
        val hello = encode(VfsProtocol.clientHello())

        assertEquals("hello", hello["type"]!!.jsonPrimitive.content)
        assertEquals(1, hello["version"]!!.jsonPrimitive.int)
        assertEquals("wondersing", hello["provider"]!!.jsonPrimitive.content)
    }

    @Test
    fun entryResponseIncludesMetadata() {
        val entry =
            VfsEntry(
                path = "conductor/note.md",
                name = "note.md",
                isDirectory = false,
                size = 42,
                mtimeMs = 1_700_000_000_000L,
                mimeType = "text/markdown; charset=utf-8",
                etag = "\"42-7\"",
            )

        val message = encode(VfsProtocol.entry(7, entry))
        val body = message["entry"]!!.jsonObject

        assertEquals("entry", message["type"]!!.jsonPrimitive.content)
        assertEquals(7L, message["id"]!!.jsonPrimitive.long)
        assertEquals("conductor/note.md", body["path"]!!.jsonPrimitive.content)
        assertEquals("note.md", body["name"]!!.jsonPrimitive.content)
        assertFalse(body["isDirectory"]!!.jsonPrimitive.boolean)
        assertEquals(42L, body["size"]!!.jsonPrimitive.long)
        assertEquals(1_700_000_000_000L, body["mtimeMs"]!!.jsonPrimitive.long)
        assertEquals("text/markdown; charset=utf-8", body["mimeType"]!!.jsonPrimitive.content)
        assertEquals("\"42-7\"", body["etag"]!!.jsonPrimitive.content)
    }

    @Test
    fun entryResponseOmitsUnknownOptionalFields() {
        val entry = VfsEntry("", "", true, 0, 1_000L)

        val body = encode(VfsProtocol.entry(1, entry))["entry"]!!.jsonObject

        assertFalse(body.containsKey("mimeType"))
        assertFalse(body.containsKey("etag"))
        assertTrue(body["isDirectory"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun entriesResponseListsEveryEntry() {
        val entries =
            listOf(
                VfsEntry("conductor/a.md", "a.md", false, 1, 2),
                VfsEntry("conductor/sub", "sub", true, 0, 3),
            )

        val message = encode(VfsProtocol.entries(9, entries))
        val array = message["entries"]!!.let { it as kotlinx.serialization.json.JsonArray }

        assertEquals("entries", message["type"]!!.jsonPrimitive.content)
        assertEquals(9L, message["id"]!!.jsonPrimitive.long)
        assertEquals(2, array.size)
        assertEquals("conductor/a.md", array[0].jsonObject["path"]!!.jsonPrimitive.content)
        assertTrue(array[1].jsonObject["isDirectory"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun dataResponseUsesBase64AndEof() {
        val message = encode(VfsProtocol.data(5, "hello".toByteArray(), eof = true))

        assertEquals("data", message["type"]!!.jsonPrimitive.content)
        assertEquals(5L, message["id"]!!.jsonPrimitive.long)
        assertEquals("aGVsbG8=", message["data"]!!.jsonPrimitive.content)
        assertTrue(message["eof"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun errorResponseKeepsCodeAndOmitsBlankMessage() {
        val withMessage = encode(VfsProtocol.error(3, VfsErrorCode.NOT_FOUND, "No such path."))
        val withoutMessage = encode(VfsProtocol.error(3, VfsErrorCode.NOT_FOUND, " "))

        assertEquals("error", withMessage["type"]!!.jsonPrimitive.content)
        assertEquals(3L, withMessage["id"]!!.jsonPrimitive.long)
        assertEquals("not_found", withMessage["code"]!!.jsonPrimitive.content)
        assertEquals("No such path.", withMessage["message"]!!.jsonPrimitive.content)
        assertFalse(withoutMessage.containsKey("message"))
    }

    @Test
    fun parsesServerHello() {
        val message = VfsProtocol.parseIncoming("""{"type":"hello","version":1,"server":"vfsd"}""")

        assertEquals(VfsIncoming.ServerHello(1, "vfsd"), message)
    }

    @Test
    fun parsesServerHelloResumeAndExpiry() {
        val message =
            VfsProtocol.parseIncoming(
                """{"type":"hello","version":1,"server":"vfsd","resumeToken":"r1","expiresAt":5000}""",
            )

        assertEquals(VfsIncoming.ServerHello(1, "vfsd", "r1", 5_000L), message)
    }

    @Test
    fun parsesPingAndBuildsPong() {
        assertEquals(
            VfsIncoming.Ping(7),
            VfsProtocol.parseIncoming("""{"type":"ping","id":7}"""),
        )

        val pong = encode(VfsProtocol.pong(7))
        assertEquals("pong", pong["type"]!!.jsonPrimitive.content)
        assertEquals(7L, pong["id"]!!.jsonPrimitive.long)
    }

    @Test
    fun parsesStatListAndRead() {
        assertEquals(
            VfsIncoming.Stat(1, "conductor/a.md"),
            VfsProtocol.parseIncoming("""{"type":"stat","id":1,"path":"conductor/a.md"}"""),
        )
        assertEquals(
            VfsIncoming.List(2, ""),
            VfsProtocol.parseIncoming("""{"type":"list","id":2,"path":""}"""),
        )
        assertEquals(
            VfsIncoming.Read(3, "conductor/a.md", 4, 1024),
            VfsProtocol.parseIncoming(
                """{"type":"read","id":3,"path":"conductor/a.md","offset":4,"length":1024}""",
            ),
        )
    }

    @Test
    fun parsesZeroLengthReadForEmptyFiles() {
        assertEquals(
            VfsIncoming.Read(1, "conductor/empty.txt", 0, 0),
            VfsProtocol.parseIncoming(
                """{"type":"read","id":1,"path":"conductor/empty.txt","offset":0,"length":0}""",
            ),
        )
    }

    @Test
    fun rejectsMalformedMessages() {
        val rejected =
            listOf(
                "",
                "not json",
                "[]",
                "null",
                "{}",
                """{"type":"nope","id":1}""",
                """{"type":"stat","path":"a"}""",
                """{"type":"stat","id":-1,"path":"a"}""",
                """{"type":"stat","id":1}""",
                """{"type":"stat","id":1,"path":7}""",
                """{"type":"list","id":"2","path":""}""",
                """{"type":"read","id":1,"path":"a","offset":-1,"length":1}""",
                """{"type":"read","id":1,"path":"a","offset":0,"length":-1}""",
                """{"type":"read","id":1,"path":"a","offset":"0","length":1}""",
                """{"type":"hello","version":"1"}""",
            )

        rejected.forEach { raw ->
            assertNull(raw, VfsProtocol.parseIncoming(raw))
        }
    }

    @Test
    fun ignoresUnknownFields() {
        val message =
            VfsProtocol.parseIncoming(
                """{"type":"stat","id":1,"path":"a","extra":true}""",
            )

        assertEquals(VfsIncoming.Stat(1, "a"), message)
    }
}

package io.nekohasekai.sfa.vfs

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class VirtualDirectorySessionTest {
    private class FakeSource : VirtualDirectorySource {
        val entries = mutableMapOf<String, VfsEntry>()
        val listings = mutableMapOf<String, List<VfsEntry>>()
        val contents = mutableMapOf<String, ByteArray>()
        var failure: Throwable? = null
        var returnedEof: Boolean? = null
        var returnedData: ByteArray? = null
        var statCalls = 0
        var readCalls = 0

        override suspend fun stat(path: String): VfsEntry {
            statCalls += 1
            failure?.let { throw it }
            return entries[path] ?: throw VfsException(VfsErrorCode.NOT_FOUND, "No such path.")
        }

        override suspend fun list(path: String): List<VfsEntry> {
            failure?.let { throw it }
            return listings[path] ?: emptyList()
        }

        override suspend fun read(
            path: String,
            offset: Long,
            length: Int,
        ): VfsRead {
            readCalls += 1
            failure?.let { throw it }
            returnedData?.let { return VfsRead(it, returnedEof ?: false) }
            val data = contents[path] ?: throw VfsException(VfsErrorCode.NOT_FOUND, "No such path.")
            val start = offset.coerceAtMost(data.size.toLong()).toInt()
            val end = minOf(data.size.toLong(), offset + length).toInt()
            return VfsRead(data.copyOfRange(start, end), end >= data.size)
        }
    }

    private fun encode(raw: String): JsonObject = Json.parseToJsonElement(raw).jsonObject

    @Test
    fun serverHelloProducesNoReply() =
        runBlocking {
            val session = VirtualDirectorySession(FakeSource())

            assertNull(session.respond(VfsIncoming.ServerHello(1, "vfsd")))
        }

    @Test
    fun statReturnsAnEntry() =
        runBlocking {
            val source = FakeSource()
            source.entries["conductor/a.md"] =
                VfsEntry("conductor/a.md", "a.md", false, 3, 10, "text/markdown", "\"e\"")
            val session = VirtualDirectorySession(source)

            val reply = session.respond(VfsIncoming.Stat(7, "conductor/a.md"))!!

            val message = encode(reply.text)
            val entry = message["entry"]!!.jsonObject
            assertEquals("entry", message["type"]!!.jsonPrimitive.content)
            assertEquals(7L, message["id"]!!.jsonPrimitive.long)
            assertEquals("a.md", entry["name"]!!.jsonPrimitive.content)
            assertEquals(3L, entry["size"]!!.jsonPrimitive.long)
            assertEquals("text/markdown", entry["mimeType"]!!.jsonPrimitive.content)
            assertNull(reply.errorCode)
            assertEquals(0, reply.bytesRead)
        }

    @Test
    fun listReturnsEntries() =
        runBlocking {
            val source = FakeSource()
            source.listings["conductor"] =
                listOf(
                    VfsEntry("conductor/a.md", "a.md", false, 3, 10),
                    VfsEntry("conductor/sub", "sub", true, 0, 11),
                )
            val session = VirtualDirectorySession(source)

            val reply = session.respond(VfsIncoming.List(2, "conductor"))!!
            val message = encode(reply.text)

            assertEquals("entries", message["type"]!!.jsonPrimitive.content)
            assertEquals(2L, message["id"]!!.jsonPrimitive.long)
            assertEquals(2, (message["entries"]!! as kotlinx.serialization.json.JsonArray).size)
        }

    @Test
    fun readReturnsBase64AndByteCount() =
        runBlocking {
            val source = FakeSource()
            source.contents["conductor/a.txt"] = "hello".toByteArray()
            val session = VirtualDirectorySession(source)

            val reply = session.respond(VfsIncoming.Read(3, "conductor/a.txt", 1, 3))!!

            val message = encode(reply.text)
            assertEquals("data", message["type"]!!.jsonPrimitive.content)
            assertEquals(3L, message["id"]!!.jsonPrimitive.long)
            assertEquals("ZWxs", message["data"]!!.jsonPrimitive.content)
            assertEquals(false, message["eof"]!!.jsonPrimitive.boolean)
            assertEquals(3, reply.bytesRead)
        }

    @Test
    fun readAtEndReturnsEmptyDataWithEof() =
        runBlocking {
            val source = FakeSource()
            source.returnedData = ByteArray(0)
            source.returnedEof = true
            val session = VirtualDirectorySession(source)

            val reply = session.respond(VfsIncoming.Read(3, "conductor/empty.txt", 0, 0))!!

            val message = encode(reply.text)
            assertEquals("", message["data"]!!.jsonPrimitive.content)
            assertTrue(message["eof"]!!.jsonPrimitive.boolean)
            assertEquals(0, reply.bytesRead)
        }

    @Test
    fun readOverTheLimitIsRejectedWithoutCallingTheSource() =
        runBlocking {
            val source = FakeSource()
            val session = VirtualDirectorySession(source)

            val reply =
                session.respond(
                    VfsIncoming.Read(4, "conductor/a.txt", 0, VfsProtocol.MAX_READ_BYTES + 1),
                )!!

            val message = encode(reply.text)
            assertEquals("error", message["type"]!!.jsonPrimitive.content)
            assertEquals("bad_request", message["code"]!!.jsonPrimitive.content)
            assertEquals("bad_request", reply.errorCode)
            assertEquals(0, source.readCalls)
        }

    @Test
    fun negativeReadValuesAreRejected() =
        runBlocking {
            val session = VirtualDirectorySession(FakeSource())

            val reply = session.respond(VfsIncoming.Read(4, "conductor/a.txt", -1, 1))!!

            assertEquals("bad_request", reply.errorCode)
        }

    @Test
    fun vfsErrorsKeepTheirCodeAndId() =
        runBlocking {
            val source = FakeSource()
            source.failure = VfsException(VfsErrorCode.INVALID_PATH, "Path traversal is not allowed.")
            val session = VirtualDirectorySession(source)

            val reply = session.respond(VfsIncoming.Stat(11, "../etc/passwd"))!!

            val message = encode(reply.text)
            assertEquals("error", message["type"]!!.jsonPrimitive.content)
            assertEquals(11L, message["id"]!!.jsonPrimitive.long)
            assertEquals("invalid_path", message["code"]!!.jsonPrimitive.content)
            assertEquals("Path traversal is not allowed.", message["message"]!!.jsonPrimitive.content)
            assertEquals("invalid_path", reply.errorCode)
        }

    @Test
    fun unexpectedExceptionsBecomeProviderErrorsWithoutDetails() =
        runBlocking {
            val source = FakeSource()
            source.failure = IllegalStateException("/data/user/0/app/files/secret")
            val session = VirtualDirectorySession(source)

            val reply = session.respond(VfsIncoming.Stat(12, "conductor/a.md"))!!

            val message = encode(reply.text)
            assertEquals("provider_error", message["code"]!!.jsonPrimitive.content)
            assertEquals(
                "The provider could not complete this request.",
                message["message"]!!.jsonPrimitive.content,
            )
            assertTrue(!reply.text.contains("secret"))
        }

    @Test
    fun emptyChunkBeforeEofIsAProviderError() =
        runBlocking {
            val source = FakeSource()
            source.returnedData = ByteArray(0)
            source.returnedEof = false
            val session = VirtualDirectorySession(source)

            val reply = session.respond(VfsIncoming.Read(13, "conductor/a.txt", 0, 10))!!

            assertEquals("provider_error", reply.errorCode)
        }

    @Test
    fun oversizedChunkIsAProviderError() =
        runBlocking {
            val source = FakeSource()
            source.returnedData = ByteArray(11)
            source.returnedEof = true
            val session = VirtualDirectorySession(source)

            val reply = session.respond(VfsIncoming.Read(14, "conductor/a.txt", 0, 10))!!

            assertEquals("provider_error", reply.errorCode)
        }

    @Test
    fun cancellationIsNotSwallowed() =
        runBlocking {
            val source = FakeSource()
            source.failure = CancellationException("cancelled")
            val session = VirtualDirectorySession(source)

            try {
                session.respond(VfsIncoming.Stat(1, "conductor/a.md"))
                fail("expected cancellation")
            } catch (_: CancellationException) {
                // expected
            }
        }
}

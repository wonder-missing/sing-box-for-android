package io.nekohasekai.sfa.vfs

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VfsProviderProtocolTest {
    private val sent = mutableListOf<String>()
    private var hello: VfsServerHello? = null
    private var error: VfsConnectException? = null

    private val source =
        object : VirtualDirectorySource {
            override suspend fun stat(path: String): VfsEntry =
                VfsEntry(path, path.substringAfterLast('/'), false, 1L, 1L)

            override suspend fun list(path: String): List<VfsEntry> = emptyList()

            override suspend fun read(
                path: String,
                offset: Long,
                length: Int,
            ): VfsRead = VfsRead(ByteArray(0), true)
        }

    private fun protocol() =
        VfsProviderProtocol(
            source = source,
            send = { text ->
                sent += text
                true
            },
            onHello = { hello = it },
            onError = { error = it },
        )

    @Test
    fun pingIsAnsweredByTheAppProcessWithTheSameId() =
        runBlocking {
            val protocol = protocol()

            protocol.onMessage("""{"type":"ping","id":7}""")

            assertEquals("""{"type":"pong","id":7}""", sent.single())
        }

    @Test
    fun serverHelloCarriesResumeAndExpiry() =
        runBlocking {
            val protocol = protocol()

            protocol.onMessage(
                """{"type":"hello","version":1,"server":"vfsd","resumeToken":"r1","expiresAt":5000}""",
            )

            assertEquals(VfsServerHello("vfsd", "r1", 5_000L), hello)
            assertNull(error)
        }

    @Test
    fun requestsBeforeHelloAreIgnored() =
        runBlocking {
            val protocol = protocol()

            protocol.onMessage("""{"type":"stat","id":1,"path":"conductor/a.md"}""")

            assertTrue(sent.isEmpty())
        }

    @Test
    fun statAfterHelloIsAnswered() =
        runBlocking {
            val protocol = protocol()
            protocol.onMessage("""{"type":"hello","version":1,"server":"vfsd"}""")

            protocol.onMessage("""{"type":"stat","id":1,"path":"conductor/a.md"}""")

            assertEquals(1, sent.size)
            assertTrue(sent.single().contains("\"type\":\"entry\""))
        }

    @Test
    fun unsupportedVersionIsReported() =
        runBlocking {
            val protocol = protocol()

            protocol.onMessage("""{"type":"hello","version":2,"server":"vfsd"}""")

            assertEquals("VFS_CONNECT_FAILED", error?.code)
            assertNull(hello)
        }

    @Test
    fun clientHelloIsSentExplicitly() {
        val protocol = protocol()

        assertTrue(protocol.sendClientHello())
        assertTrue(sent.single().contains("\"type\":\"hello\""))
    }
}

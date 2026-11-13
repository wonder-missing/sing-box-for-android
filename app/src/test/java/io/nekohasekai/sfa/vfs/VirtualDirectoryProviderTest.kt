package io.nekohasekai.sfa.vfs

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualDirectoryProviderTest {
    private class FakeSource : VirtualDirectorySource {
        var entry: VfsEntry? = null
        var entries: List<VfsEntry> = emptyList()
        var readResult: VfsRead = VfsRead(ByteArray(0), true)
        var failure: Exception? = null

        override suspend fun stat(path: String): VfsEntry {
            failure?.let { throw it }
            return entry ?: throw VfsException(VfsErrorCode.NOT_FOUND, "No such path.")
        }

        override suspend fun list(path: String): List<VfsEntry> {
            failure?.let { throw it }
            return entries
        }

        override suspend fun read(
            path: String,
            offset: Long,
            length: Int,
        ): VfsRead {
            failure?.let { throw it }
            return readResult
        }
    }

    private class FakeTransport : VirtualDirectoryTransport {
        val listeners = mutableListOf<VirtualDirectoryTransport.Listener>()
        val sent = mutableListOf<String>()
        var connects = 0
        var closes = 0
        var url: String? = null
        var token: String? = null
        var sendResult = true

        override fun connect(
            url: String,
            token: String?,
            listener: VirtualDirectoryTransport.Listener,
        ) {
            connects += 1
            this.url = url
            this.token = token
            listeners += listener
        }

        override fun send(text: String): Boolean {
            if (!sendResult) return false
            sent += text
            return true
        }

        override fun close() {
            closes += 1
        }

        fun listener(index: Int = listeners.lastIndex): VirtualDirectoryTransport.Listener = listeners[index]
    }

    private class GateSleeper {
        val delays = mutableListOf<Long>()
        private var gate: CompletableDeferred<Unit>? = null

        suspend fun sleep(milliseconds: Long) {
            delays += milliseconds
            val current = CompletableDeferred<Unit>()
            gate = current
            current.await()
        }

        fun release() {
            gate?.complete(Unit)
            gate = null
        }
    }

    private class Harness {
        val transport = FakeTransport()
        val source = FakeSource()
        val sleeper = GateSleeper()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val provider =
            VirtualDirectoryProvider(
                source = source,
                transport = transport,
                scope = scope,
                sleeper = sleeper::sleep,
                now = { 1_000L },
            )

        fun start() {
            provider.start(VirtualDirectoryConfig("ws://mini:18081/provider", "secret"))
        }

        fun handshake() {
            transport.listener().onOpen()
            transport.listener().onMessage(SERVER_HELLO)
        }

        fun close() {
            provider.stop()
            scope.cancel()
        }
    }

    private fun encode(raw: String): JsonObject = Json.parseToJsonElement(raw).jsonObject

    @Test
    fun startConnectsOutWithTheConfiguredTokenAndSendsHello() {
        val harness = Harness()

        harness.start()

        assertEquals(1, harness.transport.connects)
        assertEquals("ws://mini:18081/provider", harness.transport.url)
        assertEquals("secret", harness.transport.token)
        assertEquals(VirtualDirectoryState.Connecting, harness.provider.state.value)
        assertEquals(1, harness.transport.sent.size)
        val hello = encode(harness.transport.sent.single())
        assertEquals("hello", hello["type"]!!.jsonPrimitive.content)
        assertEquals(1, hello["version"]!!.jsonPrimitive.content.toInt())
        harness.close()
    }

    @Test
    fun serverHelloMarksTheProviderConnected() {
        val harness = Harness()

        harness.start()
        harness.handshake()

        val state = harness.provider.state.value as VirtualDirectoryState.Connected
        assertEquals("vfsd", state.server)
        assertEquals(1_000L, state.since)
        assertEquals(1_000L, harness.provider.activity.value.connectedAt)
        harness.close()
    }

    @Test
    fun statRequestsAreAnswered() {
        val harness = Harness()
        harness.source.entry = VfsEntry("conductor/a.md", "a.md", false, 3, 10, "text/markdown")
        harness.start()
        harness.handshake()

        harness.transport.listener().onMessage(
            """{"type":"stat","id":7,"path":"conductor/a.md"}""",
        )

        val reply = encode(harness.transport.sent.last())
        assertEquals("entry", reply["type"]!!.jsonPrimitive.content)
        assertEquals(7L, reply["id"]!!.jsonPrimitive.long)
        assertEquals("a.md", reply["entry"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        harness.close()
    }

    @Test
    fun listRequestsAreAnswered() {
        val harness = Harness()
        harness.source.entries =
            listOf(
                VfsEntry("conductor/a.md", "a.md", false, 3, 10),
                VfsEntry("conductor/sub", "sub", true, 0, 11),
            )
        harness.start()
        harness.handshake()

        harness.transport.listener().onMessage("""{"type":"list","id":8,"path":"conductor"}""")

        val reply = encode(harness.transport.sent.last())
        assertEquals("entries", reply["type"]!!.jsonPrimitive.content)
        assertEquals(
            2,
            (reply["entries"]!! as kotlinx.serialization.json.JsonArray).size,
        )
        harness.close()
    }

    @Test
    fun readResponsesCountBytesAndReachEof() {
        val harness = Harness()
        harness.source.readResult = VfsRead("hello".toByteArray(), eof = true)
        harness.start()
        harness.handshake()

        harness.transport.listener().onMessage(
            """{"type":"read","id":9,"path":"conductor/a.txt","offset":0,"length":10}""",
        )

        val reply = encode(harness.transport.sent.last())
        assertEquals("data", reply["type"]!!.jsonPrimitive.content)
        assertEquals("aGVsbG8=", reply["data"]!!.jsonPrimitive.content)
        assertTrue(reply["eof"]!!.jsonPrimitive.boolean)
        assertEquals(5L, harness.provider.activity.value.bytesRead)
        harness.close()
    }

    @Test
    fun providerErrorsAreAnsweredAndRecorded() {
        val harness = Harness()
        harness.source.failure = VfsException(VfsErrorCode.NOT_FOUND, "No such path.")
        harness.start()
        harness.handshake()

        harness.transport.listener().onMessage(
            """{"type":"stat","id":10,"path":"conductor/missing.md"}""",
        )

        val reply = encode(harness.transport.sent.last())
        assertEquals("error", reply["type"]!!.jsonPrimitive.content)
        assertEquals("not_found", reply["code"]!!.jsonPrimitive.content)
        assertEquals("not_found", harness.provider.activity.value.lastError)
        harness.close()
    }

    @Test
    fun malformedMessagesAreIgnored() {
        val harness = Harness()
        harness.start()
        harness.handshake()
        val sentBefore = harness.transport.sent.size

        harness.transport.listener().onMessage("not json")
        harness.transport.listener().onMessage("""{"type":"stat"}""")
        harness.transport.listener().onMessage("""{"type":"unknown","id":1}""")

        assertEquals(sentBefore, harness.transport.sent.size)
        harness.close()
    }

    @Test
    fun activityTracksTheLatestRequest() {
        val harness = Harness()
        harness.source.entries = emptyList()
        harness.start()
        harness.handshake()

        harness.transport.listener().onMessage("""{"type":"stat","id":1,"path":"conductor/a.md"}""")
        harness.transport.listener().onMessage("""{"type":"list","id":2,"path":"conductor"}""")
        harness.transport.listener().onMessage("""{"type":"read","id":3,"path":"conductor/a.md","offset":0,"length":4}""")

        val activity = harness.provider.activity.value
        assertEquals(3L, activity.requestCount)
        assertEquals("read", activity.lastKind)
        assertEquals("conductor/a.md", activity.lastPath)
        assertEquals(1_000L, activity.lastRequestAt)
        harness.close()
    }

    @Test
    fun failureStartsBackoffAndReconnectsAfterTheDelay() {
        val harness = Harness()
        harness.start()
        harness.handshake()

        harness.transport.listener().onFailure("boom")

        val state = harness.provider.state.value as VirtualDirectoryState.Error
        assertEquals("Connection failed: boom", state.message)
        assertEquals(1_000L, state.retryInMs)
        assertEquals(1, harness.transport.connects)
        assertEquals(listOf(1_000L), harness.sleeper.delays)
        assertTrue(harness.transport.closes >= 1)

        harness.sleeper.release()

        assertEquals(2, harness.transport.connects)
        assertEquals(VirtualDirectoryState.Connecting, harness.provider.state.value)
        harness.close()
    }

    @Test
    fun backoffGrowsAndResetsAfterAHandshake() {
        val harness = Harness()
        harness.start()
        harness.handshake()

        harness.transport.listener().onFailure("first")
        harness.sleeper.release()
        harness.transport.listener().onFailure("second")
        harness.sleeper.release()
        harness.transport.listener().onOpen()
        harness.transport.listener().onMessage(SERVER_HELLO)
        harness.transport.listener().onFailure("third")

        assertEquals(listOf(1_000L, 2_000L, 1_000L), harness.sleeper.delays)
        harness.close()
    }

    @Test
    fun stopCancelsAPendingRetry() {
        val harness = Harness()
        harness.start()
        harness.handshake()
        harness.transport.listener().onFailure("boom")

        harness.provider.stop()
        harness.sleeper.release()

        assertEquals(VirtualDirectoryState.Disabled, harness.provider.state.value)
        assertEquals(1, harness.transport.connects)
        harness.close()
    }

    @Test
    fun startingAgainReplacesTheOldConnectionAndIgnoresItsCallbacks() {
        val harness = Harness()
        harness.start()
        harness.handshake()
        val old = harness.transport.listener()

        harness.start()
        val sentAfterRestart = harness.transport.sent.size
        old.onMessage("""{"type":"stat","id":1,"path":"conductor/a.md"}""")

        assertEquals(2, harness.transport.connects)
        assertTrue(harness.transport.closes >= 1)
        assertEquals(sentAfterRestart, harness.transport.sent.size)
        harness.close()
    }

    @Test
    fun aFailedSendSchedulesReconnect() {
        val harness = Harness()
        harness.transport.sendResult = false

        harness.start()
        harness.transport.listener().onOpen()

        val state = harness.provider.state.value
        assertTrue(state is VirtualDirectoryState.Error)
        assertEquals(1, harness.transport.connects)
        assertEquals(listOf(1_000L), harness.sleeper.delays)

        harness.sleeper.release()
        assertEquals(2, harness.transport.connects)
        harness.close()
    }

    @Test
    fun unsupportedProtocolVersionsReconnect() {
        val harness = Harness()
        harness.start()
        harness.transport.listener().onOpen()
        harness.transport.listener().onMessage("""{"type":"hello","version":2,"server":"vfsd"}""")

        val state = harness.provider.state.value as VirtualDirectoryState.Error
        assertTrue(state.message.contains("version 2"))
        assertNotNull(state.retryInMs)
        harness.sleeper.release()
        assertEquals(2, harness.transport.connects)
        harness.close()
    }

    @Test
    fun aServerCloseSchedulesReconnect() {
        val harness = Harness()
        harness.start()
        harness.handshake()

        harness.transport.listener().onClosed(1006, "gone")

        val state = harness.provider.state.value as VirtualDirectoryState.Error
        assertTrue(state.message.contains("gone"))
        harness.close()
    }

    @Test
    fun failStopsRetryingForConfigurationErrors() {
        val harness = Harness()
        harness.start()

        harness.provider.fail("bad endpoint")

        assertEquals(VirtualDirectoryState.Error("bad endpoint"), harness.provider.state.value)
        harness.sleeper.release()
        assertEquals(1, harness.transport.connects)
        harness.close()
    }

}

private const val SERVER_HELLO = """{"type":"hello","version":1,"server":"vfsd"}"""

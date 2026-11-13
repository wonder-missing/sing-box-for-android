package io.nekohasekai.sfa.agent

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AgentDataToolsTest {
    private val workingDir: File = Files.createTempDirectory("conductor-data").toFile()
    private val store = ConductorFileStore(workingDir)
    private val sent = mutableListOf<Pair<String, List<File>>>()
    private val sentSelected = mutableListOf<Pair<String, List<String>>>()
    private val taildrop =
        object : TaildropPort {
            override suspend fun peers(): List<TaildropPeerInfo> =
                listOf(TaildropPeerInfo("peer-a", online = true, lastSeen = "2026-11-13 08:00"))

            override suspend fun send(
                peer: String,
                files: List<File>,
                cancellation: ToolCancellation?,
            ): TaildropSendResult {
                sent += peer to files
                return TaildropSendResult(peer, files.map { it.name }, ok = true)
            }

            override suspend fun sendSelected(
                peer: String,
                ids: List<String>,
                cancellation: ToolCancellation?,
            ): TaildropSendResult {
                sentSelected += peer to ids
                return TaildropSendResult(peer, ids, ok = true)
            }
        }
    private val diagnostics =
        object : DiagnosticsSource {
            override fun conversationMarkdown() = "# Conversation\nwonder@example.com 100.106.21.44"

            var lastTail: Int? = null

            override fun traceMarkdown(lastEntries: Int?): String {
                lastTail = lastEntries
                return "# Trace\nhttps://api.example.com/v1?token=secret"
            }
        }
    private val tools = AgentDataTools(store, diagnostics, taildrop)

    @Test
    fun writesAndReadsInsideTheWorkspace() {
        val registry = AgentToolRegistry(tools.all)

        val write = registry.exec("file_write", """{"path":"conductor/note.md","text":"hello"}""")
        assertTrue(write.errorMessage ?: "", write.ok)
        assertEquals("conductor/note.md", write.output!!.string("path"))

        val read = registry.exec("file_read", """{"path":"conductor/note.md"}""")
        assertTrue(read.errorMessage ?: "", read.ok)
        assertEquals("hello", read.output!!.string("text"))

        val list = registry.exec("file_list", """{"path":"conductor"}""")
        val entries = list.output!!.array("entries")
        assertTrue(entries.any { it is JsonObject && (it["path"] as JsonPrimitive).content == "conductor/note.md" })
    }

    @Test
    fun fileTailAndGrepWorkThroughTheRegistry() {
        store.writeText("conductor/lines.txt", "a\nb\nc\nd")
        val registry = AgentToolRegistry(tools.all)

        val tail = registry.exec("file_tail", """{"path":"conductor/lines.txt","lines":1}""")
        assertTrue(tail.errorMessage ?: "", tail.ok)
        assertEquals("d", tail.output!!.string("text"))

        val grep = registry.exec("file_grep", """{"path":"conductor/lines.txt","query":"c","context_lines":1}""")
        assertTrue(grep.errorMessage ?: "", grep.ok)
        assertEquals(3, grep.output!!.array("matches").size)
    }

    @Test
    fun diagnosticsTailIsForwarded() {
        val registry = AgentToolRegistry(tools.all)

        val result = registry.exec("diagnostics_export", """{"kind":"trace","tail":2}""")
        assertTrue(result.errorMessage ?: "", result.ok)
        assertEquals(2, diagnostics.lastTail)
    }

    @Test
    fun requestFilesReturnsHandlesAndKeepsTheRequestCapabilities() {
        val selection =
            FakeSelection(listOf(SelectedFileInfo("abc123", "picked.txt", 5, "text/plain")))
        val registry = AgentToolRegistry(AgentDataTools(store, diagnostics, taildrop, selection).all)

        val result =
            registry.exec(
                "request_files",
                """{"reason":"Attach the log","multiple":false,"mime_types":["text/*"]}""",
            )

        assertTrue(result.errorMessage ?: "", result.ok)
        assertEquals("temporary", result.output!!.string("grant"))
        assertEquals("abc123", (result.output!!.array("files").single() as JsonObject).string("id"))
        assertEquals("Attach the log", selection.reason)
        assertEquals(false, selection.multiple)
        assertEquals(listOf("text/*"), selection.mimeTypes)
    }

    @Test
    fun selectedFileReadUsesTheHandle() {
        val selection = FakeSelection(emptyList())
        val registry = AgentToolRegistry(AgentDataTools(store, diagnostics, taildrop, selection).all)

        val result = registry.exec("file_read_selected", """{"id":"abc123"}""")

        assertTrue(result.errorMessage ?: "", result.ok)
        assertEquals("hello", result.output!!.string("text"))
        assertEquals(listOf("abc123"), selection.readIds)
    }

    @Test
    fun taildropSendSelectedRequiresConfirmationAndPassesHandles() {
        val selection = FakeSelection(emptyList())
        val registry = AgentToolRegistry(AgentDataTools(store, diagnostics, taildrop, selection).all)
        val args = """{"peer":"peer-a","ids":["one","two"]}"""

        val pending = runBlocking { registry.invokeUserCommand("taildrop_send_selected", args) }
        assertEquals("confirmation_required", pending.errorCode)
        assertTrue(sentSelected.isEmpty())

        val confirmed = runBlocking { registry.confirmUserCommand("taildrop_send_selected", args) }
        assertTrue(confirmed.errorMessage ?: "", confirmed.ok)
        assertEquals(listOf("peer-a" to listOf("one", "two")), sentSelected)
    }

    @Test
    fun diagnosticsExportScrubsPersonalData() {
        val registry = AgentToolRegistry(tools.all)

        val conversation = registry.exec("diagnostics_export", """{"kind":"conversation"}""")
        assertTrue(conversation.errorMessage ?: "", conversation.ok)
        val path = conversation.output!!.string("path")
        assertTrue(path.startsWith("conductor/conversation-"))
        val text = store.readText(path).text
        assertFalse(text.contains("wonder@example.com"))
        assertFalse(text.contains("100.106.21.44"))
        assertTrue(text.contains("[redacted-email]"))

        val trace = registry.exec("diagnostics_export", """{"kind":"trace"}""")
        assertTrue(trace.errorMessage ?: "", trace.ok)
        val traceText = store.readText(trace.output!!.string("path")).text
        assertTrue(traceText.contains("https://api.example.com/v1?token=***"))
        assertFalse(traceText.contains("secret"))
    }

    @Test
    fun taildropSendPassesResolvedFiles() {
        store.writeText("conductor/report.md", "report")
        val registry = AgentToolRegistry(tools.all)

        val result = registry.exec("taildrop_send", """{"peer":"peer-a","files":["conductor/report.md"]}""")

        assertTrue(result.errorMessage ?: "", result.ok)
        assertEquals("peer-a", result.output!!.string("peer"))
        assertEquals(1, sent.size)
        assertEquals("peer-a", sent[0].first)
        assertEquals("report.md", sent[0].second.single().name)
    }

    @Test
    fun taildropSendRejectsPathsOutsideTheWorkingDirectory() {
        val registry = AgentToolRegistry(tools.all)

        val result = registry.exec("taildrop_send", """{"peer":"peer-a","files":["../secret.txt"]}""")

        assertFalse(result.ok)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun fileQueryFindsAllFileTools() {
        val names = AgentToolCatalog(tools.all.map { it.definition }).search("file").map { it.name }

        assertTrue(names.contains("file_list"))
        assertTrue(names.contains("file_read"))
        assertTrue(names.contains("file_stat"))
        assertTrue(names.contains("file_write"))
        assertTrue(names.contains("file_delete"))
    }

    @Test
    fun structuredParametersAreValidated() {
        store.writeText("conductor/x.txt", "x")
        val registry = AgentToolRegistry(tools.all)

        val badInteger = registry.exec("file_read", """{"path":"conductor/x.txt","max_bytes":"10"}""")
        assertFalse(badInteger.ok)
        assertEquals("invalid_parameter_type", badInteger.errorCode)

        val badBoolean = registry.exec("file_write", """{"path":"conductor/y.txt","text":"y","overwrite":"yes"}""")
        assertFalse(badBoolean.ok)
        assertEquals("invalid_parameter_type", badBoolean.errorCode)

        val badArray = registry.exec("taildrop_send", """{"peer":"peer-a","files":"conductor/x.txt"}""")
        assertFalse(badArray.ok)
        assertEquals("invalid_parameter_type", badArray.errorCode)
    }

    @Test
    fun toolDescriptionsStayCapabilityOriented() {
        tools.all.forEach { tool ->
            val description = tool.definition.description.lowercase()
            assertFalse(tool.definition.name, description.contains("mini"))
            assertFalse(tool.definition.name, description.contains("主人"))
            assertFalse(tool.definition.name, description.contains("upload api trace"))
        }
    }

    private class FakeSelection(
        private val files: List<SelectedFileInfo>,
    ) : FileSelectionPort {
        var reason: String? = null
        var multiple: Boolean? = null
        var mimeTypes: List<String>? = null
        val readIds = mutableListOf<String>()

        override suspend fun request(
            reason: String,
            multiple: Boolean,
            mimeTypes: List<String>?,
            cancellation: ToolCancellation?,
        ): FileSelectionResult {
            this.reason = reason
            this.multiple = multiple
            this.mimeTypes = mimeTypes
            return FileSelectionResult(files = files)
        }

        override suspend fun read(
            id: String,
            maxBytes: Int,
        ): SelectedFileRead {
            readIds += id
            return SelectedFileRead(id, "picked.txt", 5, "hello", truncated = false)
        }
    }

    private fun AgentToolRegistry.exec(
        name: String,
        argsJson: String,
    ): AgentInvocationResult =
        runBlocking {
            val first = invokeUserCommand(name, argsJson)
            if (first.confirmation != null) confirmUserCommand(name, argsJson) else first
        }

    private fun JsonObject.string(key: String): String = (getValue(key) as JsonPrimitive).content

    private fun JsonObject.array(key: String): JsonArray = getValue(key) as JsonArray
}

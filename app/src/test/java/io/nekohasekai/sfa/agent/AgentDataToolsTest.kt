package io.nekohasekai.sfa.agent

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.long
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
            var lastSince: Long? = null

            override fun traceMarkdown(
                lastEntries: Int?,
                since: Long?,
            ): TraceExport {
                lastTail = lastEntries
                lastSince = since
                return TraceExport(
                    text = "# Trace\nhttps://api.example.com/v1?token=secret",
                    count = 1,
                    firstTimestamp = 1_700_000_000_000L,
                    lastTimestamp = 1_700_000_001_000L,
                    availableFrom = 1_700_000_000_000L,
                    missingBefore = false,
                )
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
    fun fileReadTailAndGrepWorkThroughTheRegistry() {
        store.writeText("conductor/lines.txt", "a\nb\nc\nd")
        val registry = AgentToolRegistry(tools.all)

        val tail = registry.exec("file_read", """{"path":"conductor/lines.txt","last_lines":1}""")
        assertTrue(tail.errorMessage ?: "", tail.ok)
        assertEquals("d", tail.output!!.string("text"))

        val grep = registry.exec("file_grep", """{"path":"conductor/lines.txt","query":"c","context_lines":1}""")
        assertTrue(grep.errorMessage ?: "", grep.ok)
        assertEquals(3, grep.output!!.array("matches").size)
    }

    @Test
    fun fileAppendCreatesAndAppendsWithoutRewriting() {
        val registry = AgentToolRegistry(tools.all)

        val first = registry.exec("file_append", """{"path":"conductor/log.txt","text":"a"}""")
        assertTrue(first.errorMessage ?: "", first.ok)
        assertTrue(first.output!!.boolean("created"))
        assertEquals(1, first.output!!.long("size"))

        val second = registry.exec("file_append", """{"path":"conductor/log.txt","text":"b"}""")
        assertTrue(second.errorMessage ?: "", second.ok)
        assertFalse(second.output!!.boolean("created"))
        assertEquals(1, second.output!!.long("appendedBytes"))
        assertEquals(2, second.output!!.long("size"))
        assertEquals("ab", store.readText("conductor/log.txt").text)
    }

    @Test
    fun fileEditAppliesExactReplacementWithExpectedHash() {
        store.writeText("conductor/edit.txt", "alpha\nbeta\n")
        val registry = AgentToolRegistry(tools.all)
        val read = registry.exec("file_read", """{"path":"conductor/edit.txt"}""")
        val sha = read.output!!.string("sha256")

        val result =
            registry.exec(
                "file_edit",
                """{"path":"conductor/edit.txt","expected_sha256":"$sha","edits":[{"old_text":"beta","new_text":"gamma"}]}""",
            )

        assertTrue(result.errorMessage ?: "", result.ok)
        assertEquals(1, result.output!!.int("edits"))
        assertEquals("alpha\ngamma\n", store.readText("conductor/edit.txt").text)
    }

    @Test
    fun fileEditRefusesAmbiguousMatchesAndWritesNothing() {
        store.writeText("conductor/edit.txt", "a a")
        val registry = AgentToolRegistry(tools.all)

        val result =
            registry.exec(
                "file_edit",
                """{"path":"conductor/edit.txt","edits":[{"old_text":"a","new_text":"b"}]}""",
            )

        assertFalse(result.ok)
        assertTrue(result.errorMessage.orEmpty().contains("occurs 2 times"))
        assertEquals("a a", store.readText("conductor/edit.txt").text)
    }

    @Test
    fun fileEditRollsBackWhenALaterEditFails() {
        store.writeText("conductor/edit.txt", "alpha\nbeta\n")
        val registry = AgentToolRegistry(tools.all)

        val result =
            registry.exec(
                "file_edit",
                """{"path":"conductor/edit.txt","edits":[{"old_text":"alpha","new_text":"ALPHA"},{"old_text":"missing","new_text":"x"}]}""",
            )

        assertFalse(result.ok)
        assertEquals("alpha\nbeta\n", store.readText("conductor/edit.txt").text)
    }

    @Test
    fun diagnosticsTailIsForwarded() {
        val registry = AgentToolRegistry(tools.all)

        val result = registry.exec("diagnostics_export", """{"kind":"trace","tail":2}""")
        assertTrue(result.errorMessage ?: "", result.ok)
        assertEquals(2, diagnostics.lastTail)
    }

    @Test
    fun releaseNotesComeFromTheUpdateSource() {
        val source =
            object : ReleaseNotesSource {
                override suspend fun current() =
                    io.nekohasekai.sfa.update.ReleaseNotes(
                        version = "1.0-wonder.77",
                        tag = "v1.0-wonder.77",
                        notes = "- Added release notes\n- Fixed a thing",
                        releaseUrl = "https://example.com/release",
                    )
            }
        val registry = AgentToolRegistry(AgentDataTools(store, diagnostics, taildrop, releaseNotes = source).all)

        val result = registry.exec("release_notes", "{}")

        assertTrue(result.errorMessage ?: "", result.ok)
        assertEquals("1.0-wonder.77", result.output!!.string("version"))
        assertEquals("v1.0-wonder.77", result.output!!.string("tag"))
        assertEquals("- Added release notes\n- Fixed a thing", result.output!!.string("notes"))
    }

    @Test
    fun analyzeImageSendsTheSelectedImageToTheVisionPort() {
        val selection = FakeSelection(listOf(SelectedFileInfo("img1", "shot.png", 4, "image/png")))
        var seenMime: String? = null
        var seenQuestion: String? = null
        var seenBytes: Int? = null
        val vision =
            object : VisionPort {
                override suspend fun analyze(
                    image: ByteArray,
                    mimeType: String,
                    question: String,
                ): String {
                    seenMime = mimeType
                    seenQuestion = question
                    seenBytes = image.size
                    return "A settings screen."
                }
            }
        val registry =
            AgentToolRegistry(
                AgentDataTools(store, diagnostics, taildrop, selection, vision = vision).all,
            )

        val result = registry.exec("analyze_image", """{"question":"What is shown?"}""")

        assertTrue(result.errorMessage ?: "", result.ok)
        assertEquals("A settings screen.", result.output!!.string("analysis"))
        assertEquals("image/png", seenMime)
        assertEquals("What is shown?", seenQuestion)
        assertEquals(4, seenBytes)
    }

    @Test
    fun diagnosticsTraceCarriesTheTimeRangeAndCoverage() {
        val registry = AgentToolRegistry(tools.all)

        val result = registry.exec("diagnostics_export", """{"kind":"trace","since_minutes":15}""")

        assertTrue(result.errorMessage ?: "", result.ok)
        assertNotNull(diagnostics.lastSince)
        assertEquals("1", result.output!!.string("entries"))
        assertEquals("false", result.output!!.string("missingBefore"))
        assertTrue(result.output!!.containsKey("rangeStart"))
        assertTrue(result.output!!.containsKey("rangeEnd"))
    }

    @Test
    fun diagnosticsExportCanOverwriteWhenAsked() {
        val registry = AgentToolRegistry(tools.all)

        val first = registry.exec("diagnostics_export", """{"kind":"trace","name":"same.md"}""")
        assertTrue(first.errorMessage ?: "", first.ok)
        val second = registry.exec("diagnostics_export", """{"kind":"trace","name":"same.md","overwrite":true}""")

        assertTrue(second.errorMessage ?: "", second.ok)
        assertEquals("conductor/same.md", second.output!!.string("path"))
    }

    @Test
    fun diagnosticsExportCorrectsTheExtensionToMarkdown() {
        val registry = AgentToolRegistry(tools.all)

        val trace = registry.exec("diagnostics_export", """{"kind":"trace","name":"api-trace.json"}""")
        assertTrue(trace.errorMessage ?: "", trace.ok)
        assertEquals("conductor/api-trace.md", trace.output!!.string("path"))
        assertTrue(File(workingDir, "conductor/api-trace.md").readText().startsWith("# Trace"))

        val conversation = registry.exec("diagnostics_export", """{"kind":"conversation","name":"notes"}""")
        assertTrue(conversation.errorMessage ?: "", conversation.ok)
        assertEquals("conductor/notes.md", conversation.output!!.string("path"))
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
    fun requestFilesUsesAGenericReasonWhenOmitted() {
        val selection = FakeSelection(emptyList())
        val registry = AgentToolRegistry(AgentDataTools(store, diagnostics, taildrop, selection).all)

        val result = registry.exec("request_files", "{}")

        assertTrue(result.errorMessage ?: "", result.ok)
        assertEquals("The assistant needs a file from this device.", selection.reason)
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
        assertEquals("peer_inbox", confirmed.output!!.string("delivery"))
        assertEquals(listOf("peer-a" to listOf("one", "two")), sentSelected)
    }

    @Test
    fun diagnosticsExportScrubsPersonalData() {
        val registry = AgentToolRegistry(tools.all)

        val conversation = registry.exec("diagnostics_export", """{"kind":"conversation"}""")
        assertTrue(conversation.errorMessage ?: "", conversation.ok)
        val path = conversation.output!!.string("path")
        assertTrue(path.startsWith("conductor/transcript-"))
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
        assertEquals("peer_inbox", result.output!!.string("delivery"))
        assertTrue(result.output!!.string("note").contains("Taildrop inbox"))
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
        val catalog = AgentToolCatalog(tools.all.map { it.definition })
        val expected =
            listOf(
                "file_list",
                "file_read",
                "file_grep",
                "file_stat",
                "file_write",
                "file_append",
                "file_edit",
                "file_delete",
            )

        expected.forEach { name ->
            assertEquals(name, catalog.search(name).firstOrNull()?.name)
        }
    }

    @Test
    fun parameterErrorsNameTheAcceptedFields() {
        store.writeText("conductor/x.txt", "x")
        val registry = AgentToolRegistry(AgentDataTools(store, diagnostics, taildrop, FakeSelection(emptyList())).all)

        val wrongField = registry.exec("file_read_selected", """{"handle":"abc123"}""")
        assertFalse(wrongField.ok)
        assertEquals("missing_parameter", wrongField.errorCode)
        assertTrue(wrongField.errorMessage!!.contains("id (string, required)"))

        val missing = registry.exec("file_grep", """{"path":"conductor/x.txt","pattern":"x"}""")
        assertFalse(missing.ok)
        assertEquals("missing_parameter", missing.errorCode)
        assertTrue(missing.errorMessage!!.contains("query (string, required)"))
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
            val metadata = listOf(tool.definition.name, tool.definition.description) + tool.definition.aliases
            assertTrue(
                "tool '${tool.definition.name}' metadata must stay English: $metadata",
                metadata.none { text -> text.any { it.code in 0x4E00..0x9FFF } },
            )
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

        override suspend fun readBytes(
            id: String,
            maxBytes: Int,
        ): SelectedImage =
            SelectedImage(id, "picked.png", "image/png", byteArrayOf(1, 2, 3, 4))
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

    private fun JsonObject.boolean(key: String): Boolean = (getValue(key) as JsonPrimitive).boolean

    private fun JsonObject.int(key: String): Int = (getValue(key) as JsonPrimitive).int

    private fun JsonObject.long(key: String): Long = (getValue(key) as JsonPrimitive).long

    private fun JsonObject.array(key: String): JsonArray = getValue(key) as JsonArray
}

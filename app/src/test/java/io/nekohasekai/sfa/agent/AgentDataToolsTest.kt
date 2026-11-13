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
        }
    private val diagnostics =
        object : DiagnosticsSource {
            override fun conversationMarkdown() = "# Conversation\nwonder@example.com 100.106.21.44"

            override fun traceMarkdown() = "# Trace\nhttps://api.example.com/v1?token=secret"
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
    fun chineseFileQueryFindsAllFileTools() {
        val names = AgentToolCatalog(tools.all.map { it.definition }).search("文件").map { it.name }

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

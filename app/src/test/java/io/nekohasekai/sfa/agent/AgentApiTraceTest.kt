package io.nekohasekai.sfa.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AgentApiTraceTest {
    @Test
    fun redactsCredentialHeadersInMarkdown() {
        AgentApiTrace.clear()
        AgentApiTrace.record(
            ApiTraceEntry(
                timestamp = 1_700_000_000_000L,
                method = "POST",
                url = "https://api.deepseek.com/chat/completions",
                requestHeaders =
                    AgentApiTrace.sanitizeHeaders(
                        mapOf(
                            "Authorization" to listOf("Bearer sk-secret"),
                            "Content-Type" to listOf("application/json"),
                        ),
                    ),
                requestBody = """{"model":"test"}""",
                status = 200,
                responseHeaders =
                    AgentApiTrace.sanitizeHeaders(
                        mapOf("Content-Type" to listOf("application/json")),
                    ),
                responseBody = """{"choices":[]}""",
                error = null,
            ),
        )

        val markdown = AgentApiTrace.asMarkdown()

        assertFalse(markdown.contains("sk-secret"))
        assertTrue(markdown.contains("Authorization: ***"))
        assertTrue(markdown.contains("Content-Type: application/json"))
        assertTrue(markdown.contains("## 1. POST"))
        AgentApiTrace.clear()
    }

    @Test
    fun recordsFromBeforeClearAreDropped() {
        AgentApiTrace.clear()
        val generation = AgentApiTrace.generation()
        AgentApiTrace.clear()

        AgentApiTrace.record(
            ApiTraceEntry(
                timestamp = 1_700_000_000_000L,
                method = "POST",
                url = "https://api.deepseek.com/chat/completions",
                requestHeaders = emptyMap(),
                requestBody = """{"stale":true}""",
                status = 200,
                responseHeaders = emptyMap(),
                responseBody = "{}",
                error = null,
            ),
            generation,
        )

        assertFalse(AgentApiTrace.asMarkdown().contains("stale"))
        AgentApiTrace.clear()
    }

    @Test
    fun markdownCanKeepOnlyTheLastRequests() {
        AgentApiTrace.clear()
        repeat(2) { index ->
            AgentApiTrace.record(
                ApiTraceEntry(
                    timestamp = 1_700_000_000_000L + index,
                    method = "POST",
                    url = "https://api.deepseek.com/chat/completions",
                    requestHeaders = emptyMap(),
                    requestBody = """{"index":$index}""",
                    status = 200,
                    responseHeaders = emptyMap(),
                    responseBody = "{}",
                    error = null,
                ),
            )
        }

        val markdown = AgentApiTrace.asMarkdown(lastEntries = 1)

        assertTrue(markdown.contains("- showing last 1 requests"))
        assertTrue(markdown.contains("## 1. POST"))
        assertFalse(markdown.contains("## 2. POST"))
        assertTrue(markdown.contains("\"index\": 1"))
        AgentApiTrace.clear()
    }

    @Test
    fun markdownStartsWithTheFirstSystemPromptAndMarksChanges() {
        AgentApiTrace.clear()
        AgentApiTrace.setAppVersion("test-version")
        fun entry(
            timestamp: Long,
            prompt: String,
        ) = ApiTraceEntry(
            timestamp = timestamp,
            method = "POST",
            url = "https://api.deepseek.com/chat/completions",
            requestHeaders = emptyMap(),
            requestBody =
                """{"model":"deepseek-chat","messages":[{"role":"system","content":"$prompt"},{"role":"user","content":"hi"}]}""",
            status = 200,
            responseHeaders = emptyMap(),
            responseBody = "{}",
            error = null,
        )
        AgentApiTrace.record(entry(1_700_000_000_000L, "first prompt"))
        AgentApiTrace.record(entry(1_700_000_001_000L, "second prompt"))

        val markdown = AgentApiTrace.asMarkdown()

        assertTrue(markdown.contains("## System Prompt"))
        assertTrue(markdown.indexOf("## System Prompt") < markdown.indexOf("## 1. POST"))
        assertTrue(markdown.contains("first prompt"))
        assertTrue(markdown.contains("- model: deepseek-chat"))
        assertTrue(markdown.contains("- app version: test-version"))
        assertTrue(markdown.contains("system prompt changed at request 2"))
        AgentApiTrace.clear()
    }

    @Test
    fun compactionSystemPromptIsNotReportedAsAMainPromptChange() {
        AgentApiTrace.clear()
        fun entry(
            timestamp: Long,
            prompt: String,
        ) = ApiTraceEntry(
            timestamp = timestamp,
            method = "POST",
            url = "https://api.deepseek.com/chat/completions",
            requestHeaders = emptyMap(),
            requestBody =
                buildJsonObject {
                    put("model", "deepseek-chat")
                    putJsonArray("messages") {
                        add(
                            buildJsonObject {
                                put("role", "system")
                                put("content", prompt)
                            },
                        )
                    }
                }.toString(),
            status = 200,
            responseHeaders = emptyMap(),
            responseBody = "{}",
            error = null,
        )
        AgentApiTrace.record(entry(1_700_000_000_000L, "main prompt"))
        AgentApiTrace.record(entry(1_700_000_001_000L, COMPACT_SYSTEM_PROMPT))

        val markdown = AgentApiTrace.asMarkdown()

        assertTrue(markdown.contains("main prompt"))
        assertFalse(markdown.contains("system prompt changed"))
        AgentApiTrace.clear()
    }

    @Test
    fun summariesSurviveRestoreAndAppearInMarkdown() {
        AgentApiTrace.clear()
        AgentApiTrace.setAppVersion("test-version")
        AgentApiTrace.record(
            ApiTraceEntry(
                timestamp = 1_700_000_000_000L,
                method = "POST",
                url = "https://api.deepseek.com/chat/completions",
                requestHeaders = emptyMap(),
                requestBody = "{}",
                status = 200,
                responseHeaders = emptyMap(),
                responseBody = "{}",
                error = null,
                usage = TokenUsage(inputTokens = 100, outputTokens = 20),
            ),
        )
        val summaries = AgentApiTrace.summaries()
        AgentApiTrace.clear()
        AgentApiTrace.restoreSummaries(summaries)

        assertTrue(AgentApiTrace.hasEntries())
        val markdown = AgentApiTrace.asMarkdown()
        assertTrue(markdown.contains("Request history"))
        assertTrue(markdown.contains("vtest-version"))
        assertTrue(markdown.contains("prompt=100 completion=20"))
        AgentApiTrace.clear()
    }

    @Test
    fun exportFiltersByTimeRangeAndReportsMissingCoverage() {
        AgentApiTrace.clear()
        val base = 1_700_000_000_000L
        repeat(3) { index ->
            AgentApiTrace.record(
                ApiTraceEntry(
                    timestamp = base + index * 1_000L,
                    method = "POST",
                    url = "https://api.deepseek.com/chat/completions",
                    requestHeaders = emptyMap(),
                    requestBody = """{"index":$index}""",
                    status = 200,
                    responseHeaders = emptyMap(),
                    responseBody = "{}",
                    error = null,
                ),
            )
        }

        val all = AgentApiTrace.export(since = base - 1_000L)
        assertEquals(3, all.count)
        assertTrue(all.missingBefore)

        val recent = AgentApiTrace.export(since = base + 1_500L)
        assertEquals(1, recent.count)
        assertFalse(recent.missingBefore)
        assertTrue(recent.text.contains("\"index\": 2"))

        val empty = AgentApiTrace.export(since = base + 5_000L)
        assertEquals(0, empty.count)
        assertFalse(empty.missingBefore)
        assertTrue(empty.text.contains("No API calls"))

        AgentApiTrace.clear()
    }

    @Test
    fun fullEntriesSurviveAProcessRestartThroughThePrivateFile() {
        val dir = Files.createTempDirectory("conductor-trace").toFile()
        val file = File(dir, "api-trace.json")
        AgentApiTrace.clear()
        AgentApiTrace.attachPersistence(file)
        AgentApiTrace.setAppVersion("test-version")
        AgentApiTrace.record(
            ApiTraceEntry(
                timestamp = 1_700_000_000_000L,
                method = "POST",
                url = "https://api.deepseek.com/chat/completions",
                requestHeaders = emptyMap(),
                requestBody = """{"hello":"persisted"}""",
                status = 200,
                responseHeaders = emptyMap(),
                responseBody = "{}",
                error = null,
            ),
        )
        assertTrue(file.isFile)

        AgentApiTrace.resetInMemoryForTest()
        AgentApiTrace.attachPersistence(file)

        val export = AgentApiTrace.export()
        assertEquals(1, export.count)
        assertTrue(export.text.contains("persisted"))
        assertTrue(export.text.contains("vtest-version"))
        AgentApiTrace.clear()
        dir.deleteRecursively()
    }

    @Test
    fun recordsBufferedWhileLoadingAreMergedAfterPersistedEntries() {
        val dir = Files.createTempDirectory("conductor-trace-bind").toFile()
        val file = File(dir, "api-trace.json")
        AgentApiTrace.clear()
        AgentApiTrace.bindPersistence(file)
        AgentApiTrace.loadPersisted()
        AgentApiTrace.record(traceEntry(1_700_000_000_000L, "persisted-before"))
        AgentApiTrace.resetInMemoryForTest()

        // Startup binds the file first; a record can arrive before the load.
        AgentApiTrace.bindPersistence(file)
        AgentApiTrace.record(traceEntry(1_700_000_001_000L, "recorded-while-loading"))
        assertFalse(file.readText().contains("recorded-while-loading"))

        AgentApiTrace.loadPersisted()

        val export = AgentApiTrace.export()
        assertEquals(2, export.count)
        assertTrue(
            export.text.indexOf("persisted-before") <
                export.text.indexOf("recorded-while-loading"),
        )
        assertTrue(file.readText().contains("recorded-while-loading"))
        AgentApiTrace.clear()
        dir.deleteRecursively()
    }

    @Test
    fun recordsBufferedBeforeTheFirstLoadAreFlushedAfterIt() {
        val dir = Files.createTempDirectory("conductor-trace-first-load").toFile()
        val file = File(dir, "api-trace.json")
        AgentApiTrace.clear()
        AgentApiTrace.bindPersistence(file)
        AgentApiTrace.record(traceEntry(1_700_000_002_000L, "buffered-before-load"))
        assertFalse(file.exists())

        AgentApiTrace.loadPersisted()

        assertTrue(file.isFile)
        assertEquals(1, AgentApiTrace.export().count)
        assertTrue(file.readText().contains("buffered-before-load"))
        AgentApiTrace.clear()
        dir.deleteRecursively()
    }

    private fun traceEntry(
        timestamp: Long,
        prompt: String,
    ) = ApiTraceEntry(
        timestamp = timestamp,
        method = "POST",
        url = "https://api.deepseek.com/chat/completions",
        requestHeaders = emptyMap(),
        requestBody = """{"messages":[{"role":"system","content":"$prompt"}]}""",
        status = 200,
        responseHeaders = emptyMap(),
        responseBody = "{}",
        error = null,
    )

    @Test
    fun printsErrorCodeAndRequestId() {
        AgentApiTrace.clear()
        AgentApiTrace.record(
            ApiTraceEntry(
                timestamp = 1_700_000_000_000L,
                method = "POST",
                url = "https://api.deepseek.com/chat/completions",
                requestHeaders = emptyMap(),
                requestBody = "{}",
                status = 500,
                responseHeaders = mapOf("X-Request-Id" to "req-123"),
                responseBody = "{}",
                error = "HTTP 500",
                errorCode = "model_unavailable",
            ),
        )

        val markdown = AgentApiTrace.asMarkdown()

        assertTrue(markdown.contains("- error code: model_unavailable"))
        assertTrue(markdown.contains("- request id: req-123"))
        AgentApiTrace.clear()
    }

    @Test
    fun capsRetainedBodyMemoryAndKeepsNewestEntryIntact() {
        AgentApiTrace.clear()
        repeat(20) { index ->
            val body = "req-$index-" + "x".repeat(190_000)
            AgentApiTrace.record(
                ApiTraceEntry(
                    timestamp = 1_700_000_000_000L + index,
                    method = "POST",
                    url = "https://api.deepseek.com/chat/completions",
                    requestHeaders = emptyMap(),
                    requestBody = body,
                    status = 200,
                    responseHeaders = emptyMap(),
                    responseBody = body,
                    error = null,
                ),
            )
        }

        assertTrue(AgentApiTrace.retainedBodyChars() <= 1_000_000)
        assertTrue(AgentApiTrace.retainedEntryCount() < 20)
        val markdown = AgentApiTrace.asMarkdown()
        assertTrue(markdown.contains("req-19-" + "x".repeat(190_000)))
        assertTrue(markdown.contains("[body truncated for memory]"))
        AgentApiTrace.clear()
    }

    @Test
    fun storesTheAssembledStreamingAnswerAndToolCalls() {
        AgentApiTrace.clear()
        val reasoning = "r".repeat(300_000)
        val body =
            """
            data: {"id":"chatcmpl-1","object":"chat.completion.chunk","created":1700000000,"model":"deepseek-flash","choices":[{"index":0,"delta":{"role":"assistant","reasoning_content":"$reasoning"},"finish_reason":null}]}
            data: {"id":"chatcmpl-1","object":"chat.completion.chunk","created":1700000000,"model":"deepseek-flash","choices":[{"index":0,"delta":{"content":"final answer"},"finish_reason":null}]}
            data: {"id":"chatcmpl-1","object":"chat.completion.chunk","created":1700000000,"model":"deepseek-flash","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"foo","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}
            data: [DONE]
            """.trimIndent()
        AgentApiTrace.record(
            ApiTraceEntry(
                timestamp = 1_700_000_000_000L,
                method = "POST",
                url = "https://api.deepseek.com/chat/completions",
                requestHeaders = emptyMap(),
                requestBody = "{}",
                status = 200,
                responseHeaders = emptyMap(),
                responseBody = body,
                error = null,
            ),
        )

        val markdown = AgentApiTrace.asMarkdown()

        assertTrue(markdown.contains("final answer"))
        assertTrue(markdown.contains("tool_calls"))
        assertTrue(markdown.contains("trace truncated"))
        assertTrue(markdown.contains("response body: kept"))
        AgentApiTrace.clear()
    }

    @Test
    fun keepsTheTailOfALongRequest() {
        AgentApiTrace.clear()
        val filler = "x".repeat(210_000)
        val body = """{"messages":[{"role":"system","content":"$filler"}],"tail_marker":"TAIL_SENTINEL"}"""
        AgentApiTrace.record(
            ApiTraceEntry(
                timestamp = 1_700_000_000_000L,
                method = "POST",
                url = "https://api.deepseek.com/chat/completions",
                requestHeaders = emptyMap(),
                requestBody = body,
                status = 200,
                responseHeaders = emptyMap(),
                responseBody = "{}",
                error = null,
            ),
        )

        val markdown = AgentApiTrace.asMarkdown()

        assertTrue(markdown.contains("Request tail"))
        assertTrue(markdown.contains("TAIL_SENTINEL"))
        AgentApiTrace.clear()
    }

    @Test
    fun exportReportsDroppedEntriesWithoutSince() {
        AgentApiTrace.clear()
        repeat(25) { index ->
            AgentApiTrace.record(traceEntry(1_700_000_000_000L + index, "prompt-$index"))
        }

        val export = AgentApiTrace.export()

        assertEquals(20, export.count)
        assertEquals(25, export.totalCount)
        assertTrue(export.missingBefore)
        assertTrue(export.droppedCount >= 5)
        assertTrue(export.text.contains("retained request bodies: 20 of 25"))
        AgentApiTrace.clear()
    }

    @Test
    fun turnSnapshotKeepsCallsEvictedFromTheLiveTrace() {
        val dir = Files.createTempDirectory("conductor-turn-trace").toFile()
        val file = File(dir, "api-trace.json")
        AgentApiTrace.clear()
        AgentApiTrace.attachPersistence(file)
        AgentApiTrace.beginTurn()
        repeat(25) { index ->
            AgentApiTrace.record(traceEntry(1_700_000_000_000L + index, "prompt-$index"))
        }

        val snapshot = AgentApiTrace.endTurn()

        assertNotNull(snapshot)
        assertTrue(snapshot!!.isFile)
        val root = Json.parseToJsonElement(snapshot.readText()).jsonObject
        assertEquals(25, root["entries"]!!.jsonArray.size)
        assertEquals(0, root["droppedEntries"]!!.jsonPrimitive.int)
        assertTrue(snapshot.name.startsWith("trace-turn-"))
        AgentApiTrace.clear()
        dir.deleteRecursively()
    }

    @Test
    fun turnSnapshotKeepsTheRequestTail() {
        val dir = Files.createTempDirectory("conductor-turn-tail").toFile()
        val file = File(dir, "api-trace.json")
        AgentApiTrace.clear()
        AgentApiTrace.attachPersistence(file)
        AgentApiTrace.beginTurn()
        val filler = "x".repeat(210_000)
        AgentApiTrace.record(
            ApiTraceEntry(
                timestamp = 1_700_000_000_000L,
                method = "POST",
                url = "https://api.deepseek.com/chat/completions",
                requestHeaders = emptyMap(),
                requestBody = """{"messages":[{"role":"system","content":"$filler"}],"tail_marker":"SNAPSHOT_TAIL"}""",
                status = 200,
                responseHeaders = emptyMap(),
                responseBody = "{}",
                error = null,
            ),
        )

        val snapshot = AgentApiTrace.endTurn()

        assertNotNull(snapshot)
        val entry =
            Json
                .parseToJsonElement(snapshot!!.readText())
                .jsonObject["entries"]!!
                .jsonArray
                .single()
                .jsonObject
        assertTrue(entry["requestTail"]!!.jsonPrimitive.content.contains("SNAPSHOT_TAIL"))
        AgentApiTrace.clear()
        dir.deleteRecursively()
    }

    @Test
    fun clearDropsAnActiveTurnSnapshot() {
        val dir = Files.createTempDirectory("conductor-turn-clear").toFile()
        val file = File(dir, "api-trace.json")
        AgentApiTrace.clear()
        AgentApiTrace.attachPersistence(file)
        AgentApiTrace.beginTurn()
        AgentApiTrace.record(traceEntry(1_700_000_000_000L, "prompt"))

        AgentApiTrace.clear()

        assertTrue(AgentApiTrace.endTurn() == null)
        assertFalse(File(dir, "trace-turns").exists())
        dir.deleteRecursively()
    }

    @Test
    fun hasEntriesReflectsRecordAndClear() {
        AgentApiTrace.clear()
        assertFalse(AgentApiTrace.hasEntries())

        AgentApiTrace.record(
            ApiTraceEntry(
                timestamp = 1_700_000_000_000L,
                method = "POST",
                url = "https://api.deepseek.com/chat/completions",
                requestHeaders = emptyMap(),
                requestBody = "{}",
                status = 200,
                responseHeaders = emptyMap(),
                responseBody = "{}",
                error = null,
            ),
        )

        assertTrue(AgentApiTrace.hasEntries())
        AgentApiTrace.clear()
        assertFalse(AgentApiTrace.hasEntries())
    }

    @Test
    fun redactsInlineImageDataUrlsBeforeTheTrace() {
        val base64 = "A".repeat(5_000)
        val body =
            """{"messages":[{"content":[{"type":"image_url","image_url":{"url":"data:image/jpeg;base64,$base64"}}]}]}"""

        val redacted = AgentApiTrace.truncateBody(body)!!

        assertFalse(redacted.contains(base64))
        assertTrue(redacted.contains("data:image/jpeg;base64,[redacted"))
    }

    @Test
    fun printsTokenUsageAndCacheHits() {
        AgentApiTrace.clear()
        AgentApiTrace.record(
            ApiTraceEntry(
                timestamp = 1_700_000_000_000L,
                method = "POST",
                url = "https://api.deepseek.com/chat/completions",
                requestHeaders = emptyMap(),
                requestBody = """{"model":"test"}""",
                status = 200,
                responseHeaders = emptyMap(),
                responseBody =
                    """
                    {
                      "choices": [],
                      "usage": {
                        "prompt_tokens": 120,
                        "completion_tokens": 8,
                        "prompt_cache_hit_tokens": 100,
                        "prompt_cache_miss_tokens": 20
                      }
                    }
                    """.trimIndent(),
                error = null,
            ),
        )

        val markdown = AgentApiTrace.asMarkdown()

        assertTrue(markdown.contains("- usage: prompt=120 completion=8 cache_hit=100 cache_miss=20"))
        AgentApiTrace.clear()
    }
}

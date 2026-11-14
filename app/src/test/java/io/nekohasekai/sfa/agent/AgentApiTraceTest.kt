package io.nekohasekai.sfa.agent

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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

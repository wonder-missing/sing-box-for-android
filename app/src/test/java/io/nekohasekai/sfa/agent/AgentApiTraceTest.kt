package io.nekohasekai.sfa.agent

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

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

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
}

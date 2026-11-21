package io.nekohasekai.sfa.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentTraceResponseTest {
    @Test
    fun rebuildsStreamingChunksIntoOneCompletion() {
        val body =
            """
            data: {"id":"chatcmpl-1","object":"chat.completion.chunk","created":1700000000,"model":"deepseek-flash","choices":[{"index":0,"delta":{"role":"assistant","reasoning_content":"Think"},"finish_reason":null}]}
            data: {"id":"chatcmpl-1","object":"chat.completion.chunk","created":1700000000,"model":"deepseek-flash","choices":[{"index":0,"delta":{"content":"Hello "},"finish_reason":null}]}
            data: {"id":"chatcmpl-1","object":"chat.completion.chunk","created":1700000000,"model":"deepseek-flash","choices":[{"index":0,"delta":{"content":"world","tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"foo","arguments":"{\"a\":"}}]},"finish_reason":null}]}
            data: {"id":"chatcmpl-1","object":"chat.completion.chunk","created":1700000000,"model":"deepseek-flash","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"1}"}}]},"finish_reason":"tool_calls"}]}
            data: {"id":"chatcmpl-1","object":"chat.completion.chunk","created":1700000000,"model":"deepseek-flash","choices":[],"usage":{"prompt_tokens":11,"completion_tokens":7}}
            data: [DONE]
            """.trimIndent()

        val normalized = AgentTraceResponse.normalizeStreaming(body)
        assertNotNull(normalized)
        val root = normalized as JsonObject
        assertEquals("chat.completion", root["object"]!!.jsonPrimitive.content)
        assertEquals("deepseek-flash", root["model"]!!.jsonPrimitive.content)
        val choice = root["choices"]!!.jsonArray.single().jsonObject
        assertEquals(0, choice["index"]!!.jsonPrimitive.int)
        assertEquals("tool_calls", choice["finish_reason"]!!.jsonPrimitive.content)
        val message = choice["message"]!!.jsonObject
        assertEquals("assistant", message["role"]!!.jsonPrimitive.content)
        assertEquals("Hello world", message["content"]!!.jsonPrimitive.content)
        assertEquals("Think", message["reasoning_content"]!!.jsonPrimitive.content)
        val call = message["tool_calls"]!!.jsonArray.single().jsonObject
        assertEquals("call_1", call["id"]!!.jsonPrimitive.content)
        assertEquals("foo", call["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("{\"a\":1}", call["function"]!!.jsonObject["arguments"]!!.jsonPrimitive.content)
        val usage = root["usage"]!!.jsonObject
        assertEquals(11, usage["prompt_tokens"]!!.jsonPrimitive.int)
        assertEquals(7, usage["completion_tokens"]!!.jsonPrimitive.int)
    }

    @Test
    fun capsHugeReasoningButKeepsTheFinalAnswerAndToolCalls() {
        val reasoning = "r".repeat(300_000)
        val body =
            """
            data: {"id":"chatcmpl-1","object":"chat.completion.chunk","created":1700000000,"model":"deepseek-flash","choices":[{"index":0,"delta":{"role":"assistant","reasoning_content":"$reasoning"},"finish_reason":null}]}
            data: {"id":"chatcmpl-1","object":"chat.completion.chunk","created":1700000000,"model":"deepseek-flash","choices":[{"index":0,"delta":{"content":"final answer"},"finish_reason":null}]}
            data: {"id":"chatcmpl-1","object":"chat.completion.chunk","created":1700000000,"model":"deepseek-flash","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"foo","arguments":"{\"a\":1}"}}]},"finish_reason":"tool_calls"}]}
            data: [DONE]
            """.trimIndent()

        val prepared = AgentTraceResponse.prepareForTrace(body, 200_000)

        assertTrue(prepared.normalized)
        assertTrue(prepared.truncated)
        val message =
            Json
                .parseToJsonElement(prepared.text)
                .jsonObject["choices"]!!
                .jsonArray
                .single()
                .jsonObject["message"]!!
                .jsonObject
        assertEquals("final answer", message["content"]!!.jsonPrimitive.content)
        assertTrue(message["reasoning_content"]!!.jsonPrimitive.content.contains("trace truncated"))
        val call = message["tool_calls"]!!.jsonArray.single().jsonObject
        assertEquals("call_1", call["id"]!!.jsonPrimitive.content)
        assertEquals("foo", call["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun shortStructuredJsonIsNotMarkedTruncated() {
        val body =
            """{"id":"x","object":"chat.completion","choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}"""

        val prepared = AgentTraceResponse.prepareForTrace(body, 200_000)

        assertFalse(prepared.truncated)
        assertFalse(prepared.normalized)
        assertTrue(prepared.text.contains("\"content\":\"ok\"") || prepared.text.contains("\"content\": \"ok\""))
    }

    @Test
    fun leavesOrdinaryJsonAlone() {
        val body = """{"id":"x","object":"chat.completion","choices":[]}"""
        assertNull(AgentTraceResponse.normalizeStreaming(body))
    }

    @Test
    fun ignoresMalformedOrEmptyBodies() {
        assertNull(AgentTraceResponse.normalizeStreaming(""))
        assertNull(AgentTraceResponse.normalizeStreaming("not json"))
        assertTrue(AgentTraceResponse.normalizeStreaming("data: [DONE]") == null)
    }
}

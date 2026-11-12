package io.nekohasekai.sfa.conductor

import io.nekohasekai.sfa.agent.AgentMessage
import io.nekohasekai.sfa.agent.ToolCall
import io.nekohasekai.sfa.agent.validateTranscriptPairing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConductorSessionStoreTest {
    @Test
    fun draftSurvivesLeavingAndSessionClear() {
        val store = ConductorSessionStore(MemoryConductorPrefs())
        store.save(listOf(AgentMessage.User("hi")), emptyList(), null)
        assertEquals(listOf(AgentMessage.User("hi")), store.load()?.transcript)
        store.saveDraft("  /probe ")
        assertEquals("  /probe ", store.loadDraft())
        store.saveDraft("x".repeat(ConductorSessionStore.MAX_DRAFT + 1))

        assertEquals("x".repeat(ConductorSessionStore.MAX_DRAFT), store.loadDraft())
        store.clear()
        assertNull(store.load())
        assertEquals("x".repeat(ConductorSessionStore.MAX_DRAFT), store.loadDraft())

        store.saveDraft("")
        assertEquals("", store.loadDraft())
    }

    @Test
    fun savedTranscriptNeverStartsInsideAToolBatch() {
        val history =
            mutableListOf<AgentMessage>(
                AgentMessage.User("turn"),
                AgentMessage.Assistant(null, listOf(ToolCall("c1", "x", "{}"))),
                AgentMessage.Tool("c1", "x", "{}"),
                AgentMessage.Assistant("done"),
            )
        repeat(ConductorSessionStore.MAX_TRANSCRIPT + 2 - history.size) {
            history.add(AgentMessage.User("u$it"))
        }
        assertTrue(validateTranscriptPairing(history))
        assertTrue(history.size > ConductorSessionStore.MAX_TRANSCRIPT)

        val store = ConductorSessionStore(MemoryConductorPrefs())
        store.save(history, emptyList(), null)

        val restored = store.load()?.transcript.orEmpty()
        assertTrue(validateTranscriptPairing(restored))
        assertTrue(restored.first() is AgentMessage.User)
        assertEquals(history, restored)
    }

    private class MemoryConductorPrefs : ConductorPreferenceStore {
        private val values = linkedMapOf<String, String>()

        override fun getString(key: String): String? = values[key]

        override fun putString(
            key: String,
            value: String,
        ) {
            values[key] = value
        }

        override fun remove(key: String) {
            values.remove(key)
        }
    }
}

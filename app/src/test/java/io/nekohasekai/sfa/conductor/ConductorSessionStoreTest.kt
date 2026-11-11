package io.nekohasekai.sfa.conductor

import io.nekohasekai.sfa.agent.AgentMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

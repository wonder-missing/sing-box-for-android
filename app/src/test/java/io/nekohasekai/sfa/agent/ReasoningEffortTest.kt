package io.nekohasekai.sfa.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReasoningEffortTest {
    @Test
    fun defaultAndUnknownLevelsOmitTheField() {
        assertNull(ReasoningEffort.wireValue(null))
        assertNull(ReasoningEffort.wireValue(""))
        assertNull(ReasoningEffort.wireValue(ReasoningEffort.DEFAULT))
        assertNull(ReasoningEffort.wireValue("none"))
        assertNull(ReasoningEffort.wireValue("max"))
        assertNull(ReasoningEffort.wireValue("xhigh"))
        assertEquals(ReasoningEffort.DEFAULT, ReasoningEffort.normalize("nope"))
    }

    @Test
    fun sharedLevelsPassThrough() {
        assertEquals("low", ReasoningEffort.wireValue(ReasoningEffort.LOW))
        assertEquals("medium", ReasoningEffort.wireValue(ReasoningEffort.MEDIUM))
        assertEquals("high", ReasoningEffort.wireValue(ReasoningEffort.HIGH))
    }
}

package io.nekohasekai.sfa.conductor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConductorConfirmationTest {
    @Test
    fun retrySkipsSlashCommandItemsThatArrivedLater() {
        val items =
            listOf(
                ConductorItem.User("failed prompt"),
                ConductorItem.Error("agent", "offline"),
                ConductorItem.User("/mcp use missing"),
                ConductorItem.Notice("mcp", "Unknown tool"),
            )

        assertEquals(0, retryableUserIndex(items))
    }

    @Test
    fun onlyTheCurrentAgentOrCommandRequestMatches() {
        assertTrue(confirmationMatchesCurrent(7, 7, null))
        assertTrue(confirmationMatchesCurrent(7, null, 7))
        assertFalse(confirmationMatchesCurrent(6, 7, null))
        assertFalse(confirmationMatchesCurrent(6, null, 7))
        assertFalse(confirmationMatchesCurrent(7, null, null))
    }
}

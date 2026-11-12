package io.nekohasekai.sfa.conductor

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Date
import java.util.TimeZone

class ConductorExportNamesTest {
    @Test
    fun `save names include a local timestamp`() {
        val at = Date(0)
        val utc = TimeZone.getTimeZone("UTC")

        assertEquals("conductor-conversation-19700101-000000.md", ConductorExportNames.conversation(at, utc))
        assertEquals("conductor-api-trace-19700101-000000.md", ConductorExportNames.apiTrace(at, utc))
    }
}

package io.nekohasekai.sfa.conductor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConductorLocalFileTest {
    @Test
    fun resolvesOnlyConductorResourceLinks() {
        assertEquals(
            "conductor/resources/report.md",
            conductorLocalResourcePath("resources/report.md"),
        )
        assertEquals(
            "conductor/resources/report.md",
            conductorLocalResourcePath("conductor/resources/report.md"),
        )
        assertEquals(
            "conductor/resources/report.md",
            conductorLocalResourcePath("conductor/resources/report.md#summary"),
        )
        assertEquals(
            "conductor/resources/report.md",
            conductorLocalResourcePath("/conductor/resources/report.md"),
        )
        assertEquals(
            "conductor/resources/report.md",
            conductorLocalResourcePath("/resources/report.md"),
        )
        assertEquals(
            "conductor/resources/report.md",
            conductorLocalResourcePath("./conductor/resources/report.md"),
        )
        assertNull(conductorLocalResourcePath("https://example.com/report.md"))
        assertNull(conductorLocalResourcePath("conductor/other.md"))
        assertNull(conductorLocalResourcePath("/data/local/tmp/report.md"))
        assertNull(conductorLocalResourcePath("file:///data/local/tmp/report.md"))
    }
}

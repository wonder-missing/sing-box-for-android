package io.nekohasekai.sfa.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ProfileRevisionTest {
    @Test
    fun `profile revision is a short stable hash`() {
        val first = ProfileRevision.of("{\"outbounds\":[]}")
        assertEquals(12, first.length)
        assertEquals(first, ProfileRevision.of("{\"outbounds\":[]}"))
        assertNotEquals(first, ProfileRevision.of("{\"outbounds\":[1]}"))
    }
}

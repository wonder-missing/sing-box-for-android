package io.nekohasekai.sfa.compose.component

import org.junit.Assert.assertEquals
import org.junit.Test

class HumanBytesTest {
    @Test
    fun `formats upload bytes in human units`() {
        assertEquals("0 B", humanBytes(0))
        assertEquals("1023 B", humanBytes(1023))
        assertEquals("1.0 KB", humanBytes(1024))
        assertEquals("1.5 KB", humanBytes(1536))
        assertEquals("1.0 MB", humanBytes(1024L * 1024L))
        assertEquals("1.5 GB", humanBytes(1536L * 1024L * 1024L))
    }
}

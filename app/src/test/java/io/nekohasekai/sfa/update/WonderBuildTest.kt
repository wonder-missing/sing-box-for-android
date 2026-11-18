package io.nekohasekai.sfa.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WonderBuildTest {
    @Test
    fun `maps legacy decimal builds and the first base-36 labels`() {
        assertEquals(1, WonderBuild.ordinalOf("1"))
        assertEquals(10, WonderBuild.ordinalOf("10"))
        assertEquals(99, WonderBuild.ordinalOf("99"))
        assertEquals(100, WonderBuild.ordinalOf("A0"))
        assertEquals(101, WonderBuild.ordinalOf("A1"))
        assertEquals(109, WonderBuild.ordinalOf("A9"))
        assertEquals(110, WonderBuild.ordinalOf("AA"))
        assertEquals(136, WonderBuild.ordinalOf("B0"))
        assertEquals(1035, WonderBuild.ordinalOf("ZZ"))
    }

    @Test
    fun `encodes ordinals back to the same labels`() {
        assertEquals("99", WonderBuild.codeOf(99))
        assertEquals("A0", WonderBuild.codeOf(100))
        assertEquals("A1", WonderBuild.codeOf(101))
        assertEquals("AA", WonderBuild.codeOf(110))
        assertEquals("B0", WonderBuild.codeOf(136))
        assertEquals("ZZ", WonderBuild.codeOf(1035))
    }

    @Test
    fun `rejects ambiguous or out-of-range labels`() {
        assertNull(WonderBuild.ordinalOf(""))
        assertNull(WonderBuild.ordinalOf("0"))
        assertNull(WonderBuild.ordinalOf("100"))
        assertNull(WonderBuild.ordinalOf("A"))
        assertNull(WonderBuild.ordinalOf("A1B"))
        assertNull(WonderBuild.codeOf(0))
        assertNull(WonderBuild.codeOf(1036))
    }
}

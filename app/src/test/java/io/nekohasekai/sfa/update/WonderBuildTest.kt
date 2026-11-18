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
        assertEquals(100, WonderBuild.ordinalOf("A1"))
        assertEquals(101, WonderBuild.ordinalOf("A2"))
        assertEquals(109, WonderBuild.ordinalOf("AA"))
        assertEquals(135, WonderBuild.ordinalOf("B0"))
        assertEquals(1034, WonderBuild.ordinalOf("ZZ"))
    }

    @Test
    fun `encodes ordinals back to the same labels`() {
        assertEquals("99", WonderBuild.codeOf(99))
        assertEquals("A1", WonderBuild.codeOf(100))
        assertEquals("A2", WonderBuild.codeOf(101))
        assertEquals("AA", WonderBuild.codeOf(109))
        assertEquals("B0", WonderBuild.codeOf(135))
        assertEquals("ZZ", WonderBuild.codeOf(1034))
    }

    @Test
    fun `rejects ambiguous or out-of-range labels`() {
        assertNull(WonderBuild.ordinalOf(""))
        assertNull(WonderBuild.ordinalOf("0"))
        assertNull(WonderBuild.ordinalOf("100"))
        assertNull(WonderBuild.ordinalOf("A0"))
        assertNull(WonderBuild.ordinalOf("A"))
        assertNull(WonderBuild.ordinalOf("A1B"))
        assertNull(WonderBuild.codeOf(0))
        assertNull(WonderBuild.codeOf(1035))
    }
}

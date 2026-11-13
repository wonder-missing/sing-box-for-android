package io.nekohasekai.sfa.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JankWindowTest {
    @Test
    fun emitsOnlyForSevereStallsOrEnoughJankyFrames() {
        val window = JankWindow()

        repeat(4) { window.addGap(40) }
        assertFalse(window.shouldEmit())

        window.addGap(40)
        assertTrue(window.shouldEmit())
        assertEquals(5, window.fields()["frames"])
        assertEquals(5, window.fields()["jank32"])
        assertEquals(0, window.fields()["severe200"])
    }

    @Test
    fun oneSevereStallIsEnoughAndMaxTracksLargestGap() {
        val window = JankWindow()

        window.addGap(16)
        window.addGap(250)

        assertTrue(window.shouldEmit())
        assertEquals(250L, window.fields()["max_ms"])
        assertEquals(1, window.fields()["severe200"])
    }

    @Test
    fun resetClearsTheWindow() {
        val window = JankWindow()
        window.addGap(300)
        window.reset()

        assertFalse(window.shouldEmit())
        assertEquals(0, window.fields()["frames"])
    }
}

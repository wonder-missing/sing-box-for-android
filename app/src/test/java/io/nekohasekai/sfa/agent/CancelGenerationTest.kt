package io.nekohasekai.sfa.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CancelGenerationTest {
    @Test
    fun cancelIsScopedToTheStreamGeneration() {
        val gate = CancelGeneration()
        val first = gate.current()
        assertFalse(gate.isCancelled(first))

        gate.cancel()
        assertTrue(gate.isCancelled(first))

        gate.reset()
        val second = gate.current()
        assertFalse(gate.isCancelled(second))
        assertTrue(gate.isCancelled(first))
    }

    @Test
    fun cancelBeforeTheStreamCapturesItsTokenIsNotLost() {
        val gate = CancelGeneration()
        gate.reset()
        gate.cancel()
        val capturedAfterCancel = gate.current()

        assertTrue(gate.isCancelled(capturedAfterCancel))
    }
}

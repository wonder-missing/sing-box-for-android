package io.nekohasekai.sfa.compose.screen.profileoverride

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DexScanBudgetTest {
    @Test
    fun `small dex needs fixed headroom`() {
        val required = DexScanBudget.requiredHeapFor(1L * 1024 * 1024)
        assertTrue(required >= 20L * 1024 * 1024)
    }

    @Test
    fun `large dex is skipped when the heap is tight`() {
        val dex = 8_761_152L
        assertFalse(DexScanBudget.canParse(dex, 16L * 1024 * 1024))
        assertTrue(DexScanBudget.canParse(dex, 64L * 1024 * 1024))
    }

    @Test
    fun `required heap grows with dex size`() {
        assertTrue(DexScanBudget.requiredHeapFor(2L * 1024 * 1024) > DexScanBudget.requiredHeapFor(1L * 1024 * 1024))
    }
}

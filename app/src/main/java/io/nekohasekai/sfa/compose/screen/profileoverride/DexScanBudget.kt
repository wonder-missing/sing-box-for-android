package io.nekohasekai.sfa.compose.screen.profileoverride

/**
 * dexlib2 materialises a dex while reading it. Keep a fixed headroom on top of
 * the file size so a scan cannot allocate the last free megabytes of the heap.
 */
internal object DexScanBudget {
    private const val HEADROOM_BYTES = 16L * 1024L * 1024L
    private const val MULTIPLIER = 4L

    fun requiredHeapFor(dexBytes: Long): Long = dexBytes * MULTIPLIER + HEADROOM_BYTES

    fun canParse(dexBytes: Long, availableHeap: Long): Boolean =
        requiredHeapFor(dexBytes) <= availableHeap
}

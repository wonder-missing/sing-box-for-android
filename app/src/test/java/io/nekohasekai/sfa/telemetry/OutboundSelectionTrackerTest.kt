package io.nekohasekai.sfa.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OutboundSelectionTrackerTest {
    private fun snapshot(
        selectorSelected: String = "auto",
        urltestSelected: String = "node-a",
        urltestDelay: Int? = 120,
        urltestAvailable: Boolean? = true,
        urltestAvailableCount: Int = 2,
        urltestFastest: String? = "node-a",
        urltestFastestDelay: Int? = 120,
    ): List<OutboundGroupSelection> = listOf(
        OutboundGroupSelection(
            tag = "proxy",
            type = "selector",
            selectable = true,
            selected = selectorSelected,
            itemTags = listOf("auto", "node-c"),
        ),
        OutboundGroupSelection(
            tag = "auto",
            type = "urltest",
            selectable = false,
            selected = urltestSelected,
            selectedDelayMs = urltestDelay,
            itemTags = listOf("node-a", "node-b"),
            itemCount = 2,
            availableCount = urltestAvailableCount,
            fastestTag = urltestFastest,
            fastestDelayMs = urltestFastestDelay,
            selectedAvailable = urltestAvailable,
        ),
    )

    @Test
    fun firstSnapshotReportsStartForRootAndNestedGroups() {
        val tracker = OutboundSelectionTracker()

        val changes = tracker.update(snapshot()).changes

        assertEquals(2, changes.size)
        val root = changes[0]
        assertEquals("proxy", root.group)
        assertNull(root.from)
        assertEquals("auto", root.to)
        assertEquals(SwitchReason.START, root.reason)
        assertTrue(root.root)

        val nested = changes[1]
        assertEquals("auto", nested.group)
        assertNull(nested.from)
        assertEquals("node-a", nested.to)
        assertEquals(SwitchReason.START, nested.reason)
        assertEquals(false, nested.root)
        assertEquals(120, nested.ms)
    }

    @Test
    fun identicalSnapshotsAreDeduplicated() {
        val tracker = OutboundSelectionTracker()
        tracker.update(snapshot())

        assertTrue(tracker.update(snapshot()).changes.isEmpty())
        assertTrue(tracker.update(snapshot()).changes.isEmpty())
    }

    @Test
    fun urltestSwitchIsReportedAsAutomatic() {
        val tracker = OutboundSelectionTracker()
        tracker.update(snapshot())

        val changes = tracker.update(
            snapshot(
                urltestSelected = "node-b",
                urltestDelay = 90,
                urltestAvailableCount = 2,
                urltestFastest = "node-b",
                urltestFastestDelay = 90,
            ),
        ).changes

        assertEquals(1, changes.size)
        val change = changes.single()
        assertEquals("auto", change.group)
        assertEquals("node-a", change.from)
        assertEquals("node-b", change.to)
        assertEquals(SwitchReason.URLTEST, change.reason)
        assertEquals(90, change.ms)
    }

    @Test
    fun selectorSwitchIsReportedAsUser() {
        val tracker = OutboundSelectionTracker()
        tracker.update(snapshot())

        val changes = tracker.update(snapshot(selectorSelected = "node-c")).changes

        assertEquals(1, changes.size)
        val change = changes.single()
        assertEquals("proxy", change.group)
        assertEquals("auto", change.from)
        assertEquals("node-c", change.to)
        assertEquals(SwitchReason.USER, change.reason)
    }

    @Test
    fun nonSelectableUnknownGroupIsNotGuessed() {
        val tracker = OutboundSelectionTracker()
        tracker.update(
            listOf(
                OutboundGroupSelection(
                    tag = "fallback",
                    type = "fallback",
                    selectable = false,
                    selected = "node-a",
                    itemTags = listOf("node-a", "node-b"),
                ),
            ),
        )

        val changes = tracker.update(
            listOf(
                OutboundGroupSelection(
                    tag = "fallback",
                    type = "fallback",
                    selectable = false,
                    selected = "node-b",
                    itemTags = listOf("node-a", "node-b"),
                ),
            ),
        ).changes

        assertEquals(SwitchReason.UNKNOWN, changes.single().reason)
    }

    @Test
    fun blankSelectionIsIgnoredAndResetSpeaksAgain() {
        val tracker = OutboundSelectionTracker()
        tracker.update(
            listOf(OutboundGroupSelection(tag = "proxy", type = "selector", selectable = true, selected = "")),
        )

        assertEquals(SwitchReason.START, tracker.update(snapshot()).changes.first().reason)

        tracker.reset()
        val afterReset = tracker.update(snapshot()).changes
        assertEquals(SwitchReason.START, afterReset.first().reason)
        assertNull(afterReset.first().from)
    }

    @Test
    fun urltestSelectionCarriesHealthSummary() {
        val tracker = OutboundSelectionTracker()

        val change = tracker.update(snapshot()).changes.last()

        assertEquals(2, change.availableCount)
        assertEquals(2, change.itemCount)
        assertEquals("node-a", change.fastestTag)
        assertEquals(120, change.fastestDelayMs)
    }

    @Test
    fun selectedNodeDownAndUpEmitHealthTransitions() {
        val tracker = OutboundSelectionTracker()
        tracker.update(snapshot())

        val down = tracker.update(
            snapshot(
                urltestDelay = null,
                urltestAvailable = false,
                urltestAvailableCount = 1,
                urltestFastest = "node-b",
                urltestFastestDelay = 90,
            ),
        )
        assertTrue(down.changes.isEmpty())
        val downEvent = down.healthChanges.single()
        assertEquals("auto", downEvent.group)
        assertEquals("node-a", downEvent.outbound)
        assertEquals(false, downEvent.up)
        assertNull(downEvent.delayMs)
        assertEquals(1, downEvent.availableCount)
        assertEquals(2, downEvent.itemCount)
        assertEquals(false, downEvent.root)

        assertTrue(
            tracker.update(
                snapshot(
                    urltestDelay = null,
                    urltestAvailable = false,
                    urltestAvailableCount = 1,
                ),
            ).healthChanges.isEmpty(),
        )

        val up = tracker.update(
            snapshot(
                urltestDelay = 90,
                urltestAvailable = true,
                urltestAvailableCount = 2,
                urltestFastest = "node-a",
                urltestFastestDelay = 90,
            ),
        )
        assertTrue(up.changes.isEmpty())
        val upEvent = up.healthChanges.single()
        assertEquals(true, upEvent.up)
        assertEquals(90, upEvent.delayMs)
    }

    @Test
    fun selectionChangeResetsHealthBaselineWithoutSpuriousDown() {
        val tracker = OutboundSelectionTracker()
        tracker.update(snapshot())

        val switched = tracker.update(
            snapshot(
                urltestSelected = "node-b",
                urltestDelay = null,
                urltestAvailable = false,
                urltestAvailableCount = 1,
            ),
        )
        assertEquals(1, switched.changes.size)
        assertEquals("node-b", switched.changes.single().to)
        assertTrue(switched.healthChanges.isEmpty())

        assertTrue(
            tracker.update(
                snapshot(
                    urltestSelected = "node-b",
                    urltestDelay = null,
                    urltestAvailable = false,
                    urltestAvailableCount = 1,
                ),
            ).healthChanges.isEmpty(),
        )

        val recovered = tracker.update(
            snapshot(
                urltestSelected = "node-b",
                urltestDelay = 80,
                urltestAvailable = true,
                urltestAvailableCount = 2,
                urltestFastest = "node-b",
                urltestFastestDelay = 80,
            ),
        )
        assertEquals("node-b", recovered.healthChanges.single().outbound)
        assertEquals(true, recovered.healthChanges.single().up)
    }

    @Test
    fun nonUrlTestGroupsDoNotEmitHealth() {
        val tracker = OutboundSelectionTracker()
        tracker.update(
            listOf(
                OutboundGroupSelection(
                    tag = "fallback",
                    type = "fallback",
                    selectable = false,
                    selected = "node-a",
                    itemTags = listOf("node-a", "node-b"),
                ),
            ),
        )

        val update = tracker.update(
            listOf(
                OutboundGroupSelection(
                    tag = "fallback",
                    type = "fallback",
                    selectable = false,
                    selected = "node-a",
                    itemTags = listOf("node-a", "node-b"),
                    itemCount = 2,
                    availableCount = 1,
                    selectedAvailable = false,
                ),
            ),
        )

        assertTrue(update.changes.isEmpty())
        assertTrue(update.healthChanges.isEmpty())
    }
}

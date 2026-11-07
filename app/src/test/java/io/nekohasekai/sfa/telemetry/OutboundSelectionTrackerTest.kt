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
        ),
    )

    @Test
    fun firstSnapshotReportsStartForRootAndNestedGroups() {
        val tracker = OutboundSelectionTracker()

        val changes = tracker.update(snapshot())

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

        assertTrue(tracker.update(snapshot()).isEmpty())
        assertTrue(tracker.update(snapshot()).isEmpty())
    }

    @Test
    fun urltestSwitchIsReportedAsAutomatic() {
        val tracker = OutboundSelectionTracker()
        tracker.update(snapshot())

        val changes = tracker.update(snapshot(urltestSelected = "node-b", urltestDelay = 90))

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

        val changes = tracker.update(snapshot(selectorSelected = "node-c"))

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
        )

        assertEquals(SwitchReason.UNKNOWN, changes.single().reason)
    }

    @Test
    fun blankSelectionIsIgnoredAndResetSpeaksAgain() {
        val tracker = OutboundSelectionTracker()
        tracker.update(
            listOf(OutboundGroupSelection(tag = "proxy", type = "selector", selectable = true, selected = "")),
        )

        assertEquals(SwitchReason.START, tracker.update(snapshot()).first().reason)

        tracker.reset()
        val afterReset = tracker.update(snapshot())
        assertEquals(SwitchReason.START, afterReset.first().reason)
        assertNull(afterReset.first().from)
    }
}

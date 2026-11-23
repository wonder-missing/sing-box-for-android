package io.nekohasekai.sfa.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupHealthMonitorTest {
    private fun group(
        selected: String = "a",
        selectedAvailable: Boolean? = true,
        available: Int = 2,
        total: Int = 3,
        summary: Boolean = true,
    ) = OutboundGroupSelection(
        tag = "proxy",
        type = "urltest",
        selectable = false,
        selected = selected,
        itemTags = List(total) { "node-$it" },
        itemCount = total,
        availableCount = available,
        hasItemSummary = summary,
        selectedAvailable = selectedAvailable,
    )

    @Test
    fun `no available node alerts once per episode`() {
        val monitor = GroupHealthMonitor(stuckAfterMs = 60_000, graceMs = 0, noNodesAfterMs = 0)

        val first = monitor.observe(listOf(group(available = 0)), 1_000)
        assertEquals(GroupHealthAlertKind.NO_AVAILABLE_NODES, first.single().kind)
        assertEquals(0, first.single().availableCount)
        assertEquals(3, first.single().itemCount)

        assertTrue(monitor.observe(listOf(group(available = 0)), 2_000).isEmpty())
        assertTrue(monitor.observe(listOf(group(available = 2)), 3_000).isEmpty())

        val again = monitor.observe(listOf(group(available = 0)), 4_000)
        assertEquals(GroupHealthAlertKind.NO_AVAILABLE_NODES, again.single().kind)
    }

    @Test
    fun `stuck on an unhealthy node needs ten minutes`() {
        val monitor = GroupHealthMonitor(stuckAfterMs = 60_000, graceMs = 0, noNodesAfterMs = 0)

        assertTrue(monitor.observe(listOf(group(selectedAvailable = false)), 1_000).isEmpty())
        assertTrue(monitor.observe(listOf(group(selectedAvailable = false)), 30_000).isEmpty())

        val alert = monitor.observe(listOf(group(selectedAvailable = false)), 61_000)
        assertEquals(GroupHealthAlertKind.STUCK_ON_UNHEALTHY, alert.single().kind)
        assertEquals(1_000, alert.single().since)
        assertEquals("a", alert.single().selected)

        assertTrue(monitor.observe(listOf(group(selectedAvailable = false)), 120_000).isEmpty())
    }

    @Test
    fun `selection change restarts the stuck timer`() {
        val monitor = GroupHealthMonitor(stuckAfterMs = 60_000, graceMs = 0, noNodesAfterMs = 0)

        monitor.observe(listOf(group(selectedAvailable = false)), 1_000)
        monitor.observe(listOf(group(selected = "b", selectedAvailable = false)), 20_000)
        assertTrue(monitor.observe(listOf(group(selected = "b", selectedAvailable = false)), 50_000).isEmpty())

        val alert = monitor.observe(listOf(group(selected = "b", selectedAvailable = false)), 80_000)
        assertEquals(GroupHealthAlertKind.STUCK_ON_UNHEALTHY, alert.single().kind)
        assertEquals(20_000, alert.single().since)
    }

    @Test
    fun `no-node episode does not also report stuck`() {
        val monitor = GroupHealthMonitor(stuckAfterMs = 60_000, graceMs = 0, noNodesAfterMs = 0)

        val alert =
            monitor.observe(
                listOf(group(available = 0, selectedAvailable = false)),
                1_000,
            )
        assertEquals(GroupHealthAlertKind.NO_AVAILABLE_NODES, alert.single().kind)

        assertTrue(
            monitor.observe(
                listOf(group(available = 0, selectedAvailable = false)),
                100_000,
            ).isEmpty(),
        )
    }

    @Test
    fun `summary-less snapshots keep the no-node state`() {
        val monitor = GroupHealthMonitor(stuckAfterMs = 60_000, graceMs = 0, noNodesAfterMs = 0)

        monitor.observe(listOf(group(available = 0)), 1_000)
        assertTrue(
            monitor.observe(
                listOf(group(available = 0, summary = false, selectedAvailable = false)),
                100_000,
            ).isEmpty(),
        )
        assertTrue(monitor.observe(listOf(group(available = 2)), 101_000).isEmpty())
    }

    @Test
    fun `startup snapshots get a grace period before no-node alerts`() {
        val monitor = GroupHealthMonitor(stuckAfterMs = 60_000, graceMs = 30_000, noNodesAfterMs = 0)

        assertTrue(monitor.observe(listOf(group(available = 0)), 1_000).isEmpty())
        assertTrue(monitor.observe(listOf(group(available = 0)), 20_000).isEmpty())

        val alert = monitor.observe(listOf(group(available = 0)), 31_000)
        assertEquals(GroupHealthAlertKind.NO_AVAILABLE_NODES, alert.single().kind)
        assertEquals(31_000, alert.single().since)
    }

    @Test
    fun `zero available must survive a confirmation window`() {
        val monitor = GroupHealthMonitor(stuckAfterMs = 60_000, graceMs = 0, noNodesAfterMs = 60_000)

        assertTrue(monitor.observe(listOf(group(available = 0)), 1_000).isEmpty())
        assertTrue(monitor.observe(listOf(group(available = 0)), 30_000).isEmpty())

        val alert = monitor.observe(listOf(group(available = 0)), 61_000)
        assertEquals(GroupHealthAlertKind.NO_AVAILABLE_NODES, alert.single().kind)
        assertEquals(1_000, alert.single().since)

        assertTrue(monitor.observe(listOf(group(available = 2)), 70_000).isEmpty())
        assertTrue(monitor.observe(listOf(group(available = 0)), 80_000).isEmpty())
        val again = monitor.observe(listOf(group(available = 0)), 141_000)
        assertEquals(80_000, again.single().since)
    }

    @Test
    fun `non urltest groups are ignored`() {
        val monitor = GroupHealthMonitor(stuckAfterMs = 60_000, graceMs = 0, noNodesAfterMs = 0)
        val selector =
            OutboundGroupSelection(
                tag = "manual",
                type = "selector",
                selectable = true,
                selected = "a",
                itemTags = listOf("a", "b"),
                selectedAvailable = null,
            )

        assertTrue(monitor.observe(listOf(selector), 1_000).isEmpty())
        assertTrue(monitor.observe(listOf(selector), 100_000).isEmpty())
    }
}

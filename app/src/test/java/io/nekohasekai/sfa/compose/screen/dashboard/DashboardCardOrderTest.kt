package io.nekohasekai.sfa.compose.screen.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardCardOrderTest {
    @Test
    fun defaultPlacesVfsAboveActivity() {
        val vfs = DefaultDashboardCardOrder.indexOf(CardGroup.Vfs)
        val activity = DefaultDashboardCardOrder.indexOf(CardGroup.Activity)
        assertTrue(vfs >= 0)
        assertTrue(activity >= 0)
        assertTrue(vfs < activity)
    }

    @Test
    fun untouchedLegacyOrderMovesToNewDefault() {
        assertEquals(DefaultDashboardCardOrder, migrateDashboardCardOrder(LegacyDashboardCardOrder))
    }

    @Test
    fun customOrderIsPreserved() {
        val custom =
            listOf(
                CardGroup.ClashMode,
                CardGroup.Profiles,
                CardGroup.Logs,
                CardGroup.Vfs,
                CardGroup.Activity,
            )
        assertEquals(custom, migrateDashboardCardOrder(custom))
    }
}

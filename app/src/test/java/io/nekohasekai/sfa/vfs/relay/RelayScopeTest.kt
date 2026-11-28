package io.nekohasekai.sfa.vfs.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayScopeTest {
    @Test
    fun proxyGroupsAreHiddenButOldWiresStillParse() {
        val groups = RelayScope.APP_DATA_GROUPS
        assertFalse(groups.available)
        assertFalse(RelayScope.fixed.contains(groups))
        assertFalse(RelayScope.recommended().contains(groups))
        assertFalse(RelayScope.everything().contains(groups))
        assertEquals(groups, RelayScope.fromWire("app.data.groups"))
        assertTrue(RelayScope.fixed.isNotEmpty())
    }
}

package io.nekohasekai.sfa.vfs

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VfsSharingPolicyTest {
    @Test
    fun `service runs for connecting connected and retrying sessions`() {
        assertTrue(VfsSharingPolicy.shouldRun(VfsConnectionState.Connecting, false))
        assertTrue(
            VfsSharingPolicy.shouldRun(
                VfsConnectionState.Connected("https://example.com", setOf(VfsScope.CONDUCTOR), 1L),
                false,
            ),
        )
        assertTrue(
            VfsSharingPolicy.shouldRun(
                VfsConnectionState.Backoff(1, 1_000L, "network"),
                false,
            ),
        )
    }

    @Test
    fun `service stops when idle and stops after a terminal error without a session`() {
        assertFalse(VfsSharingPolicy.shouldRun(VfsConnectionState.Idle, false))
        assertFalse(
            VfsSharingPolicy.shouldRun(
                VfsConnectionState.Error("VFS_UNAUTHORIZED", "token rotated"),
                false,
            ),
        )
    }

    @Test
    fun `a session that outlives an error keeps the notification`() {
        assertTrue(
            VfsSharingPolicy.shouldRun(
                VfsConnectionState.Error("VFS_CONFIRMATION_TIMEOUT", "no answer"),
                true,
            ),
        )
        assertTrue(
            VfsSharingPolicy.shouldRun(
                VfsConnectionState.Confirming(
                    VfsLink("https://example.com", "link_1", setOf(VfsScope.TRACE)),
                    "Portal",
                ),
                true,
            ),
        )
    }
}

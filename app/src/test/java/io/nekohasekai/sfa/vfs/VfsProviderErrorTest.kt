package io.nekohasekai.sfa.vfs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VfsProviderErrorTest {
    @Test
    fun conflictDistinguishesAlreadyConnectedFromRebind() {
        assertEquals(
            "VFS_ALREADY_CONNECTED",
            mapProviderFailure(409, "", "conflict").code,
        )
        assertEquals(
            "VFS_DEVICE_REBIND_REQUIRED",
            mapProviderFailure(409, """{"error":"VFS_DEVICE_REBIND_REQUIRED"}""", "conflict").code,
        )
    }

    @Test
    fun httpStatusMapsToThePublicErrorTable() {
        assertEquals("VFS_UNAUTHORIZED", mapProviderFailure(401, null, null).code)
        assertEquals("VFS_DEVICE_MISMATCH", mapProviderFailure(403, null, null).code)
        assertEquals("VFS_LINK_INVALID", mapProviderFailure(404, null, null).code)
        assertEquals("VFS_LINK_EXPIRED", mapProviderFailure(410, null, null).code)
        assertEquals("VFS_CONNECT_FAILED", mapProviderFailure(503, null, null).code)
    }

    @Test
    fun closeCodesSeparateTerminalFromBackoff() {
        assertTrue(VfsCloseCodes.isTerminal(VfsCloseCodes.REVOKED))
        assertTrue(VfsCloseCodes.isTerminal(VfsCloseCodes.LINK_EXPIRED))
        assertTrue(VfsCloseCodes.isTerminal(VfsCloseCodes.UNAUTHORIZED))
        assertTrue(VfsCloseCodes.isTerminal(VfsCloseCodes.DEVICE_MISMATCH))
        assertTrue(VfsCloseCodes.isTerminal(VfsCloseCodes.REBIND_REQUIRED))
        assertTrue(VfsCloseCodes.isBackoff(VfsCloseCodes.HEARTBEAT_TIMEOUT))
        assertTrue(VfsCloseCodes.isBackoff(VfsCloseCodes.VFSD_RESTART))
        assertTrue(VfsCloseCodes.isBackoff(VfsCloseCodes.RATE_LIMITED))
        assertFalse(VfsCloseCodes.isTerminal(VfsCloseCodes.REPLACED))
        assertFalse(VfsCloseCodes.isBackoff(VfsCloseCodes.REPLACED))
    }
}

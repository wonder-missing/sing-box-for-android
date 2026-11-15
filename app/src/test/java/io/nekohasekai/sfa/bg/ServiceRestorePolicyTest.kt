package io.nekohasekai.sfa.bg

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceRestorePolicyTest {
    @Test
    fun aPendingUpdateRestoreStartsEvenAfterTheUpdaterClearedStartedByUser() {
        assertTrue(ServiceRestorePolicy.shouldStart(startedByUser = false, restoreAfterUpdate = true))
    }

    @Test
    fun aNormalBootStartsTheServiceTheUserLeftRunning() {
        assertTrue(ServiceRestorePolicy.shouldStart(startedByUser = true, restoreAfterUpdate = false))
    }

    @Test
    fun neitherReasonDoesNotStartTheService() {
        assertFalse(ServiceRestorePolicy.shouldStart(startedByUser = false, restoreAfterUpdate = false))
    }
}

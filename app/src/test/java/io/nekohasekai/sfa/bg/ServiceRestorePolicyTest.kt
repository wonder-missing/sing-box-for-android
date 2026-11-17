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

    @Test
    fun anOldUnreadCrashDoesNotBlockTheNextUpdate() {
        assertFalse(
            ServiceRestorePolicy.shouldBlockForCrash(
                unreadVersions = listOf(CrashVersion(11404088, "1.15.0-alpha.13-wonder.88")),
                currentCode = 11404092,
                currentName = "1.15.0-alpha.13-wonder.92",
            ),
        )
    }

    @Test
    fun anUnreadCrashFromTheCurrentBuildBlocksRestore() {
        assertTrue(
            ServiceRestorePolicy.shouldBlockForCrash(
                unreadVersions = listOf(CrashVersion(11404092, "1.15.0-alpha.13-wonder.92")),
                currentCode = 11404092,
                currentName = "1.15.0-alpha.13-wonder.92",
            ),
        )
    }

    @Test
    fun aMatchingNameAloneStillBlocksRestore() {
        assertTrue(
            ServiceRestorePolicy.shouldBlockForCrash(
                unreadVersions = listOf(CrashVersion(null, "1.15.0-alpha.13-wonder.92")),
                currentCode = 11404092,
                currentName = "1.15.0-alpha.13-wonder.92",
            ),
        )
    }
}

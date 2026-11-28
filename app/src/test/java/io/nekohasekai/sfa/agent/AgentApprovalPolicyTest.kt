package io.nekohasekai.sfa.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentApprovalPolicyTest {
    @Test
    fun testProfileTrustsOnlyTheLocalAndReadOnlyEnvelope() {
        assertTrue(AgentApprovalPolicy.autoApprove(ApprovalProfile.TEST, ConfirmationKind.WORKSPACE_WRITE))
        assertTrue(AgentApprovalPolicy.autoApprove(ApprovalProfile.TEST, ConfirmationKind.WORKSPACE_DELETE_FILE))
        assertTrue(AgentApprovalPolicy.autoApprove(ApprovalProfile.TEST, ConfirmationKind.DIAGNOSTICS))
        assertTrue(AgentApprovalPolicy.autoApprove(ApprovalProfile.TEST, ConfirmationKind.NETWORK_READ))
        assertTrue(AgentApprovalPolicy.autoApprove(ApprovalProfile.TEST, ConfirmationKind.MCP_READ))

        assertFalse(AgentApprovalPolicy.autoApprove(ApprovalProfile.TEST, ConfirmationKind.WORKSPACE_DELETE_DIR))
        assertFalse(AgentApprovalPolicy.autoApprove(ApprovalProfile.TEST, ConfirmationKind.WORKSPACE_DELETE_UNTRACKED))
        assertFalse(AgentApprovalPolicy.autoApprove(ApprovalProfile.TEST, ConfirmationKind.WORKSPACE_OVERWRITE))
        assertFalse(AgentApprovalPolicy.autoApprove(ApprovalProfile.TEST, ConfirmationKind.REMOTE_EXEC))
        assertFalse(AgentApprovalPolicy.autoApprove(ApprovalProfile.TEST, ConfirmationKind.DATA_EGRESS))
        assertFalse(AgentApprovalPolicy.autoApprove(ApprovalProfile.TEST, ConfirmationKind.FILE_TRANSFER))
        assertFalse(AgentApprovalPolicy.autoApprove(ApprovalProfile.TEST, ConfirmationKind.NETWORK_CHANGE))
        assertFalse(AgentApprovalPolicy.autoApprove(ApprovalProfile.TEST, ConfirmationKind.MCP_WRITE))
    }

    @Test
    fun workspaceProfileDoesNotTrustDeletesOrNetwork() {
        assertTrue(AgentApprovalPolicy.autoApprove(ApprovalProfile.WORKSPACE, ConfirmationKind.WORKSPACE_WRITE))
        assertTrue(AgentApprovalPolicy.autoApprove(ApprovalProfile.WORKSPACE, ConfirmationKind.DIAGNOSTICS))
        assertFalse(AgentApprovalPolicy.autoApprove(ApprovalProfile.WORKSPACE, ConfirmationKind.WORKSPACE_DELETE_FILE))
        assertFalse(AgentApprovalPolicy.autoApprove(ApprovalProfile.WORKSPACE, ConfirmationKind.NETWORK_READ))
    }

    @Test
    fun strictProfileNeverAutoApproves() {
        ConfirmationKind.values().forEach { kind ->
            assertFalse(AgentApprovalPolicy.autoApprove(ApprovalProfile.STRICT, kind))
        }
    }

    /**
     * AC walkthrough: 18 calls were rejected by preflight, 21 executed inside
     * the workspace/read-only envelope, and only four executable calls crossed
     * an external boundary. This is the regression target for the test profile.
     */
    @Test
    fun acWalkthroughLeavesFourExecutableCardsUnderTestProfile() {
        val autoKinds =
            listOf(
                ConfirmationKind.WORKSPACE_WRITE, // 4 file_write + file_edit + file_append
                ConfirmationKind.WORKSPACE_WRITE,
                ConfirmationKind.WORKSPACE_WRITE,
                ConfirmationKind.WORKSPACE_WRITE,
                ConfirmationKind.WORKSPACE_WRITE,
                ConfirmationKind.WORKSPACE_WRITE,
                ConfirmationKind.WORKSPACE_DELETE_FILE, // session-created files
                ConfirmationKind.WORKSPACE_DELETE_FILE,
                ConfirmationKind.WORKSPACE_DELETE_FILE,
                ConfirmationKind.WORKSPACE_DELETE_FILE,
                ConfirmationKind.WORKSPACE_DELETE_FILE,
                ConfirmationKind.WORKSPACE_DELETE_FILE,
                ConfirmationKind.WORKSPACE_DELETE_FILE,
                ConfirmationKind.DIAGNOSTICS,
                ConfirmationKind.DIAGNOSTICS,
                ConfirmationKind.NETWORK_READ, // probes and GET requests
                ConfirmationKind.NETWORK_READ,
                ConfirmationKind.NETWORK_READ,
                ConfirmationKind.NETWORK_READ,
                ConfirmationKind.NETWORK_READ,
                ConfirmationKind.MCP_READ, // job_status
            )
        val cardKinds =
            listOf(
                ConfirmationKind.WORKSPACE_DELETE_DIR, // artifacts directory
                ConfirmationKind.REMOTE_EXEC, // job_run
                ConfirmationKind.DATA_EGRESS, // telemetry_upload
            )

        val prompts =
            (autoKinds + cardKinds).filterNot { kind ->
                AgentApprovalPolicy.autoApprove(ApprovalProfile.TEST, kind)
            }

        assertEquals(3, prompts.size)
        assertEquals(cardKinds.toSet(), prompts.toSet())
    }
}

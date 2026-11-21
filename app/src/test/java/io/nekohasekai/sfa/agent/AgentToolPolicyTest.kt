package io.nekohasekai.sfa.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolPolicyTest {
    private fun definition(
        name: String,
        permission: AgentPermission,
        policy: ConfirmationPolicy,
        category: String = "files",
    ): AgentToolDefinition =
        AgentToolDefinition(
            name = name,
            description = name,
            permission = permission,
            parameters = emptyList(),
            confirmationPolicy = policy,
            category = category,
        )

    @Test
    fun writesDeletesAndEgressAreNeverBulkApproved() {
        assertFalse(toolAllowsBulkApproval(definition("file_write", AgentPermission.WRITE_FILES, ConfirmationPolicy.AGENT_ONLY)))
        assertFalse(toolAllowsBulkApproval(definition("file_edit", AgentPermission.WRITE_FILES, ConfirmationPolicy.AGENT_ONLY)))
        assertFalse(toolAllowsBulkApproval(definition("file_delete", AgentPermission.WRITE_FILES, ConfirmationPolicy.AGENT_ONLY)))
        assertFalse(toolAllowsBulkApproval(definition("http_fetch", AgentPermission.DATA_EGRESS, ConfirmationPolicy.AGENT_ONLY)))
        assertFalse(toolAllowsBulkApproval(definition("analyze_image", AgentPermission.DATA_EGRESS, ConfirmationPolicy.NEVER)))
        assertFalse(toolAllowsBulkApproval(definition("mcp_tool", AgentPermission.READ_NETWORK_STATE, ConfirmationPolicy.AGENT_ONLY, "mcp")))
    }

    @Test
    fun readOnlyLocalToolsCanStillBeBulkApproved() {
        assertTrue(toolAllowsBulkApproval(definition("file_read", AgentPermission.READ_FILES, ConfirmationPolicy.AGENT_ONLY)))
    }
}

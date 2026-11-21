package io.nekohasekai.sfa.agent

/**
 * Batch/turn approval is only for read-only, locally scoped calls. Writes,
 * data egress and transfers always keep their individual confirmation.
 */
internal fun toolAllowsBulkApproval(definition: AgentToolDefinition): Boolean {
    if (definition.category == "mcp") return false
    if (definition.name == "file_delete") return false
    if (
        definition.permission == AgentPermission.WRITE_FILES ||
        definition.permission == AgentPermission.DATA_EGRESS ||
        definition.permission == AgentPermission.FILE_TRANSFER ||
        definition.permission == AgentPermission.NETWORK_CHANGE
    ) {
        return false
    }
    return definition.effectiveConfirmationPolicy == ConfirmationPolicy.AGENT_ONLY
}

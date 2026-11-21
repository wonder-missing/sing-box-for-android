package io.nekohasekai.sfa.agent

/**
 * Whether a tool's confirmation may be reused for a batch or a whole turn.
 * The final word is on [AgentConfirmation.bulkApproval] because some tools
 * (file_delete, http_fetch) classify by arguments. This helper is for catalog
 * and batch summaries that only have a definition.
 */
internal fun toolAllowsBulkApproval(definition: AgentToolDefinition): Boolean {
    if (definition.bulkApproval != BulkApproval.NONE) return true
    // Keep the old safe-read-only path for tools that have not opted into an
    // explicit scope yet.
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

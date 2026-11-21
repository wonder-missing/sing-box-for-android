package io.nekohasekai.sfa.agent

/**
 * Confirmation policy for a Conductor session.
 *
 * Preflight removes calls that cannot run. This object decides which remaining
 * calls cross the user's selected trust boundary and which may reuse a
 * turn/batch approval.
 */
internal object AgentApprovalPolicy {
    /**
     * Auto-approval is a session profile, not a per-call heuristic. A profile
     * only skips cards inside its explicit envelope; every external boundary
     * still asks.
     */
    fun autoApprove(
        profile: ApprovalProfile,
        kind: ConfirmationKind,
    ): Boolean =
        when (profile) {
            ApprovalProfile.STRICT -> false
            ApprovalProfile.WORKSPACE ->
                kind == ConfirmationKind.WORKSPACE_WRITE ||
                    kind == ConfirmationKind.DIAGNOSTICS
            ApprovalProfile.TEST ->
                kind == ConfirmationKind.WORKSPACE_WRITE ||
                    kind == ConfirmationKind.WORKSPACE_DELETE_FILE ||
                    kind == ConfirmationKind.DIAGNOSTICS ||
                    kind == ConfirmationKind.NETWORK_READ ||
                    kind == ConfirmationKind.MCP_READ
        }

    /** The scope key used by the UI's "run all in this turn" shortcut. */
    fun defaultScope(
        kind: ConfirmationKind,
        tool: String,
    ): String =
        when (kind) {
            ConfirmationKind.WORKSPACE_WRITE -> "workspace_write"
            ConfirmationKind.WORKSPACE_OVERWRITE -> "workspace_overwrite"
            ConfirmationKind.WORKSPACE_DELETE_FILE,
            ConfirmationKind.WORKSPACE_DELETE_DIR,
            ConfirmationKind.WORKSPACE_DELETE_UNTRACKED,
            -> "workspace_delete"
            ConfirmationKind.DIAGNOSTICS -> "diagnostics"
            ConfirmationKind.NETWORK_READ -> "network_read"
            ConfirmationKind.MCP_READ -> "mcp_read"
            ConfirmationKind.REMOTE_EXEC -> "remote_exec"
            else -> tool
        }
}

package io.nekohasekai.sfa.vfs

/**
 * The user talks about the workspace, not about scope names. A whole-workspace
 * request expands to every VFS root for that turn; a named subset stays narrow.
 */
private val WHOLE_WORKSPACE =
    Regex(
        """(?i)整个(?:工作)?目录|全部(?:工作)?目录|整个(?:工作)?区|所有文件|整个\s*workspace|whole\s+workspace|all\s+(?:files|work(?:ing)?\s+directory)""",
    )

internal fun isWholeWorkspaceRequest(text: String): Boolean =
    WHOLE_WORKSPACE.containsMatchIn(text)

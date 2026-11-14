package io.nekohasekai.sfa.vfs

/**
 * The user talks about the workspace, not about scope names. A whole-workspace
 * request expands to every VFS root for that turn; a named subset stays narrow.
 * The mount verb without a named subset also defaults to the full workspace,
 * so "把工作目录挂上" and "mount it" both reach all roots.
 */
private val WHOLE =
    Regex(
        """(?i)整个|全部|所有|whole|entire|workspace|working\s+directory|工作目录|工作区""",
    )
private val MOUNT =
    Regex("""(?i)挂|mount|vfs|virtual\s+filesystem|连接|link|portal""")
private val NAMED_SCOPE =
    Regex("""(?i)trace|conductor|conversation|system\s*prompt|对话|提示词""")

internal fun isWholeWorkspaceRequest(text: String): Boolean {
    if (WHOLE.containsMatchIn(text)) return true
    if (NAMED_SCOPE.containsMatchIn(text)) return false
    return MOUNT.containsMatchIn(text)
}

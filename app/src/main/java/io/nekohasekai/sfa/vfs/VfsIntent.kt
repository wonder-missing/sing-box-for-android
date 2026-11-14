package io.nekohasekai.sfa.vfs

/**
 * The user talks about the workspace, not about scope names. A whole-workspace
 * request expands to every VFS root for that turn; a named subset stays narrow.
 * The mount verb without a named subset also defaults to the full workspace,
 * so "把工作目录挂上" and "mount it" both reach all roots.
 */
private val TOTAL_WORKSPACE =
    Regex(
        """(?i)整个(?:工作)?目录|整个(?:工作)?区|整个\s*workspace|whole\s+workspace|entire\s+workspace|whole\s+working\s+directory|全部(?:工作)?目录|全部工作区|所有文件|全部文件|all\s+files""",
    )
private val WORKSPACE_WORD =
    Regex("""(?i)workspace|working\s+directory|工作目录|工作区""")
private val NAMED_SCOPE =
    Regex("""(?i)trace|conductor|conversation|system\s*prompt|对话|提示词""")
private val EXPLICIT_NARROW =
    Regex("""(?i)只(?:挂(?!了)|要|需|读|看|取|用|想)|仅(?:挂|要|需|读|看|取|用)|只要|only|just\s+(?:mount|read|the)""")
private val MOUNT =
    Regex("""(?i)挂|mount|vfs|virtual\s+filesystem|连接|connect""")

internal fun isWholeWorkspaceRequest(text: String): Boolean {
    if (NAMED_SCOPE.containsMatchIn(text)) {
        // "整个工作目录里的 trace" still names the whole workspace; "把工作目录里的
        // trace 挂上" and "所有 trace 都挂上" are subsets. A "only/只挂" marker
        // always wins, except the past-tense complaint "只挂了 Conductor".
        return TOTAL_WORKSPACE.containsMatchIn(text) && !EXPLICIT_NARROW.containsMatchIn(text)
    }
    if (WORKSPACE_WORD.containsMatchIn(text)) return true
    return MOUNT.containsMatchIn(text)
}

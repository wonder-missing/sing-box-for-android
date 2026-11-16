package io.nekohasekai.sfa.vfs

/**
 * Client-side presets for natural-language requests. Portal treats every scope
 * name as opaque; the app expands these names into concrete roots when parsing
 * a Link, so trust and consent always record the exact roots.
 */
object VfsScopeAliases {
    const val ALL = "all"
    const val CONDUCTOR_SESSION = "conductor_session"

    private val aliases: Map<String, Set<VfsScope>> =
        mapOf(
            // workdir already contains conductor/.
            ALL to
                setOf(
                    VfsScope.WORKDIR,
                    VfsScope.APPFILES,
                    VfsScope.TRACE,
                    VfsScope.SYSTEM_PROMPT,
                    VfsScope.CONVERSATION,
                ),
            CONDUCTOR_SESSION to
                setOf(
                    VfsScope.CONDUCTOR,
                    VfsScope.TRACE,
                    VfsScope.SYSTEM_PROMPT,
                    VfsScope.CONVERSATION,
                ),
        )

    val names: List<String> = aliases.keys.toList()

    fun expand(name: String): Set<VfsScope>? = aliases[name]
}

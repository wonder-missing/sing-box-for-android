package io.nekohasekai.sfa.compose.component

import android.content.Context
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.vfs.VfsScope

/** Localized, human-readable scope label for non-Compose settings surfaces. */
fun vfsScopeLabel(
    context: Context,
    scope: VfsScope,
): String =
    context.getString(
        when (scope) {
            VfsScope.CONDUCTOR -> R.string.vfs_scope_conductor
            VfsScope.TRACE -> R.string.vfs_scope_trace
            VfsScope.SYSTEM_PROMPT -> R.string.vfs_scope_system_prompt
            VfsScope.CONVERSATION -> R.string.vfs_scope_conversation
            VfsScope.WORKDIR -> R.string.vfs_scope_workdir
            VfsScope.APPFILES -> R.string.vfs_scope_appfiles
        },
    )

/** Scope chips the sharing card shows before collapsing the rest into "+N". */
internal const val VFS_SCOPE_CHIP_LIMIT = 4

internal data class VfsScopeChips(
    val visible: List<VfsScope>,
    val hiddenCount: Int,
)

/**
 * Scope chips in the canonical consent order, capped at [limit] so the
 * floating card keeps one compact row on narrow screens.
 */
internal fun vfsScopeChips(
    scope: Set<VfsScope>,
    limit: Int = VFS_SCOPE_CHIP_LIMIT,
): VfsScopeChips {
    val ordered = VfsScope.entries.filter { it in scope }
    return VfsScopeChips(
        visible = ordered.take(limit),
        hiddenCount = (ordered.size - limit).coerceAtLeast(0),
    )
}

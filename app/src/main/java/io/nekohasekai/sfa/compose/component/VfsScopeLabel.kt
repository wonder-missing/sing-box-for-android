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
        },
    )

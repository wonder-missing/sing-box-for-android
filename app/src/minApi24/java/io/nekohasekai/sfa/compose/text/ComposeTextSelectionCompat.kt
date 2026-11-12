package io.nekohasekai.sfa.compose.text

import androidx.compose.foundation.ComposeFoundationFlags
import androidx.compose.foundation.ExperimentalFoundationApi

/**
 * Compose 1.11's new text context menu and smart selection are on by default
 * in this variant. Both regressed SelectionContainer's Copy action in
 * Conductor (issue #7), so this variant opts back into the legacy path.
 *
 * Remove this switch once the upstream context menu keeps Copy for a
 * SelectionContainer inside a LazyColumn item.
 */
object ComposeTextSelectionCompat {
    @OptIn(ExperimentalFoundationApi::class)
    fun install() {
        ComposeFoundationFlags.isNewContextMenuEnabled = false
        ComposeFoundationFlags.isSmartSelectionEnabled = false
    }
}

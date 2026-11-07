package io.nekohasekai.sfa.compose.model

import io.nekohasekai.sfa.database.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object GroupLayout {
    const val TWO_COLUMNS = 2

    fun rows(
        items: List<GroupItem>,
        singleColumn: Boolean,
    ): List<List<GroupItem>> = if (singleColumn) items.map { listOf(it) } else items.chunked(TWO_COLUMNS)

    fun columns(singleColumn: Boolean): Int = if (singleColumn) 1 else TWO_COLUMNS
}

/**
 * Live node-list layout preference shared by the groups screen, the dashboard
 * sheet and app settings. Persisted in [Settings].
 */
object GroupLayoutPreference {
    private val state = MutableStateFlow(Settings.groupSingleColumn)
    val singleColumn: StateFlow<Boolean> = state.asStateFlow()

    fun setSingleColumn(enabled: Boolean) {
        state.value = enabled
        Settings.groupSingleColumn = enabled
    }

    fun toggle() = setSingleColumn(!state.value)
}

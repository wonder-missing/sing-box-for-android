package io.nekohasekai.sfa.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

internal const val PLAN_UPDATE_TOOL = "plan_update"
internal const val MAX_PLAN_ITEMS = 20
internal const val MAX_PLAN_GOAL_CHARS = 200
internal const val MAX_PLAN_STEP_CHARS = 200
internal const val MAX_PLAN_NOTE_CHARS = 300

@Serializable
enum class PlanItemStatus {
    @SerialName("pending")
    PENDING,

    @SerialName("in_progress")
    IN_PROGRESS,

    @SerialName("done")
    DONE,
    ;

    val wire: String
        get() =
            when (this) {
                PENDING -> "pending"
                IN_PROGRESS -> "in_progress"
                DONE -> "done"
            }

    fun next(): PlanItemStatus =
        when (this) {
            PENDING -> IN_PROGRESS
            IN_PROGRESS -> DONE
            DONE -> PENDING
        }
}

@Serializable
data class PlanItem(
    val step: String,
    val status: PlanItemStatus = PlanItemStatus.PENDING,
)

@Serializable
data class PlanState(
    val version: Long = 1L,
    val goal: String = "",
    val items: List<PlanItem> = emptyList(),
    val note: String = "",
) {
    /**
     * A compact recitation for the end of the model request. The full plan
     * lives in the tool result; this block only keeps the current step,
     * progress and blockers in the model's recent attention window.
     */
    fun recitation(): String {
        val done = items.count { it.status == PlanItemStatus.DONE }
        val current =
            items.firstOrNull { it.status == PlanItemStatus.IN_PROGRESS }
                ?: items.firstOrNull { it.status == PlanItemStatus.PENDING }
        val currentIndex = current?.let { items.indexOf(it) } ?: -1
        val next = items.drop(currentIndex + 1).firstOrNull { it.status == PlanItemStatus.PENDING }
        return buildString {
            appendLine("[runtime:plan v=$version]")
            if (goal.isNotBlank()) appendLine("goal: ${escapeRuntimeMarker(goal)}")
            appendLine("progress: $done/${items.size} done")
            if (current == null) {
                appendLine("all steps done")
            } else {
                appendLine("now: ${escapeRuntimeMarker(current.step)}")
                next?.let { appendLine("next: ${escapeRuntimeMarker(it.step)}") }
            }
            if (note.isNotBlank()) appendLine("blocked: ${escapeRuntimeMarker(note)}")
            append("[/runtime:plan]")
        }
    }
}

data class PlanUpdateResult(
    val state: PlanState?,
    val changed: Boolean,
    val cleared: Boolean,
)

/**
 * Process-level plan state. The model writes it through plan_update; the user
 * can edit or clear the same store from the plan card. The version increments
 * only when the canonical content changes.
 */
class PlanStore(
    initial: PlanState? = null,
) {
    private val lock = Any()
    private val _state = MutableStateFlow(normalized(initial))
    val state: StateFlow<PlanState?> = _state.asStateFlow()

    fun current(): PlanState? = _state.value

    fun restore(value: PlanState?) {
        synchronized(lock) {
            _state.value = normalized(value)
        }
    }

    fun update(
        goal: String,
        items: List<PlanItem>,
        note: String,
    ): PlanUpdateResult =
        synchronized(lock) {
            val current = _state.value
            val normalizedItems = items.map { it.copy(step = it.step.trim()) }.filter { it.step.isNotEmpty() }
            if (normalizedItems.isEmpty()) {
                if (current == null) {
                    PlanUpdateResult(null, changed = false, cleared = false)
                } else {
                    _state.value = null
                    PlanUpdateResult(null, changed = true, cleared = true)
                }
            } else {
                val normalizedGoal = goal.trim()
                val normalizedNote = note.trim()
                if (
                    current != null &&
                    current.goal == normalizedGoal &&
                    current.items == normalizedItems &&
                    current.note == normalizedNote
                ) {
                    PlanUpdateResult(current, changed = false, cleared = false)
                } else {
                    val next =
                        PlanState(
                            version = (current?.version ?: 0L) + 1L,
                            goal = normalizedGoal,
                            items = normalizedItems,
                            note = normalizedNote,
                        )
                    _state.value = next
                    PlanUpdateResult(next, changed = true, cleared = false)
                }
            }
        }

    fun clear(): PlanUpdateResult = update("", emptyList(), "")

    private fun normalized(value: PlanState?): PlanState? =
        value
            ?.takeIf { it.items.isNotEmpty() }
            ?.let { it.copy(items = it.items.filter { item -> item.step.isNotBlank() }) }
            ?.takeIf { it.items.isNotEmpty() }
}

private fun escapeRuntimeMarker(text: String): String =
    text
        .replace("[runtime:", "\\[runtime:")
        .replace("[/runtime:", "\\[/runtime:")

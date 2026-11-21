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
internal const val MAX_PLAN_EVENTS = 20

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
enum class PlanClearedBy {
    @SerialName("user")
    USER,

    @SerialName("model")
    MODEL,
    ;

    val wire: String
        get() =
            when (this) {
                USER -> "user"
                MODEL -> "model"
            }
}

@Serializable
enum class PlanEventAction {
    @SerialName("created")
    CREATED,

    @SerialName("updated")
    UPDATED,

    @SerialName("cleared")
    CLEARED,
    ;

    val wire: String
        get() =
            when (this) {
                CREATED -> "created"
                UPDATED -> "updated"
                CLEARED -> "cleared"
            }
}

@Serializable
data class PlanEvent(
    val version: Long,
    val at: Long,
    val actor: PlanClearedBy,
    val action: PlanEventAction,
    val plan: PlanState,
)

@Serializable
data class PlanState(
    val version: Long = 1L,
    val goal: String = "",
    val items: List<PlanItem> = emptyList(),
    val note: String = "",
    /**
     * A cleared tombstone. It keeps the version monotonic and lets the model
     * know that an earlier plan_update result is no longer active.
     */
    val cleared: Boolean = false,
    /** Who cleared the plan; null on old snapshots that predate this field. */
    val clearedBy: PlanClearedBy? = null,
    /** Who wrote this version; null on old snapshots that predate this field. */
    val updatedBy: PlanClearedBy? = null,
) {
    fun hasOpenSteps(): Boolean = items.any { it.status != PlanItemStatus.DONE }

    /**
     * A compact recitation for the end of the model request. The full plan
     * lives in the tool result; this block only keeps the current step,
     * progress and blockers in the model's recent attention window.
     */
    fun recitation(): String {
        if (cleared) {
            val actor =
                when (clearedBy) {
                    PlanClearedBy.USER -> " by the user"
                    PlanClearedBy.MODEL -> " by the model"
                    null -> ""
                }
            return buildString {
                appendLine("[runtime:plan v=$version]")
                appendLine("state: no active plan (cleared$actor)")
                append("[/runtime:plan]")
            }
        }
        val done = items.count { it.status == PlanItemStatus.DONE }
        val current =
            items.firstOrNull { it.status == PlanItemStatus.IN_PROGRESS }
                ?: items.firstOrNull { it.status == PlanItemStatus.PENDING }
        val currentIndex = current?.let { items.indexOf(it) } ?: -1
        val next = items.drop(currentIndex + 1).firstOrNull { it.status == PlanItemStatus.PENDING }
        return buildString {
            appendLine("[runtime:plan v=$version]")
            if (goal.isNotBlank()) appendLine("goal: ${modelText(goal)}")
            appendLine("progress: $done/${items.size} done")
            updatedBy?.let { appendLine("updated by: ${it.wire}") }
            if (current == null) {
                appendLine("all steps done")
            } else {
                appendLine("now: ${modelText(current.step)}")
                next?.let { appendLine("next: ${modelText(it.step)}") }
            }
            if (note.isNotBlank()) appendLine("blocked: ${modelText(note)}")
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
    private val _history = MutableStateFlow<List<PlanEvent>>(emptyList())
    val history: StateFlow<List<PlanEvent>> = _history.asStateFlow()

    fun current(): PlanState? = _state.value

    fun restore(
        value: PlanState?,
        history: List<PlanEvent> = emptyList(),
    ) {
        synchronized(lock) {
            _state.value = normalized(value)
            _history.value = if (value == null) emptyList() else history.takeLast(MAX_PLAN_EVENTS)
        }
    }

    fun update(
        goal: String,
        items: List<PlanItem>,
        note: String,
        updatedBy: PlanClearedBy = PlanClearedBy.MODEL,
        clearedBy: PlanClearedBy = updatedBy,
    ): PlanUpdateResult =
        synchronized(lock) {
            val current = _state.value
            val normalizedItems =
                items
                    .take(MAX_PLAN_ITEMS)
                    .map { it.copy(step = cleanPlanText(it.step, MAX_PLAN_STEP_CHARS)) }
                    .filter { it.step.isNotEmpty() }
            if (normalizedItems.isEmpty()) {
                if (current == null || current.cleared) {
                    PlanUpdateResult(current, changed = false, cleared = false)
                } else {
                    val clearedState =
                        PlanState(
                            version = current.version + 1L,
                            cleared = true,
                            clearedBy = clearedBy,
                        )
                    record(clearedState, clearedBy, PlanEventAction.CLEARED)
                    _state.value = clearedState
                    PlanUpdateResult(clearedState, changed = true, cleared = true)
                }
            } else {
                val normalizedGoal = cleanPlanText(goal, MAX_PLAN_GOAL_CHARS)
                val normalizedNote = cleanPlanText(note, MAX_PLAN_NOTE_CHARS)
                if (
                    current != null &&
                    !current.cleared &&
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
                            updatedBy = updatedBy,
                        )
                    val action =
                        if (current == null || current.cleared) {
                            PlanEventAction.CREATED
                        } else {
                            PlanEventAction.UPDATED
                        }
                    record(next, updatedBy, action)
                    _state.value = next
                    PlanUpdateResult(next, changed = true, cleared = false)
                }
            }
        }

    fun clear(): PlanUpdateResult = update("", emptyList(), "", updatedBy = PlanClearedBy.USER, clearedBy = PlanClearedBy.USER)

    private fun record(
        state: PlanState,
        actor: PlanClearedBy,
        action: PlanEventAction,
    ) {
        val event =
            PlanEvent(
                version = state.version,
                at = System.currentTimeMillis(),
                actor = actor,
                action = action,
                plan = state,
            )
        _history.value = (_history.value + event).takeLast(MAX_PLAN_EVENTS)
    }

    private fun normalized(value: PlanState?): PlanState? {
        if (value == null) return null
        if (value.cleared) return value.copy(items = emptyList())
        val items =
            value.items
                .take(MAX_PLAN_ITEMS)
                .map { it.copy(step = cleanPlanText(it.step, MAX_PLAN_STEP_CHARS)) }
                .filter { item -> item.step.isNotBlank() }
        return value
            .copy(
                goal = cleanPlanText(value.goal, MAX_PLAN_GOAL_CHARS),
                items = items,
                note = cleanPlanText(value.note, MAX_PLAN_NOTE_CHARS),
                clearedBy = null,
            ).takeIf { items.isNotEmpty() }
    }
}

private fun cleanPlanText(
    text: String,
    maxChars: Int,
): String = AgentSanitizer.scrubForModelRead(text.trim()).take(maxChars)

private fun modelText(text: String): String =
    AgentSanitizer
        .sanitizeText(text)
        .replace("[runtime:", "\\[runtime:")
        .replace("[/runtime:", "\\[/runtime:")

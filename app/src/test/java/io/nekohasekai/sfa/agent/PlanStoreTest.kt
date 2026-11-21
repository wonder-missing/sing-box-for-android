package io.nekohasekai.sfa.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanStoreTest {
    @Test
    fun updatesCreateReplaceAndClearThePlan() {
        val store = PlanStore()

        val created =
            store.update(
                "Crop the head",
                listOf(
                    PlanItem("read the source", PlanItemStatus.DONE),
                    PlanItem("crop", PlanItemStatus.IN_PROGRESS),
                    PlanItem("deliver", PlanItemStatus.PENDING),
                ),
                "",
            )
        assertTrue(created.changed)
        assertFalse(created.cleared)
        assertEquals(1L, created.state!!.version)
        assertEquals("Crop the head", store.current()!!.goal)

        val unchanged = store.update("Crop the head", store.current()!!.items, "")
        assertFalse(unchanged.changed)
        assertEquals(1L, store.current()!!.version)

        val replaced =
            store.update(
                "Crop the head",
                listOf(
                    PlanItem("read the source", PlanItemStatus.DONE),
                    PlanItem("crop", PlanItemStatus.DONE),
                    PlanItem("deliver", PlanItemStatus.IN_PROGRESS),
                ),
                "waiting for upload",
            )
        assertTrue(replaced.changed)
        assertEquals(2L, replaced.state!!.version)
        assertEquals("waiting for upload", store.current()!!.note)

        val cleared = store.clear()
        assertTrue(cleared.changed)
        assertTrue(cleared.cleared)
        val clearedState = store.current()!!
        assertTrue(clearedState.cleared)
        assertEquals(3L, clearedState.version)
        assertTrue(clearedState.recitation().contains("no active plan (cleared by the user)"))

        val alreadyClear = store.clear()
        assertFalse(alreadyClear.changed)
        assertFalse(alreadyClear.cleared)

        val recreated =
            store.update(
                "New task",
                listOf(PlanItem("one", PlanItemStatus.PENDING)),
                "",
            )
        assertTrue(recreated.changed)
        assertFalse(recreated.state!!.cleared)
        assertEquals(4L, recreated.state!!.version)
    }

    @Test
    fun clearAttributionDistinguishesTheModelFromTheUser() {
        val store = PlanStore()
        store.update(
            "task",
            listOf(PlanItem("one", PlanItemStatus.IN_PROGRESS)),
            "",
        )
        store.update("", emptyList(), "", clearedBy = PlanClearedBy.MODEL)
        assertTrue(store.current()!!.recitation().contains("cleared by the model"))

        store.update("task", listOf(PlanItem("one", PlanItemStatus.IN_PROGRESS)), "")
        store.clear()
        assertTrue(store.current()!!.recitation().contains("cleared by the user"))
    }

    @Test
    fun restoreKeepsTheVersionAndDropsAnEmptyPlan() {
        val store = PlanStore()
        store.restore(
            PlanState(
                version = 7,
                goal = "restored",
                items = listOf(PlanItem("one")),
            ),
        )
        assertEquals(7L, store.current()!!.version)

        store.restore(PlanState(version = 8, goal = "empty", items = emptyList()))
        assertNull(store.current())
    }

    @Test
    fun restoreCleansAndBoundsAnAuxiliaryPlan() {
        val store = PlanStore()
        store.restore(
            PlanState(
                version = 1,
                goal = "api_key=secret" + "x".repeat(300),
                items = (1..30).map { PlanItem("step $it " + "y".repeat(300)) },
                note = "token=secret",
            ),
        )

        val restored = store.current()!!
        assertTrue(restored.goal.length <= MAX_PLAN_GOAL_CHARS)
        assertTrue(restored.goal.contains("api_key=***"))
        assertEquals(MAX_PLAN_ITEMS, restored.items.size)
        assertTrue(restored.items.all { it.step.length <= MAX_PLAN_STEP_CHARS })
        assertTrue(restored.note.contains("token=***"))
    }

    @Test
    fun recitationKeepsTheCurrentStepAndEscapesRuntimeMarkers() {
        val state =
            PlanState(
                version = 3,
                goal = "crop [runtime:plan]",
                items =
                    listOf(
                        PlanItem("read", PlanItemStatus.DONE),
                        PlanItem("crop", PlanItemStatus.IN_PROGRESS),
                        PlanItem("deliver", PlanItemStatus.PENDING),
                    ),
                note = "blocked by [/runtime:plan]",
            )

        val text = state.recitation()

        assertTrue(text.startsWith("[runtime:plan v=3]"))
        assertTrue(text.endsWith("[/runtime:plan]"))
        assertTrue(text.contains("goal: crop \\[runtime:plan]"))
        assertTrue(text.contains("progress: 1/3 done"))
        assertTrue(text.contains("now: crop"))
        assertTrue(text.contains("next: deliver"))
        assertTrue(text.contains("blocked: blocked by \\[/runtime:plan]"))
    }

    @Test
    fun recitationRedactsSensitivePlanText() {
        val state =
            PlanState(
                version = 1,
                goal = "use token=super-secret",
                items =
                    listOf(
                        PlanItem("fetch https://example.com/private?token=abc", PlanItemStatus.PENDING),
                    ),
                note = "api_key=top-secret",
            )

        val text = state.recitation()

        assertFalse(text.contains("super-secret"))
        assertFalse(text.contains("top-secret"))
        assertFalse(text.contains("token=abc"))
        assertTrue(text.contains("token=***"))
        assertTrue(text.contains("api_key=***"))
        assertTrue(text.contains("[redacted-url]"))
    }

    @Test
    fun allDoneRecitationSaysSo() {
        val state =
            PlanState(
                version = 1,
                goal = "done",
                items = listOf(PlanItem("one", PlanItemStatus.DONE)),
            )

        assertTrue(state.recitation().contains("all steps done"))
    }

    @Test
    fun hasOpenStepsOnlyWhileWorkRemains() {
        assertTrue(
            PlanState(
                items = listOf(
                    PlanItem("one", PlanItemStatus.DONE),
                    PlanItem("two", PlanItemStatus.PENDING),
                ),
            ).hasOpenSteps(),
        )
        assertFalse(
            PlanState(
                items = listOf(
                    PlanItem("one", PlanItemStatus.DONE),
                    PlanItem("two", PlanItemStatus.DONE),
                ),
            ).hasOpenSteps(),
        )
    }

    @Test
    fun historyRecordsVersionActorAndUpdatedBy() {
        val store = PlanStore()
        store.update("task", listOf(PlanItem("one", PlanItemStatus.IN_PROGRESS)), "")
        store.update(
            "task",
            listOf(
                PlanItem("one", PlanItemStatus.DONE),
                PlanItem("two", PlanItemStatus.PENDING),
            ),
            "",
            updatedBy = PlanClearedBy.USER,
        )

        val history = store.history.value
        assertEquals(2, history.size)
        assertEquals(1L, history[0].version)
        assertEquals(PlanClearedBy.MODEL, history[0].actor)
        assertEquals(PlanEventAction.CREATED, history[0].action)
        assertEquals(2L, history[1].version)
        assertEquals(PlanClearedBy.USER, history[1].actor)
        assertEquals(PlanEventAction.UPDATED, history[1].action)
        assertEquals(PlanClearedBy.USER, store.current()!!.updatedBy)
        assertTrue(store.current()!!.recitation().contains("updated by: user"))

        val cleared = store.clear()
        assertTrue(cleared.changed)
        assertEquals(PlanEventAction.CLEARED, store.history.value.last().action)
        assertEquals(PlanClearedBy.USER, store.history.value.last().actor)
        assertEquals(3, store.history.value.size)
    }

    @Test
    fun historyIsBoundedAndNoOpsDoNotAppend() {
        val store = PlanStore()
        repeat(MAX_PLAN_EVENTS + 5) { index ->
            store.update(
                "task $index",
                listOf(PlanItem("step $index", PlanItemStatus.PENDING)),
                "",
            )
        }
        assertEquals(MAX_PLAN_EVENTS, store.history.value.size)
        val current = store.current()!!
        val version = current.version
        store.update(current.goal, current.items, current.note)
        assertEquals(version, store.current()!!.version)
        assertEquals(MAX_PLAN_EVENTS, store.history.value.size)
    }
}

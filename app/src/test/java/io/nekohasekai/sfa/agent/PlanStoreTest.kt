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
}

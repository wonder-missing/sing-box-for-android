package io.nekohasekai.sfa.conductor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConductorStreamingTest {
    @Test
    fun settlingReasoningStopsSpinnerEvenWhenAssistantFollows() {
        val items =
            listOf(
                ConductorItem.Reasoning("thought", streaming = true),
                ConductorItem.Assistant("answer", streaming = true),
            )

        val settled = items.settleReasoning()

        assertEquals(
            listOf(
                ConductorItem.Reasoning("thought", streaming = false),
                ConductorItem.Assistant("answer", streaming = true),
            ),
            settled,
        )
    }

    @Test
    fun assistantDeltaSettlesReasoningBeforeStreamingAnswer() {
        val items = listOf(ConductorItem.Reasoning("thought", streaming = true))

        val updated = items.withAssistantDelta("ans")

        assertEquals(ConductorItem.Reasoning("thought", streaming = false), updated[0])
        assertEquals(ConductorItem.Assistant("ans", streaming = true), updated[1])
        assertFalse(updated.any { it is ConductorItem.Reasoning && it.streaming })
    }

    @Test
    fun newPlaceholderSettlesThePreviousStepAndKeepsOneSpinner() {
        val items =
            listOf(
                ConductorItem.Reasoning("previous step", streaming = true),
                ConductorItem.Notice("tool", "done"),
            )

        val updated = items.withReasoningPlaceholder()

        assertEquals(ConductorItem.Reasoning("previous step", streaming = false), updated[0])
        assertEquals(1, updated.count { it is ConductorItem.Reasoning && it.streaming })
        assertTrue(updated.last() is ConductorItem.Reasoning)
    }

    @Test
    fun blankPlaceholderIsRemovedInsteadOfLeftSpinning() {
        val items = listOf(ConductorItem.Reasoning("", streaming = true))

        val settled = items.settleReasoning()

        assertTrue(settled.isEmpty())
    }

    @Test
    fun settlingAssistantsClearsEveryStreamingAnswer() {
        val items =
            listOf(
                ConductorItem.Assistant("first", streaming = true),
                ConductorItem.Notice("tool", "done"),
                ConductorItem.Assistant("second", streaming = true),
            )

        val settled = items.settleStreamingAssistants()

        assertFalse(settled.any { it is ConductorItem.Assistant && it.streaming })
    }

    @Test
    fun finalAssistantMessageReusesTheStreamingItem() {
        val items =
            listOf(
                ConductorItem.Reasoning("thought", streaming = true),
                ConductorItem.Assistant("partial", streaming = true),
            )

        val updated = items.withAssistantFinal("final answer")

        assertEquals(ConductorItem.Reasoning("thought", streaming = false), updated[0])
        assertEquals(ConductorItem.Assistant("final answer", streaming = false), updated[1])
        assertEquals(2, updated.size)
    }

    @Test
    fun streamResetDropsOnlyStreamingOutput() {
        val items =
            listOf(
                ConductorItem.Assistant("partial", streaming = true),
                ConductorItem.Notice("tool", "done"),
                ConductorItem.Reasoning("", streaming = true),
                ConductorItem.Assistant("settled"),
            )

        val updated = items.dropStreamingOutput()

        assertEquals(
            listOf(
                ConductorItem.Notice("tool", "done"),
                ConductorItem.Assistant("settled"),
            ),
            updated,
        )
    }
}

package io.nekohasekai.sfa.conductor

import io.nekohasekai.sfa.agent.ConfirmationRequest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConductorInputHintTest {
    @Test
    fun `shows the your-turn hint only after leaving the latest message`() {
        assertFalse(shouldShowYourTurnHint(atBottom = true, hasPendingUserInteraction = true, turnEndedWhileAway = true))
        assertFalse(shouldShowYourTurnHint(atBottom = false, hasPendingUserInteraction = false, turnEndedWhileAway = false))
        assertTrue(shouldShowYourTurnHint(atBottom = false, hasPendingUserInteraction = true, turnEndedWhileAway = false))
        assertTrue(shouldShowYourTurnHint(atBottom = false, hasPendingUserInteraction = false, turnEndedWhileAway = true))
    }

    @Test
    fun `detects confirmations questions and file requests`() {
        val confirmation =
            ConductorItem.Confirmation(
                ConfirmationRequest(
                    id = 1,
                    toolName = "shell",
                    displayArgsJson = "{}",
                    reason = "file access",
                ),
            )
        val question =
            ConductorItem.Question(
                id = "q1",
                question = "Continue?",
                options = listOf("Yes", "No"),
            )

        assertTrue(hasPendingUserInteraction(listOf(confirmation), hasFileRequest = false))
        assertTrue(hasPendingUserInteraction(listOf(question), hasFileRequest = false))
        assertTrue(hasPendingUserInteraction(emptyList(), hasFileRequest = true))
    }

    @Test
    fun `ignores settled questions`() {
        val answered = ConductorItem.Question(id = "q1", question = "Continue?", answered = true)
        val cancelled = ConductorItem.Question(id = "q2", question = "Continue?", cancelled = true)

        assertFalse(hasPendingUserInteraction(listOf(answered, cancelled), hasFileRequest = false))
    }
}

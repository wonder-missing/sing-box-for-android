package io.nekohasekai.sfa.conductor

import io.nekohasekai.sfa.agent.AgentMessage
import io.nekohasekai.sfa.agent.ApiTraceSummary
import io.nekohasekai.sfa.agent.ToolCallView
import io.nekohasekai.sfa.agent.ToolResultView
import io.nekohasekai.sfa.agent.ToolStatus
import io.nekohasekai.sfa.agent.TurnEvent
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class ConductorSessionSerializationTest {
    @Test
    fun sessionRoundTripsThroughJson() {
        val session =
            ConductorSession(
                transcript =
                    listOf(
                        AgentMessage.User("hi"),
                        AgentMessage.Assistant("hello"),
                    ),
                pendingEvent = TurnEvent("turn_interrupted", "user_stop"),
                appVersion = "1.15.0-alpha.13-wonder.50",
                trace =
                    listOf(
                        ApiTraceSummary(
                            timestamp = 1_700_000_000_000L,
                            version = "1.15.0-alpha.13-wonder.50",
                            status = 200,
                            durationMs = 1_234,
                            inputTokens = 100,
                            outputTokens = 20,
                        ),
                    ),
                items =
                    listOf(
                        ConductorItem.User("hi"),
                        ConductorItem.Assistant("hello"),
                        ConductorItem.Tool(
                            call = ToolCallView("call_1", "list_nodes", "{}"),
                            status = ToolStatus.SUCCEEDED,
                            result =
                                ToolResultView(
                                    callId = "call_1",
                                    name = "list_nodes",
                                    ok = true,
                                    output = buildJsonObject { put("ok", true) },
                                    errorCode = null,
                                    message = null,
                                    truncated = false,
                                    degraded = true,
                                ),
                        ),
                    ),
            )
        val json = Json { encodeDefaults = true }
        val decoded = json.decodeFromString<ConductorSession>(json.encodeToString(session))
        assertEquals(session, decoded)
    }
}

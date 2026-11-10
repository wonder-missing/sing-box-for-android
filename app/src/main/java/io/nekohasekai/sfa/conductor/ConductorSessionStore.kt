package io.nekohasekai.sfa.conductor

import android.content.Context
import io.nekohasekai.sfa.agent.AgentMessage
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class ConductorSession(
    val transcript: List<AgentMessage> = emptyList(),
    val items: List<ConductorItem> = emptyList(),
)

class ConductorSessionStore(
    context: Context,
) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    fun load(): ConductorSession? =
        prefs
            .getString(KEY, null)
            ?.let { raw -> runCatching { json.decodeFromString<ConductorSession>(raw) }.getOrNull() }

    fun save(
        transcript: List<AgentMessage>,
        items: List<ConductorItem>,
    ) {
        val session =
            ConductorSession(
                transcript = transcript.takeLast(MAX_TRANSCRIPT),
                items = items.takeLast(MAX_ITEMS),
            )
        runCatching {
            prefs.edit().putString(KEY, json.encodeToString(session)).apply()
        }
    }

    fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    companion object {
        private const val PREFS = "conductor_session"
        private const val KEY = "session_v1"
        private const val MAX_TRANSCRIPT = 120
        private const val MAX_ITEMS = 200
    }
}

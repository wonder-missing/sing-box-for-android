package io.nekohasekai.sfa.conductor

import android.content.Context
import io.nekohasekai.sfa.agent.AgentMessage
import io.nekohasekai.sfa.agent.TurnEvent
import io.nekohasekai.sfa.agent.trimTranscriptToLast
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class ConductorSession(
    val transcript: List<AgentMessage> = emptyList(),
    val items: List<ConductorItem> = emptyList(),
    val pendingEvent: TurnEvent? = null,
)

internal interface ConductorPreferenceStore {
    fun getString(key: String): String?

    fun putString(
        key: String,
        value: String,
    )

    fun remove(key: String)
}

private class AndroidConductorPreferenceStore(
    context: Context,
) : ConductorPreferenceStore {
    private val prefs =
        context.applicationContext.getSharedPreferences(
            ConductorSessionStore.PREFS,
            Context.MODE_PRIVATE,
        )

    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun putString(
        key: String,
        value: String,
    ) {
        prefs.edit().putString(key, value).apply()
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }
}

class ConductorSessionStore internal constructor(
    private val prefs: ConductorPreferenceStore,
) {
    constructor(context: Context) : this(AndroidConductorPreferenceStore(context))

    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    fun load(): ConductorSession? =
        prefs.getString(KEY)?.let { raw ->
            runCatching { json.decodeFromString<ConductorSession>(raw) }.getOrNull()
        }

    fun save(
        transcript: List<AgentMessage>,
        items: List<ConductorItem>,
        pendingEvent: TurnEvent? = null,
    ) {
        val session =
            ConductorSession(
                transcript = trimTranscriptToLast(transcript, MAX_TRANSCRIPT),
                items = items.takeLast(MAX_ITEMS),
                pendingEvent = pendingEvent,
            )
        runCatching {
            prefs.putString(KEY, json.encodeToString(session))
        }
    }

    fun loadDraft(): String = prefs.getString(DRAFT_KEY).orEmpty().take(MAX_DRAFT)

    /** Unsent composer text. Kept apart from [clear] so wiping a session does not drop a draft. */
    fun saveDraft(text: String) {
        val normalized = text.take(MAX_DRAFT)
        if (normalized.isEmpty()) {
            if (prefs.getString(DRAFT_KEY) != null) prefs.remove(DRAFT_KEY)
            return
        }
        if (prefs.getString(DRAFT_KEY) == normalized) return
        prefs.putString(DRAFT_KEY, normalized)
    }

    fun clear() {
        prefs.remove(KEY)
    }

    companion object {
        internal const val PREFS = "conductor_session"
        internal const val MAX_DRAFT = 8_000
        private const val KEY = "session_v1"
        private const val DRAFT_KEY = "draft_v1"
        internal const val MAX_TRANSCRIPT = 120
        private const val MAX_ITEMS = 200
    }
}

package io.nekohasekai.sfa.agent

import io.nekohasekai.sfa.database.Settings

data class ModelProviderPreset(
    val id: String,
    val label: String,
    val baseUrl: String,
    val defaultModel: String,
    val keyHint: String,
    val customBaseUrl: Boolean = false,
)

data class ModelEndpoint(
    val providerId: String,
    val providerLabel: String,
    val baseUrl: String,
    val model: String,
)

object ModelProviders {
    const val DEEPSEEK = "deepseek"
    const val XAI = "xai"
    const val GENERIC = "generic"

    val all =
        listOf(
            ModelProviderPreset(
                id = DEEPSEEK,
                label = "DeepSeek",
                baseUrl = "https://api.deepseek.com",
                defaultModel = "deepseek-v4-flash",
                keyHint = "sk-...",
            ),
            ModelProviderPreset(
                id = XAI,
                label = "xAI",
                baseUrl = "https://api.x.ai/v1",
                defaultModel = "grok-4",
                keyHint = "xai-...",
            ),
            ModelProviderPreset(
                id = GENERIC,
                label = "Custom (OpenAI-compatible)",
                baseUrl = "",
                defaultModel = "",
                keyHint = "sk-...",
                customBaseUrl = true,
            ),
        )

    fun byId(id: String?): ModelProviderPreset = all.firstOrNull { it.id == id } ?: all.first()
}

/**
 * Resolves the endpoint and model for the active provider. The selected
 * provider is persisted in Settings; a turn pins it so changing the settings
 * while a turn is running cannot mix one provider's key with another's URL.
 */
object ModelProviderSettings {
    @Volatile
    private var pinnedId: String? = null

    fun selected(): ModelProviderPreset = ModelProviders.byId(Settings.conductorProvider)

    fun active(): ModelProviderPreset = ModelProviders.byId(pinnedId ?: Settings.conductorProvider)

    fun pinForTurn() {
        pinnedId = selected().id
    }

    fun unpin() {
        pinnedId = null
    }

    fun endpoint(): ModelEndpoint = endpointFor(active())

    fun endpointFor(preset: ModelProviderPreset): ModelEndpoint {
        val baseUrl = if (preset.customBaseUrl) Settings.conductorGenericBaseUrl else preset.baseUrl
        val model =
            when (preset.id) {
                ModelProviders.XAI -> Settings.conductorXaiModel
                ModelProviders.GENERIC -> Settings.conductorGenericModel
                else -> Settings.conductorModel
            }.ifBlank { preset.defaultModel }
        return ModelEndpoint(
            providerId = preset.id,
            providerLabel = preset.label,
            baseUrl = baseUrl,
            model = model,
        )
    }

    fun modelFor(id: String): String = endpointFor(ModelProviders.byId(id)).model

    fun setModel(
        id: String,
        model: String,
    ) {
        when (ModelProviders.byId(id).id) {
            ModelProviders.XAI -> Settings.conductorXaiModel = model
            ModelProviders.GENERIC -> Settings.conductorGenericModel = model
            else -> Settings.conductorModel = model
        }
    }

    fun baseUrlFor(id: String): String = endpointFor(ModelProviders.byId(id)).baseUrl

    fun setBaseUrl(
        id: String,
        baseUrl: String,
    ) {
        if (ModelProviders.byId(id).customBaseUrl) {
            Settings.conductorGenericBaseUrl = baseUrl
        }
    }

    fun select(id: String) {
        Settings.conductorProvider = ModelProviders.byId(id).id
    }
}

/**
 * OpenAI-compatible providers use `<base>/chat/completions`. A base may be
 * `https://api.deepseek.com`, `https://api.x.ai/v1`, or a full endpoint.
 */
internal fun chatCompletionsUrl(baseUrl: String): String {
    val trimmed = baseUrl.trim().trimEnd('/')
    require(trimmed.isNotEmpty()) { "Base URL is empty." }
    return if (trimmed.endsWith("/chat/completions")) trimmed else "$trimmed/chat/completions"
}

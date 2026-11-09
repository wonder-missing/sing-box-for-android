package io.nekohasekai.sfa.agent

class UnsupportedAgentApiKeyProvider : AgentApiKeyProvider {
    override val supported: Boolean = false

    override suspend fun apiKey(): String? = null

    override suspend fun save(apiKey: String) = Unit

    override suspend fun clear() = Unit
}

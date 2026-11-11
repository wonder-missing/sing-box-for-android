package io.nekohasekai.sfa.agent

import android.content.Context

object AgentApiKeyProviderFactory {
    fun create(
        context: Context,
        providerId: String? = null,
    ): AgentApiKeyProvider = KeystoreAgentApiKeyProvider(context, providerId)
}

package io.nekohasekai.sfa.agent

import android.content.Context

object AgentApiKeyProviderFactory {
    fun create(context: Context): AgentApiKeyProvider = KeystoreAgentApiKeyProvider(context)
}

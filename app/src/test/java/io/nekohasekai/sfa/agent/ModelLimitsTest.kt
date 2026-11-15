package io.nekohasekai.sfa.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelLimitsTest {
    private fun endpoint(
        provider: String,
        model: String,
    ) = ModelEndpoint(
        providerId = provider,
        providerLabel = provider,
        baseUrl = "https://example.com",
        model = model,
    )

    @Test
    fun deepSeekUsesTheFlashCeilings() {
        val limits = ModelProviderSettings.limitsFor(endpoint(ModelProviders.DEEPSEEK, "deepseek-flash"))

        assertEquals(1_000_000, limits.contextWindow)
        assertEquals(384_000, limits.maxOutputTokens)
    }

    @Test
    fun legacyDeepSeekModelNamesNormalizeToTheCurrentAlias() {
        assertEquals(
            "deepseek-flash",
            ModelProviderSettings.normalizeModel(ModelProviders.DEEPSEEK, "deepseek-v4-flash"),
        )
        assertEquals(
            "deepseek-flash",
            ModelProviderSettings.normalizeModel(ModelProviders.DEEPSEEK, "deepseek-v4-flash-vision-exp"),
        )
        assertEquals(
            "deepseek-reasoner",
            ModelProviderSettings.normalizeModel(ModelProviders.DEEPSEEK, "deepseek-reasoner"),
        )
        assertEquals(
            "deepseek-v4-flash",
            ModelProviderSettings.normalizeModel(ModelProviders.XAI, "deepseek-v4-flash"),
        )
    }

    @Test
    fun xaiFastUsesTheTwoMillionWindow() {
        val fast = ModelProviderSettings.limitsFor(endpoint(ModelProviders.XAI, "grok-4-fast-reasoning"))
        assertEquals(2_000_000, fast.contextWindow)
        assertEquals(2_000_000, fast.maxOutputTokens)

        val base = ModelProviderSettings.limitsFor(endpoint(ModelProviders.XAI, "grok-4"))
        assertEquals(256_000, base.contextWindow)
        assertEquals(256_000, base.maxOutputTokens)
    }

    @Test
    fun unknownProvidersOmitTheOutputCap() {
        val limits = ModelProviderSettings.limitsFor(endpoint(ModelProviders.GENERIC, "local-model"))

        assertEquals(128_000, limits.contextWindow)
        assertNull(limits.maxOutputTokens)
    }
}

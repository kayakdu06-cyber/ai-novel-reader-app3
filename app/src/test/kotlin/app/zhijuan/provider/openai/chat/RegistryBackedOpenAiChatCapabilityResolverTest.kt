package app.zhijuan.provider.openai.chat

import app.zhijuan.provider.common.CapabilitySource
import app.zhijuan.provider.common.CapabilitySupport
import app.zhijuan.provider.common.InMemoryProviderCapabilityStore
import app.zhijuan.provider.common.ProviderCapabilityRegistry
import app.zhijuan.provider.common.ProviderCapabilitySnapshot
import app.zhijuan.provider.common.ProviderConnectionProfile
import app.zhijuan.provider.common.ProviderModelId
import app.zhijuan.provider.common.ProviderProtocol
import app.zhijuan.provider.common.ProviderStreamFormat
import app.zhijuan.provider.common.TokenizerFamily
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RegistryBackedOpenAiChatCapabilityResolverTest {
    @Test
    fun `relay capabilities use the model scoped registry override`() = runBlocking {
        val profile = ProviderConnectionProfile.create("c1", ProviderProtocol.OPENAI_CHAT_COMPAT, "https://relay.example/v1")
        val model = ProviderModelId.from("model-a")
        val registry = ProviderCapabilityRegistry(InMemoryProviderCapabilityStore()) { NOW }
        registry.setUserOverride(profile, override(profile.protocol, model), NOW)

        val result = RegistryBackedOpenAiChatCapabilityResolver(registry).resolve(
            profile,
            model,
            OpenAiChatCompatibilityMode.RELAY_MINIMAL,
            NOW,
            VERSION,
        )

        assertEquals(CapabilitySupport.SUPPORTED, result.structuredOutput)
        assertEquals(CapabilitySource.USER_OVERRIDE, result.source)
    }

    private fun override(protocol: ProviderProtocol, model: ProviderModelId) = ProviderCapabilitySnapshot(
        protocol, model,
        CapabilitySupport.UNKNOWN, ProviderStreamFormat.UNKNOWN,
        CapabilitySupport.SUPPORTED, CapabilitySupport.UNKNOWN, CapabilitySupport.UNKNOWN,
        CapabilitySupport.UNKNOWN, CapabilitySupport.UNKNOWN, CapabilitySupport.UNKNOWN,
        CapabilitySupport.UNKNOWN, CapabilitySupport.UNKNOWN, CapabilitySupport.UNKNOWN,
        null, null, TokenizerFamily.UNKNOWN, CapabilitySource.USER_OVERRIDE, NOW, null, VERSION,
    )

    private companion object {
        const val NOW = 10_000L
        const val VERSION = "openai-chat-1"
    }
}

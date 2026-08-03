package app.zhijuan.provider.anthropic

import app.zhijuan.provider.common.CapabilitySource
import app.zhijuan.provider.common.CapabilitySupport
import app.zhijuan.provider.common.ProviderCapabilitySnapshot
import app.zhijuan.provider.common.ProviderCapabilityRegistry
import app.zhijuan.provider.common.ProviderConnectionProfile
import app.zhijuan.provider.common.ProviderModelId
import app.zhijuan.provider.common.ProviderProtocol
import app.zhijuan.provider.common.ProviderStreamFormat
import app.zhijuan.provider.common.TokenizerFamily

fun interface AnthropicCapabilityResolver {
    suspend fun resolve(
        profile: ProviderConnectionProfile,
        modelId: ProviderModelId,
        verifiedAt: Long,
        adapterVersion: String,
    ): ProviderCapabilitySnapshot
}

class ConservativeAnthropicCapabilityResolver : AnthropicCapabilityResolver {
    override suspend fun resolve(
        profile: ProviderConnectionProfile,
        modelId: ProviderModelId,
        verifiedAt: Long,
        adapterVersion: String,
    ): ProviderCapabilitySnapshot = anthropicCapabilities(
        modelId,
        verifiedAt,
        adapterVersion,
        CapabilitySupport.UNKNOWN,
    )
}

class RegistryBackedAnthropicCapabilityResolver(
    private val registry: ProviderCapabilityRegistry,
) : AnthropicCapabilityResolver {
    override suspend fun resolve(
        profile: ProviderConnectionProfile,
        modelId: ProviderModelId,
        verifiedAt: Long,
        adapterVersion: String,
    ): ProviderCapabilitySnapshot = registry.resolve(
        profile = profile,
        modelId = modelId,
        adapterVersion = adapterVersion,
        builtIn = anthropicCapabilities(
            modelId = modelId,
            verifiedAt = verifiedAt,
            adapterVersion = adapterVersion,
            modelSpecificOptions = CapabilitySupport.UNKNOWN,
        ),
    )
}

internal fun anthropicCapabilities(
    modelId: ProviderModelId,
    verifiedAt: Long,
    adapterVersion: String,
    modelSpecificOptions: CapabilitySupport,
) = ProviderCapabilitySnapshot(
    protocol = ProviderProtocol.ANTHROPIC_MESSAGES,
    modelId = modelId,
    streaming = CapabilitySupport.SUPPORTED,
    streamFormat = ProviderStreamFormat.SSE,
    structuredOutput = modelSpecificOptions,
    usageInStream = CapabilitySupport.SUPPORTED,
    systemInstruction = CapabilitySupport.SUPPORTED,
    temperature = modelSpecificOptions,
    topP = modelSpecificOptions,
    maxOutputTokensParameter = CapabilitySupport.SUPPORTED,
    seed = CapabilitySupport.UNSUPPORTED,
    reasoningEffort = modelSpecificOptions,
    idempotencyKey = CapabilitySupport.UNSUPPORTED,
    contextLimit = null,
    maxOutputTokens = null,
    tokenizerFamily = TokenizerFamily.ANTHROPIC,
    source = CapabilitySource.BUILT_IN,
    verifiedAt = verifiedAt.coerceAtLeast(0),
    expiresAt = null,
    adapterVersion = adapterVersion,
)

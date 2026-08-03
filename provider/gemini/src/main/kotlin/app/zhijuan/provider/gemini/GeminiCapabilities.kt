package app.zhijuan.provider.gemini

import app.zhijuan.provider.common.CapabilitySource
import app.zhijuan.provider.common.CapabilitySupport
import app.zhijuan.provider.common.ProviderCapabilitySnapshot
import app.zhijuan.provider.common.ProviderCapabilityRegistry
import app.zhijuan.provider.common.ProviderConnectionProfile
import app.zhijuan.provider.common.ProviderModelId
import app.zhijuan.provider.common.ProviderProtocol
import app.zhijuan.provider.common.ProviderStreamFormat
import app.zhijuan.provider.common.TokenizerFamily

fun interface GeminiCapabilityResolver {
    suspend fun resolve(
        profile: ProviderConnectionProfile,
        modelId: ProviderModelId,
        verifiedAt: Long,
        adapterVersion: String,
    ): ProviderCapabilitySnapshot
}

class ConservativeGeminiCapabilityResolver : GeminiCapabilityResolver {
    override suspend fun resolve(
        profile: ProviderConnectionProfile,
        modelId: ProviderModelId,
        verifiedAt: Long,
        adapterVersion: String,
    ): ProviderCapabilitySnapshot = geminiCapabilities(
        modelId = modelId,
        verifiedAt = verifiedAt,
        adapterVersion = adapterVersion,
        modelSpecificOptions = CapabilitySupport.UNKNOWN,
    )
}

class RegistryBackedGeminiCapabilityResolver(
    private val registry: ProviderCapabilityRegistry,
) : GeminiCapabilityResolver {
    override suspend fun resolve(
        profile: ProviderConnectionProfile,
        modelId: ProviderModelId,
        verifiedAt: Long,
        adapterVersion: String,
    ): ProviderCapabilitySnapshot = registry.resolve(
        profile = profile,
        modelId = modelId,
        adapterVersion = adapterVersion,
        builtIn = geminiCapabilities(
            modelId = modelId,
            verifiedAt = verifiedAt,
            adapterVersion = adapterVersion,
            modelSpecificOptions = CapabilitySupport.UNKNOWN,
        ),
    )
}

internal fun geminiCapabilities(
    modelId: ProviderModelId,
    verifiedAt: Long,
    adapterVersion: String,
    modelSpecificOptions: CapabilitySupport,
) = ProviderCapabilitySnapshot(
    protocol = ProviderProtocol.GEMINI_GENERATE_CONTENT,
    modelId = modelId,
    streaming = CapabilitySupport.SUPPORTED,
    streamFormat = ProviderStreamFormat.SSE,
    structuredOutput = modelSpecificOptions,
    usageInStream = CapabilitySupport.SUPPORTED,
    systemInstruction = CapabilitySupport.SUPPORTED,
    temperature = modelSpecificOptions,
    topP = modelSpecificOptions,
    maxOutputTokensParameter = CapabilitySupport.SUPPORTED,
    seed = modelSpecificOptions,
    reasoningEffort = modelSpecificOptions,
    idempotencyKey = CapabilitySupport.UNSUPPORTED,
    contextLimit = null,
    maxOutputTokens = null,
    tokenizerFamily = TokenizerFamily.GOOGLE,
    source = CapabilitySource.BUILT_IN,
    verifiedAt = verifiedAt.coerceAtLeast(0),
    expiresAt = null,
    adapterVersion = adapterVersion,
)

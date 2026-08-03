package app.zhijuan.provider.openai.responses

import app.zhijuan.provider.common.CapabilitySource
import app.zhijuan.provider.common.CapabilitySupport
import app.zhijuan.provider.common.ProviderCapabilitySnapshot
import app.zhijuan.provider.common.ProviderCapabilityRegistry
import app.zhijuan.provider.common.ProviderConnectionProfile
import app.zhijuan.provider.common.ProviderModelId
import app.zhijuan.provider.common.ProviderProtocol
import app.zhijuan.provider.common.ProviderStreamFormat
import app.zhijuan.provider.common.TokenizerFamily

fun interface OpenAiResponsesCapabilityResolver {
    suspend fun resolve(
        profile: ProviderConnectionProfile,
        modelId: ProviderModelId,
        verifiedAt: Long,
        adapterVersion: String,
    ): ProviderCapabilitySnapshot
}

class ConservativeOpenAiResponsesCapabilityResolver : OpenAiResponsesCapabilityResolver {
    override suspend fun resolve(
        profile: ProviderConnectionProfile,
        modelId: ProviderModelId,
        verifiedAt: Long,
        adapterVersion: String,
    ): ProviderCapabilitySnapshot = openAiResponsesCapabilities(
        modelId = modelId,
        verifiedAt = verifiedAt,
        adapterVersion = adapterVersion,
        modelSpecificOptions = CapabilitySupport.UNKNOWN,
    )
}

class RegistryBackedOpenAiResponsesCapabilityResolver(
    private val registry: ProviderCapabilityRegistry,
) : OpenAiResponsesCapabilityResolver {
    override suspend fun resolve(
        profile: ProviderConnectionProfile,
        modelId: ProviderModelId,
        verifiedAt: Long,
        adapterVersion: String,
    ): ProviderCapabilitySnapshot = registry.resolve(
        profile = profile,
        modelId = modelId,
        adapterVersion = adapterVersion,
        builtIn = openAiResponsesCapabilities(
            modelId = modelId,
            verifiedAt = verifiedAt,
            adapterVersion = adapterVersion,
            modelSpecificOptions = CapabilitySupport.UNKNOWN,
        ),
    )
}

internal fun openAiResponsesCapabilities(
    modelId: ProviderModelId,
    verifiedAt: Long,
    adapterVersion: String,
    modelSpecificOptions: CapabilitySupport,
): ProviderCapabilitySnapshot = ProviderCapabilitySnapshot(
    protocol = ProviderProtocol.OPENAI_RESPONSES,
    modelId = modelId,
    streaming = CapabilitySupport.SUPPORTED,
    streamFormat = ProviderStreamFormat.SSE,
    structuredOutput = modelSpecificOptions,
    usageInStream = CapabilitySupport.SUPPORTED,
    systemInstruction = CapabilitySupport.SUPPORTED,
    temperature = modelSpecificOptions,
    topP = modelSpecificOptions,
    maxOutputTokensParameter = CapabilitySupport.SUPPORTED,
    // The Responses create schema does not define seed or an idempotency-key field.
    seed = CapabilitySupport.UNSUPPORTED,
    reasoningEffort = modelSpecificOptions,
    idempotencyKey = CapabilitySupport.UNSUPPORTED,
    contextLimit = null,
    maxOutputTokens = null,
    tokenizerFamily = TokenizerFamily.UNKNOWN,
    source = CapabilitySource.BUILT_IN,
    verifiedAt = verifiedAt.coerceAtLeast(0),
    expiresAt = null,
    adapterVersion = adapterVersion,
)

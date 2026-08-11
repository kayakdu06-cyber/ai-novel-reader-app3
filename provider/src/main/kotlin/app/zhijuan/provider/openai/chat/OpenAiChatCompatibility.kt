package app.zhijuan.provider.openai.chat

import app.zhijuan.provider.common.CapabilitySource
import app.zhijuan.provider.common.CapabilitySupport
import app.zhijuan.provider.common.ProviderCapabilitySnapshot
import app.zhijuan.provider.common.ProviderCapabilityRegistry
import app.zhijuan.provider.common.ProviderConnectionProfile
import app.zhijuan.provider.common.ProviderModelId
import app.zhijuan.provider.common.ProviderProtocol
import app.zhijuan.provider.common.ProviderStreamFormat
import app.zhijuan.provider.common.TokenizerFamily

enum class OpenAiChatCompatibilityMode {
    OPENAI,
    DEEPSEEK,
    RELAY_MINIMAL,
}

fun interface OpenAiChatCompatibilityResolver {
    fun resolve(profile: ProviderConnectionProfile): OpenAiChatCompatibilityMode
}

class FixedOpenAiChatCompatibilityResolver(
    private val mode: OpenAiChatCompatibilityMode,
) : OpenAiChatCompatibilityResolver {
    override fun resolve(profile: ProviderConnectionProfile): OpenAiChatCompatibilityMode = mode
}

fun interface OpenAiChatCapabilityResolver {
    suspend fun resolve(
        profile: ProviderConnectionProfile,
        modelId: ProviderModelId,
        mode: OpenAiChatCompatibilityMode,
        verifiedAt: Long,
        adapterVersion: String,
    ): ProviderCapabilitySnapshot
}

class ConservativeOpenAiChatCapabilityResolver : OpenAiChatCapabilityResolver {
    override suspend fun resolve(
        profile: ProviderConnectionProfile,
        modelId: ProviderModelId,
        mode: OpenAiChatCompatibilityMode,
        verifiedAt: Long,
        adapterVersion: String,
    ): ProviderCapabilitySnapshot = mode.capabilities(modelId, verifiedAt, adapterVersion)
}

class RegistryBackedOpenAiChatCapabilityResolver(
    private val registry: ProviderCapabilityRegistry,
) : OpenAiChatCapabilityResolver {
    override suspend fun resolve(
        profile: ProviderConnectionProfile,
        modelId: ProviderModelId,
        mode: OpenAiChatCompatibilityMode,
        verifiedAt: Long,
        adapterVersion: String,
    ): ProviderCapabilitySnapshot = registry.resolve(
        profile = profile,
        modelId = modelId,
        adapterVersion = adapterVersion,
        builtIn = mode.capabilities(modelId, verifiedAt, adapterVersion),
    )
}

internal enum class MaximumTokenField {
    MAX_COMPLETION_TOKENS,
    MAX_TOKENS,
    OMIT,
}

internal enum class StructuredOutputEncoding {
    JSON_SCHEMA,
    JSON_OBJECT,
    OMIT,
}

internal data class OpenAiChatProtocolPolicy(
    val mode: OpenAiChatCompatibilityMode,
    val maximumTokenField: MaximumTokenField,
    val structuredOutputEncoding: StructuredOutputEncoding,
    val useSystemMessage: Boolean,
    val includeStreamUsage: Boolean,
)

internal fun OpenAiChatCompatibilityMode.protocolPolicy(): OpenAiChatProtocolPolicy = when (this) {
    OpenAiChatCompatibilityMode.OPENAI -> OpenAiChatProtocolPolicy(
        mode = this,
        maximumTokenField = MaximumTokenField.MAX_COMPLETION_TOKENS,
        structuredOutputEncoding = StructuredOutputEncoding.JSON_SCHEMA,
        useSystemMessage = true,
        includeStreamUsage = true,
    )
    OpenAiChatCompatibilityMode.DEEPSEEK -> OpenAiChatProtocolPolicy(
        mode = this,
        maximumTokenField = MaximumTokenField.MAX_TOKENS,
        structuredOutputEncoding = StructuredOutputEncoding.JSON_OBJECT,
        useSystemMessage = true,
        includeStreamUsage = true,
    )
    OpenAiChatCompatibilityMode.RELAY_MINIMAL -> OpenAiChatProtocolPolicy(
        mode = this,
        maximumTokenField = MaximumTokenField.OMIT,
        structuredOutputEncoding = StructuredOutputEncoding.OMIT,
        useSystemMessage = false,
        includeStreamUsage = false,
    )
}

internal fun OpenAiChatCompatibilityMode.capabilities(
    modelId: ProviderModelId,
    verifiedAt: Long,
    adapterVersion: String,
): ProviderCapabilitySnapshot {
    val known = this != OpenAiChatCompatibilityMode.RELAY_MINIMAL
    val supportedWhenKnown = if (known) CapabilitySupport.SUPPORTED else CapabilitySupport.UNKNOWN
    val unsupportedWhenRelay = if (known) CapabilitySupport.SUPPORTED else CapabilitySupport.UNSUPPORTED
    return ProviderCapabilitySnapshot(
        protocol = ProviderProtocol.OPENAI_CHAT_COMPAT,
        modelId = modelId,
        streaming = CapabilitySupport.SUPPORTED,
        streamFormat = ProviderStreamFormat.SSE,
        structuredOutput = supportedWhenKnown,
        usageInStream = supportedWhenKnown,
        systemInstruction = unsupportedWhenRelay,
        temperature = supportedWhenKnown,
        topP = supportedWhenKnown,
        maxOutputTokensParameter = supportedWhenKnown,
        seed = if (this == OpenAiChatCompatibilityMode.OPENAI) {
            CapabilitySupport.SUPPORTED
        } else if (known) {
            CapabilitySupport.UNSUPPORTED
        } else {
            CapabilitySupport.UNKNOWN
        },
        reasoningEffort = supportedWhenKnown,
        idempotencyKey = CapabilitySupport.UNKNOWN,
        contextLimit = null,
        maxOutputTokens = null,
        tokenizerFamily = TokenizerFamily.UNKNOWN,
        source = CapabilitySource.BUILT_IN,
        verifiedAt = verifiedAt.coerceAtLeast(0),
        expiresAt = null,
        adapterVersion = adapterVersion,
    )
}

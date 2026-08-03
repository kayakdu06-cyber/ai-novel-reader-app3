package app.zhijuan.provider.common

enum class CapabilitySupport {
    SUPPORTED,
    UNSUPPORTED,
    UNKNOWN,
}

enum class ProviderStreamFormat {
    SSE,
    NDJSON,
    NONE,
    UNKNOWN,
}

enum class ProviderRequestField {
    STREAMING,
    TEMPERATURE,
    TOP_P,
    MAX_OUTPUT_TOKENS,
    SEED,
    STRUCTURED_OUTPUT,
    STREAM_USAGE,
    SYSTEM_INSTRUCTION,
    REASONING_EFFORT,
    IDEMPOTENCY_KEY,
}

enum class CapabilitySource {
    BUILT_IN,
    OFFICIAL_METADATA,
    PROBED,
    USER_OVERRIDE,
    CONSERVATIVE_DEFAULT,
}

enum class TokenizerFamily {
    CL100K_BASE,
    O200K_BASE,
    ANTHROPIC,
    GOOGLE,
    LLAMA,
    UNKNOWN,
}

data class ProviderCapabilitySnapshot(
    val protocol: ProviderProtocol,
    val modelId: ProviderModelId,
    val streaming: CapabilitySupport,
    val streamFormat: ProviderStreamFormat,
    val structuredOutput: CapabilitySupport,
    val usageInStream: CapabilitySupport,
    val systemInstruction: CapabilitySupport,
    val temperature: CapabilitySupport,
    val topP: CapabilitySupport,
    val maxOutputTokensParameter: CapabilitySupport,
    val seed: CapabilitySupport,
    val reasoningEffort: CapabilitySupport,
    val idempotencyKey: CapabilitySupport,
    val contextLimit: Int?,
    val maxOutputTokens: Int?,
    val tokenizerFamily: TokenizerFamily,
    val source: CapabilitySource,
    val verifiedAt: Long,
    val expiresAt: Long?,
    val adapterVersion: String,
) {
    init {
        require(contextLimit == null || contextLimit >= 1_024) { "Context limit is invalid." }
        require(maxOutputTokens == null || maxOutputTokens > 0) { "Maximum output tokens are invalid." }
        require(contextLimit == null || maxOutputTokens == null || maxOutputTokens <= contextLimit) {
            "Maximum output cannot exceed the context limit."
        }
        require(verifiedAt >= 0) { "Capability verification time is invalid." }
        require(expiresAt == null || expiresAt >= verifiedAt) { "Capability expiry is invalid." }
        require(adapterVersion.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"))) {
            "Adapter version is invalid."
        }
        require(streaming != CapabilitySupport.UNSUPPORTED || streamFormat == ProviderStreamFormat.NONE)
        require(streaming != CapabilitySupport.SUPPORTED || streamFormat in setOf(
            ProviderStreamFormat.SSE,
            ProviderStreamFormat.NDJSON,
        ))
    }

    fun supportFor(field: ProviderRequestField): CapabilitySupport = when (field) {
        ProviderRequestField.STREAMING -> streaming
        ProviderRequestField.TEMPERATURE -> temperature
        ProviderRequestField.TOP_P -> topP
        ProviderRequestField.MAX_OUTPUT_TOKENS -> maxOutputTokensParameter
        ProviderRequestField.SEED -> seed
        ProviderRequestField.STRUCTURED_OUTPUT -> structuredOutput
        ProviderRequestField.STREAM_USAGE -> usageInStream
        ProviderRequestField.SYSTEM_INSTRUCTION -> systemInstruction
        ProviderRequestField.REASONING_EFFORT -> reasoningEffort
        ProviderRequestField.IDEMPOTENCY_KEY -> idempotencyKey
    }

    fun maySend(field: ProviderRequestField): Boolean =
        supportFor(field) == CapabilitySupport.SUPPORTED

    companion object {
        fun conservativeUnknown(
            protocol: ProviderProtocol,
            modelId: ProviderModelId,
            verifiedAt: Long,
            adapterVersion: String,
        ): ProviderCapabilitySnapshot = ProviderCapabilitySnapshot(
            protocol = protocol,
            modelId = modelId,
            streaming = CapabilitySupport.UNKNOWN,
            streamFormat = ProviderStreamFormat.UNKNOWN,
            structuredOutput = CapabilitySupport.UNKNOWN,
            usageInStream = CapabilitySupport.UNKNOWN,
            systemInstruction = CapabilitySupport.UNKNOWN,
            temperature = CapabilitySupport.UNKNOWN,
            topP = CapabilitySupport.UNKNOWN,
            maxOutputTokensParameter = CapabilitySupport.UNKNOWN,
            seed = CapabilitySupport.UNKNOWN,
            reasoningEffort = CapabilitySupport.UNKNOWN,
            idempotencyKey = CapabilitySupport.UNKNOWN,
            contextLimit = null,
            maxOutputTokens = null,
            tokenizerFamily = TokenizerFamily.UNKNOWN,
            source = CapabilitySource.CONSERVATIVE_DEFAULT,
            verifiedAt = verifiedAt,
            expiresAt = null,
            adapterVersion = adapterVersion,
        )
    }
}

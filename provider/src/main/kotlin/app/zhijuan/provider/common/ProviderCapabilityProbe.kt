package app.zhijuan.provider.common

enum class CapabilityProbeOutcome {
    SUPPORTED,
    EXPLICITLY_UNSUPPORTED,
    INCONCLUSIVE,
}

data class ProviderCapabilityProbeEvidence(
    val streaming: CapabilityProbeOutcome = CapabilityProbeOutcome.INCONCLUSIVE,
    val observedStreamFormat: ProviderStreamFormat = ProviderStreamFormat.UNKNOWN,
    val structuredOutput: CapabilityProbeOutcome = CapabilityProbeOutcome.INCONCLUSIVE,
    val usageInStream: CapabilityProbeOutcome = CapabilityProbeOutcome.INCONCLUSIVE,
    val systemInstruction: CapabilityProbeOutcome = CapabilityProbeOutcome.INCONCLUSIVE,
    val temperature: CapabilityProbeOutcome = CapabilityProbeOutcome.INCONCLUSIVE,
    val topP: CapabilityProbeOutcome = CapabilityProbeOutcome.INCONCLUSIVE,
    val maxOutputTokensParameter: CapabilityProbeOutcome = CapabilityProbeOutcome.INCONCLUSIVE,
    val seed: CapabilityProbeOutcome = CapabilityProbeOutcome.INCONCLUSIVE,
    val reasoningEffort: CapabilityProbeOutcome = CapabilityProbeOutcome.INCONCLUSIVE,
    val idempotencyKey: CapabilityProbeOutcome = CapabilityProbeOutcome.INCONCLUSIVE,
    val contextLimit: Int? = null,
    val maxOutputTokens: Int? = null,
    val tokenizerFamily: TokenizerFamily = TokenizerFamily.UNKNOWN,
) {
    init {
        require(
            streaming == CapabilityProbeOutcome.SUPPORTED &&
                observedStreamFormat in setOf(ProviderStreamFormat.SSE, ProviderStreamFormat.NDJSON) ||
                streaming == CapabilityProbeOutcome.EXPLICITLY_UNSUPPORTED &&
                observedStreamFormat == ProviderStreamFormat.NONE ||
                streaming == CapabilityProbeOutcome.INCONCLUSIVE &&
                observedStreamFormat == ProviderStreamFormat.UNKNOWN,
        ) { "Probe stream evidence is inconsistent." }
        require(contextLimit == null || contextLimit >= 1_024) { "Probed context limit is invalid." }
        require(maxOutputTokens == null || maxOutputTokens > 0) { "Probed output limit is invalid." }
        require(contextLimit == null || maxOutputTokens == null || maxOutputTokens <= contextLimit) {
            "Probed output limit exceeds the context limit."
        }
    }

    fun toSnapshot(
        protocol: ProviderProtocol,
        modelId: ProviderModelId,
        adapterVersion: String,
        verifiedAt: Long,
        validForMillis: Long,
    ): ProviderCapabilitySnapshot {
        require(validForMillis in MINIMUM_TTL_MILLIS..MAXIMUM_TTL_MILLIS) {
            "Capability probe lifetime is outside the safe refresh window."
        }
        require(verifiedAt >= 0 && verifiedAt <= Long.MAX_VALUE - validForMillis) {
            "Capability probe verification time is invalid."
        }
        return ProviderCapabilitySnapshot(
            protocol = protocol,
            modelId = modelId,
            streaming = streaming.toSupport(),
            streamFormat = observedStreamFormat,
            structuredOutput = structuredOutput.toSupport(),
            usageInStream = usageInStream.toSupport(),
            systemInstruction = systemInstruction.toSupport(),
            temperature = temperature.toSupport(),
            topP = topP.toSupport(),
            maxOutputTokensParameter = maxOutputTokensParameter.toSupport(),
            seed = seed.toSupport(),
            reasoningEffort = reasoningEffort.toSupport(),
            idempotencyKey = idempotencyKey.toSupport(),
            contextLimit = contextLimit,
            maxOutputTokens = maxOutputTokens,
            tokenizerFamily = tokenizerFamily,
            source = CapabilitySource.PROBED,
            verifiedAt = verifiedAt,
            expiresAt = verifiedAt + validForMillis,
            adapterVersion = adapterVersion,
        )
    }

    private fun CapabilityProbeOutcome.toSupport(): CapabilitySupport = when (this) {
        CapabilityProbeOutcome.SUPPORTED -> CapabilitySupport.SUPPORTED
        CapabilityProbeOutcome.EXPLICITLY_UNSUPPORTED -> CapabilitySupport.UNSUPPORTED
        CapabilityProbeOutcome.INCONCLUSIVE -> CapabilitySupport.UNKNOWN
    }

    companion object {
        const val MINIMUM_TTL_MILLIS = 60L * 60 * 1_000
        const val DEFAULT_TTL_MILLIS = 7L * 24 * 60 * 60 * 1_000
        const val MAXIMUM_TTL_MILLIS = 30L * 24 * 60 * 60 * 1_000
    }
}

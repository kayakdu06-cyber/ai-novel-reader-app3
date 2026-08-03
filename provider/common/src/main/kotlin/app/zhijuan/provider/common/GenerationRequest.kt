package app.zhijuan.provider.common

enum class PromptLayer {
    APPLICATION_HARD_RULES,
    STAGE_CONTRACT,
    STORY_BIBLE,
    CURRENT_PLAN,
    RUNTIME_MEMORY,
    RECENT_SUMMARY,
    WRITING_STYLE,
    USER_REQUEST,
}

class PromptPart(
    val layer: PromptLayer,
    val content: SensitiveProviderText,
) {
    override fun toString(): String =
        "PromptPart(layer=" + layer.name + ", characters=" + content.characterCount + ")"
}

class ProviderPrompt(parts: List<PromptPart>) {
    private val parts = parts.toList()

    val partCount: Int
        get() = parts.size

    val characterCount: Int
        get() = parts.sumOf { it.content.characterCount }

    fun <T> withParts(block: (List<PromptPart>) -> T): T = block(parts)

    override fun toString(): String =
        "ProviderPrompt(parts=$partCount, characters=$characterCount)"

    init {
        require(parts.isNotEmpty()) { "Provider prompt must not be empty." }
        require(parts.size <= 64) { "Provider prompt has too many parts." }
        require(parts.any { it.layer == PromptLayer.STAGE_CONTRACT }) {
            "Provider prompt must include a stage contract."
        }
        require(parts.zipWithNext().all { (first, second) -> first.layer.ordinal <= second.layer.ordinal }) {
            "Provider prompt layers must use stable precedence order."
        }
        require(characterCount in 1..SensitiveProviderText.MAX_CHARACTERS) {
            "Provider prompt size is invalid."
        }
    }
}

enum class ReasoningEffort {
    LOW,
    MEDIUM,
    HIGH,
}

data class GenerationParameters(
    val temperature: Double? = null,
    val topP: Double? = null,
    val maxOutputTokens: Int? = null,
    val seed: Long? = null,
    val reasoningEffort: ReasoningEffort? = null,
) {
    init {
        require(temperature == null || temperature.isFinite() && temperature in 0.0..2.0)
        require(topP == null || topP.isFinite() && topP > 0.0 && topP <= 1.0)
        require(maxOutputTokens == null || maxOutputTokens > 0)
    }
}

class ProviderJsonSchema private constructor(
    private val value: String,
) {
    val characterCount: Int
        get() = value.length

    fun <T> withValue(block: (String) -> T): T = block(value)

    override fun toString(): String = "<json-schema characters=$characterCount>"

    companion object {
        fun from(value: String): ProviderJsonSchema {
            val trimmed = value.trim()
            require(trimmed.startsWith("{") && trimmed.endsWith("}")) {
                "Structured output schema must be a JSON object."
            }
            require(trimmed.length <= 256_000) { "Structured output schema is too large." }
            return ProviderJsonSchema(trimmed)
        }
    }
}

data class ProviderTimeoutPolicy(
    val connectMillis: Long,
    val firstByteMillis: Long,
    val streamIdleMillis: Long,
    val totalStageMillis: Long,
) {
    init {
        require(connectMillis in 1_000..120_000)
        require(firstByteMillis in connectMillis..300_000)
        require(streamIdleMillis in 1_000..300_000)
        require(totalStageMillis >= firstByteMillis && totalStageMillis <= 3_600_000)
    }
}

class GenerationRequest(
    val requestId: String,
    val generationId: String,
    val stageId: String,
    val attemptId: String,
    val modelId: ProviderModelId,
    val prompt: ProviderPrompt,
    val parameters: GenerationParameters,
    val structuredOutputSchema: ProviderJsonSchema?,
    val stream: Boolean,
    val timeouts: ProviderTimeoutPolicy,
    val idempotencyKey: String?,
) {
    init {
        listOf(requestId, generationId, stageId, attemptId).forEach {
            require(it.matches(IDENTIFIER_PATTERN)) { "Generation request identifier is invalid." }
        }
        require(idempotencyKey == null || idempotencyKey.matches(IDEMPOTENCY_PATTERN)) {
            "Idempotency key is invalid."
        }
    }

    fun fieldsRequested(): Set<ProviderRequestField> = buildSet {
        if (stream) add(ProviderRequestField.STREAMING)
        parameters.temperature?.let { add(ProviderRequestField.TEMPERATURE) }
        parameters.topP?.let { add(ProviderRequestField.TOP_P) }
        parameters.maxOutputTokens?.let { add(ProviderRequestField.MAX_OUTPUT_TOKENS) }
        parameters.seed?.let { add(ProviderRequestField.SEED) }
        parameters.reasoningEffort?.let { add(ProviderRequestField.REASONING_EFFORT) }
        structuredOutputSchema?.let { add(ProviderRequestField.STRUCTURED_OUTPUT) }
        idempotencyKey?.let { add(ProviderRequestField.IDEMPOTENCY_KEY) }
    }

    fun unsupportedFields(
        profile: ProviderConnectionProfile,
        capabilities: ProviderCapabilitySnapshot,
    ): Set<ProviderRequestField> {
        require(profile.protocol == capabilities.protocol) {
            "Capability snapshot protocol does not match the connection profile."
        }
        require(modelId == capabilities.modelId) {
            "Capability snapshot model does not match the generation request."
        }
        return fieldsRequested().filterNot(capabilities::maySend).toSet()
    }

    override fun toString(): String =
        "GenerationRequest(requestId=" + requestId +
            ", promptParts=" + prompt.partCount +
            ", promptCharacters=" + prompt.characterCount +
            ", stream=" + stream +
            ", structuredOutput=" + (structuredOutputSchema != null) + ")"

    private companion object {
        val IDENTIFIER_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
        val IDEMPOTENCY_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{7,255}")
    }
}

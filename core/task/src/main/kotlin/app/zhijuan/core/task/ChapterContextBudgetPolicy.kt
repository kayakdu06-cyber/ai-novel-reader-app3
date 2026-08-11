package app.zhijuan.core.task

import java.security.MessageDigest
import kotlin.math.ceil

enum class ChapterContextLimitSource {
    OFFICIAL_METADATA,
    PROBED,
    USER_CONFIRMED,
    UNKNOWN,
}

data class ChapterContextBudgetSpec(
    val contextLimitTokens: Int?,
    val maximumOutputTokens: Int?,
    val requestedOutputTokens: Int,
    val limitSource: ChapterContextLimitSource,
    val unknownLimitConfirmed: Boolean,
    val tokenizerFamily: String,
) {
    init {
        require(contextLimitTokens == null || contextLimitTokens >= 1_024) {
            "Context limit must be at least 1,024 tokens."
        }
        require(maximumOutputTokens == null || maximumOutputTokens > 0) {
            "Maximum output tokens must be positive when known."
        }
        require(
            contextLimitTokens == null || maximumOutputTokens == null ||
                maximumOutputTokens <= contextLimitTokens,
        ) { "Maximum output tokens cannot exceed the context limit." }
        require(requestedOutputTokens in 1..MAX_REQUESTED_OUTPUT_TOKENS) {
            "Requested output tokens are outside the supported range."
        }
        require(TOKENIZER_ID.matches(tokenizerFamily)) { "Tokenizer family id is invalid." }
        require(limitSource != ChapterContextLimitSource.UNKNOWN || contextLimitTokens == null) {
            "A known context limit requires a non-unknown source."
        }
    }

    private companion object {
        const val MAX_REQUESTED_OUTPUT_TOKENS = 1_000_000
        val TOKENIZER_ID = Regex("[A-Za-z0-9._-]{1,64}")
    }
}

enum class ChapterContextKind(
    val requiredByPolicy: Boolean,
    internal val precedence: Int,
) {
    APPLICATION_HARD_RULE(true, 10),
    STAGE_CONTRACT(true, 20),
    ADULT_AND_IDENTITY_FACT(true, 30),
    BIBLE_WORLD_RULE(true, 40),
    BIBLE_HARD_FACT(true, 50),
    FORBIDDEN_CHANGE(true, 60),
    TARGET_ARC(true, 70),
    TARGET_CHAPTER_PLAN(true, 80),
    PREVIOUS_CHAPTER_SUMMARY(true, 90),
    CURRENT_STATE(true, 100),
    DUE_FORESHADOW(true, 110),
    WRITING_STYLE(true, 120),
    USER_ADDITION(true, 130),
    BIBLE_THEME(false, 200),
    RECENT_CHAPTER_SUMMARY(false, 210),
    RUNTIME_HISTORY(false, 220),
    TIMELINE_HISTORY(false, 230),
    OPEN_FORESHADOW(false, 240),
    DISTANT_PLAN(false, 250),
}

data class ChapterContextSourceRef(
    val sourceType: String,
    val sourceId: String,
    val sourceVersionId: String?,
    val sourceContentHash: String,
) {
    init {
        require(SOURCE_TYPE.matches(sourceType)) { "Context source type is invalid." }
        require(IDENTIFIER.matches(sourceId)) { "Context source id is invalid." }
        require(sourceVersionId == null || IDENTIFIER.matches(sourceVersionId)) {
            "Context source version id is invalid."
        }
        require(SHA_256.matches(sourceContentHash)) { "Context source hash is invalid." }
    }

    private companion object {
        val SOURCE_TYPE = Regex("[A-Z][A-Z0-9_]{0,63}")
        val IDENTIFIER = Regex("[A-Za-z0-9._:-]{1,128}")
        val SHA_256 = Regex("[0-9a-f]{64}")
    }
}

data class ChapterContextCandidate(
    val itemId: String,
    val kind: ChapterContextKind,
    val content: String,
    val source: ChapterContextSourceRef,
    val relevanceMicros: Int = 0,
    val importance: Int = 0,
    val chapterIndex: Int? = null,
    val storyOrder: Long? = null,
) {
    val contentHash: String = sha256Utf8(content)

    init {
        require(IDENTIFIER.matches(itemId)) { "Context item id is invalid." }
        require(content.isNotBlank() && content.toByteArray(Charsets.UTF_8).size <= MAX_ITEM_BYTES) {
            "Context item content is empty or too large."
        }
        require(relevanceMicros in 0..1_000_000) { "Context relevance is invalid." }
        require(importance in 0..100) { "Context importance is invalid." }
        require(chapterIndex == null || chapterIndex >= 1) { "Context chapter index is invalid." }
        require(storyOrder == null || storyOrder >= 0L) { "Context story order is invalid." }
    }

    override fun toString(): String =
        "ChapterContextCandidate(itemId=$itemId, kind=$kind, content=redacted, source=redacted)"

    private companion object {
        val IDENTIFIER = Regex("[A-Za-z0-9._:-]{1,128}")
        const val MAX_ITEM_BYTES = 64 * 1_024
    }
}

enum class ChapterContextBlockReason {
    UNKNOWN_CONTEXT_LIMIT_REQUIRES_CONFIRMATION,
    OUTPUT_RESERVE_LEAVES_NO_INPUT_BUDGET,
    REQUIRED_SOURCE_MISSING,
    REQUIRED_CONTEXT_EXCEEDS_BUDGET,
    MANDATORY_MEMORY_SELECTION_EXCEEDS_LIMIT,
    MEMORY_SEARCH_INDEX_INVALID,
}

sealed interface ChapterContextAssemblyResult {
    data class Ready(
        val providerPayloadJson: String,
        val sourceManifestJson: String,
        val contentHash: String,
        val effectiveContextLimitTokens: Int,
        val outputReserveTokens: Int,
        val safetyReserveTokens: Int,
        val inputBudgetTokens: Int,
        val estimatedInputTokens: Int,
        val selectedItemCount: Int,
        val omittedItemCount: Int,
        val usedConservativeLimit: Boolean,
    ) : ChapterContextAssemblyResult {
        override fun toString(): String =
            "ChapterContextAssemblyResult.Ready(selected=$selectedItemCount, omitted=$omittedItemCount, " +
                "tokens=$estimatedInputTokens/$inputBudgetTokens, content=redacted)"
    }

    data class Blocked(
        val reason: ChapterContextBlockReason,
        val effectiveContextLimitTokens: Int?,
        val inputBudgetTokens: Int?,
        val requiredEstimatedTokens: Int?,
        val missingKinds: Set<ChapterContextKind> = emptySet(),
    ) : ChapterContextAssemblyResult
}

object ChapterContextBudgetPolicyV1 {
    const val POLICY_VERSION = "zhijuan.chapter-context-policy.v1"
    const val MANIFEST_SCHEMA_ID = "chapter-context-manifest.v1"
    const val CONSERVATIVE_UNKNOWN_CONTEXT_LIMIT = 8_192
    const val SAFETY_PERCENT = 10
    const val PROVIDER_ENVELOPE_RESERVE_TOKENS = 128
    const val MAX_CANDIDATES = 2_048

    private val REQUIRED_BASE_KINDS = setOf(
        ChapterContextKind.APPLICATION_HARD_RULE,
        ChapterContextKind.STAGE_CONTRACT,
        ChapterContextKind.ADULT_AND_IDENTITY_FACT,
        ChapterContextKind.BIBLE_WORLD_RULE,
        ChapterContextKind.BIBLE_HARD_FACT,
        ChapterContextKind.FORBIDDEN_CHANGE,
        ChapterContextKind.TARGET_ARC,
        ChapterContextKind.TARGET_CHAPTER_PLAN,
        ChapterContextKind.WRITING_STYLE,
    )

    fun assemble(
        targetChapterIndex: Int,
        budget: ChapterContextBudgetSpec,
        candidates: List<ChapterContextCandidate>,
    ): ChapterContextAssemblyResult {
        require(targetChapterIndex >= 1) { "Target chapter index is invalid." }
        require(candidates.size <= MAX_CANDIDATES) { "Too many context candidates." }
        require(candidates.map(ChapterContextCandidate::itemId).distinct().size == candidates.size) {
            "Context candidate ids must be unique."
        }

        val knownLimit = budget.contextLimitTokens
        if (knownLimit == null && !budget.unknownLimitConfirmed) {
            return ChapterContextAssemblyResult.Blocked(
                reason = ChapterContextBlockReason.UNKNOWN_CONTEXT_LIMIT_REQUIRES_CONFIRMATION,
                effectiveContextLimitTokens = null,
                inputBudgetTokens = null,
                requiredEstimatedTokens = null,
            )
        }
        val effectiveLimit = knownLimit ?: CONSERVATIVE_UNKNOWN_CONTEXT_LIMIT
        val outputReserve = minOf(
            budget.requestedOutputTokens,
            budget.maximumOutputTokens ?: budget.requestedOutputTokens,
        )
        val safetyReserve = ceil(effectiveLimit * (SAFETY_PERCENT / 100.0)).toInt()
        val inputBudget = effectiveLimit - outputReserve - safetyReserve
        if (inputBudget <= PROVIDER_ENVELOPE_RESERVE_TOKENS) {
            return ChapterContextAssemblyResult.Blocked(
                reason = ChapterContextBlockReason.OUTPUT_RESERVE_LEAVES_NO_INPUT_BUDGET,
                effectiveContextLimitTokens = effectiveLimit,
                inputBudgetTokens = inputBudget.coerceAtLeast(0),
                requiredEstimatedTokens = null,
            )
        }

        val kinds = candidates.map(ChapterContextCandidate::kind).toSet()
        val expectedKinds = if (targetChapterIndex == 1) {
            REQUIRED_BASE_KINDS
        } else {
            REQUIRED_BASE_KINDS + ChapterContextKind.PREVIOUS_CHAPTER_SUMMARY
        }
        val missingKinds = expectedKinds - kinds
        if (missingKinds.isNotEmpty()) {
            return ChapterContextAssemblyResult.Blocked(
                reason = ChapterContextBlockReason.REQUIRED_SOURCE_MISSING,
                effectiveContextLimitTokens = effectiveLimit,
                inputBudgetTokens = inputBudget,
                requiredEstimatedTokens = null,
                missingKinds = missingKinds,
            )
        }

        val required = candidates
            .filter { it.kind.requiredByPolicy }
            .sortedWith(REQUIRED_ORDER)
        val requiredPayload = providerPayload(targetChapterIndex, required)
        val requiredEstimate = estimatedTokens(requiredPayload)
        if (requiredEstimate > inputBudget) {
            return ChapterContextAssemblyResult.Blocked(
                reason = ChapterContextBlockReason.REQUIRED_CONTEXT_EXCEEDS_BUDGET,
                effectiveContextLimitTokens = effectiveLimit,
                inputBudgetTokens = inputBudget,
                requiredEstimatedTokens = requiredEstimate,
            )
        }

        val selected = required.toMutableList()
        val omitted = mutableListOf<ChapterContextCandidate>()
        candidates.filterNot { it.kind.requiredByPolicy }
            .sortedWith(OPTIONAL_ORDER)
            .forEach { candidate ->
                val proposal = selected + candidate
                if (estimatedTokens(providerPayload(targetChapterIndex, proposal)) <= inputBudget) {
                    selected += candidate
                } else {
                    omitted += candidate
                }
            }
        val payload = providerPayload(targetChapterIndex, selected)
        val payloadHash = sha256Utf8(payload)
        val estimate = estimatedTokens(payload)
        val manifest = sourceManifest(
            targetChapterIndex = targetChapterIndex,
            budget = budget,
            effectiveLimit = effectiveLimit,
            outputReserve = outputReserve,
            safetyReserve = safetyReserve,
            inputBudget = inputBudget,
            estimatedInput = estimate,
            usedConservativeLimit = knownLimit == null,
            payloadHash = payloadHash,
            selected = selected,
            omitted = omitted,
        )
        return ChapterContextAssemblyResult.Ready(
            providerPayloadJson = payload,
            sourceManifestJson = manifest,
            contentHash = payloadHash,
            effectiveContextLimitTokens = effectiveLimit,
            outputReserveTokens = outputReserve,
            safetyReserveTokens = safetyReserve,
            inputBudgetTokens = inputBudget,
            estimatedInputTokens = estimate,
            selectedItemCount = selected.size,
            omittedItemCount = omitted.size,
            usedConservativeLimit = knownLimit == null,
        )
    }

    fun conservativeTokenUpperBound(value: String): Int = value.toByteArray(Charsets.UTF_8).size

    private fun estimatedTokens(payload: String): Int = Math.addExact(
        conservativeTokenUpperBound(payload),
        PROVIDER_ENVELOPE_RESERVE_TOKENS,
    )

    private fun providerPayload(
        targetChapterIndex: Int,
        items: List<ChapterContextCandidate>,
    ): String = buildString {
        append("{\"schemaVersion\":1,\"policyVersion\":")
        appendQuoted(POLICY_VERSION)
        append(",\"targetChapterIndex\":")
        append(targetChapterIndex)
        append(",\"layers\":[")
        items.forEachIndexed { index, item ->
            if (index > 0) append(',')
            append("{\"itemId\":")
            appendQuoted(item.itemId)
            append(",\"kind\":")
            appendQuoted(item.kind.name)
            append(",\"content\":")
            appendQuoted(item.content)
            append('}')
        }
        append("]}")
    }

    private fun sourceManifest(
        targetChapterIndex: Int,
        budget: ChapterContextBudgetSpec,
        effectiveLimit: Int,
        outputReserve: Int,
        safetyReserve: Int,
        inputBudget: Int,
        estimatedInput: Int,
        usedConservativeLimit: Boolean,
        payloadHash: String,
        selected: List<ChapterContextCandidate>,
        omitted: List<ChapterContextCandidate>,
    ): String = buildString {
        append("{\"schemaVersion\":1,\"schemaId\":")
        appendQuoted(MANIFEST_SCHEMA_ID)
        append(",\"policyVersion\":")
        appendQuoted(POLICY_VERSION)
        append(",\"targetChapterIndex\":")
        append(targetChapterIndex)
        append(",\"budget\":{\"contextLimitTokens\":")
        append(effectiveLimit)
        append(",\"outputReserveTokens\":")
        append(outputReserve)
        append(",\"safetyReserveTokens\":")
        append(safetyReserve)
        append(",\"inputBudgetTokens\":")
        append(inputBudget)
        append(",\"estimatedInputTokens\":")
        append(estimatedInput)
        append(",\"limitSource\":")
        appendQuoted(budget.limitSource.name)
        append(",\"usedConservativeLimit\":")
        append(usedConservativeLimit)
        append(",\"tokenizerFamily\":")
        appendQuoted(budget.tokenizerFamily)
        append("},\"providerPayloadHash\":")
        appendQuoted(payloadHash)
        append(",\"selected\":[")
        selected.forEachIndexed { index, item ->
            if (index > 0) append(',')
            appendManifestItem(item, includeContent = true)
        }
        append("],\"omitted\":[")
        omitted.forEachIndexed { index, item ->
            if (index > 0) append(',')
            appendManifestItem(item, includeContent = false)
        }
        append("]}")
    }

    private fun StringBuilder.appendManifestItem(
        item: ChapterContextCandidate,
        includeContent: Boolean,
    ) {
        append("{\"itemId\":")
        appendQuoted(item.itemId)
        append(",\"kind\":")
        appendQuoted(item.kind.name)
        append(",\"required\":")
        append(item.kind.requiredByPolicy)
        append(",\"contentHash\":")
        appendQuoted(item.contentHash)
        append(",\"sourceType\":")
        appendQuoted(item.source.sourceType)
        append(",\"sourceId\":")
        appendQuoted(item.source.sourceId)
        append(",\"sourceVersionId\":")
        if (item.source.sourceVersionId == null) append("null") else appendQuoted(item.source.sourceVersionId)
        append(",\"sourceContentHash\":")
        appendQuoted(item.source.sourceContentHash)
        append(",\"relevanceMicros\":")
        append(item.relevanceMicros)
        append(",\"importance\":")
        append(item.importance)
        append(",\"chapterIndex\":")
        if (item.chapterIndex == null) append("null") else append(item.chapterIndex)
        append(",\"storyOrder\":")
        if (item.storyOrder == null) append("null") else append(item.storyOrder)
        if (includeContent) {
            append(",\"content\":")
            appendQuoted(item.content)
        }
        append('}')
    }

    private fun StringBuilder.appendQuoted(value: String) {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) {
                    append("\\u")
                    append(character.code.toString(16).padStart(4, '0'))
                } else {
                    append(character)
                }
            }
        }
        append('"')
    }

    private val REQUIRED_ORDER = compareBy<ChapterContextCandidate>(
        { it.kind.precedence },
        { it.chapterIndex ?: Int.MIN_VALUE },
        { it.storyOrder ?: Long.MIN_VALUE },
        ChapterContextCandidate::itemId,
    )

    private val OPTIONAL_ORDER = compareBy<ChapterContextCandidate> { it.kind.precedence }
        .thenByDescending(ChapterContextCandidate::relevanceMicros)
        .thenByDescending(ChapterContextCandidate::importance)
        .thenByDescending { it.chapterIndex ?: Int.MIN_VALUE }
        .thenByDescending { it.storyOrder ?: Long.MIN_VALUE }
        .thenBy(ChapterContextCandidate::itemId)
}

private fun sha256Utf8(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString(separator = "") { byte -> "%02x".format(byte) }

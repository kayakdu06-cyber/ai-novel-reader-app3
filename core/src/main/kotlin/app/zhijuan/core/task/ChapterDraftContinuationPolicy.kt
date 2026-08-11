package app.zhijuan.core.task

import java.security.MessageDigest

enum class ChapterDraftContinuationBlockReason {
    EMPTY_PARTIAL_DRAFT,
    NO_SAFE_ANCHOR,
    AUTOMATIC_CONTINUATION_LIMIT_REACHED,
    STAGE_ATTEMPT_LIMIT_REACHED,
    CHAPTER_SIZE_LIMIT_REACHED,
}

data class ChapterDraftContinuationInput(
    val accumulatedText: String,
    val completedTruncations: Int,
    val totalAttemptsUsed: Int,
    val stageMaximumAttempts: Int,
) {
    init {
        require(completedTruncations >= 0)
        require(totalAttemptsUsed >= 1)
        require(stageMaximumAttempts >= 1)
    }
}

sealed interface ChapterDraftContinuationDecision {
    class ContinueAutomatically internal constructor(
        val continuationIndex: Int,
        val anchorCodePoints: Int,
        val tailCodePoints: Int,
        val lastCompleteParagraphCodePoint: Int,
        val accumulatedUtf8Bytes: Int,
        val anchorHash: String,
        private val anchor: String,
        private val tail: String,
    ) : ChapterDraftContinuationDecision {
        fun <T> withAnchor(block: (String) -> T): T = block(anchor)

        fun <T> withTail(block: (String) -> T): T = block(tail)

        override fun toString(): String =
            "ContinueAutomatically(index=$continuationIndex, anchorCodePoints=$anchorCodePoints, " +
                "tailCodePoints=$tailCodePoints, accumulatedUtf8Bytes=$accumulatedUtf8Bytes, content=redacted)"
    }

    data class NeedsAction(
        val reason: ChapterDraftContinuationBlockReason,
        val completedTruncations: Int,
        val totalAttemptsUsed: Int,
    ) : ChapterDraftContinuationDecision
}

object ChapterDraftContinuationPolicyV1 {
    const val POLICY_VERSION = "zhijuan.chapter-continuation-policy.v1"
    const val OUTPUT_CONTRACT_ID = "chapter-draft.v1"
    const val MAXIMUM_AUTOMATIC_CONTINUATIONS = 3
    const val ANCHOR_CODE_POINTS = 96
    const val MINIMUM_ANCHOR_CODE_POINTS = 24
    const val TAIL_WINDOW_CODE_POINTS = 2_048
    const val MAXIMUM_CHAPTER_UTF8_BYTES = 4 * 1_024 * 1_024

    fun evaluate(input: ChapterDraftContinuationInput): ChapterDraftContinuationDecision {
        val utf8Bytes = input.accumulatedText.toByteArray(Charsets.UTF_8).size
        if (input.accumulatedText.isEmpty()) {
            return blocked(input, ChapterDraftContinuationBlockReason.EMPTY_PARTIAL_DRAFT)
        }
        if (utf8Bytes >= MAXIMUM_CHAPTER_UTF8_BYTES) {
            return blocked(input, ChapterDraftContinuationBlockReason.CHAPTER_SIZE_LIMIT_REACHED)
        }
        if (input.completedTruncations >= MAXIMUM_AUTOMATIC_CONTINUATIONS) {
            return blocked(input, ChapterDraftContinuationBlockReason.AUTOMATIC_CONTINUATION_LIMIT_REACHED)
        }
        if (input.totalAttemptsUsed >= input.stageMaximumAttempts) {
            return blocked(input, ChapterDraftContinuationBlockReason.STAGE_ATTEMPT_LIMIT_REACHED)
        }
        val codePoints = input.accumulatedText.codePointCount(0, input.accumulatedText.length)
        if (codePoints < MINIMUM_ANCHOR_CODE_POINTS) {
            return blocked(input, ChapterDraftContinuationBlockReason.NO_SAFE_ANCHOR)
        }
        val anchorCount = minOf(codePoints, ANCHOR_CODE_POINTS)
        val tailCount = minOf(codePoints, TAIL_WINDOW_CODE_POINTS)
        val anchor = takeLastCodePoints(input.accumulatedText, anchorCount)
        val tail = takeLastCodePoints(input.accumulatedText, tailCount)
        return ChapterDraftContinuationDecision.ContinueAutomatically(
            continuationIndex = input.completedTruncations + 1,
            anchorCodePoints = anchorCount,
            tailCodePoints = tailCount,
            lastCompleteParagraphCodePoint = lastCompleteParagraphCodePoint(input.accumulatedText),
            accumulatedUtf8Bytes = utf8Bytes,
            anchorHash = sha256(anchor),
            anchor = anchor,
            tail = tail,
        )
    }

    fun continuationInputHash(
        stageInputVersionHash: String,
        parentOutputHash: String,
        anchorHash: String,
        continuationIndex: Int,
    ): String {
        require(SHA_256.matches(stageInputVersionHash))
        require(SHA_256.matches(parentOutputHash))
        require(SHA_256.matches(anchorHash))
        require(continuationIndex in 1..MAXIMUM_AUTOMATIC_CONTINUATIONS)
        return sha256(
            listOf(POLICY_VERSION, stageInputVersionHash, parentOutputHash, anchorHash, continuationIndex.toString())
                .joinToString(separator = "|") { value -> "${value.length}:$value" },
        )
    }

    private fun blocked(
        input: ChapterDraftContinuationInput,
        reason: ChapterDraftContinuationBlockReason,
    ) = ChapterDraftContinuationDecision.NeedsAction(
        reason = reason,
        completedTruncations = input.completedTruncations,
        totalAttemptsUsed = input.totalAttemptsUsed,
    )

    private fun takeLastCodePoints(value: String, count: Int): String {
        val start = value.offsetByCodePoints(value.length, -count)
        return value.substring(start)
    }

    private fun lastCompleteParagraphCodePoint(value: String): Int {
        val newline = value.lastIndexOf('\n')
        val boundary = if (newline >= 0) {
            newline + 1
        } else {
            val punctuation = value.indexOfLast { it in PARAGRAPH_FALLBACK_BOUNDARIES }
            if (punctuation >= 0) punctuation + 1 else 0
        }
        return value.codePointCount(0, boundary)
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private val SHA_256 = Regex("[0-9a-f]{64}")
    private val PARAGRAPH_FALLBACK_BOUNDARIES = setOf('。', '！', '？', '!', '?')
}

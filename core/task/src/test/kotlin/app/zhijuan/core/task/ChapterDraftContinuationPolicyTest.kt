package app.zhijuan.core.task

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChapterDraftContinuationPolicyTest {
    @Test
    fun `normal truncation produces exact bounded anchor and tail`() {
        val text = (1..240).joinToString("") { "第${it}字" } + "\n未完段落"
        val decision = ready(text, completedTruncations = 0, attempts = 1, maximum = 4)

        assertEquals(1, decision.continuationIndex)
        assertEquals(96, decision.anchorCodePoints)
        assertTrue(decision.tailCodePoints >= decision.anchorCodePoints)
        decision.withAnchor { anchor -> assertTrue(text.endsWith(anchor)) }
        decision.withTail { tail -> assertTrue(text.endsWith(tail)) }
        assertFalse(decision.toString().contains("未完段落"))
    }

    @Test
    fun `unicode anchor never splits a supplementary code point`() {
        val text = "段落。" + "人物🙂继续行动。".repeat(40)
        val decision = ready(text, completedTruncations = 1, attempts = 2, maximum = 4)

        decision.withAnchor { anchor ->
            assertEquals(decision.anchorCodePoints, anchor.codePointCount(0, anchor.length))
            assertTrue(text.endsWith(anchor))
        }
    }

    @Test
    fun `three automatic continuations is a hard limit`() {
        val decision = ChapterDraftContinuationPolicyV1.evaluate(
            input("足够长的候选正文。".repeat(20), completed = 3, attempts = 4, maximum = 6),
        ) as ChapterDraftContinuationDecision.NeedsAction

        assertEquals(
            ChapterDraftContinuationBlockReason.AUTOMATIC_CONTINUATION_LIMIT_REACHED,
            decision.reason,
        )
    }

    @Test
    fun `stage attempt limit can stop before continuation limit`() {
        val decision = ChapterDraftContinuationPolicyV1.evaluate(
            input("足够长的候选正文。".repeat(20), completed = 1, attempts = 2, maximum = 2),
        ) as ChapterDraftContinuationDecision.NeedsAction

        assertEquals(ChapterDraftContinuationBlockReason.STAGE_ATTEMPT_LIMIT_REACHED, decision.reason)
    }

    @Test
    fun `empty or tiny partial draft cannot be guessed into a continuation`() {
        val empty = ChapterDraftContinuationPolicyV1.evaluate(input("", 0, 1, 4))
            as ChapterDraftContinuationDecision.NeedsAction
        val tiny = ChapterDraftContinuationPolicyV1.evaluate(input("太短", 0, 1, 4))
            as ChapterDraftContinuationDecision.NeedsAction

        assertEquals(ChapterDraftContinuationBlockReason.EMPTY_PARTIAL_DRAFT, empty.reason)
        assertEquals(ChapterDraftContinuationBlockReason.NO_SAFE_ANCHOR, tiny.reason)
    }

    @Test
    fun `continuation input fingerprint changes with parent anchor and index`() {
        val hash = "a".repeat(64)
        val first = ChapterDraftContinuationPolicyV1.continuationInputHash(hash, hash, hash, 1)

        assertEquals(first, ChapterDraftContinuationPolicyV1.continuationInputHash(hash, hash, hash, 1))
        assertFalse(first == ChapterDraftContinuationPolicyV1.continuationInputHash(hash, "b".repeat(64), hash, 1))
        assertFalse(first == ChapterDraftContinuationPolicyV1.continuationInputHash(hash, hash, hash, 2))
    }

    private fun ready(
        text: String,
        completedTruncations: Int,
        attempts: Int,
        maximum: Int,
    ) = ChapterDraftContinuationPolicyV1.evaluate(
        input(text, completedTruncations, attempts, maximum),
    ) as ChapterDraftContinuationDecision.ContinueAutomatically

    private fun input(text: String, completed: Int, attempts: Int, maximum: Int) =
        ChapterDraftContinuationInput(
            accumulatedText = text,
            completedTruncations = completed,
            totalAttemptsUsed = attempts,
            stageMaximumAttempts = maximum,
        )
}

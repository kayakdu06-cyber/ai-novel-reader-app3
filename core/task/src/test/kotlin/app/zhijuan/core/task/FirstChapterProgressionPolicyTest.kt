package app.zhijuan.core.task

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class FirstChapterProgressionPolicyTest {
    @Test
    fun fastLaneAllowsOnlyChapterOneWithSeedBootstrapAndHardGate() {
        val evidence = ChapterPlanningEvidence(
            storySeedReady = true,
            firstChapterBootstrapReady = true,
            adultAndHardRuleGatePassed = true,
            storyBibleReady = false,
            masterOutlineReady = false,
            targetChapterWindowReady = false,
            previousChapterCommitted = false,
            fullPlanningAdaptedToFirstChapter = false,
        )

        assertEquals(
            ChapterProgressionDecision.Ready,
            FirstChapterProgressionPolicyV1.evaluate(1, FirstChapterGenerationMode.FAST_LANE, evidence),
        )
        assertEquals(
            ChapterProgressionDecision.Blocked(ChapterProgressionBlockReason.STORY_BIBLE_MISSING),
            FirstChapterProgressionPolicyV1.evaluate(2, FirstChapterGenerationMode.FAST_LANE, evidence),
        )
    }

    @Test
    fun disablingFastLaneRequiresTheFullPlanningChainEvenForChapterOne() {
        val incomplete = ChapterPlanningEvidence(
            storySeedReady = true,
            firstChapterBootstrapReady = true,
            adultAndHardRuleGatePassed = true,
            storyBibleReady = true,
            masterOutlineReady = true,
            targetChapterWindowReady = false,
            previousChapterCommitted = false,
            fullPlanningAdaptedToFirstChapter = false,
        )

        assertEquals(
            ChapterProgressionDecision.Blocked(ChapterProgressionBlockReason.TARGET_CHAPTER_WINDOW_MISSING),
            FirstChapterProgressionPolicyV1.evaluate(
                1,
                FirstChapterGenerationMode.FULL_PLANNING,
                incomplete,
            ),
        )
    }

    @Test
    fun fullPlanningEvidenceAllowsEveryPositiveChapter() {
        val complete = ChapterPlanningEvidence(
            storySeedReady = false,
            firstChapterBootstrapReady = false,
            adultAndHardRuleGatePassed = false,
            storyBibleReady = true,
            masterOutlineReady = true,
            targetChapterWindowReady = true,
            previousChapterCommitted = true,
            fullPlanningAdaptedToFirstChapter = true,
        )

        assertEquals(
            ChapterProgressionDecision.Ready,
            FirstChapterProgressionPolicyV1.evaluate(2, FirstChapterGenerationMode.FAST_LANE, complete),
        )
        assertThrows(IllegalArgumentException::class.java) {
            FirstChapterProgressionPolicyV1.evaluate(0, FirstChapterGenerationMode.FAST_LANE, complete)
        }
    }

    @Test
    fun fastLaneChapterTwoRequiresCommittedChapterOneAndAdaptedPlanning() {
        val complete = ChapterPlanningEvidence(
            storySeedReady = true,
            firstChapterBootstrapReady = true,
            adultAndHardRuleGatePassed = true,
            storyBibleReady = true,
            masterOutlineReady = true,
            targetChapterWindowReady = true,
            previousChapterCommitted = false,
            fullPlanningAdaptedToFirstChapter = false,
        )

        assertEquals(
            ChapterProgressionDecision.Blocked(ChapterProgressionBlockReason.PREVIOUS_CHAPTER_NOT_COMMITTED),
            FirstChapterProgressionPolicyV1.evaluate(2, FirstChapterGenerationMode.FAST_LANE, complete),
        )
        assertEquals(
            ChapterProgressionDecision.Blocked(
                ChapterProgressionBlockReason.FULL_PLANNING_NOT_ADAPTED_TO_FIRST_CHAPTER,
            ),
            FirstChapterProgressionPolicyV1.evaluate(
                2,
                FirstChapterGenerationMode.FAST_LANE,
                complete.copy(previousChapterCommitted = true),
            ),
        )
    }
}

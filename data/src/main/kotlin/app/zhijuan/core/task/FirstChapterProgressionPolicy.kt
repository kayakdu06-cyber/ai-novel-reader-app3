package app.zhijuan.core.task

enum class FirstChapterGenerationMode {
    FAST_LANE,
    FULL_PLANNING,
}

data class ChapterPlanningEvidence(
    val storySeedReady: Boolean,
    val firstChapterBootstrapReady: Boolean,
    val adultAndHardRuleGatePassed: Boolean,
    val storyBibleReady: Boolean,
    val masterOutlineReady: Boolean,
    val targetChapterWindowReady: Boolean,
    val previousChapterCommitted: Boolean,
    val fullPlanningAdaptedToFirstChapter: Boolean,
)

enum class ChapterProgressionBlockReason {
    STORY_SEED_MISSING,
    FIRST_CHAPTER_BOOTSTRAP_MISSING,
    ADULT_OR_HARD_RULE_GATE_MISSING,
    STORY_BIBLE_MISSING,
    MASTER_OUTLINE_MISSING,
    TARGET_CHAPTER_WINDOW_MISSING,
    PREVIOUS_CHAPTER_NOT_COMMITTED,
    FULL_PLANNING_NOT_ADAPTED_TO_FIRST_CHAPTER,
}

sealed interface ChapterProgressionDecision {
    data object Ready : ChapterProgressionDecision

    data class Blocked(val reason: ChapterProgressionBlockReason) : ChapterProgressionDecision
}

/**
 * Local, deterministic gate. Database code supplies persisted evidence; this policy never accepts a
 * UI flag as proof. Only chapter one can use the reduced dependency set.
 */
object FirstChapterProgressionPolicyV1 {
    const val POLICY_VERSION = "zhijuan.first-chapter-progression.v1"
    const val FAST_LANE_CONTRACT_VERSION = "zhijuan.first-chapter-fast-lane.v1"
    const val FAST_LANE_OUTPUT_SCHEMA_ID = "first-chapter-bootstrap.v1"
    const val REQUIRED_ROUGH_CHAPTER_COUNT = 3

    fun evaluate(
        chapterIndex: Int,
        mode: FirstChapterGenerationMode,
        evidence: ChapterPlanningEvidence,
    ): ChapterProgressionDecision {
        require(chapterIndex >= 1) { "Chapter index must be positive." }
        if (chapterIndex == 1 && mode == FirstChapterGenerationMode.FAST_LANE) {
            return when {
                !evidence.storySeedReady -> blocked(ChapterProgressionBlockReason.STORY_SEED_MISSING)
                !evidence.firstChapterBootstrapReady -> blocked(
                    ChapterProgressionBlockReason.FIRST_CHAPTER_BOOTSTRAP_MISSING,
                )
                !evidence.adultAndHardRuleGatePassed -> blocked(
                    ChapterProgressionBlockReason.ADULT_OR_HARD_RULE_GATE_MISSING,
                )
                else -> ChapterProgressionDecision.Ready
            }
        }
        return when {
            !evidence.storyBibleReady -> blocked(ChapterProgressionBlockReason.STORY_BIBLE_MISSING)
            !evidence.masterOutlineReady -> blocked(ChapterProgressionBlockReason.MASTER_OUTLINE_MISSING)
            !evidence.targetChapterWindowReady -> blocked(
                ChapterProgressionBlockReason.TARGET_CHAPTER_WINDOW_MISSING,
            )
            chapterIndex >= 2 && !evidence.previousChapterCommitted -> blocked(
                ChapterProgressionBlockReason.PREVIOUS_CHAPTER_NOT_COMMITTED,
            )
            chapterIndex >= 2 && mode == FirstChapterGenerationMode.FAST_LANE &&
                !evidence.fullPlanningAdaptedToFirstChapter -> blocked(
                ChapterProgressionBlockReason.FULL_PLANNING_NOT_ADAPTED_TO_FIRST_CHAPTER,
            )
            else -> ChapterProgressionDecision.Ready
        }
    }

    private fun blocked(reason: ChapterProgressionBlockReason) = ChapterProgressionDecision.Blocked(reason)
}

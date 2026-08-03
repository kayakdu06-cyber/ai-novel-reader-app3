package app.zhijuan.core.task

data class ActiveArcAnchor(
    val arcId: String,
    val startChapter: Int,
    val endChapter: Int,
    val contentHash: String,
)

data class ArcPlanningWindowInput(
    val targetChapterCount: Int,
    val nextChapterIndex: Int,
    val enclosingBeatStartChapter: Int,
    val enclosingBeatEndChapter: Int,
    val activeArc: ActiveArcAnchor? = null,
)

data class ArcPlanningWindowSelection(
    val policyVersion: String,
    val arcId: String,
    val arcStartChapter: Int,
    val arcEndChapter: Int,
    val windowId: String,
    val windowStartChapter: Int,
    val windowEndChapter: Int,
    val nextWindowStartChapter: Int?,
) {
    val chapterCount: Int
        get() = windowEndChapter - windowStartChapter + 1

    val hasMoreChaptersInArc: Boolean
        get() = windowEndChapter < arcEndChapter
}

object ArcPlanningWindowPolicyV1 {
    const val POLICY_VERSION = "zhijuan.arc-window-policy.v1"
    const val MAX_ARC_CHAPTERS = 40
    const val MAX_WINDOW_CHAPTERS = 8
    const val REPLENISH_WHEN_REMAINING_CHAPTERS = 3

    fun select(input: ArcPlanningWindowInput): ArcPlanningWindowSelection {
        require(input.targetChapterCount in 80..10_000) { "Target chapter count is outside the product policy." }
        require(input.nextChapterIndex in 1..input.targetChapterCount) { "Next chapter is outside the book." }
        require(
            input.enclosingBeatStartChapter in 1..input.nextChapterIndex &&
                input.enclosingBeatEndChapter in input.nextChapterIndex..input.targetChapterCount,
        ) { "The master-outline beat must contain the next chapter." }

        val active = input.activeArc?.also { anchor ->
            require(IDENTIFIER.matches(anchor.arcId)) { "Active arc id is invalid." }
            require(HASH.matches(anchor.contentHash)) { "Active arc content hash is invalid." }
            require(
                anchor.startChapter in input.enclosingBeatStartChapter..input.nextChapterIndex &&
                    anchor.endChapter in input.nextChapterIndex..input.enclosingBeatEndChapter &&
                    anchor.endChapter - anchor.startChapter + 1 <= MAX_ARC_CHAPTERS,
            ) { "Active arc does not contain the next chapter inside the current master beat." }
        }

        val arcStart = active?.startChapter ?: input.nextChapterIndex
        val arcEnd = active?.endChapter ?: minOf(
            input.enclosingBeatEndChapter,
            input.nextChapterIndex + MAX_ARC_CHAPTERS - 1,
        )
        val arcId = active?.arcId ?: "arc.$arcStart.$arcEnd"
        val windowStart = input.nextChapterIndex
        val windowEnd = minOf(arcEnd, windowStart + MAX_WINDOW_CHAPTERS - 1)
        val windowId = "window.$windowStart.$windowEnd"
        return ArcPlanningWindowSelection(
            policyVersion = POLICY_VERSION,
            arcId = arcId,
            arcStartChapter = arcStart,
            arcEndChapter = arcEnd,
            windowId = windowId,
            windowStartChapter = windowStart,
            windowEndChapter = windowEnd,
            nextWindowStartChapter = (windowEnd + 1).takeIf { it <= input.targetChapterCount },
        )
    }

    fun shouldReplenish(
        currentChapterIndex: Int,
        plannedThroughChapter: Int,
        targetChapterCount: Int,
    ): Boolean {
        require(targetChapterCount in 80..10_000)
        require(currentChapterIndex in 0..targetChapterCount)
        require(plannedThroughChapter in currentChapterIndex..targetChapterCount)
        if (plannedThroughChapter == targetChapterCount) return false
        return plannedThroughChapter - currentChapterIndex <= REPLENISH_WHEN_REMAINING_CHAPTERS
    }

    private val IDENTIFIER = Regex("[A-Za-z0-9._:-]{1,128}")
    private val HASH = Regex("[0-9a-f]{64}")
}

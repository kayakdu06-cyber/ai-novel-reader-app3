package app.zhijuan.core.task

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ArcPlanningWindowPolicyTest {
    @Test
    fun `first window is bounded to eight chapters and arc to forty`() {
        val selected = ArcPlanningWindowPolicyV1.select(
            ArcPlanningWindowInput(10_000, 1, 1, 2_500),
        )

        assertEquals(1..40, selected.arcStartChapter..selected.arcEndChapter)
        assertEquals(1..8, selected.windowStartChapter..selected.windowEndChapter)
        assertEquals(9, selected.nextWindowStartChapter)
        assertTrue(selected.hasMoreChaptersInArc)
    }

    @Test
    fun `master beat and book ending clamp ranges without gaps`() {
        val beatEnd = ArcPlanningWindowPolicyV1.select(
            ArcPlanningWindowInput(80, 24, 1, 26),
        )
        assertEquals(24..26, beatEnd.arcStartChapter..beatEnd.arcEndChapter)
        assertEquals(24..26, beatEnd.windowStartChapter..beatEnd.windowEndChapter)
        assertEquals(27, beatEnd.nextWindowStartChapter)

        val bookEnd = ArcPlanningWindowPolicyV1.select(
            ArcPlanningWindowInput(80, 78, 54, 80),
        )
        assertEquals(78..80, bookEnd.windowStartChapter..bookEnd.windowEndChapter)
        assertEquals(null, bookEnd.nextWindowStartChapter)
        assertFalse(bookEnd.hasMoreChaptersInArc)
    }

    @Test
    fun `active arc is reused but only the next finite chapter window is selected`() {
        val selected = ArcPlanningWindowPolicyV1.select(
            ArcPlanningWindowInput(
                targetChapterCount = 300,
                nextChapterIndex = 9,
                enclosingBeatStartChapter = 1,
                enclosingBeatEndChapter = 60,
                activeArc = ActiveArcAnchor("arc.1.40", 1, 40, "a".repeat(64)),
            ),
        )

        assertEquals("arc.1.40", selected.arcId)
        assertEquals(9..16, selected.windowStartChapter..selected.windowEndChapter)
        assertEquals(17, selected.nextWindowStartChapter)
    }

    @Test
    fun `invalid active arc or chapter range fails before any provider request`() {
        assertThrows(IllegalArgumentException::class.java) {
            ArcPlanningWindowPolicyV1.select(
                ArcPlanningWindowInput(
                    300,
                    9,
                    1,
                    60,
                    ActiveArcAnchor("arc.1.8", 1, 8, "a".repeat(64)),
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArcPlanningWindowPolicyV1.select(ArcPlanningWindowInput(10_000, 10_001, 1, 10_000))
        }
    }

    @Test
    fun `replenishment waits until only three planned chapters remain`() {
        assertFalse(ArcPlanningWindowPolicyV1.shouldReplenish(4, 8, 300))
        assertTrue(ArcPlanningWindowPolicyV1.shouldReplenish(5, 8, 300))
        assertFalse(ArcPlanningWindowPolicyV1.shouldReplenish(298, 300, 300))
    }
}

package app.zhijuan.core.database.generation

import app.zhijuan.core.model.GenerationJobType
import app.zhijuan.core.model.GenerationPhase
import app.zhijuan.core.model.GenerationTargetType
import app.zhijuan.core.task.ActiveArcAnchor
import app.zhijuan.core.task.ArcPlanningWindowInput
import app.zhijuan.core.task.PromptBundleCatalogV1
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ArcWindowPlanningJobFactoryTest {
    @Test
    fun `creates one frozen bounded arc-window stage`() {
        val setup = ArcWindowPlanningJobFactory.create(spec())
        val stage = setup.generationSetup.stages.single()

        assertEquals(GenerationJobType.CONTINUE_BOOK, setup.generationSetup.jobType)
        assertEquals(PromptBundleCatalogV1.BUNDLE_VERSION, setup.generationSetup.promptBundleVersion)
        assertEquals(GenerationPhase.BUILD_ARC_PLAN, stage.phase)
        assertEquals(GenerationTargetType.OUTLINE, stage.targetType)
        assertEquals(1..8, setup.selection.windowStartChapter..setup.selection.windowEndChapter)
        assertTrue(stage.inputSourcesJson.contains("\"outputSchemaId\":\"arc-plan.v1\""))
        assertTrue(stage.inputSourcesJson.contains("\"windowEndChapter\":8"))
    }

    @Test
    fun `same frozen input is deterministic and next window changes idempotency`() {
        val first = ArcWindowPlanningJobFactory.create(spec())
        val again = ArcWindowPlanningJobFactory.create(spec())
        assertEquals(
            first.generationSetup.stages.single().inputVersionHash,
            again.generationSetup.stages.single().inputVersionHash,
        )
        assertEquals(
            first.generationSetup.stages.single().idempotencyKey,
            again.generationSetup.stages.single().idempotencyKey,
        )

        val next = ArcWindowPlanningJobFactory.create(
            spec(
                nextChapter = 9,
                active = ActiveArcAnchor("arc.1.40", 1, 40, "c".repeat(64)),
            ),
        )
        assertNotEquals(
            first.generationSetup.stages.single().idempotencyKey,
            next.generationSetup.stages.single().idempotencyKey,
        )
        assertEquals(9..16, next.selection.windowStartChapter..next.selection.windowEndChapter)
    }

    @Test
    fun `invalid outline evidence or unbounded range fails before setup`() {
        assertThrows(IllegalArgumentException::class.java) {
            ArcWindowPlanningJobFactory.create(spec().copy(masterOutlineContentHash = "bad"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArcWindowPlanningJobFactory.create(spec(nextChapter = 10_001))
        }
    }

    private fun spec(
        nextChapter: Int = 1,
        active: ActiveArcAnchor? = null,
    ) = ArcWindowPlanningJobSpec(
        jobId = "job.arc-window",
        stageId = "stage.arc-window",
        bookId = "book.window",
        userIntentJson = "{}",
        budgetSnapshotJson = "{}",
        masterOutlineRevisionId = "outline.master.1",
        masterOutlineContentHash = "a".repeat(64),
        parentOutlineRevisionId = "outline.parent.1",
        parentOutlineContentHash = "b".repeat(64),
        windowInput = ArcPlanningWindowInput(
            targetChapterCount = 10_000,
            nextChapterIndex = nextChapter,
            enclosingBeatStartChapter = 1,
            enclosingBeatEndChapter = 2_500,
            activeArc = active,
        ),
        createdAt = 1L,
    )
}

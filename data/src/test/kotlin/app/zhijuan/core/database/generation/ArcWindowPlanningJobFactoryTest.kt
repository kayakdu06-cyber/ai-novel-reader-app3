package app.zhijuan.core.database.generation

import app.zhijuan.core.model.GenerationPhase
import app.zhijuan.core.model.GenerationStageStatus
import app.zhijuan.core.model.GenerationTargetType
import app.zhijuan.core.task.ArcPlanningWindowInput
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ArcWindowPlanningJobFactoryTest {
    @Test
    fun `factory freezes one deterministic bounded v2 window`() {
        val first = ArcWindowPlanningJobFactory.create(spec())
        val replay = ArcWindowPlanningJobFactory.create(spec())
        assertEquals(first, replay)
        assertEquals(1, first.generationSetup.stages.size)
        val stage = first.generationSetup.stages.single().toEntity()
        assertEquals(GenerationPhase.BUILD_ARC_PLAN, stage.phase)
        assertEquals(GenerationRunnerStageRoute.ARC_WINDOW_V1, GenerationRunnerStageRouteResolver.resolve(stage))
        assertEquals(1, first.selection.windowStartChapter)
        assertEquals(8, first.selection.windowEndChapter)
        assertEquals(ArcWindowPlanningJobFactory.OUTPUT_SCHEMA_ID, "arc-plan.v2")
        assertEquals(first.selection, ArcWindowPlanningJobFactory.parseAndVerify(stage).selection)
    }

    @Test
    fun `changed authority changes binding and tampering fails closed`() {
        val first = ArcWindowPlanningJobFactory.create(spec()).generationSetup.stages.single()
        val changed = ArcWindowPlanningJobFactory.create(
            spec().copy(parentOutlineContentHash = "c".repeat(64)),
        ).generationSetup.stages.single()
        assertNotEquals(first.inputVersionHash, changed.inputVersionHash)
        assertNotEquals(first.idempotencyKey, changed.idempotencyKey)
        assertThrows<IllegalArgumentException> {
            ArcWindowPlanningJobFactory.parseAndVerify(first.toEntity().copy(inputSourcesJson = "{}"))
        }
    }

    private fun spec() = ArcWindowPlanningJobSpec(
        jobId = "job-window-1",
        stageId = "stage-window-1",
        bookId = "book-1",
        userIntentJson = "{}",
        budgetSnapshotJson = "{}",
        masterOutlineRevisionId = "outline-master-1",
        masterOutlineContentHash = "a".repeat(64),
        parentOutlineRevisionId = "outline-master-1",
        parentOutlineContentHash = "b".repeat(64),
        windowInput = ArcPlanningWindowInput(
            targetChapterCount = 80,
            nextChapterIndex = 1,
            enclosingBeatStartChapter = 1,
            enclosingBeatEndChapter = 20,
        ),
        createdAt = 1L,
    )

    private fun GenerationStageSetup.toEntity() = GenerationStageEntity(
        stageId = stageId,
        jobId = "job-window-1",
        phase = phase,
        targetType = GenerationTargetType.OUTLINE,
        targetId = targetId,
        status = GenerationStageStatus.PENDING,
        inputVersionHash = inputVersionHash,
        idempotencyKey = idempotencyKey,
        maxAttempts = maxAttempts,
        inputSourcesJson = inputSourcesJson,
        createdAt = 1L,
        updatedAt = 1L,
    )
}

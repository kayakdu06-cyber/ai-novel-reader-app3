package app.zhijuan.core.database.generation

import app.zhijuan.core.model.GenerationPhase
import app.zhijuan.core.model.GenerationTargetType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class InitialPlanningJobFactoryTest {
    @Test
    fun `factory freezes deterministic three stage chain`() {
        val first = InitialPlanningJobFactory.create(spec())
        val replay = InitialPlanningJobFactory.create(spec())
        assertEquals(first.stages, replay.stages)
        assertEquals(
            listOf(
                GenerationPhase.BUILD_STORY_SEED,
                GenerationPhase.BUILD_BIBLE,
                GenerationPhase.BUILD_MASTER_OUTLINE,
            ),
            first.stages.map { it.phase },
        )
        assertEquals(
            listOf(GenerationTargetType.BOOK, GenerationTargetType.STORY_BIBLE, GenerationTargetType.OUTLINE),
            first.stages.map { it.targetType },
        )
        first.stages.forEach { stage ->
            assertEquals(
                "snapshot-1",
                InitialPlanningJobFactory.parseAndVerify(stage.toEntity()).creationSnapshotId,
            )
        }
    }

    @Test
    fun `changed frozen source changes every stage and tampering fails closed`() {
        val first = InitialPlanningJobFactory.create(spec())
        val changed = InitialPlanningJobFactory.create(spec().copy(creationSnapshotHash = "c".repeat(64)))
        first.stages.zip(changed.stages).forEach { (left, right) ->
            assertNotEquals(left.inputVersionHash, right.inputVersionHash)
            assertNotEquals(left.idempotencyKey, right.idempotencyKey)
        }
        assertThrows<IllegalArgumentException> {
            InitialPlanningJobFactory.parseAndVerify(
                first.stages.first().toEntity().copy(inputSourcesJson = "{}"),
            )
        }
    }

    private fun spec() = InitialPlanningJobSpec(
        jobId = "job-1",
        bookId = "book-1",
        creationSnapshotId = "snapshot-1",
        creationSnapshotHash = "a".repeat(64),
        promptBundleBindingHash = "b".repeat(64),
        targetChapterCount = 80,
        userIntentJson = "{}",
        budgetSnapshotJson = "{}",
        stageIds = InitialPlanningStageIds("seed-1", "bible-1", "outline-1"),
        createdAt = 1L,
    )

    private fun GenerationStageSetup.toEntity() = GenerationStageEntity(
        stageId = stageId,
        jobId = "job-1",
        phase = phase,
        targetType = targetType,
        targetId = targetId,
        status = app.zhijuan.core.model.GenerationStageStatus.PENDING,
        inputVersionHash = inputVersionHash,
        idempotencyKey = idempotencyKey,
        maxAttempts = maxAttempts,
        inputSourcesJson = inputSourcesJson,
        createdAt = 1L,
        updatedAt = 1L,
    )
}

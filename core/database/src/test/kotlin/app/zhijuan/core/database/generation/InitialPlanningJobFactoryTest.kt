package app.zhijuan.core.database.generation

import app.zhijuan.core.model.GenerationPhase
import app.zhijuan.core.model.GenerationTargetType
import app.zhijuan.core.task.PromptBundleCatalogV1
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InitialPlanningJobFactoryTest {
    @Test
    fun factoryFreezesTheExactThreeStagePlanningChain() {
        val setup = InitialPlanningJobFactory.create(spec())
        assertEquals(PromptBundleCatalogV1.BUNDLE_VERSION, setup.promptBundleVersion)
        assertEquals(
            listOf(
                GenerationPhase.BUILD_STORY_SEED,
                GenerationPhase.BUILD_BIBLE,
                GenerationPhase.BUILD_MASTER_OUTLINE,
            ),
            setup.stages.map { it.phase },
        )
        assertEquals(
            listOf(
                GenerationTargetType.BOOK,
                GenerationTargetType.STORY_BIBLE,
                GenerationTargetType.OUTLINE,
            ),
            setup.stages.map { it.targetType },
        )
        assertTrue(setup.stages.all { it.targetId == "book.initial" })
        assertEquals(3, setup.stages.map { it.idempotencyKey }.distinct().size)
        assertTrue("stage.seed" in setup.stages[1].inputSourcesJson)
        assertTrue("stage.bible" in setup.stages[2].inputSourcesJson)
    }

    @Test
    fun factoryIsDeterministicAndSnapshotChangesInvalidateEveryStage() {
        val first = InitialPlanningJobFactory.create(spec())
        val replay = InitialPlanningJobFactory.create(spec())
        val changed = InitialPlanningJobFactory.create(spec(snapshotHash = "b".repeat(64)))
        assertEquals(first.stages, replay.stages)
        first.stages.zip(changed.stages).forEach { (old, new) ->
            assertNotEquals(old.inputVersionHash, new.inputVersionHash)
            assertNotEquals(old.idempotencyKey, new.idempotencyKey)
        }
    }

    @Test
    fun duplicateStageIdsAndMalformedSnapshotHashesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            InitialPlanningJobFactory.create(
                spec().copy(stageIds = InitialPlanningStageIds("same", "same", "outline")),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            InitialPlanningJobFactory.create(spec(snapshotHash = "not-a-hash"))
        }
    }

    private fun spec(snapshotHash: String = "a".repeat(64)) = InitialPlanningJobSpec(
        jobId = "job.initial",
        bookId = "book.initial",
        userIntentJson = "{}",
        budgetSnapshotJson = "{}",
        creationSnapshotHash = snapshotHash,
        stageIds = InitialPlanningStageIds("stage.seed", "stage.bible", "stage.outline"),
        createdAt = 1L,
    )
}

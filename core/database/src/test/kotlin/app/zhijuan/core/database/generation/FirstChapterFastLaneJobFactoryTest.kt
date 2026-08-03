package app.zhijuan.core.database.generation

import app.zhijuan.core.model.GenerationPhase
import app.zhijuan.core.model.GenerationTargetType
import app.zhijuan.core.task.FirstChapterProgressionPolicyV1
import app.zhijuan.core.task.PromptBundleCatalogV1
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FirstChapterFastLaneJobFactoryTest {
    @Test
    fun freezesSeedAdultGateAndThreeChapterBootstrapContract() {
        val setup = FirstChapterFastLaneJobFactory.create(spec())
        val stage = setup.stages.single()

        assertEquals(PromptBundleCatalogV1.BUNDLE_VERSION, setup.promptBundleVersion)
        assertEquals(GenerationPhase.BUILD_CHAPTER_PLAN, stage.phase)
        assertEquals(GenerationTargetType.CHAPTER, stage.targetType)
        assertEquals("chapter-1", stage.targetId)
        assertTrue(stage.inputSourcesJson.contains(FirstChapterProgressionPolicyV1.FAST_LANE_CONTRACT_VERSION))
        assertTrue(stage.inputSourcesJson.contains("\"requiredRoughChapterCount\":3"))
        assertTrue(stage.inputSourcesJson.contains("\"seedStageId\":\"seed-stage\""))
    }

    @Test
    fun sameFrozenEvidenceIsDeterministicAndChangedSeedChangesTheKey() {
        val first = FirstChapterFastLaneJobFactory.create(spec())
        val second = FirstChapterFastLaneJobFactory.create(spec())
        val changed = FirstChapterFastLaneJobFactory.create(spec().copy(seedContentHash = "e".repeat(64)))

        assertEquals(first.stages.single().inputVersionHash, second.stages.single().inputVersionHash)
        assertEquals(first.stages.single().idempotencyKey, second.stages.single().idempotencyKey)
        assertTrue(first.stages.single().idempotencyKey != changed.stages.single().idempotencyKey)
    }

    @Test
    fun fastLaneRejectsAnyChapterOtherThanOne() {
        assertThrows(IllegalArgumentException::class.java) {
            FirstChapterFastLaneJobFactory.create(spec().copy(chapterIndex = 2))
        }
    }

    private fun spec() = FirstChapterFastLaneJobSpec(
        jobId = "fast-job",
        stageId = "fast-stage",
        bookId = "book-1",
        chapterId = "chapter-1",
        chapterIndex = 1,
        userIntentJson = "{}",
        budgetSnapshotJson = "{}",
        creationSnapshotHash = "a".repeat(64),
        promptBindingHash = "b".repeat(64),
        seedStageId = "seed-stage",
        seedRawOutputHash = "c".repeat(64),
        seedContentHash = "d".repeat(64),
        createdAt = 10L,
    )
}

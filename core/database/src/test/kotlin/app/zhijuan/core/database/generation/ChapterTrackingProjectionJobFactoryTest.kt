package app.zhijuan.core.database.generation

import app.zhijuan.core.model.GenerationJobType
import app.zhijuan.core.model.GenerationPhase
import app.zhijuan.core.model.GenerationStageStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChapterTrackingProjectionJobFactoryTest {
    @Test
    fun createsOneBoundRebuildStageAndParsesTheExactSource() {
        val source = source()
        val setup = ChapterTrackingProjectionJobFactory.create(
            ChapterTrackingProjectionJobSpec(
                jobId = "job.tracking",
                stageId = "stage.tracking",
                bookId = "book.one",
                userIntentJson = "{}",
                budgetSnapshotJson = "{}",
                source = source,
                createdAt = 10L,
            ),
        )

        assertEquals(GenerationJobType.REBUILD_MEMORY, setup.jobType)
        assertEquals(1, setup.stages.size)
        assertEquals(GenerationPhase.EXTRACT_MEMORY, setup.stages.single().phase)
        assertEquals(2, setup.stages.single().maxAttempts)
        val entity = setup.stages.single().toEntity("job.tracking", 10L)
        assertEquals(source, ChapterTrackingProjectionJobFactory.parseAndVerify(entity))
        assertTrue(ChapterTrackingProjectionJobFactory.isBound(entity))
    }

    @Test
    fun frozenSourceHashRejectsAnySnapshotTampering() {
        val setup = ChapterTrackingProjectionJobFactory.create(
            ChapterTrackingProjectionJobSpec(
                jobId = "job.tracking",
                stageId = "stage.tracking",
                bookId = "book.one",
                userIntentJson = "{}",
                budgetSnapshotJson = "{}",
                source = source(),
                createdAt = 10L,
            ),
        )
        val stage = setup.stages.single().toEntity("job.tracking", 10L)
        val tampered = stage.copy(
            inputSourcesJson = stage.inputSourcesJson.replace(HASH_B, HASH_D),
        )

        assertThrows(IllegalArgumentException::class.java) {
            ChapterTrackingProjectionJobFactory.parseAndVerify(tampered)
        }
    }

    @Test
    fun chapterMemoryStageIsNotMisclassifiedAsTracking() {
        val memory = ChapterMemoryExtractionJobFactory.create(
            ChapterMemoryExtractionJobSpec(
                jobId = "job.memory",
                stageId = "stage.memory",
                bookId = "book.one",
                userIntentJson = "{}",
                budgetSnapshotJson = "{}",
                source = ChapterMemoryExtractionSourceV1("version.one", HASH_A, "chapter.one", 2),
                createdAt = 10L,
            ),
        ).stages.single().toEntity("job.memory", 10L)

        assertFalse(ChapterTrackingProjectionJobFactory.isBound(memory))
    }

    private fun source() = ChapterTrackingProjectionSourceV1(
        chapterVersionId = "version.one",
        chapterContentHash = HASH_A,
        chapterId = "chapter.one",
        chapterIndex = 2,
        memorySnapshotHash = HASH_B,
        priorForeshadowSnapshotHash = HASH_C,
        knownEntitySnapshotHash = HASH_D,
    )

    private fun GenerationStageSetup.toEntity(jobId: String, createdAt: Long) = GenerationStageEntity(
        stageId = stageId,
        jobId = jobId,
        phase = phase,
        targetType = targetType,
        targetId = targetId,
        status = GenerationStageStatus.PENDING,
        inputVersionHash = inputVersionHash,
        idempotencyKey = idempotencyKey,
        maxAttempts = maxAttempts,
        inputSourcesJson = inputSourcesJson,
        createdAt = createdAt,
        updatedAt = createdAt,
    )

    private companion object {
        val HASH_A = "a".repeat(64)
        val HASH_B = "b".repeat(64)
        val HASH_C = "c".repeat(64)
        val HASH_D = "d".repeat(64)
    }
}

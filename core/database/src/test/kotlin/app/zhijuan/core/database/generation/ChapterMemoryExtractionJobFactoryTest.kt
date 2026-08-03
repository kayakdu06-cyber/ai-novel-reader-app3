package app.zhijuan.core.database.generation

import app.zhijuan.core.model.GenerationJobType
import app.zhijuan.core.model.GenerationPhase
import app.zhijuan.core.model.GenerationTargetType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ChapterMemoryExtractionJobFactoryTest {
    @Test
    fun createsOneBoundExtractionStageForTheFrozenCurrentVersion() {
        val setup = ChapterMemoryExtractionJobFactory.create(spec())
        val stage = setup.stages.single()

        assertEquals(GenerationJobType.CONTINUE_BOOK, setup.jobType)
        assertEquals(GenerationPhase.EXTRACT_MEMORY, stage.phase)
        assertEquals(GenerationTargetType.CHAPTER, stage.targetType)
        assertEquals("chapter.1", stage.targetId)
        assertEquals(2, stage.maxAttempts)
        assertEquals(source(), ChapterMemoryExtractionJobFactory.parseAndVerify(stage.toEntity()))
    }

    @Test
    fun sourceHashAndVersionChangeBothChangeInputAndIdempotency() {
        val first = ChapterMemoryExtractionJobFactory.create(spec()).stages.single()
        val changedHash = ChapterMemoryExtractionJobFactory.create(
            spec().copy(source = source().copy(chapterContentHash = "b".repeat(64))),
        ).stages.single()
        val changedVersion = ChapterMemoryExtractionJobFactory.create(
            spec().copy(source = source().copy(chapterVersionId = "chapter.version.2")),
        ).stages.single()

        assertNotEquals(first.inputVersionHash, changedHash.inputVersionHash)
        assertNotEquals(first.idempotencyKey, changedHash.idempotencyKey)
        assertNotEquals(first.inputVersionHash, changedVersion.inputVersionHash)
    }

    @Test
    fun invalidSourceAndExtraRepairAttemptsFailBeforePersistence() {
        assertThrows(IllegalArgumentException::class.java) {
            source().copy(chapterContentHash = "bad")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ChapterMemoryExtractionJobFactory.create(spec().copy(maxAttempts = 3))
        }
    }

    private fun spec() = ChapterMemoryExtractionJobSpec(
        jobId = "job.extract.1",
        stageId = "stage.extract.1",
        bookId = "book.1",
        userIntentJson = "{}",
        budgetSnapshotJson = "{}",
        source = source(),
        createdAt = 1L,
    )

    private fun source() = ChapterMemoryExtractionSourceV1(
        chapterVersionId = "chapter.version.1",
        chapterContentHash = "a".repeat(64),
        chapterId = "chapter.1",
        chapterIndex = 1,
    )

    private fun GenerationStageSetup.toEntity() = GenerationStageEntity(
        stageId = stageId,
        jobId = "job.extract.1",
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

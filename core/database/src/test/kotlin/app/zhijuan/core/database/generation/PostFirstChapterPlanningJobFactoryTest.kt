package app.zhijuan.core.database.generation

import app.zhijuan.core.model.GenerationPhase
import app.zhijuan.core.task.FirstChapterProgressionPolicyV1
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PostFirstChapterPlanningJobFactoryTest {
    @Test
    fun `freezes the readable first chapter into bible and outline planning`() {
        val setup = PostFirstChapterPlanningJobFactory.create(spec())

        assertEquals(listOf(GenerationPhase.BUILD_BIBLE, GenerationPhase.BUILD_MASTER_OUTLINE), setup.stages.map { it.phase })
        assertTrue(setup.stages.all { it.inputSourcesJson.contains("\"chapterVersionId\":\"chapter-version-1\"") })
        assertTrue(setup.stages.all { it.inputSourcesJson.contains(FirstChapterProgressionPolicyV1.POLICY_VERSION) })
        assertTrue(setup.stages[1].inputSourcesJson.contains("\"dependencyStageIds\":[\"bible-after-first\"]"))
    }

    @Test
    fun `changed first chapter content changes both idempotency keys`() {
        val first = PostFirstChapterPlanningJobFactory.create(spec())
        val changed = PostFirstChapterPlanningJobFactory.create(
            spec().copy(chapterContentHash = "f".repeat(64)),
        )

        first.stages.zip(changed.stages).forEach { (left, right) ->
            assertNotEquals(left.idempotencyKey, right.idempotencyKey)
        }
    }

    @Test
    fun `invalid chapter hash fails before a job can be created`() {
        assertThrows(IllegalArgumentException::class.java) {
            PostFirstChapterPlanningJobFactory.create(spec().copy(chapterContentHash = "not-a-hash"))
        }
    }

    private fun spec() = PostFirstChapterPlanningJobSpec(
        jobId = "planning-after-first",
        bookId = "book-1",
        userIntentJson = "{}",
        budgetSnapshotJson = "{}",
        seedStageId = "seed-stage",
        seedRawOutputHash = "a".repeat(64),
        seedContentHash = "b".repeat(64),
        chapterId = "chapter-1",
        chapterVersionId = "chapter-version-1",
        chapterContentHash = "c".repeat(64),
        bibleStageId = "bible-after-first",
        outlineStageId = "outline-after-first",
        createdAt = 20L,
    )
}

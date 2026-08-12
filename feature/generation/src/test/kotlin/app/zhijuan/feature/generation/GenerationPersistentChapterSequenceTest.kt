package app.zhijuan.feature.generation

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class GenerationPersistentChapterSequenceTest {
    @Test
    fun `five chapters run sequentially and prepare only four successors`() = runBlocking {
        val fixture = SequenceFixture()

        val result = fixture.sequence().run(chapter(7), 5, "sequence-owner")

        assertEquals(GenerationChapterSequenceDisposition.TARGET_COMPLETED, result.disposition)
        assertEquals(listOf(7, 8, 9, 10, 11), result.completedChapters.map { it.chapterOrdinal })
        assertEquals(listOf(7, 8, 9, 10, 11), fixture.runOrdinals)
        assertEquals(listOf(8, 9, 10, 11), fixture.preparedOrdinals)
        assertEquals(20, result.executedStageCount)
    }

    @Test
    fun `paused second chapter halts without preparing or opening a third`() = runBlocking {
        val fixture = SequenceFixture(
            dispositions = mutableMapOf(2 to GenerationPersistentRunDisposition.NEEDS_ACTION),
        )

        val result = fixture.sequence().run(chapter(1), 4, "sequence-owner")

        assertEquals(GenerationChapterSequenceDisposition.RUNNER_HALTED, result.disposition)
        assertEquals(GenerationPersistentRunDisposition.NEEDS_ACTION, result.runnerDisposition)
        assertEquals(listOf(1), result.completedChapters.map { it.chapterOrdinal })
        assertEquals(listOf(1, 2), fixture.runOrdinals)
        assertEquals(listOf(2), fixture.preparedOrdinals)
    }

    @Test
    fun `preparer cannot skip or duplicate a chapter identity`() = runBlocking {
        val runner = GenerationTotalRunnerPort { _, _ -> completed() }
        val sequence = GenerationPersistentChapterSequenceV1(runner) { _, expected ->
            GenerationNextChapterPreparationResult.Prepared(chapter(expected + 1))
        }

        val result = sequence.run(chapter(3), 3, "sequence-owner")

        assertEquals(GenerationChapterSequenceDisposition.INVALID_NEXT_CHAPTER, result.disposition)
        assertEquals(listOf(3), result.completedChapters.map { it.chapterOrdinal })
        assertEquals(5, result.currentChapter.chapterOrdinal)
    }

    @Test
    fun `preparation not ready stops after the committed chapter`() = runBlocking {
        val runner = GenerationTotalRunnerPort { _, _ -> completed() }
        val sequence = GenerationPersistentChapterSequenceV1(runner) { _, _ ->
            GenerationNextChapterPreparationResult.NotReady
        }

        val result = sequence.run(chapter(1), 3, "sequence-owner")

        assertEquals(GenerationChapterSequenceDisposition.NEXT_CHAPTER_NOT_READY, result.disposition)
        assertEquals(listOf(1), result.completedChapters.map { it.chapterOrdinal })
    }

    @Test
    fun `chapter batch is strictly bounded to three through five`() {
        val fixture = SequenceFixture()

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { fixture.sequence().run(chapter(1), 2, "owner") }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { fixture.sequence().run(chapter(1), 6, "owner") }
        }
    }

    private class SequenceFixture(
        val dispositions: MutableMap<Int, GenerationPersistentRunDisposition> = mutableMapOf(),
    ) {
        val runOrdinals = mutableListOf<Int>()
        val preparedOrdinals = mutableListOf<Int>()

        fun sequence() = GenerationPersistentChapterSequenceV1(
            runner = GenerationTotalRunnerPort { jobId, _ ->
                val ordinal = jobId.substringAfterLast('.').toInt()
                runOrdinals += ordinal
                val disposition = dispositions[ordinal] ?: GenerationPersistentRunDisposition.COMPLETED
                GenerationPersistentRunResult(disposition, if (disposition == GenerationPersistentRunDisposition.COMPLETED) 4 else 0)
            },
            nextChapterPreparation = GenerationNextChapterPreparationPort { _, expected ->
                preparedOrdinals += expected
                GenerationNextChapterPreparationResult.Prepared(chapter(expected))
            },
        )
    }

    private companion object {
        fun chapter(ordinal: Int) = GenerationChapterRun("job.chapter.$ordinal", ordinal)

        fun completed() = GenerationPersistentRunResult(
            GenerationPersistentRunDisposition.COMPLETED,
            4,
        )
    }
}

package app.zhijuan.feature.generation

data class GenerationChapterRun(
    val bookId: String,
    val jobId: String,
    val chapterOrdinal: Int,
) {
    init {
        require(bookId.isNotBlank())
        require(jobId.isNotBlank())
        require(chapterOrdinal >= 1)
    }
}

sealed interface GenerationNextChapterPreparationResult {
    data class Prepared(val chapter: GenerationChapterRun) : GenerationNextChapterPreparationResult

    data object NotReady : GenerationNextChapterPreparationResult
}

/**
 * Creates or replays one frozen next-chapter Job. Implementations must be idempotent for the
 * completed Job plus expected ordinal and must not return before the next starting Stage is durable.
 */
fun interface GenerationNextChapterPreparationPort {
    suspend fun prepareNext(
        completedChapter: GenerationChapterRun,
        expectedChapterOrdinal: Int,
    ): GenerationNextChapterPreparationResult
}

enum class GenerationChapterSequenceDisposition {
    TARGET_COMPLETED,
    RUNNER_HALTED,
    NEXT_CHAPTER_NOT_READY,
    INVALID_NEXT_CHAPTER,
}

data class GenerationChapterSequenceResult(
    val disposition: GenerationChapterSequenceDisposition,
    val completedChapters: List<GenerationChapterRun>,
    val currentChapter: GenerationChapterRun,
    val executedStageCount: Int,
    val runnerDisposition: GenerationPersistentRunDisposition,
) {
    init {
        require(executedStageCount >= 0)
        require(completedChapters.size <= MAX_CHAPTERS_PER_SEQUENCE)
    }
}

/**
 * A bounded book-level loop. It never owns a Stage cursor: every chapter is delegated to the
 * existing persistent total runner, and only a completed Job may unlock preparation of one next Job.
 */
class GenerationPersistentChapterSequenceV1(
    private val runner: GenerationTotalRunnerPort,
    private val nextChapterPreparation: GenerationNextChapterPreparationPort,
) {
    suspend fun run(
        initialChapter: GenerationChapterRun,
        requestedChapterCount: Int,
        runnerOwnerPrefix: String,
        alreadyCompletedChapterCount: Int = 0,
    ): GenerationChapterSequenceResult {
        require(requestedChapterCount in MIN_CHAPTERS_PER_SEQUENCE..MAX_CHAPTERS_PER_SEQUENCE)
        require(alreadyCompletedChapterCount in 0 until requestedChapterCount)
        require(runnerOwnerPrefix.isNotBlank())

        val completed = mutableListOf<GenerationChapterRun>()
        val observedJobIds = mutableSetOf<String>()
        var current = initialChapter
        var executedStages = 0

        repeat(requestedChapterCount - alreadyCompletedChapterCount) {
            if (!observedJobIds.add(current.jobId)) {
                return result(
                    GenerationChapterSequenceDisposition.INVALID_NEXT_CHAPTER,
                    completed,
                    current,
                    executedStages,
                    GenerationPersistentRunDisposition.COMPLETED,
                )
            }
            val runResult = runner.runJob(
                current.jobId,
                "$runnerOwnerPrefix:${current.chapterOrdinal}",
            )
            executedStages += runResult.executedStageCount
            if (runResult.disposition != GenerationPersistentRunDisposition.COMPLETED) {
                return result(
                    GenerationChapterSequenceDisposition.RUNNER_HALTED,
                    completed,
                    current,
                    executedStages,
                    runResult.disposition,
                )
            }

            completed += current
            if (completed.size + alreadyCompletedChapterCount == requestedChapterCount) {
                return result(
                    GenerationChapterSequenceDisposition.TARGET_COMPLETED,
                    completed,
                    current,
                    executedStages,
                    runResult.disposition,
                )
            }

            if (current.chapterOrdinal == Int.MAX_VALUE) {
                return result(
                    GenerationChapterSequenceDisposition.INVALID_NEXT_CHAPTER,
                    completed,
                    current,
                    executedStages,
                    runResult.disposition,
                )
            }
            val expectedOrdinal = current.chapterOrdinal + 1
            when (val preparation = nextChapterPreparation.prepareNext(current, expectedOrdinal)) {
                GenerationNextChapterPreparationResult.NotReady -> {
                    return result(
                        GenerationChapterSequenceDisposition.NEXT_CHAPTER_NOT_READY,
                        completed,
                        current,
                        executedStages,
                        runResult.disposition,
                    )
                }

                is GenerationNextChapterPreparationResult.Prepared -> {
                    if (
                        preparation.chapter.bookId != current.bookId ||
                        preparation.chapter.chapterOrdinal != expectedOrdinal
                    ) {
                        return result(
                            GenerationChapterSequenceDisposition.INVALID_NEXT_CHAPTER,
                            completed,
                            preparation.chapter,
                            executedStages,
                            runResult.disposition,
                        )
                    }
                    current = preparation.chapter
                }
            }
        }
        error("Bounded chapter loop returned without a terminal result.")
    }

    private fun result(
        disposition: GenerationChapterSequenceDisposition,
        completed: List<GenerationChapterRun>,
        current: GenerationChapterRun,
        executedStages: Int,
        runnerDisposition: GenerationPersistentRunDisposition,
    ) = GenerationChapterSequenceResult(
        disposition = disposition,
        completedChapters = completed.toList(),
        currentChapter = current,
        executedStageCount = executedStages,
        runnerDisposition = runnerDisposition,
    )
}

private const val MIN_CHAPTERS_PER_SEQUENCE = 3
private const val MAX_CHAPTERS_PER_SEQUENCE = 5

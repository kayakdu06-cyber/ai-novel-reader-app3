package app.zhijuan.reader.reading

import app.zhijuan.core.contract.GenerationController
import app.zhijuan.core.contract.LibraryChapterSummary
import app.zhijuan.core.contract.LibraryRepository
import app.zhijuan.core.model.GenerationJobStatus
import javax.inject.Inject

sealed interface ReaderChapterState {
    val chapter: LibraryChapterSummary

    data class Ready(
        override val chapter: LibraryChapterSummary,
        val content: String,
    ) : ReaderChapterState

    data class Pending(
        override val chapter: LibraryChapterSummary,
    ) : ReaderChapterState
}

data class ReaderPosition(
    val chapterId: String,
    val progressPermille: Int,
) {
    init {
        require(chapterId.isNotBlank()) { "Chapter id must not be blank." }
        require(progressPermille in 0..1000) { "Reading progress must be between 0 and 1000." }
    }
}

class ReaderSessionCoordinator @Inject constructor(
    private val library: LibraryRepository,
    private val generation: GenerationController,
) {
    suspend fun openChapter(chapter: LibraryChapterSummary): ReaderChapterState {
        val content = library.readChapter(chapter.chapterId)
        return if (content.isNullOrBlank()) {
            ReaderChapterState.Pending(chapter)
        } else {
            ReaderChapterState.Ready(chapter, content)
        }
    }

    suspend fun pauseGeneration(jobId: String, requestedAt: Long): GenerationJobStatus =
        generation.pauseGeneration(jobId, requestedAt)

    suspend fun stopGeneration(jobId: String, requestedAt: Long): GenerationJobStatus =
        generation.stopGeneration(jobId, requestedAt)
}

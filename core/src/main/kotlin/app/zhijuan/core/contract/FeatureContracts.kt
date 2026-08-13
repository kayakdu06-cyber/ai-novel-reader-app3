package app.zhijuan.core.contract

import app.zhijuan.core.model.GenerationJobStatus
import app.zhijuan.core.model.BookStatus
import app.zhijuan.core.model.ChapterStatus

data class CurrentConnectionSelection(
    val connectionId: String,
    val modelId: String,
) {
    init {
        require(connectionId.isNotBlank()) { "Connection id must not be blank." }
        require(modelId.isNotBlank()) { "Model id must not be blank." }
    }
}

interface CurrentConnectionGateway {
    suspend fun currentConnection(): CurrentConnectionSelection?
}

interface GenerationController {
    suspend fun findGenerationStatus(jobId: String): GenerationJobStatus?

    suspend fun pauseGeneration(jobId: String, requestedAt: Long): GenerationJobStatus

    suspend fun resumeGeneration(jobId: String, requestedAt: Long): GenerationJobStatus

    suspend fun stopGeneration(jobId: String, requestedAt: Long): GenerationJobStatus
}

data class LibraryBookSummary(
    val bookId: String,
    val title: String,
    val completedChapterCount: Int,
    val targetChapterCount: Int = completedChapterCount,
    val status: BookStatus = BookStatus.DRAFT,
    val generationStatus: GenerationJobStatus? = null,
    val generationJobId: String? = null,
    val generationStatusSummary: String = "",
) {
    init {
        require(bookId.isNotBlank()) { "Book id must not be blank." }
        require(title.isNotBlank()) { "Book title must not be blank." }
        require(completedChapterCount >= 0) { "Chapter count must not be negative." }
        require(targetChapterCount >= completedChapterCount) { "Book target cannot be below completed chapters." }
        require(generationJobId == null || generationJobId.isNotBlank()) { "Generation job id must not be blank." }
    }
}

data class LibraryChapterSummary(
    val chapterId: String?,
    val ordinal: Int,
    val title: String,
    val status: ChapterStatus = ChapterStatus.PLANNED,
    val hasReadableContent: Boolean = status in setOf(ChapterStatus.READY, ChapterStatus.EDITED),
) {
    init {
        require(chapterId == null || chapterId.isNotBlank()) { "Chapter id must not be blank." }
        require(ordinal > 0) { "Chapter ordinal must be positive." }
        require(title.isNotBlank()) { "Chapter title must not be blank." }
        require(!hasReadableContent || chapterId != null) { "Readable chapter requires a persisted chapter id." }
    }
}

data class LibraryDraftProjection(
    val chapterId: String,
    val text: String,
    val revision: Int,
) {
    init {
        require(chapterId.isNotBlank())
        require(text.isNotBlank())
        require(revision >= 0)
    }

    override fun toString(): String =
        "LibraryDraftProjection(characters=${text.length}, revision=$revision, content=redacted)"
}

interface LibraryRepository {
    suspend fun listBooks(): List<LibraryBookSummary>

    suspend fun listChapters(bookId: String): List<LibraryChapterSummary>

    suspend fun readChapter(chapterId: String): String?

    suspend fun readInProgressChapter(chapterId: String): LibraryDraftProjection? = null
}

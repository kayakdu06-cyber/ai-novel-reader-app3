package app.zhijuan.core.contract

import app.zhijuan.core.model.GenerationJobStatus

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

    suspend fun stopGeneration(jobId: String, requestedAt: Long): GenerationJobStatus
}

data class LibraryBookSummary(
    val bookId: String,
    val title: String,
    val completedChapterCount: Int,
) {
    init {
        require(bookId.isNotBlank()) { "Book id must not be blank." }
        require(title.isNotBlank()) { "Book title must not be blank." }
        require(completedChapterCount >= 0) { "Chapter count must not be negative." }
    }
}

data class LibraryChapterSummary(
    val chapterId: String,
    val ordinal: Int,
    val title: String,
) {
    init {
        require(chapterId.isNotBlank()) { "Chapter id must not be blank." }
        require(ordinal > 0) { "Chapter ordinal must be positive." }
        require(title.isNotBlank()) { "Chapter title must not be blank." }
    }
}

interface LibraryRepository {
    suspend fun listBooks(): List<LibraryBookSummary>

    suspend fun listChapters(bookId: String): List<LibraryChapterSummary>

    suspend fun readChapter(chapterId: String): String?
}

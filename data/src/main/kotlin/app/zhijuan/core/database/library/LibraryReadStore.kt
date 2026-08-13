package app.zhijuan.core.database.library

import app.zhijuan.core.contract.LibraryBookSummary
import app.zhijuan.core.contract.LibraryChapterSummary
import app.zhijuan.core.contract.LibraryDraftProjection
import app.zhijuan.core.database.ZhijuanDatabase
import app.zhijuan.core.database.generation.InProgressChapterDraftProjectionRepository
import app.zhijuan.core.model.ChapterStatus
import app.zhijuan.core.model.GenerationJobStatus
import app.zhijuan.core.model.OutlineNodeType
import app.zhijuan.core.security.AndroidProtectedArtifactStore

/** Narrow read-only facade. Feature modules never receive Room DAOs or mutable entities. */
class LibraryReadStore(
    private val database: ZhijuanDatabase,
    private val artifactStore: AndroidProtectedArtifactStore? = null,
) {
    suspend fun listBooks(): List<LibraryBookSummary> = database.libraryDao()
        .activeBooks()
        .map { book ->
            val activeJob = database.generationDao().jobsForBook(book.bookId)
                .firstOrNull { it.status !in TERMINAL_JOB_STATUSES }
            LibraryBookSummary(
                bookId = book.bookId,
                title = book.title,
                completedChapterCount = book.completedChapterCount,
                targetChapterCount = requireNotNull(book.targetChapters),
                status = book.status,
                generationStatus = activeJob?.status,
                generationJobId = activeJob?.jobId,
                generationStatusSummary = book.generationStatusSummary,
            )
        }

    suspend fun listChapters(bookId: String): List<LibraryChapterSummary> {
        require(bookId.isNotBlank()) { "Book id must not be blank." }
        val persisted = database.libraryDao().chaptersForBook(bookId).associateBy { it.chapterIndex }
        val planned = plannedChapterTitles(bookId)
        val maximumOrdinal = maxOf(persisted.keys.maxOrNull() ?: 0, planned.keys.maxOrNull() ?: 0)
        return (1..maximumOrdinal).map { ordinal ->
            val chapter = persisted[ordinal]
            LibraryChapterSummary(
                chapterId = chapter?.chapterId,
                ordinal = ordinal,
                title = chapter?.displayTitle?.ifBlank { chapter.plannedTitle }
                    ?: planned[ordinal]
                    ?: "第${ordinal}章",
                status = chapter?.status ?: ChapterStatus.PLANNED,
                hasReadableContent = chapter?.currentVersionId != null,
            )
        }
    }

    suspend fun readChapter(chapterId: String): String? {
        require(chapterId.isNotBlank()) { "Chapter id must not be blank." }
        return database.libraryDao().currentChapterContent(chapterId)
    }

    suspend fun readInProgressChapter(chapterId: String): LibraryDraftProjection? {
        require(chapterId.isNotBlank()) { "Chapter id must not be blank." }
        val store = artifactStore ?: return null
        val chapter = database.libraryDao().findChapter(chapterId) ?: return null
        if (chapter.currentVersionId != null) return null
        val projectionRepository = InProgressChapterDraftProjectionRepository(database, store)
        val stages = database.generationDao().draftStagesForChapter(chapter.bookId, chapterId)
        for (stage in stages) {
            val projection = runCatching { projectionRepository.current(stage.stageId) }.getOrNull()
            if (projection != null) {
                projection.text.takeIf(String::isNotBlank)?.let { text ->
                    return LibraryDraftProjection(chapterId, text, projection.revision)
                }
            }
        }
        return null
    }

    private suspend fun plannedChapterTitles(bookId: String): Map<Int, String> {
        val head = database.memoryDao().findMemoryHead(bookId) ?: return emptyMap()
        var cursor = head.currentOutlineRevisionId?.let { database.memoryDao().findOutlineRevision(it) }
        val titles = linkedMapOf<Int, String>()
        while (cursor != null) {
            database.memoryDao().findOutlineNodes(cursor.outlineRevisionId)
                .filter { it.nodeType == OutlineNodeType.CHAPTER && it.plannedChapterIndex != null }
                .sortedBy { it.plannedChapterIndex }
                .forEach { node -> titles.putIfAbsent(requireNotNull(node.plannedChapterIndex), node.title) }
            cursor = cursor.parentRevisionId?.let { database.memoryDao().findOutlineRevision(it) }
        }
        return titles
    }

    private companion object {
        val TERMINAL_JOB_STATUSES = setOf(
            GenerationJobStatus.COMPLETED,
            GenerationJobStatus.STOPPED,
        )
    }
}

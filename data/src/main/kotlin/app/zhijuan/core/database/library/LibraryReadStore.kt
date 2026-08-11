package app.zhijuan.core.database.library

import app.zhijuan.core.contract.LibraryBookSummary
import app.zhijuan.core.contract.LibraryChapterSummary
import app.zhijuan.core.database.ZhijuanDatabase

/** Narrow read-only facade. Feature modules never receive Room DAOs or mutable entities. */
class LibraryReadStore(private val database: ZhijuanDatabase) {
    suspend fun listBooks(): List<LibraryBookSummary> = database.libraryDao()
        .activeBooks()
        .map { book ->
            LibraryBookSummary(
                bookId = book.bookId,
                title = book.title,
                completedChapterCount = book.completedChapterCount,
            )
        }

    suspend fun listChapters(bookId: String): List<LibraryChapterSummary> {
        require(bookId.isNotBlank()) { "Book id must not be blank." }
        return database.libraryDao().chaptersForBook(bookId).map { chapter ->
            LibraryChapterSummary(
                chapterId = chapter.chapterId,
                ordinal = chapter.chapterIndex,
                title = chapter.displayTitle.ifBlank { chapter.plannedTitle },
            )
        }
    }

    suspend fun readChapter(chapterId: String): String? {
        require(chapterId.isNotBlank()) { "Chapter id must not be blank." }
        return database.libraryDao().currentChapterContent(chapterId)
    }
}

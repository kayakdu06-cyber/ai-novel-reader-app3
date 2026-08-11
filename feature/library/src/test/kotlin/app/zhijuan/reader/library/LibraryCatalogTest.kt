package app.zhijuan.reader.library

import app.zhijuan.core.contract.LibraryBookSummary
import app.zhijuan.core.contract.LibraryChapterSummary
import app.zhijuan.core.contract.LibraryRepository
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LibraryCatalogTest {
    @Test
    fun `contents remain in chapter order even when storage response is unordered`() = runBlocking {
        val catalog = LibraryCatalog(FakeLibrary())

        assertEquals(listOf("chapter-1", "chapter-2"), catalog.contents("book-1").map { it.chapterId })
    }

    @Test
    fun `shelf delegates stable book summaries`() = runBlocking {
        val catalog = LibraryCatalog(FakeLibrary())

        assertEquals("织卷", catalog.shelf().single().title)
    }
}

private class FakeLibrary : LibraryRepository {
    override suspend fun listBooks() = listOf(LibraryBookSummary("book-1", "织卷", 1))
    override suspend fun listChapters(bookId: String) = listOf(
        LibraryChapterSummary("chapter-2", 2, "第二章"),
        LibraryChapterSummary("chapter-1", 1, "第一章"),
    )
    override suspend fun readChapter(chapterId: String): String? = null
}

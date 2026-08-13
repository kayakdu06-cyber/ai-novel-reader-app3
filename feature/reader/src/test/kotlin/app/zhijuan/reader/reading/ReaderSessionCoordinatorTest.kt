package app.zhijuan.reader.reading

import app.zhijuan.core.contract.GenerationController
import app.zhijuan.core.contract.LibraryBookSummary
import app.zhijuan.core.contract.LibraryChapterSummary
import app.zhijuan.core.contract.LibraryRepository
import app.zhijuan.core.contract.LibraryDraftProjection
import app.zhijuan.core.model.GenerationJobStatus
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class ReaderSessionCoordinatorTest {
    @Test
    fun `opens completed chapter without waiting for later generation`() = runBlocking {
        val chapter = LibraryChapterSummary("chapter-1", 1, "第一章")
        val coordinator = ReaderSessionCoordinator(FakeLibrary(mapOf("chapter-1" to "正文")), FakeGeneration())

        val state = assertInstanceOf(ReaderChapterState.Ready::class.java, coordinator.openChapter(chapter))

        assertEquals("正文", state.content)
    }

    @Test
    fun `reports planned chapter as pending instead of inventing content`() = runBlocking {
        val chapter = LibraryChapterSummary("chapter-2", 2, "第二章")
        val coordinator = ReaderSessionCoordinator(FakeLibrary(emptyMap()), FakeGeneration())

        assertInstanceOf(ReaderChapterState.Pending::class.java, coordinator.openChapter(chapter))
    }

    @Test
    fun `marks protected draft as generating instead of formal content`() = runBlocking {
        val chapter = LibraryChapterSummary("chapter-2", 2, "第二章")
        val coordinator = ReaderSessionCoordinator(
            FakeLibrary(emptyMap(), LibraryDraftProjection("chapter-2", "完整草稿段落", 3)),
            FakeGeneration(),
        )

        val state = assertInstanceOf(ReaderChapterState.Generating::class.java, coordinator.openChapter(chapter))

        assertEquals("完整草稿段落", state.content)
        assertEquals(3, state.revision)
    }
}

private class FakeLibrary(
    private val content: Map<String, String>,
    private val draft: LibraryDraftProjection? = null,
) : LibraryRepository {
    override suspend fun listBooks(): List<LibraryBookSummary> = emptyList()
    override suspend fun listChapters(bookId: String): List<LibraryChapterSummary> = emptyList()
    override suspend fun readChapter(chapterId: String): String? = content[chapterId]
    override suspend fun readInProgressChapter(chapterId: String): LibraryDraftProjection? =
        draft?.takeIf { it.chapterId == chapterId }
}

private class FakeGeneration : GenerationController {
    override suspend fun findGenerationStatus(jobId: String): GenerationJobStatus? = null
    override suspend fun pauseGeneration(jobId: String, requestedAt: Long): GenerationJobStatus =
        GenerationJobStatus.PAUSED
    override suspend fun resumeGeneration(jobId: String, requestedAt: Long): GenerationJobStatus =
        GenerationJobStatus.READY
    override suspend fun stopGeneration(jobId: String, requestedAt: Long): GenerationJobStatus =
        GenerationJobStatus.STOPPED
}

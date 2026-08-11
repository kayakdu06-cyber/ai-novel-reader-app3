package app.zhijuan.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.zhijuan.core.database.library.BookCreationSnapshotEntity
import app.zhijuan.core.database.library.BookEntity
import app.zhijuan.core.database.search.MemorySearchDocumentEntity
import app.zhijuan.core.database.search.MemorySearchRecallRepositoryV1
import app.zhijuan.core.database.search.MemorySearchSourceTypeV1
import app.zhijuan.core.database.search.SearchIndexText
import app.zhijuan.core.model.BookLengthMode
import app.zhijuan.core.model.BookStatus
import app.zhijuan.core.model.TitleSource
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MemorySearchRecallDatabaseTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: ZhijuanDatabase
    private lateinit var repository: MemorySearchRecallRepositoryV1

    @Before
    fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(context, ZhijuanDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(LibraryDatabaseGuards.callback)
            .build()
            .also { it.openHelper.writableDatabase }
        createBook(BOOK_ID)
        repository = MemorySearchRecallRepositoryV1(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun accumulatesRouteHitsRanksDeterministicallyAndExcludesFutureAndOtherBooks() = runBlocking {
        createBook(SECOND_BOOK_ID)
        database.memorySearchDao().replaceAll(
            listOf(
                document("doc-multi", BOOK_ID, "玄铁剑", chapterIndex = 4, importance = 10),
                document("doc-target", BOOK_ID, "玄铁", chapterIndex = 3, importance = 100),
                document("doc-user", BOOK_ID, "密令", chapterIndex = 2, importance = 100),
                document("doc-arc", BOOK_ID, "黑木崖", chapterIndex = null, importance = 100),
                document("doc-future", BOOK_ID, "玄铁剑", chapterIndex = 5, importance = 100),
                document("doc-other-book", SECOND_BOOK_ID, "玄铁剑", chapterIndex = null, importance = 100),
            ),
        )

        val first = recall(
            targetChapterTitle = "玄铁剑",
            userAddition = "密令",
            targetArcTitle = "黑木崖",
        )
        val replay = recall(
            targetChapterTitle = "玄铁剑",
            userAddition = "密令",
            targetArcTitle = "黑木崖",
        )

        assertEquals(listOf("doc-multi", "doc-target", "doc-user", "doc-arc"), first.hits.map { it.document.documentId })
        assertEquals(2, first.hits[0].targetChapterProbeHits)
        assertEquals(1, first.hits[1].targetChapterProbeHits)
        assertEquals(1, first.hits[2].userAdditionProbeHits)
        assertEquals(2, first.hits[3].targetArcProbeHits)
        assertEquals(first, replay)
        assertEquals(first.queryFingerprint, replay.queryFingerprint)
        assertEquals(5, first.executedProbeCount)
        assertEquals(2, first.executedTargetChapterProbeCount)
        assertEquals(1, first.executedUserAdditionProbeCount)
        assertEquals(2, first.executedTargetArcProbeCount)

        val changed = recall(
            targetChapterTitle = "玄铁刀",
            userAddition = "密令",
            targetArcTitle = "黑木崖",
        )
        assertNotEquals(first.queryFingerprint, changed.queryFingerprint)

        val rendered = first.toString() + first.hits.joinToString()
        listOf("doc-multi", "source-doc-multi", "玄铁剑", "g7384x94c1").forEach { secret ->
            assertFalse(rendered.contains(secret))
        }
        assertTrue(rendered.contains("redacted"))
    }

    @Test
    fun cjkBigramRemainsOneFtsTokenAndDoesNotMatchSeparatedCharacters() = runBlocking {
        database.memorySearchDao().replaceAll(
            listOf(
                document("doc-adjacent", BOOK_ID, "甲乙", chapterIndex = 1, importance = 50),
                document("doc-separated", BOOK_ID, "甲丙乙", chapterIndex = 1, importance = 100),
            ),
        )

        val result = recall(targetChapterTitle = "甲乙")

        assertEquals(listOf("doc-adjacent"), result.hits.map { it.document.documentId })
        assertEquals(1, result.executedProbeCount)
    }

    @Test
    fun rankingUsesImportanceChapterStoryOrderAndDocumentIdentityTieBreakers() = runBlocking {
        database.memorySearchDao().replaceAll(
            listOf(
                document("doc-importance", BOOK_ID, "rankkey", chapterIndex = 1, storyOrder = 1, importance = 100),
                document("doc-chapter-new", BOOK_ID, "rankkey", chapterIndex = 4, storyOrder = 1, importance = 90),
                document("doc-chapter-old", BOOK_ID, "rankkey", chapterIndex = 2, storyOrder = 99, importance = 90),
                document("doc-story-high", BOOK_ID, "rankkey", chapterIndex = null, storyOrder = 20, importance = 80),
                document("doc-story-low", BOOK_ID, "rankkey", chapterIndex = null, storyOrder = 10, importance = 80),
                document("doc-tie-a", BOOK_ID, "rankkey", chapterIndex = null, storyOrder = null, importance = 70),
                document("doc-tie-b", BOOK_ID, "rankkey", chapterIndex = null, storyOrder = null, importance = 70),
            ),
        )

        val result = recall(targetChapterTitle = "rankkey")

        assertEquals(
            listOf(
                "doc-importance",
                "doc-chapter-new",
                "doc-chapter-old",
                "doc-story-high",
                "doc-story-low",
                "doc-tie-a",
                "doc-tie-b",
            ),
            result.hits.map { it.document.documentId },
        )
    }

    @Test
    fun routeQuotasBoundExecutionWithoutFailingTheChapter() = runBlocking {
        val result = repository.recall(
            bookId = BOOK_ID,
            targetChapterIndex = TARGET_CHAPTER_INDEX,
            targetChapterTitle = (0 until 40).joinToString(" ") { "target$it" },
            targetChapterPlanJson = "{}",
            targetArcTitle = (0 until 20).joinToString(" ") { "arc$it" },
            targetArcPlanJson = "{}",
            userAddition = (0 until 20).joinToString(" ") { "user$it" },
        )

        assertTrue(result.hits.isEmpty())
        assertEquals(80, result.compiledProbeCount)
        assertEquals(0, result.omittedCompiledProbeCount)
        assertEquals(64, result.executedProbeCount)
        assertEquals(32, result.executedTargetChapterProbeCount)
        assertEquals(16, result.executedUserAdditionProbeCount)
        assertEquals(16, result.executedTargetArcProbeCount)
        assertEquals(16, result.omittedExecutionProbeCount)
    }

    @Test
    fun perProbeAndFinalDocumentLimitsAreEnforced() = runBlocking {
        val documents = (0 until 9).flatMap { bucket ->
            (0 until 17).map { item ->
                document(
                    documentId = "doc-${bucket.toString().padStart(2, '0')}-${item.toString().padStart(2, '0')}",
                    bookId = BOOK_ID,
                    text = "bucket$bucket",
                    chapterIndex = null,
                    importance = 50,
                )
            }
        }
        database.memorySearchDao().replaceAll(documents)

        val result = recall(
            targetChapterTitle = (0 until 9).joinToString(" ") { "bucket$it" },
        )

        assertEquals(128, result.hits.size)
        assertEquals(16, result.omittedRankedDocumentCount)
        assertEquals(9, result.executedProbeCount)
        assertEquals(128, result.hits.map { it.document.documentId }.distinct().size)
    }

    @Test
    fun emptyProbesAreLegalAndMissingBooksFailClosedWithoutEchoingInputs() = runBlocking {
        val empty = repository.recall(
            bookId = BOOK_ID,
            targetChapterIndex = TARGET_CHAPTER_INDEX,
            targetChapterTitle = " ，。 ",
            targetChapterPlanJson = "{}",
            targetArcTitle = "",
            targetArcPlanJson = "[]",
            userAddition = null,
        )
        assertTrue(empty.hits.isEmpty())
        assertEquals(0, empty.executedProbeCount)

        val error = expectFailure {
            repository.recall(
                bookId = "missing-book",
                targetChapterIndex = TARGET_CHAPTER_INDEX,
                targetChapterTitle = "PRIVATE_CANARY",
                targetChapterPlanJson = "{}",
                targetArcTitle = "",
                targetArcPlanJson = "{}",
                userAddition = null,
            )
        }
        assertEquals("Recall book does not exist.", error.message)
        assertFalse(error.toString().contains("PRIVATE_CANARY"))
    }

    private suspend fun recall(
        targetChapterTitle: String,
        userAddition: String? = null,
        targetArcTitle: String = "",
    ) = repository.recall(
        bookId = BOOK_ID,
        targetChapterIndex = TARGET_CHAPTER_INDEX,
        targetChapterTitle = targetChapterTitle,
        targetChapterPlanJson = "{}",
        targetArcTitle = targetArcTitle,
        targetArcPlanJson = "{}",
        userAddition = userAddition,
    )

    private fun document(
        documentId: String,
        bookId: String,
        text: String,
        chapterIndex: Int?,
        storyOrder: Long? = null,
        importance: Int,
    ) = MemorySearchDocumentEntity(
        documentId = documentId,
        bookId = bookId,
        sourceType = MemorySearchSourceTypeV1.STORY_ENTITY.name,
        sourceId = "source-$documentId",
        chapterIndex = chapterIndex,
        storyOrder = storyOrder,
        importance = importance,
        sourceContentHash = "a".repeat(64),
        searchTerms = SearchIndexText.indexTerms(text),
        updatedAt = 1,
    )

    private suspend fun createBook(bookId: String) {
        val snapshotId = "snapshot-$bookId"
        database.libraryDao().createBook(
            BookCreationSnapshotEntity(
                snapshotId = snapshotId,
                rawInputJson = "{}",
                normalizedInputJson = "{}",
                inferenceProvenanceJson = "{}",
                genrePayloadJson = "{}",
                presentationProfileJson = "{}",
                modelPreferenceJson = "{}",
                schemaVersion = 1,
                promptBundleVersion = "prompt-1",
                contentControlSchemaVersion = 1,
                contentHash = "hash-$snapshotId",
                createdAt = 1,
            ),
            BookEntity(
                bookId = bookId,
                creationSnapshotId = snapshotId,
                title = "Book $bookId",
                titleSource = TitleSource.USER,
                status = BookStatus.DRAFT,
                lengthMode = BookLengthMode.LONG,
                targetCharacters = 500_000,
                targetChapters = 500,
                minimumChapters = 301,
                lengthPolicySchemaVersion = 1,
                createdAt = 1,
                updatedAt = 1,
            ),
        )
    }

    private suspend fun expectFailure(block: suspend () -> Unit): Throwable = try {
        block()
        throw AssertionError("Expected operation to fail.")
    } catch (error: Throwable) {
        if (error is AssertionError && error.message == "Expected operation to fail.") throw error
        error
    }

    private companion object {
        const val BOOK_ID = "recall-book-1"
        const val SECOND_BOOK_ID = "recall-book-2"
        const val TARGET_CHAPTER_INDEX = 5
    }
}

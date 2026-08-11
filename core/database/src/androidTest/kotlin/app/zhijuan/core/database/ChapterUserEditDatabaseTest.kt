package app.zhijuan.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.zhijuan.core.database.generation.GenerationJobEntity
import app.zhijuan.core.database.generation.GenerationStageEntity
import app.zhijuan.core.database.library.BookCreationSnapshotEntity
import app.zhijuan.core.database.library.BookEntity
import app.zhijuan.core.database.library.ChapterEntity
import app.zhijuan.core.database.library.ChapterUserEditCommand
import app.zhijuan.core.database.library.ChapterUserEditRepository
import app.zhijuan.core.database.library.CommitChapterVersionCommand
import app.zhijuan.core.database.memory.AggregateStateProjectionEntity
import app.zhijuan.core.database.memory.ChapterSummaryEntity
import app.zhijuan.core.database.memory.ConsistencyReportEntity
import app.zhijuan.core.database.memory.ContextSnapshotEntity
import app.zhijuan.core.database.search.MemorySearchDocumentFactoryV1
import app.zhijuan.core.database.search.MemorySearchSourceTypeV1
import app.zhijuan.core.model.BookLengthMode
import app.zhijuan.core.model.BookStatus
import app.zhijuan.core.model.ChapterStatus
import app.zhijuan.core.model.ChapterVersionSource
import app.zhijuan.core.model.ConsistencyStatus
import app.zhijuan.core.model.DerivedDataStatus
import app.zhijuan.core.model.GenerationJobStatus
import app.zhijuan.core.model.GenerationJobType
import app.zhijuan.core.model.GenerationPhase
import app.zhijuan.core.model.GenerationStageStatus
import app.zhijuan.core.model.GenerationTargetType
import app.zhijuan.core.model.TitleSource
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChapterUserEditDatabaseTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: ZhijuanDatabase
    private lateinit var repository: ChapterUserEditRepository

    @Before
    fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(context, ZhijuanDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(LibraryDatabaseGuards.callback)
            .build()
            .also { it.openHelper.writableDatabase }
        repository = ChapterUserEditRepository(database)
        createBook(BOOK_ID)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun editChapterThreeAtomicallyInvalidatesDerivedStateWithoutDeletingLaterBodies() = runBlocking {
        seedTenChapters()
        val oldSummary = seedChapterThreeAndFutureDerivedState()
        val search = database.memorySearchDao()
        search.insert(requireNotNull(MemorySearchDocumentFactoryV1.from(oldSummary)))
        assertEquals(1, search.count())
        assertEquals(1, scalarInt("SELECT COUNT(*) FROM memory_search_document_fts"))

        val command = editCommand()
        val result = repository.commit(command)

        assertFalse(result.replayed)
        assertTrue(result.isCurrentVersion)
        assertEquals(2, result.versionNo)
        val stale = requireNotNull(result.staleCascade)
        assertEquals(1, stale.summaries)
        assertEquals(8, stale.aggregateStates)
        assertEquals(7, stale.futureContexts)
        assertEquals(7, stale.futureReports)
        assertEquals(7, stale.futureChapters)
        assertEquals(1, stale.searchIdentitiesInvalidated)

        val library = database.libraryDao()
        val editedChapter = requireNotNull(library.findChapter("chapter-3"))
        assertEquals("chapter-3-v2", editedChapter.currentVersionId)
        assertEquals(ChapterStatus.EDITED, editedChapter.status)
        assertEquals(ConsistencyStatus.UNKNOWN, editedChapter.consistencyStatus)

        val versions = library.versionsForChapter("chapter-3")
        assertEquals(listOf("chapter-3-v1", "chapter-3-v2"), versions.map { it.chapterVersionId })
        assertEquals("原始正文-3", versions[0].content)
        assertEquals(ChapterVersionSource.IMPORTED, versions[0].source)
        assertEquals("修改后的第三章正文", versions[1].content)
        assertEquals(ChapterVersionSource.USER_EDIT, versions[1].source)
        assertEquals("chapter-3-v1", versions[1].parentVersionId)
        assertEquals(sha256("修改后的第三章正文"), versions[1].contentHash)
        assertNull(versions[1].generationStageId)
        assertNull(versions[1].modelSnapshotJson)

        assertEquals("STALE", database.memoryDao().latestSummaryHistoryStatus("chapter-3-v1"))
        for (index in 3..10) {
            assertEquals(
                "STALE",
                scalarString(
                    "SELECT status FROM aggregate_state_projection " +
                        "WHERE aggregate_state_id = 'aggregate-$index'",
                ),
            )
        }
        for (index in 4..10) {
            assertEquals(
                "STALE",
                scalarString("SELECT status FROM context_snapshot WHERE context_snapshot_id = 'context-$index'"),
            )
            assertEquals(
                "STALE",
                scalarString("SELECT status FROM consistency_report WHERE consistency_report_id = 'report-$index'"),
            )
            val futureChapter = requireNotNull(library.findChapter("chapter-$index"))
            assertEquals(ChapterStatus.CONSISTENCY_UNKNOWN, futureChapter.status)
            assertEquals(ConsistencyStatus.UNKNOWN, futureChapter.consistencyStatus)
            assertEquals("chapter-$index-v1", futureChapter.currentVersionId)
            assertEquals(
                "原始正文-$index",
                requireNotNull(library.findChapterVersion("chapter-$index-v1")).content,
            )
        }
        assertEquals(ChapterStatus.READY, library.findChapter("chapter-1")?.status)
        assertEquals(ChapterStatus.READY, library.findChapter("chapter-2")?.status)

        assertNull(
            search.findBySource(
                BOOK_ID,
                MemorySearchSourceTypeV1.CHAPTER_SUMMARY.name,
                oldSummary.chapterSummaryId,
            ),
        )
        assertEquals(0, search.count())
        assertEquals(0, scalarInt("SELECT COUNT(*) FROM memory_search_document_fts"))
        assertFalse(command.toString().contains(command.content))
        assertFalse(result.toString().contains(command.chapterId))
        assertFalse(result.toString().contains(command.newVersionId))
    }

    @Test
    fun exactReplayIsIdempotentAndConflictingReuseOfVersionIdFails() = runBlocking {
        seedTenChapters()
        val command = editCommand()

        val first = repository.commit(command)
        val replay = repository.commit(command)

        assertFalse(first.replayed)
        assertTrue(replay.replayed)
        assertTrue(replay.isCurrentVersion)
        assertNull(replay.staleCascade)
        assertEquals(2, database.libraryDao().versionCount("chapter-3"))

        val conflict = expectFailure {
            repository.commit(command.copy(content = "同一个版本编号下的另一份正文"))
        }
        assertTrue(conflict is IllegalArgumentException)
        assertEquals(2, database.libraryDao().versionCount("chapter-3"))
        assertEquals(
            "修改后的第三章正文",
            requireNotNull(database.libraryDao().findChapterVersion("chapter-3-v2")).content,
        )
    }

    @Test
    fun staleOrCrossBookEditFailsBeforeChangingCurrentState() = runBlocking {
        seedTenChapters()
        val summary = seedChapterThreeAndFutureDerivedState()
        database.memorySearchDao().insert(requireNotNull(MemorySearchDocumentFactoryV1.from(summary)))
        createBook(SECOND_BOOK_ID)

        val crossBook = expectFailure {
            repository.commit(editCommand().copy(bookId = SECOND_BOOK_ID, newVersionId = "cross-book-v2"))
        }
        assertTrue(crossBook is IllegalArgumentException)
        assertEquals("VALID", database.memoryDao().latestSummaryHistoryStatus("chapter-3-v1"))
        assertEquals(1, database.memorySearchDao().count())
        assertEquals("chapter-3-v1", database.libraryDao().findChapter("chapter-3")?.currentVersionId)

        repository.commit(editCommand())
        val versionCount = database.libraryDao().versionCount("chapter-3")
        val stale = expectFailure {
            repository.commit(
                editCommand().copy(
                    newVersionId = "chapter-3-v3",
                    content = "过期编辑正文",
                    editedAt = 101,
                ),
            )
        }
        assertTrue(stale is IllegalStateException)
        assertEquals(versionCount, database.libraryDao().versionCount("chapter-3"))
        assertEquals("chapter-3-v2", database.libraryDao().findChapter("chapter-3")?.currentVersionId)
        assertNull(database.libraryDao().findChapterVersion("chapter-3-v3"))

        val wrongChapter = expectFailure {
            repository.commit(
                ChapterUserEditCommand(
                    bookId = BOOK_ID,
                    chapterId = "chapter-3",
                    expectedCurrentVersionId = "chapter-2-v1",
                    newVersionId = "wrong-chapter-v2",
                    content = "错误章节来源",
                    editedAt = 102,
                ),
            )
        }
        assertTrue(wrongChapter is IllegalArgumentException)
        assertNull(database.libraryDao().findChapterVersion("wrong-chapter-v2"))
    }

    private fun editCommand() = ChapterUserEditCommand(
        bookId = BOOK_ID,
        chapterId = "chapter-3",
        expectedCurrentVersionId = "chapter-3-v1",
        newVersionId = "chapter-3-v2",
        content = "修改后的第三章正文",
        editedAt = 100,
    )

    private suspend fun seedTenChapters() {
        for (index in 1..10) {
            database.libraryDao().createChapter(
                ChapterEntity(
                    chapterId = "chapter-$index",
                    bookId = BOOK_ID,
                    chapterIndex = index,
                    plannedTitle = "第${index}章",
                    displayTitle = "第${index}章",
                    status = ChapterStatus.PLANNED,
                    consistencyStatus = ConsistencyStatus.UNKNOWN,
                    createdAt = 2,
                    updatedAt = 2,
                ),
            )
            database.libraryDao().commitChapterVersion(
                CommitChapterVersionCommand(
                    chapterVersionId = "chapter-$index-v1",
                    chapterId = "chapter-$index",
                    expectedCurrentVersionId = null,
                    content = "原始正文-$index",
                    contentHash = "fixture-hash-$index",
                    source = ChapterVersionSource.IMPORTED,
                    generationStageId = null,
                    modelSnapshotJson = null,
                    createdAt = 10L + index,
                ),
            )
        }
    }

    private suspend fun seedChapterThreeAndFutureDerivedState(): ChapterSummaryEntity {
        val memory = database.memoryDao()
        val summary = ChapterSummaryEntity(
            chapterSummaryId = "summary-3-old",
            bookId = BOOK_ID,
            chapterVersionId = "chapter-3-v1",
            chapterIndex = 3,
            schemaVersion = 1,
            summaryJson = """{"summary":"第三章旧摘要"}""",
            importance = 80,
            status = DerivedDataStatus.VALID,
            modelSnapshotJson = null,
            createdAt = 30,
            updatedAt = 30,
        )
        memory.insertSummary(summary)
        for (index in 3..10) {
            memory.insertAggregateState(
                AggregateStateProjectionEntity(
                    aggregateStateId = "aggregate-$index",
                    bookId = BOOK_ID,
                    throughChapterIndex = index,
                    sourceThroughChapterVersionId = "chapter-$index-v1",
                    schemaVersion = 1,
                    stateJson = "{}",
                    contentHash = "aggregate-hash-$index",
                    status = DerivedDataStatus.VALID,
                    createdAt = 30,
                    updatedAt = 30,
                ),
            )
        }
        createFutureAuditStages()
        for (index in 4..10) {
            memory.insertContextSnapshot(
                ContextSnapshotEntity(
                    contextSnapshotId = "context-$index",
                    bookId = BOOK_ID,
                    targetChapterId = "chapter-$index",
                    targetChapterIndex = index,
                    generationStageId = "context-stage-$index",
                    sourceManifestJson = "{}",
                    contentHash = "context-hash-$index",
                    status = DerivedDataStatus.VALID,
                    createdAt = 40,
                    updatedAt = 40,
                ),
            )
            memory.insertConsistencyReport(
                ConsistencyReportEntity(
                    consistencyReportId = "report-$index",
                    bookId = BOOK_ID,
                    targetChapterVersionId = "chapter-$index-v1",
                    targetChapterIndex = index,
                    generationStageId = null,
                    checkerVersion = "checker-1",
                    issuesJson = "[]",
                    status = DerivedDataStatus.VALID,
                    createdAt = 40,
                    updatedAt = 40,
                ),
            )
        }
        return summary
    }

    private suspend fun createFutureAuditStages() {
        database.generationDao().createJob(
            GenerationJobEntity(
                jobId = "edit-audit-job",
                bookId = BOOK_ID,
                jobType = GenerationJobType.REBUILD_MEMORY,
                status = GenerationJobStatus.CREATED,
                userIntentJson = "{}",
                budgetSnapshotJson = "{}",
                promptBundleVersion = "prompt-1",
                createdAt = 35,
                updatedAt = 35,
            ),
            (4..10).map { index ->
                GenerationStageEntity(
                    stageId = "context-stage-$index",
                    jobId = "edit-audit-job",
                    phase = GenerationPhase.ASSEMBLE_CONTEXT,
                    targetType = GenerationTargetType.CHAPTER,
                    targetId = "chapter-$index",
                    status = GenerationStageStatus.PENDING,
                    inputVersionHash = "input-$index",
                    idempotencyKey = "edit-context-$index",
                    maxAttempts = 3,
                    inputSourcesJson = "[]",
                    createdAt = 35L + index,
                    updatedAt = 35L + index,
                )
            },
        )
    }

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
                contentHash = "snapshot-hash-$bookId",
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

    private fun scalarInt(sql: String): Int = database.openHelper.writableDatabase.query(sql).use { cursor ->
        assertTrue(cursor.moveToFirst())
        cursor.getInt(0)
    }

    private fun scalarString(sql: String): String = database.openHelper.writableDatabase.query(sql).use { cursor ->
        assertTrue(cursor.moveToFirst())
        cursor.getString(0)
    }

    private suspend fun expectFailure(block: suspend () -> Unit): Throwable = try {
        block()
        throw AssertionError("Expected operation to fail.")
    } catch (error: Throwable) {
        if (error is AssertionError && error.message == "Expected operation to fail.") throw error
        error
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }

    private companion object {
        const val BOOK_ID = "edit-book"
        const val SECOND_BOOK_ID = "other-book"
    }
}

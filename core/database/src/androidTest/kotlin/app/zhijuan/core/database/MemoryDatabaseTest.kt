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
import app.zhijuan.core.database.library.CommitChapterVersionCommand
import app.zhijuan.core.database.memory.AggregateStateProjectionEntity
import app.zhijuan.core.database.memory.CanonFactEntity
import app.zhijuan.core.database.memory.ChapterSummaryEntity
import app.zhijuan.core.database.memory.ChapterTrackingProjectionEntity
import app.zhijuan.core.database.memory.ConsistencyReportEntity
import app.zhijuan.core.database.memory.ContextSnapshotEntity
import app.zhijuan.core.database.memory.EntityEventEntity
import app.zhijuan.core.database.memory.ForeshadowItemEntity
import app.zhijuan.core.database.memory.ForeshadowTransitionEntity
import app.zhijuan.core.database.memory.OutlineNodeEntity
import app.zhijuan.core.database.memory.OutlineRevisionEntity
import app.zhijuan.core.database.memory.StoryBibleRevisionEntity
import app.zhijuan.core.database.memory.StoryEntity
import app.zhijuan.core.database.memory.TimelineEventEntity
import app.zhijuan.core.model.AdultStatus
import app.zhijuan.core.model.BookLengthMode
import app.zhijuan.core.model.BookStatus
import app.zhijuan.core.model.CanonLevel
import app.zhijuan.core.model.ChapterStatus
import app.zhijuan.core.model.ChapterVersionSource
import app.zhijuan.core.model.ConsistencyStatus
import app.zhijuan.core.model.DerivedDataStatus
import app.zhijuan.core.model.ForeshadowStatus
import app.zhijuan.core.model.GenerationJobStatus
import app.zhijuan.core.model.GenerationJobType
import app.zhijuan.core.model.GenerationPhase
import app.zhijuan.core.model.GenerationStageStatus
import app.zhijuan.core.model.GenerationTargetType
import app.zhijuan.core.model.MemorySource
import app.zhijuan.core.model.OutlineNodeType
import app.zhijuan.core.model.RevisionSource
import app.zhijuan.core.model.StoryEntityType
import app.zhijuan.core.model.TitleSource
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MemoryDatabaseTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: ZhijuanDatabase

    @Before
    fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(context, ZhijuanDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(LibraryDatabaseGuards.callback)
            .build()
            .also { it.openHelper.writableDatabase }
        createBook(BOOK_ID)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun bibleAndOutlineRevisionsAreAppendOnlyAndHeadsAdvance() = runBlocking {
        val memory = database.memoryDao()
        memory.createBibleRevision(bible("bible-1", 1, null))
        memory.createBibleRevision(bible("bible-2", 2, "bible-1"))
        assertEquals("bible-2", memory.findMemoryHead(BOOK_ID)?.currentBibleRevisionId)

        expectFailure {
            database.openHelper.writableDatabase.execSQL(
                "UPDATE story_bible_revision SET payload_json = '{}' WHERE bible_revision_id = 'bible-1'",
            )
        }

        memory.createOutlineRevision(outline("outline-1", 1, null), listOf(node("node-1", "outline-1")))
        assertEquals("outline-1", memory.findMemoryHead(BOOK_ID)?.currentOutlineRevisionId)
        expectFailure {
            memory.createOutlineRevision(
                outline("outline-2", 2, "outline-1"),
                listOf(node("node-2", "outline-2", parent = "node-1")),
            )
        }
        assertEquals(1, memory.maximumOutlineRevision(BOOK_ID))
        assertEquals("outline-1", memory.findMemoryHead(BOOK_ID)?.currentOutlineRevisionId)
    }

    @Test
    fun adultClassificationRequiresExplicitAndConsistentAgeFacts() = runBlocking {
        val memory = database.memoryDao()
        memory.insertStoryEntity(character("adult", AdultStatus.CONFIRMED_ADULT, 22))
        memory.insertStoryEntity(character("minor", AdultStatus.NOT_ADULT, 17))
        memory.insertStoryEntity(character("unknown", AdultStatus.UNKNOWN, null))
        memory.insertStoryEntity(
            character("place", AdultStatus.NOT_APPLICABLE, null).copy(entityType = StoryEntityType.LOCATION),
        )

        expectFailure { memory.insertStoryEntity(character("bad-adult", AdultStatus.CONFIRMED_ADULT, 17)) }
        expectFailure { memory.insertStoryEntity(character("missing-age", AdultStatus.CONFIRMED_ADULT, null)) }
        expectFailure {
            memory.insertStoryEntity(
                character("bad-place", AdultStatus.CONFIRMED_ADULT, 22).copy(entityType = StoryEntityType.LOCATION),
            )
        }
        assertEquals("CONFIRMED_ADULT", memory.adultStatus("adult"))
        assertEquals(null, memory.adultStatus("bad-adult"))
    }

    @Test
    fun derivedMemoryCannotClaimAChapterVersionFromAnotherBook() = runBlocking {
        createBook("book-2")
        createChapter("book-2", "book-2-chapter-1", 1)
        commit("book-2-chapter-1", "book-2-version-1", null, "other")

        val error = expectFailure {
            database.memoryDao().insertSummary(
                summary("summary-cross", BOOK_ID, "book-2-version-1", 1),
            )
        }
        assertNotNull(error)
        assertEquals(null, database.memoryDao().summaryStatus("book-2-version-1"))
    }

    @Test
    fun replacingEarlierChapterStalesDerivedChainWithoutDeletingLaterText() = runBlocking {
        val library = database.libraryDao()
        val memory = database.memoryDao()
        (1..3).forEach { index -> createChapter(BOOK_ID, "chapter-$index", index) }
        commit("chapter-1", "version-1-old", null, "old")
        commit("chapter-2", "version-2", null, "future-two")
        commit("chapter-3", "version-3", null, "future-three")
        commit("chapter-1", "version-1-new", "version-1-old", "new")

        memory.insertSummary(summary("summary-old", BOOK_ID, "version-1-old", 1))
        memory.insertSummary(summary("summary-new", BOOK_ID, "version-1-new", 1))
        memory.insertStoryEntity(character("hero", AdultStatus.CONFIRMED_ADULT, 25))
        memory.insertEntityEvents(
            listOf(
                EntityEventEntity(
                    entityEventId = "event-1",
                    bookId = BOOK_ID,
                    entityId = "hero",
                    sourceChapterVersionId = "version-1-old",
                    storyOrder = 1,
                    attributeKey = "state",
                    oldValueJson = null,
                    newValueJson = "{}",
                    storyTimeExpression = null,
                    confidenceMicros = 900_000,
                    canonLevel = CanonLevel.STORY_CANON,
                    evidenceJson = "{}",
                    status = DerivedDataStatus.VALID,
                    createdAt = 20,
                ),
            ),
        )
        memory.insertCanonFacts(
            listOf(
                CanonFactEntity(
                    canonFactId = "fact-1",
                    bookId = BOOK_ID,
                    entityId = "hero",
                    factText = "fact",
                    factPayloadJson = "{}",
                    canonLevel = CanonLevel.STORY_CANON,
                    scopeJson = "{}",
                    sourceChapterVersionId = "version-1-old",
                    sourceBibleRevisionId = null,
                    validFromStoryOrder = 1,
                    validToStoryOrder = null,
                    conflictGroupId = null,
                    status = DerivedDataStatus.VALID,
                    createdAt = 20,
                ),
            ),
        )
        memory.insertTimelineEvents(
            listOf(
                TimelineEventEntity(
                    timelineEventId = "timeline-1",
                    bookId = BOOK_ID,
                    name = "event",
                    participantsJson = "[]",
                    locationEntityId = null,
                    storyTimeExpression = "day-1",
                    storyOrder = 1,
                    constraintsJson = "{}",
                    sourceChapterVersionId = "version-1-old",
                    status = DerivedDataStatus.VALID,
                    createdAt = 20,
                ),
            ),
        )
        memory.insertForeshadows(
            listOf(
                ForeshadowItemEntity(
                    foreshadowItemId = "foreshadow-1",
                    bookId = BOOK_ID,
                    description = "seed",
                    foreshadowStatus = ForeshadowStatus.PLANTED,
                    memoryStatus = DerivedDataStatus.VALID,
                    targetStartChapterIndex = 2,
                    targetEndChapterIndex = 3,
                    sourceChapterVersionId = "version-1-new",
                    plantedChapterVersionId = "version-1-new",
                    resolvedChapterVersionId = null,
                    visibleEntityIdsJson = "[]",
                    importance = 80,
                    source = MemorySource.CHAPTER_EXTRACTION,
                    createdAt = 20,
                    updatedAt = 20,
                ),
            ),
        )
        memory.insertAggregateState(aggregate("aggregate-1", 1, "version-1-old"))
        memory.insertAggregateState(aggregate("aggregate-2", 2, "version-2"))
        createAuditStages()
        memory.insertTrackingProjection(
            ChapterTrackingProjectionEntity(
                projectionId = "projection-1",
                bookId = BOOK_ID,
                chapterVersionId = "version-1-old",
                chapterIndex = 1,
                generationStageId = "tracking-stage-1",
                sourceChapterContentHash = "a".repeat(64),
                sourceMemorySnapshotHash = "b".repeat(64),
                priorForeshadowSnapshotHash = "c".repeat(64),
                outputContentHash = "d".repeat(64),
                payloadHash = "e".repeat(64),
                status = DerivedDataStatus.VALID,
                modelSnapshotJson = "{}",
                timelineEventCount = 1,
                foreshadowTransitionCount = 1,
                createdAt = 20,
                updatedAt = 20,
            ),
        )
        memory.insertForeshadowTransitions(
            listOf(
                ForeshadowTransitionEntity(
                    transitionId = "transition-1",
                    foreshadowItemId = "foreshadow-1",
                    bookId = BOOK_ID,
                    sourceChapterVersionId = "version-1-old",
                    generationStageId = "tracking-stage-1",
                    storyOrder = 1,
                    operation = "DEVELOP",
                    fromStatus = ForeshadowStatus.PLANTED,
                    toStatus = ForeshadowStatus.DEVELOPING,
                    evidenceJson = "{}",
                    status = DerivedDataStatus.VALID,
                    createdAt = 20,
                ),
            ),
        )
        memory.insertContextSnapshot(contextSnapshot("context-2", "chapter-2", 2, "context-stage-2"))
        memory.insertContextSnapshot(contextSnapshot("context-3", "chapter-3", 3, "context-stage-3"))
        memory.insertConsistencyReport(report("report-2", "version-2", 2))
        memory.insertConsistencyReport(report("report-3", "version-3", 3))

        val versionCountBefore = scalarInt("SELECT COUNT(*) FROM chapter_version")
        val result = memory.markDerivedDataStaleForReplacedChapter(BOOK_ID, "version-1-old", 30)

        assertEquals(1, result.summaries)
        assertEquals(1, result.entityEvents)
        assertEquals(1, result.canonFacts)
        assertEquals(1, result.timelineEvents)
        assertEquals(1, result.foreshadows)
        assertEquals(1, result.trackingProjections)
        assertEquals(1, result.foreshadowTransitions)
        assertEquals(2, result.aggregateStates)
        assertEquals(2, result.futureContexts)
        assertEquals(2, result.futureReports)
        assertEquals(2, result.futureChapters)
        assertEquals("STALE", memory.summaryStatus("version-1-old"))
        assertEquals("VALID", memory.summaryStatus("version-1-new"))
        assertEquals("STALE", memory.aggregateStatus("aggregate-2"))
        assertEquals(1, scalarInt("SELECT COUNT(*) FROM chapter_tracking_projection WHERE status = 'STALE'"))
        assertEquals(1, scalarInt("SELECT COUNT(*) FROM foreshadow_transition WHERE status = 'STALE'"))
        assertEquals(ChapterStatus.CONSISTENCY_UNKNOWN, library.findChapter("chapter-2")?.status)
        assertEquals(ConsistencyStatus.UNKNOWN, library.findChapter("chapter-3")?.consistencyStatus)
        assertEquals(versionCountBefore, scalarInt("SELECT COUNT(*) FROM chapter_version"))
        assertEquals("future-two", library.versionsForChapter("chapter-2").single().content)
        assertEquals(0, memory.markDerivedDataStaleForReplacedChapter(BOOK_ID, "version-1-old", 31).summaries)
    }

    @Test
    fun staleCascadeRejectsWrongBookBeforeChangingAnything() = runBlocking {
        createChapter(BOOK_ID, "chapter-1", 1)
        commit("chapter-1", "version-1", null, "content")
        database.memoryDao().insertSummary(summary("summary-1", BOOK_ID, "version-1", 1))
        createBook("book-2")

        expectFailure {
            database.memoryDao().markDerivedDataStaleForReplacedChapter("book-2", "version-1", 20)
        }
        assertEquals("VALID", database.memoryDao().summaryStatus("version-1"))
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

    private suspend fun createChapter(bookId: String, chapterId: String, index: Int) {
        database.libraryDao().createChapter(
            ChapterEntity(
                chapterId = chapterId,
                bookId = bookId,
                chapterIndex = index,
                plannedTitle = "Chapter $index",
                displayTitle = "Chapter $index",
                status = ChapterStatus.PLANNED,
                consistencyStatus = ConsistencyStatus.UNKNOWN,
                createdAt = 2,
                updatedAt = 2,
            ),
        )
    }

    private suspend fun commit(chapterId: String, versionId: String, expected: String?, content: String) {
        database.libraryDao().commitChapterVersion(
            CommitChapterVersionCommand(
                chapterVersionId = versionId,
                chapterId = chapterId,
                expectedCurrentVersionId = expected,
                content = content,
                contentHash = "hash-$versionId",
                source = ChapterVersionSource.USER_EDIT,
                generationStageId = null,
                modelSnapshotJson = null,
                createdAt = 10,
            ),
        )
    }

    private suspend fun createAuditStages() {
        database.generationDao().createJob(
            GenerationJobEntity(
                jobId = "audit-job",
                bookId = BOOK_ID,
                jobType = GenerationJobType.REBUILD_MEMORY,
                status = GenerationJobStatus.CREATED,
                userIntentJson = "{}",
                budgetSnapshotJson = "{}",
                promptBundleVersion = "prompt-1",
                createdAt = 15,
                updatedAt = 15,
            ),
            listOf("context-stage-2", "context-stage-3", "tracking-stage-1").mapIndexed { index, id ->
                GenerationStageEntity(
                    stageId = id,
                    jobId = "audit-job",
                    phase = if (id == "tracking-stage-1") GenerationPhase.EXTRACT_MEMORY else GenerationPhase.ASSEMBLE_CONTEXT,
                    targetType = GenerationTargetType.CHAPTER,
                    targetId = if (id == "tracking-stage-1") "chapter-1" else "chapter-${index + 2}",
                    status = GenerationStageStatus.PENDING,
                    inputVersionHash = "input-$id",
                    idempotencyKey = "idem-$id",
                    maxAttempts = 3,
                    inputSourcesJson = "[]",
                    createdAt = 15L + index,
                    updatedAt = 15L + index,
                )
            },
        )
    }

    private fun bible(id: String, revision: Int, parent: String?) = StoryBibleRevisionEntity(
        bibleRevisionId = id,
        bookId = BOOK_ID,
        revisionNo = revision,
        parentRevisionId = parent,
        source = RevisionSource.USER,
        schemaVersion = 1,
        contentControlSchemaVersion = 1,
        payloadJson = "{}",
        contentHash = "hash-$id",
        generationStageId = null,
        createdAt = revision.toLong(),
    )

    private fun outline(id: String, revision: Int, parent: String?) = OutlineRevisionEntity(
        outlineRevisionId = id,
        bookId = BOOK_ID,
        revisionNo = revision,
        parentRevisionId = parent,
        source = RevisionSource.USER,
        schemaVersion = 1,
        summaryJson = "{}",
        contentHash = "hash-$id",
        generationStageId = null,
        createdAt = revision.toLong(),
    )

    private fun node(id: String, revisionId: String, parent: String? = null) = OutlineNodeEntity(
        outlineNodeId = id,
        outlineRevisionId = revisionId,
        parentNodeId = parent,
        nodeType = OutlineNodeType.BOOK,
        orderKey = 1,
        plannedChapterIndex = null,
        title = "Plan",
        planJson = "{}",
        contentHash = "hash-$id",
        createdAt = 1,
    )

    private fun character(id: String, adultStatus: AdultStatus, age: Int?) = StoryEntity(
        entityId = id,
        bookId = BOOK_ID,
        entityType = StoryEntityType.CHARACTER,
        canonicalName = id,
        aliasesJson = "[]",
        stableDefinitionJson = "{}",
        adultStatus = adultStatus,
        ageYears = age,
        sourceBibleRevisionId = null,
        createdAt = 1,
        updatedAt = 1,
    )

    private fun summary(id: String, bookId: String, versionId: String, index: Int) = ChapterSummaryEntity(
        chapterSummaryId = id,
        bookId = bookId,
        chapterVersionId = versionId,
        chapterIndex = index,
        schemaVersion = 1,
        summaryJson = "{}",
        importance = 50,
        status = DerivedDataStatus.VALID,
        modelSnapshotJson = null,
        createdAt = 20,
        updatedAt = 20,
    )

    private fun aggregate(id: String, index: Int, versionId: String) = AggregateStateProjectionEntity(
        aggregateStateId = id,
        bookId = BOOK_ID,
        throughChapterIndex = index,
        sourceThroughChapterVersionId = versionId,
        schemaVersion = 1,
        stateJson = "{}",
        contentHash = "hash-$id",
        status = DerivedDataStatus.VALID,
        createdAt = 20,
        updatedAt = 20,
    )

    private fun contextSnapshot(id: String, chapterId: String, index: Int, stageId: String) = ContextSnapshotEntity(
        contextSnapshotId = id,
        bookId = BOOK_ID,
        targetChapterId = chapterId,
        targetChapterIndex = index,
        generationStageId = stageId,
        sourceManifestJson = "{}",
        contentHash = "hash-$id",
        status = DerivedDataStatus.VALID,
        createdAt = 20,
        updatedAt = 20,
    )

    private fun report(id: String, versionId: String, index: Int) = ConsistencyReportEntity(
        consistencyReportId = id,
        bookId = BOOK_ID,
        targetChapterVersionId = versionId,
        targetChapterIndex = index,
        generationStageId = null,
        checkerVersion = "checker-1",
        issuesJson = "[]",
        status = DerivedDataStatus.VALID,
        createdAt = 20,
        updatedAt = 20,
    )

    private fun scalarInt(sql: String): Int = database.openHelper.writableDatabase.query(sql).use { cursor ->
        assertTrue(cursor.moveToFirst())
        cursor.getInt(0)
    }

    private suspend fun expectFailure(block: suspend () -> Unit): Throwable = try {
        block()
        throw AssertionError("Expected operation to fail.")
    } catch (error: Throwable) {
        if (error is AssertionError && error.message == "Expected operation to fail.") throw error
        error
    }

    private companion object {
        const val BOOK_ID = "book-1"
    }
}

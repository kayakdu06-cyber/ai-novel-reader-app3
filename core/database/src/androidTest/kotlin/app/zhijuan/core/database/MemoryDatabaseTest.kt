package app.zhijuan.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.zhijuan.core.database.generation.GenerationJobEntity
import app.zhijuan.core.database.generation.GenerationStageEntity
import app.zhijuan.core.database.generation.ForeshadowProjectionRevisionWriterV1
import app.zhijuan.core.database.generation.ForeshadowProjectionSnapshotCodecV1
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
import app.zhijuan.core.database.search.MemorySearchDocumentFactoryV1
import app.zhijuan.core.database.search.MemorySearchBackfillDispositionV1
import app.zhijuan.core.database.search.MemorySearchBackfillRepositoryV1
import app.zhijuan.core.database.search.MemorySearchBackfillStateEntity
import app.zhijuan.core.database.search.MemorySearchIndexWriterV1
import app.zhijuan.core.database.search.MemorySearchSourceIdentityV1
import app.zhijuan.core.database.search.MemorySearchSourceTypeV1
import app.zhijuan.core.database.search.SearchIndexText
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
        assertEquals(null, database.memoryDao().latestSummaryHistoryStatus("book-2-version-1"))
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
        assertEquals(0, result.foreshadowProjectionRevisions)
        assertEquals(1, result.foreshadowTransitions)
        assertEquals(2, result.aggregateStates)
        assertEquals(2, result.futureContexts)
        assertEquals(2, result.futureReports)
        assertEquals(2, result.futureChapters)
        assertEquals("STALE", memory.latestSummaryHistoryStatus("version-1-old"))
        assertEquals("VALID", memory.latestSummaryHistoryStatus("version-1-new"))
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
        assertEquals("VALID", database.memoryDao().latestSummaryHistoryStatus("version-1"))
    }

    @Test
    fun foreshadowProjectionRevisionCapturesExactAfterStateAndIsAppendOnly() = runBlocking {
        val memory = database.memoryDao()
        createChapter(BOOK_ID, "revision-chapter", 1)
        commit("revision-chapter", "revision-version", null, "revision content")
        createHistoryStage("revision-job", "revision-stage", 20)
        val item = ForeshadowItemEntity(
            foreshadowItemId = "revision-foreshadow",
            bookId = BOOK_ID,
            description = "sealed clue",
            foreshadowStatus = ForeshadowStatus.PLANTED,
            memoryStatus = DerivedDataStatus.VALID,
            targetStartChapterIndex = 2,
            targetEndChapterIndex = 5,
            sourceChapterVersionId = "revision-version",
            plantedChapterVersionId = "revision-version",
            resolvedChapterVersionId = null,
            visibleEntityIdsJson = "[]",
            importance = 88,
            source = MemorySource.CHAPTER_EXTRACTION,
            createdAt = 20,
            updatedAt = 20,
        )
        val transition = ForeshadowTransitionEntity(
            transitionId = "revision-transition",
            foreshadowItemId = item.foreshadowItemId,
            bookId = BOOK_ID,
            sourceChapterVersionId = "revision-version",
            generationStageId = "revision-stage",
            storyOrder = 1,
            operation = "PLANT",
            fromStatus = null,
            toStatus = ForeshadowStatus.PLANTED,
            evidenceJson = "{}",
            status = DerivedDataStatus.VALID,
            createdAt = 20,
        )
        memory.insertForeshadows(listOf(item))
        memory.insertForeshadowTransitions(listOf(transition))

        val writer = ForeshadowProjectionRevisionWriterV1(memory)
        val inserted = writer.persistAfterStates(
            bookId = BOOK_ID,
            chapterIndex = 1,
            sourceChapterVersionId = "revision-version",
            generationStageId = "revision-stage",
            transitions = listOf(transition),
        ).single()

        assertEquals(item, ForeshadowProjectionSnapshotCodecV1.decodeAndVerify(inserted.snapshotJson, inserted.snapshotHash))
        assertTrue(inserted.toString().contains("snapshot=redacted"))
        assertFalse(inserted.toString().contains(item.description))
        assertEquals(listOf(inserted), memory.foreshadowProjectionRevisionsForStage("revision-stage"))
        assertEquals(listOf(inserted), memory.foreshadowProjectionRevisionHistoryForTransition(transition.transitionId))
        writer.requireStoredAfterStates(
            bookId = BOOK_ID,
            chapterIndex = 1,
            sourceChapterVersionId = "revision-version",
            generationStageId = "revision-stage",
            transitions = listOf(transition),
        )

        expectFailure {
            database.openHelper.writableDatabase.execSQL(
                "UPDATE foreshadow_projection_revision SET snapshot_json = '{}' WHERE transition_id = 'revision-transition'",
            )
        }
        expectFailure { memory.staleForeshadowTransitions("revision-version") }
        assertEquals(1, memory.staleForeshadowProjectionRevisions("revision-version"))
        assertEquals(1, memory.staleForeshadowTransitions("revision-version"))
        assertTrue(memory.foreshadowProjectionRevisionsForStage("revision-stage").isEmpty())
        assertEquals(
            DerivedDataStatus.STALE,
            memory.foreshadowProjectionRevisionHistoryForTransition(transition.transitionId).single().status,
        )
        expectFailure {
            database.openHelper.writableDatabase.execSQL(
                "UPDATE foreshadow_projection_revision SET status = 'VALID' WHERE transition_id = 'revision-transition'",
            )
        }
        expectFailure {
            database.openHelper.writableDatabase.execSQL(
                "DELETE FROM foreshadow_projection_revision WHERE transition_id = 'revision-transition'",
            )
        }
        Unit
    }

    @Test
    fun derivedHistorySlotsKeepStaleRowsAndExposeOnlyOneValidHead() = runBlocking {
        val memory = database.memoryDao()
        createChapter(BOOK_ID, "history-chapter", 1)
        commit("history-chapter", "history-version", null, "history")
        createHistoryStage("history-job-1", "history-stage-1", 20)
        createHistoryStage("history-job-2", "history-stage-2", 40)
        memory.insertStoryEntity(character("history-hero", AdultStatus.CONFIRMED_ADULT, 25))
        memory.insertForeshadows(
            listOf(
                ForeshadowItemEntity(
                    foreshadowItemId = "history-foreshadow",
                    bookId = BOOK_ID,
                    description = "history clue",
                    foreshadowStatus = ForeshadowStatus.PLANTED,
                    memoryStatus = DerivedDataStatus.VALID,
                    targetStartChapterIndex = 2,
                    targetEndChapterIndex = 3,
                    sourceChapterVersionId = "history-version",
                    plantedChapterVersionId = "history-version",
                    resolvedChapterVersionId = null,
                    visibleEntityIdsJson = "[]",
                    importance = 80,
                    source = MemorySource.CHAPTER_EXTRACTION,
                    createdAt = 20,
                    updatedAt = 20,
                ),
            ),
        )

        val firstSummary = summary("history-summary-1", BOOK_ID, "history-version", 1)
        val firstTracking = ChapterTrackingProjectionEntity(
            projectionId = "history-projection-1",
            bookId = BOOK_ID,
            chapterVersionId = "history-version",
            chapterIndex = 1,
            generationStageId = "history-stage-1",
            sourceChapterContentHash = "history-content-hash",
            sourceMemorySnapshotHash = "history-memory-hash-1",
            priorForeshadowSnapshotHash = "history-foreshadow-hash-1",
            outputContentHash = "history-output-hash-1",
            payloadHash = "history-payload-hash-1",
            status = DerivedDataStatus.VALID,
            modelSnapshotJson = "{}",
            timelineEventCount = 0,
            foreshadowTransitionCount = 1,
            createdAt = 20,
            updatedAt = 20,
        )
        val firstAggregate = aggregate("history-aggregate-1", 1, "history-version")
        val firstTransition = ForeshadowTransitionEntity(
            transitionId = "history-transition-1",
            foreshadowItemId = "history-foreshadow",
            bookId = BOOK_ID,
            sourceChapterVersionId = "history-version",
            generationStageId = "history-stage-1",
            storyOrder = 1,
            operation = "PLANT",
            fromStatus = null,
            toStatus = ForeshadowStatus.PLANTED,
            evidenceJson = "{}",
            status = DerivedDataStatus.VALID,
            createdAt = 20,
        )
        memory.insertSummary(firstSummary)
        memory.insertTrackingProjection(firstTracking)
        memory.insertAggregateState(firstAggregate)
        memory.insertForeshadowTransitions(listOf(firstTransition))

        assertEquals(1, memory.staleSummary("history-version", 30))
        assertEquals(1, memory.staleTrackingProjection("history-version", 30))
        assertEquals(1, memory.staleAggregateStates(BOOK_ID, 1, 30))
        assertEquals(1, memory.staleForeshadowTransitions("history-version"))

        val secondSummary = firstSummary.copy(
            chapterSummaryId = "history-summary-2",
            summaryJson = """{"generation":2}""",
            createdAt = 40,
            updatedAt = 40,
        )
        val secondTracking = firstTracking.copy(
            projectionId = "history-projection-2",
            generationStageId = "history-stage-2",
            sourceMemorySnapshotHash = "history-memory-hash-2",
            priorForeshadowSnapshotHash = "history-foreshadow-hash-2",
            outputContentHash = "history-output-hash-2",
            payloadHash = "history-payload-hash-2",
            createdAt = 40,
            updatedAt = 40,
        )
        val secondAggregate = firstAggregate.copy(
            aggregateStateId = "history-aggregate-2",
            stateJson = """{"generation":2}""",
            contentHash = "history-aggregate-hash-2",
            createdAt = 40,
            updatedAt = 40,
        )
        val secondTransition = firstTransition.copy(
            transitionId = "history-transition-2",
            generationStageId = "history-stage-2",
            evidenceJson = """{"generation":2}""",
            createdAt = 40,
        )
        memory.insertSummary(secondSummary)
        memory.insertTrackingProjection(secondTracking)
        memory.insertAggregateState(secondAggregate)
        memory.insertForeshadowTransitions(listOf(secondTransition))

        assertEquals(secondSummary, memory.findSummaryForVersion("history-version"))
        assertEquals(secondTracking, memory.findTrackingProjectionForVersion("history-version"))
        assertEquals("VALID", memory.latestSummaryHistoryStatus("history-version"))
        assertEquals(
            listOf(DerivedDataStatus.STALE, DerivedDataStatus.VALID),
            memory.summaryHistoryForVersion("history-version").map { it.status },
        )
        assertEquals(
            listOf(DerivedDataStatus.STALE, DerivedDataStatus.VALID),
            memory.trackingProjectionHistoryForVersion("history-version").map { it.status },
        )
        assertEquals(
            listOf(secondTracking),
            memory.validTrackingProjectionsFromChapter(BOOK_ID, 1),
        )
        assertEquals(
            listOf(firstTracking.copy(status = DerivedDataStatus.STALE, updatedAt = 30), secondTracking),
            memory.trackingProjectionHistoryFromChapter(BOOK_ID, 1),
        )
        assertEquals(
            listOf(DerivedDataStatus.STALE, DerivedDataStatus.VALID),
            memory.aggregateStateHistoryForChapter(BOOK_ID, 1).map { it.status },
        )
        assertEquals(
            listOf(DerivedDataStatus.STALE, DerivedDataStatus.VALID),
            memory.foreshadowTransitionHistoryForSlot("history-foreshadow", "history-version").map { it.status },
        )
        assertEquals(listOf(secondTransition), memory.foreshadowTransitionsForStage("history-stage-2"))
        assertTrue(memory.foreshadowTransitionsForStage("history-stage-1").isEmpty())

        memory.insertEntityEvents(
            listOf(
                EntityEventEntity(
                    entityEventId = "history-event-stale",
                    bookId = BOOK_ID,
                    entityId = "history-hero",
                    sourceChapterVersionId = "history-version",
                    storyOrder = 1,
                    attributeKey = "state",
                    oldValueJson = null,
                    newValueJson = """{"value":"old"}""",
                    storyTimeExpression = null,
                    confidenceMicros = 900_000,
                    canonLevel = CanonLevel.STORY_CANON,
                    evidenceJson = "{}",
                    status = DerivedDataStatus.STALE,
                    createdAt = 20,
                ),
                EntityEventEntity(
                    entityEventId = "history-event-valid",
                    bookId = BOOK_ID,
                    entityId = "history-hero",
                    sourceChapterVersionId = "history-version",
                    storyOrder = 1,
                    attributeKey = "state",
                    oldValueJson = null,
                    newValueJson = """{"value":"new"}""",
                    storyTimeExpression = null,
                    confidenceMicros = 900_000,
                    canonLevel = CanonLevel.STORY_CANON,
                    evidenceJson = "{}",
                    status = DerivedDataStatus.VALID,
                    createdAt = 40,
                ),
            ),
        )
        memory.insertCanonFacts(
            listOf(
                CanonFactEntity(
                    canonFactId = "history-fact-stale",
                    bookId = BOOK_ID,
                    entityId = "history-hero",
                    factText = "old fact",
                    factPayloadJson = "{}",
                    canonLevel = CanonLevel.STORY_CANON,
                    scopeJson = "{}",
                    sourceChapterVersionId = "history-version",
                    sourceBibleRevisionId = null,
                    validFromStoryOrder = 1,
                    validToStoryOrder = null,
                    conflictGroupId = null,
                    status = DerivedDataStatus.STALE,
                    createdAt = 20,
                ),
                CanonFactEntity(
                    canonFactId = "history-fact-valid",
                    bookId = BOOK_ID,
                    entityId = "history-hero",
                    factText = "new fact",
                    factPayloadJson = "{}",
                    canonLevel = CanonLevel.STORY_CANON,
                    scopeJson = "{}",
                    sourceChapterVersionId = "history-version",
                    sourceBibleRevisionId = null,
                    validFromStoryOrder = 1,
                    validToStoryOrder = null,
                    conflictGroupId = null,
                    status = DerivedDataStatus.VALID,
                    createdAt = 40,
                ),
            ),
        )
        memory.insertTimelineEvents(
            listOf(
                TimelineEventEntity(
                    timelineEventId = "history-timeline-stale",
                    bookId = BOOK_ID,
                    name = "old event",
                    participantsJson = "[]",
                    locationEntityId = null,
                    storyTimeExpression = "day one",
                    storyOrder = 1,
                    constraintsJson = "{}",
                    sourceChapterVersionId = "history-version",
                    status = DerivedDataStatus.STALE,
                    createdAt = 20,
                ),
                TimelineEventEntity(
                    timelineEventId = "history-timeline-valid",
                    bookId = BOOK_ID,
                    name = "new event",
                    participantsJson = "[]",
                    locationEntityId = null,
                    storyTimeExpression = "day one",
                    storyOrder = 1,
                    constraintsJson = "{}",
                    sourceChapterVersionId = "history-version",
                    status = DerivedDataStatus.VALID,
                    createdAt = 40,
                ),
            ),
        )
        assertEquals(listOf("history-event-valid"), memory.entityEventsForVersion("history-version").map { it.entityEventId })
        assertEquals(2, memory.entityEventHistoryForVersion("history-version").size)
        assertEquals(listOf("history-fact-valid"), memory.canonFactsForVersion("history-version").map { it.canonFactId })
        assertEquals(2, memory.canonFactHistoryForVersion("history-version").size)
        assertEquals(listOf("history-timeline-valid"), memory.timelineEventsForVersion("history-version").map { it.timelineEventId })
        assertEquals(2, memory.timelineEventHistoryForVersion("history-version").size)

        expectFailure { memory.insertSummary(secondSummary.copy(chapterSummaryId = "history-summary-3")) }
        expectFailure { memory.insertTrackingProjection(secondTracking.copy(projectionId = "history-projection-3")) }
        expectFailure { memory.insertAggregateState(secondAggregate.copy(aggregateStateId = "history-aggregate-3")) }
        expectFailure {
            memory.insertForeshadowTransitions(
                listOf(secondTransition.copy(transitionId = "history-transition-3")),
            )
        }
        expectFailure {
            database.openHelper.writableDatabase.execSQL(
                "UPDATE chapter_summary SET summary_json = '{\"tampered\":true}' " +
                    "WHERE chapter_summary_id = 'history-summary-1'",
            )
        }
        expectFailure {
            database.openHelper.writableDatabase.execSQL(
                "UPDATE chapter_summary SET status = 'VALID', updated_at = 50 " +
                    "WHERE chapter_summary_id = 'history-summary-1'",
            )
        }
        expectFailure {
            database.openHelper.writableDatabase.execSQL(
                "UPDATE chapter_summary SET updated_at = 29 " +
                    "WHERE chapter_summary_id = 'history-summary-1'",
            )
        }
        expectFailure {
            database.openHelper.writableDatabase.execSQL(
                "UPDATE chapter_summary SET model_snapshot_json = '{}' " +
                    "WHERE chapter_summary_id = 'history-summary-1'",
            )
        }
        expectFailure {
            database.openHelper.writableDatabase.execSQL(
                "UPDATE chapter_tracking_projection SET payload_hash = 'tampered' " +
                    "WHERE projection_id = 'history-projection-1'",
            )
        }
        expectFailure {
            database.openHelper.writableDatabase.execSQL(
                "UPDATE aggregate_state_projection SET state_json = '{\"tampered\":true}' " +
                    "WHERE aggregate_state_id = 'history-aggregate-1'",
            )
        }
        expectFailure {
            database.openHelper.writableDatabase.execSQL(
                "UPDATE foreshadow_transition SET from_status = 'PLANTED' " +
                    "WHERE transition_id = 'history-transition-1'",
            )
        }
        listOf(
            "UPDATE chapter_tracking_projection SET status = 'VALID', updated_at = 50 WHERE projection_id = 'history-projection-1'",
            "UPDATE aggregate_state_projection SET status = 'VALID', updated_at = 50 WHERE aggregate_state_id = 'history-aggregate-1'",
            "UPDATE foreshadow_transition SET status = 'VALID' WHERE transition_id = 'history-transition-1'",
            "UPDATE entity_event SET status = 'VALID' WHERE entity_event_id = 'history-event-stale'",
            "UPDATE canon_fact SET status = 'VALID' WHERE canon_fact_id = 'history-fact-stale'",
            "UPDATE timeline_event SET status = 'VALID' WHERE timeline_event_id = 'history-timeline-stale'",
        ).forEach { sql ->
            expectFailure { database.openHelper.writableDatabase.execSQL(sql) }
        }
        listOf(
            "UPDATE entity_event SET new_value_json = '{}' WHERE entity_event_id = 'history-event-valid'",
            "UPDATE canon_fact SET fact_text = 'tampered' WHERE canon_fact_id = 'history-fact-valid'",
            "UPDATE timeline_event SET name = 'tampered' WHERE timeline_event_id = 'history-timeline-valid'",
        ).forEach { sql ->
            expectFailure { database.openHelper.writableDatabase.execSQL(sql) }
        }
        mapOf(
            "chapter_summary" to "chapter_summary_id = 'history-summary-1'",
            "entity_event" to "entity_event_id = 'history-event-stale'",
            "canon_fact" to "canon_fact_id = 'history-fact-stale'",
            "timeline_event" to "timeline_event_id = 'history-timeline-stale'",
            "chapter_tracking_projection" to "projection_id = 'history-projection-1'",
            "aggregate_state_projection" to "aggregate_state_id = 'history-aggregate-1'",
            "foreshadow_transition" to "transition_id = 'history-transition-1'",
        ).forEach { (table, predicate) ->
            expectFailure {
                database.openHelper.writableDatabase.execSQL("DELETE FROM $table WHERE $predicate")
            }
        }

        createChapter(BOOK_ID, "history-concurrent-chapter", 2)
        commit("history-concurrent-chapter", "history-concurrent-version", null, "concurrent")
        val concurrentResults = coroutineScope {
            listOf("a", "b").map { suffix ->
                async {
                    runCatching {
                        memory.insertSummary(
                            summary(
                                id = "history-concurrent-summary-$suffix",
                                bookId = BOOK_ID,
                                versionId = "history-concurrent-version",
                                index = 2,
                            ),
                        )
                    }.isSuccess
                }
            }.awaitAll()
        }
        assertEquals(1, concurrentResults.count { it })
        assertEquals(1, memory.summaryHistoryForVersion("history-concurrent-version").size)
        Unit
    }

    @Test
    fun productionMemorySearchReplacementSynchronizesFtsAndRejectsIdentityCollision() = runBlocking {
        val search = database.memorySearchDao()
        val source = character("search-item", AdultStatus.NOT_APPLICABLE, null).copy(
            entityType = StoryEntityType.ITEM,
            canonicalName = "玄铁剑",
            aliasesJson = """["玄铁","铁剑"]""",
            stableDefinitionJson = """{"类型":"兵器"}""",
        )
        MemorySearchIndexWriterV1.replaceStoryBible(search, listOf(source), emptyList())
        val first = requireNotNull(search.findBySource(BOOK_ID, "STORY_ENTITY", source.entityId))
        val firstExpression = requireNotNull(SearchIndexText.matchExpression("玄铁剑"))
        assertEquals(1, search.count())
        assertTrue(firstExpression.split(' ').all { it in first.searchTerms.split(' ') })
        assertEquals(1, scalarInt("SELECT COUNT(*) FROM memory_search_document_fts"))
        assertEquals(
            1,
            scalarInt(
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'trigger' " +
                    "AND name = 'room_fts_content_sync_memory_search_document_fts_AFTER_INSERT'",
            ),
        )
        assertEquals(1, scalarInt("SELECT COUNT(*) FROM memory_search_document_fts WHERE memory_search_document_fts MATCH 'c7384'"))
        assertEquals(1, scalarInt("SELECT COUNT(*) FROM memory_search_document_fts WHERE memory_search_document_fts MATCH 'g7384x94c1'"))
        assertEquals(
            1,
            scalarInt(
                "SELECT COUNT(*) FROM memory_search_document_fts " +
                    "WHERE memory_search_document_fts MATCH '$firstExpression'",
            ),
        )
        assertEquals(
            listOf(first.documentId),
            search.searchBeforeChapter(
                bookId = BOOK_ID,
                matchExpression = firstExpression,
                targetChapterIndex = 1,
                limit = 10,
            ).map { it.documentId },
        )

        val updated = source.copy(canonicalName = "青铜铃", aliasesJson = "[]", updatedAt = 2)
        MemorySearchIndexWriterV1.replaceStoryBible(search, listOf(updated), emptyList())
        val replaced = requireNotNull(search.findBySource(BOOK_ID, "STORY_ENTITY", source.entityId))
        assertEquals(first.rowId, replaced.rowId)
        assertEquals(1, search.count())
        assertTrue(
            search.searchBeforeChapter(
                BOOK_ID,
                requireNotNull(SearchIndexText.matchExpression("玄铁剑")),
                1,
                10,
            ).isEmpty(),
        )
        assertEquals(
            listOf(replaced.documentId),
            search.searchBeforeChapter(
                BOOK_ID,
                requireNotNull(SearchIndexText.matchExpression("青铜铃")),
                1,
                10,
            ).map { it.documentId },
        )

        val collision = requireNotNull(MemorySearchDocumentFactoryV1.from(updated)).copy(sourceId = "another-source")
        expectFailure { search.replaceAll(listOf(collision)) }
        assertEquals(replaced, search.findBySource(BOOK_ID, "STORY_ENTITY", source.entityId))
        assertNull(search.findBySource(BOOK_ID, "STORY_ENTITY", "another-source"))

        search.deleteSources(
            listOf(MemorySearchSourceIdentityV1(BOOK_ID, MemorySearchSourceTypeV1.STORY_ENTITY, source.entityId)),
        )
        assertEquals(0, search.count())
        assertEquals(0, scalarInt("SELECT COUNT(*) FROM memory_search_document_fts"))
    }

    @Test
    fun legacySearchBackfillIndexesOnlyCurrentAuthoritativeRowsAcrossAllSixSourceTypes() = runBlocking {
        val fixture = seedBackfillSources()
        createBook(SECOND_BOOK_ID)
        val secondBookSeed = requireNotNull(
            MemorySearchDocumentFactoryV1.from(
                character("second-book-seed", AdultStatus.NOT_APPLICABLE, null).copy(
                    bookId = SECOND_BOOK_ID,
                    entityType = StoryEntityType.ITEM,
                    canonicalName = "second book seed",
                ),
            ),
        )
        database.memorySearchDao().replaceAll(listOf(secondBookSeed))
        val secondBookBefore = requireNotNull(
            database.memorySearchDao().findBySource(
                SECOND_BOOK_ID,
                MemorySearchSourceTypeV1.STORY_ENTITY.name,
                "second-book-seed",
            ),
        )

        val result = MemorySearchBackfillRepositoryV1(database, pageSize = 1).ensureReady(
            bookId = BOOK_ID,
            completedAt = 100,
        )

        assertEquals(MemorySearchBackfillDispositionV1.REBUILT, result.disposition)
        assertEquals(fixture.includedSourceIds.values.sumOf(Set<String>::size).toLong(), result.indexedDocumentCount)
        val documents = database.memorySearchDao().documentsForBook(BOOK_ID)
        assertEquals(
            fixture.includedSourceIds,
            documents.groupBy { MemorySearchSourceTypeV1.valueOf(it.sourceType) }
                .mapValues { (_, rows) -> rows.map { it.sourceId }.toSet() },
        )
        assertTrue(documents.none { it.sourceId in fixture.excludedSourceIds })
        fixture.expectedChapterIndices.forEach { (sourceId, expectedChapterIndex) ->
            assertEquals(
                "Unexpected chapter index for $sourceId",
                expectedChapterIndex,
                documents.single { it.sourceId == sourceId }.chapterIndex,
            )
        }
        val marker = requireNotNull(database.memorySearchBackfillStateDao().find(BOOK_ID))
        assertEquals(2, marker.indexSchemaVersion)
        assertEquals(100, marker.completedAt)
        assertEquals(secondBookBefore, database.memorySearchDao().documentsForBook(SECOND_BOOK_ID).single())
        assertEquals(documents.size + 1, scalarInt("SELECT COUNT(*) FROM memory_search_document_fts"))
    }

    @Test
    fun completedLegacySearchBackfillMarkerSkipsASecondRebuild() = runBlocking {
        seedBackfillSources()
        val repository = MemorySearchBackfillRepositoryV1(database, pageSize = 1)
        repository.ensureReady(BOOK_ID, completedAt = 100)
        val before = database.memorySearchDao().documentsForBook(BOOK_ID)
        database.memoryDao().insertStoryEntity(
            character("added-after-marker", AdultStatus.NOT_APPLICABLE, null).copy(
                entityType = StoryEntityType.ITEM,
                canonicalName = "added after marker",
            ),
        )

        val result = repository.ensureReady(BOOK_ID, completedAt = 200)

        assertEquals(MemorySearchBackfillDispositionV1.ALREADY_READY, result.disposition)
        assertEquals(before.size.toLong(), result.indexedDocumentCount)
        assertEquals(before, database.memorySearchDao().documentsForBook(BOOK_ID))
        assertEquals(100, requireNotNull(database.memorySearchBackfillStateDao().find(BOOK_ID)).completedAt)
    }

    @Test
    fun versionOneSearchMarkerRebuildsTokenizerUnsafeBigramsToVersionTwo() = runBlocking {
        val source = character("legacy-token-source", AdultStatus.NOT_APPLICABLE, null).copy(
            entityType = StoryEntityType.ITEM,
            canonicalName = "玄铁剑",
        )
        database.memoryDao().insertStoryEntity(source)
        val currentDocument = requireNotNull(MemorySearchDocumentFactoryV1.from(source))
        val legacyDocument = currentDocument.copy(
            searchTerms = currentDocument.searchTerms.replace('x', '_'),
        )
        database.memorySearchDao().replaceAll(listOf(legacyDocument))
        database.memorySearchBackfillStateDao().store(
            MemorySearchBackfillStateEntity(
                bookId = BOOK_ID,
                indexSchemaVersion = 1,
                completedAt = 100,
            ),
        )

        val result = MemorySearchBackfillRepositoryV1(database).ensureReady(
            bookId = BOOK_ID,
            completedAt = 200,
        )

        assertEquals(MemorySearchBackfillDispositionV1.REBUILT, result.disposition)
        assertEquals(1, result.indexedDocumentCount)
        val rebuilt = database.memorySearchDao().documentsForBook(BOOK_ID).single()
        assertTrue("g7384x94c1" in rebuilt.searchTerms)
        assertFalse("g7384_94c1" in rebuilt.searchTerms)
        val marker = requireNotNull(database.memorySearchBackfillStateDao().find(BOOK_ID))
        assertEquals(2, marker.indexSchemaVersion)
        assertEquals(200, marker.completedAt)
    }

    @Test
    fun legacySearchBackfillFailureRollsBackDeletedIndexAndCompletionMarker() = runBlocking {
        val previousSeed = requireNotNull(
            MemorySearchDocumentFactoryV1.from(
                character("previous-index", AdultStatus.NOT_APPLICABLE, null).copy(
                    entityType = StoryEntityType.ITEM,
                    canonicalName = "previous index",
                ),
            ),
        )
        database.memorySearchDao().replaceAll(listOf(previousSeed))
        val previousDocument = database.memorySearchDao().documentsForBook(BOOK_ID).single()
        database.memoryDao().insertStoryEntity(
            character("invalid-json-source", AdultStatus.NOT_APPLICABLE, null).copy(
                entityType = StoryEntityType.ITEM,
                aliasesJson = "{",
            ),
        )

        expectFailure {
            MemorySearchBackfillRepositoryV1(database, pageSize = 1).ensureReady(BOOK_ID, completedAt = 100)
        }

        assertEquals(listOf(previousDocument), database.memorySearchDao().documentsForBook(BOOK_ID))
        assertNull(database.memorySearchBackfillStateDao().find(BOOK_ID))
        assertEquals(1, scalarInt("SELECT COUNT(*) FROM memory_search_document_fts"))
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

    private suspend fun seedBackfillSources(): BackfillFixture {
        val memory = database.memoryDao()
        createChapter(BOOK_ID, "backfill-chapter", 1)
        commit("backfill-chapter", "backfill-version-old", null, "old")
        commit("backfill-chapter", "backfill-version-current", "backfill-version-old", "current")

        memory.insertStoryEntity(
            character("backfill-hero", AdultStatus.CONFIRMED_ADULT, 25).copy(
                canonicalName = "active hero",
                stableDefinitionJson = """{"role":"hero"}""",
            ),
        )
        memory.insertStoryEntity(
            character("backfill-archived", AdultStatus.NOT_APPLICABLE, null).copy(
                entityType = StoryEntityType.ITEM,
                canonicalName = "archived item",
                archivedAt = 30,
            ),
        )
        memory.insertSummary(
            summary("backfill-summary-old", BOOK_ID, "backfill-version-old", 1).copy(
                summaryJson = """{"summary":"old summary"}""",
            ),
        )
        memory.insertSummary(
            summary("backfill-summary-current", BOOK_ID, "backfill-version-current", 1).copy(
                summaryJson = """{"summary":"current summary"}""",
            ),
        )
        memory.insertEntityEvents(
            listOf(
                backfillEntityEvent("backfill-event-old", "backfill-version-old"),
                backfillEntityEvent("backfill-event-current", "backfill-version-current"),
            ),
        )
        memory.insertCanonFacts(
            listOf(
                backfillCanonFact("backfill-fact-old", "backfill-version-old"),
                backfillCanonFact("backfill-fact-current", "backfill-version-current"),
                backfillCanonFact("backfill-fact-global", null),
            ),
        )
        memory.insertTimelineEvents(
            listOf(
                backfillTimelineEvent("backfill-timeline-old", "backfill-version-old"),
                backfillTimelineEvent("backfill-timeline-current", "backfill-version-current"),
            ),
        )
        memory.insertForeshadows(
            listOf(
                backfillForeshadow("backfill-foreshadow-global", null, DerivedDataStatus.VALID),
                backfillForeshadow("backfill-foreshadow-stale", "backfill-version-current", DerivedDataStatus.STALE),
            ),
        )

        return BackfillFixture(
            includedSourceIds = mapOf(
                MemorySearchSourceTypeV1.STORY_ENTITY to setOf("backfill-hero"),
                MemorySearchSourceTypeV1.CHAPTER_SUMMARY to setOf("backfill-summary-current"),
                MemorySearchSourceTypeV1.ENTITY_EVENT to setOf("backfill-event-current"),
                MemorySearchSourceTypeV1.CANON_FACT to setOf("backfill-fact-current", "backfill-fact-global"),
                MemorySearchSourceTypeV1.TIMELINE_EVENT to setOf("backfill-timeline-current"),
                MemorySearchSourceTypeV1.FORESHADOW to setOf("backfill-foreshadow-global"),
            ),
            excludedSourceIds = setOf(
                "backfill-archived",
                "backfill-summary-old",
                "backfill-event-old",
                "backfill-fact-old",
                "backfill-timeline-old",
                "backfill-foreshadow-stale",
            ),
            expectedChapterIndices = mapOf(
                "backfill-hero" to null,
                "backfill-summary-current" to 1,
                "backfill-event-current" to 1,
                "backfill-fact-current" to 1,
                "backfill-fact-global" to null,
                "backfill-timeline-current" to 1,
                "backfill-foreshadow-global" to null,
            ),
        )
    }

    private suspend fun createHistoryStage(jobId: String, stageId: String, createdAt: Long) {
        database.generationDao().createJob(
            GenerationJobEntity(
                jobId = jobId,
                bookId = BOOK_ID,
                jobType = GenerationJobType.REBUILD_MEMORY,
                status = GenerationJobStatus.CREATED,
                userIntentJson = "{}",
                budgetSnapshotJson = "{}",
                promptBundleVersion = "prompt-1",
                createdAt = createdAt,
                updatedAt = createdAt,
            ),
            listOf(
                GenerationStageEntity(
                    stageId = stageId,
                    jobId = jobId,
                    phase = GenerationPhase.EXTRACT_MEMORY,
                    targetType = GenerationTargetType.CHAPTER,
                    targetId = "history-chapter",
                    status = GenerationStageStatus.PENDING,
                    inputVersionHash = "input-$stageId",
                    idempotencyKey = "idem-$stageId",
                    maxAttempts = 2,
                    inputSourcesJson = "[]",
                    createdAt = createdAt,
                    updatedAt = createdAt,
                ),
            ),
        )
    }

    private fun backfillEntityEvent(id: String, versionId: String) = EntityEventEntity(
        entityEventId = id,
        bookId = BOOK_ID,
        entityId = "backfill-hero",
        sourceChapterVersionId = versionId,
        storyOrder = 1,
        attributeKey = "state",
        oldValueJson = null,
        newValueJson = """{"value":"awake"}""",
        storyTimeExpression = "day one",
        confidenceMicros = 900_000,
        canonLevel = CanonLevel.STORY_CANON,
        evidenceJson = """{"evidence":"chapter"}""",
        status = DerivedDataStatus.VALID,
        createdAt = 20,
    )

    private fun backfillCanonFact(id: String, versionId: String?) = CanonFactEntity(
        canonFactId = id,
        bookId = BOOK_ID,
        entityId = "backfill-hero",
        factText = "canonical fact $id",
        factPayloadJson = "{}",
        canonLevel = CanonLevel.STORY_CANON,
        scopeJson = "{}",
        sourceChapterVersionId = versionId,
        sourceBibleRevisionId = null,
        validFromStoryOrder = 1,
        validToStoryOrder = null,
        conflictGroupId = null,
        status = DerivedDataStatus.VALID,
        createdAt = 20,
    )

    private fun backfillTimelineEvent(id: String, versionId: String) = TimelineEventEntity(
        timelineEventId = id,
        bookId = BOOK_ID,
        name = "timeline $id",
        participantsJson = "[]",
        locationEntityId = null,
        storyTimeExpression = "day one",
        storyOrder = 1,
        constraintsJson = "{}",
        sourceChapterVersionId = versionId,
        status = DerivedDataStatus.VALID,
        createdAt = 20,
    )

    private fun backfillForeshadow(
        id: String,
        versionId: String?,
        status: DerivedDataStatus,
    ) = ForeshadowItemEntity(
        foreshadowItemId = id,
        bookId = BOOK_ID,
        description = "foreshadow $id",
        foreshadowStatus = ForeshadowStatus.PLANTED,
        memoryStatus = status,
        targetStartChapterIndex = 2,
        targetEndChapterIndex = 3,
        sourceChapterVersionId = versionId,
        plantedChapterVersionId = versionId,
        resolvedChapterVersionId = null,
        visibleEntityIdsJson = "[]",
        importance = 80,
        source = if (versionId == null) MemorySource.USER else MemorySource.CHAPTER_EXTRACTION,
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
        const val SECOND_BOOK_ID = "book-2"
    }

    private data class BackfillFixture(
        val includedSourceIds: Map<MemorySearchSourceTypeV1, Set<String>>,
        val excludedSourceIds: Set<String>,
        val expectedChapterIndices: Map<String, Int?>,
    )
}

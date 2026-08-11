package app.zhijuan.core.database

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.zhijuan.core.database.library.BookCreationSnapshotEntity
import app.zhijuan.core.database.library.BookEntity
import app.zhijuan.core.database.library.ChapterEntity
import app.zhijuan.core.database.library.CommitChapterVersionCommand
import app.zhijuan.core.database.memory.CanonFactEntity
import app.zhijuan.core.database.memory.ChapterSummaryEntity
import app.zhijuan.core.database.memory.EntityEventEntity
import app.zhijuan.core.database.memory.ForeshadowItemEntity
import app.zhijuan.core.database.memory.StoryBibleRevisionEntity
import app.zhijuan.core.database.memory.StoryEntity
import app.zhijuan.core.database.memory.TimelineEventEntity
import app.zhijuan.core.database.search.CanonFactSourceV1
import app.zhijuan.core.database.search.ChapterSummarySourceV1
import app.zhijuan.core.database.search.EntityEventSourceV1
import app.zhijuan.core.database.search.ForeshadowSourceV1
import app.zhijuan.core.database.search.MemorySearchDocumentEntity
import app.zhijuan.core.database.search.MemorySearchHydrationRepositoryV1
import app.zhijuan.core.database.search.MemorySearchIndexWriterV1
import app.zhijuan.core.database.search.MemorySearchRecallHitV1
import app.zhijuan.core.database.search.MemorySearchRecallResultV1
import app.zhijuan.core.database.search.MemorySearchSourceTypeV1
import app.zhijuan.core.database.search.StoryEntitySourceV1
import app.zhijuan.core.database.search.TimelineEventSourceV1
import app.zhijuan.core.model.AdultStatus
import app.zhijuan.core.model.BookLengthMode
import app.zhijuan.core.model.BookStatus
import app.zhijuan.core.model.CanonLevel
import app.zhijuan.core.model.ChapterStatus
import app.zhijuan.core.model.ChapterVersionSource
import app.zhijuan.core.model.ConsistencyStatus
import app.zhijuan.core.model.DerivedDataStatus
import app.zhijuan.core.model.ForeshadowStatus
import app.zhijuan.core.model.MemorySource
import app.zhijuan.core.model.RevisionSource
import app.zhijuan.core.model.StoryEntityType
import app.zhijuan.core.model.TitleSource
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MemorySearchHydrationDatabaseTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: ZhijuanDatabase
    private lateinit var repository: MemorySearchHydrationRepositoryV1

    @Before
    fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(context, ZhijuanDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(LibraryDatabaseGuards.callback)
            .build()
            .also { it.openHelper.writableDatabase }
        createBook(BOOK_ID)
        repository = MemorySearchHydrationRepositoryV1(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun hydratesAllAuthoritativeTypesPreservesRecallOrderAndRedactsRendering() = runBlocking {
        val fixture = seedAllSources()
        val requested = listOf(
            fixture.document(GLOBAL_FORESHADOW_ID),
            fixture.document(CHAPTER_FACT_ID),
            fixture.document(STORY_ID),
            fixture.document(TIMELINE_ID),
            fixture.document(BIBLE_FACT_ID),
            fixture.document(SUMMARY_ID),
            fixture.document(SOURCE_FORESHADOW_ID),
            fixture.document(EVENT_ID),
        )

        val result = repository.hydrate(
            bookId = BOOK_ID,
            targetChapterIndex = TARGET_CHAPTER_INDEX,
            recallResult = recallResult(requested),
        )

        assertEquals(requested.map { it.sourceId }, result.hits.map { it.recallHit.document.sourceId })
        assertEquals(8, result.inputPointerCount)
        assertEquals(0, result.rejectedPointerCount)
        assertFalse(result.indexRebuildRequired)
        assertEquals(
            setOf(
                MemorySearchSourceTypeV1.STORY_ENTITY,
                MemorySearchSourceTypeV1.CHAPTER_SUMMARY,
                MemorySearchSourceTypeV1.ENTITY_EVENT,
                MemorySearchSourceTypeV1.CANON_FACT,
                MemorySearchSourceTypeV1.TIMELINE_EVENT,
                MemorySearchSourceTypeV1.FORESHADOW,
            ),
            result.hits.map { it.authoritativeSource.sourceType }.toSet(),
        )
        assertTrue(result.hits.any { it.authoritativeSource is StoryEntitySourceV1 })
        assertTrue(result.hits.any { it.authoritativeSource is ChapterSummarySourceV1 })
        assertTrue(result.hits.any { it.authoritativeSource is EntityEventSourceV1 })
        assertTrue(result.hits.any { it.authoritativeSource is CanonFactSourceV1 })
        assertTrue(result.hits.any { it.authoritativeSource is TimelineEventSourceV1 })
        assertTrue(result.hits.any { it.authoritativeSource is ForeshadowSourceV1 })

        val story = result.hits.first {
            it.authoritativeSource.sourceType == MemorySearchSourceTypeV1.STORY_ENTITY
        }.authoritativeSource as StoryEntitySourceV1
        assertEquals(PRIVATE_STORY_TEXT, story.story.canonicalName)

        val rendered = result.toString() + result.hits.joinToString()
        PRIVATE_VALUES.forEach { value -> assertFalse(rendered.contains(value)) }
        assertTrue(rendered.contains("redacted"))
    }

    @Test
    fun expiredSourcesAreRejectedWithoutAbortingTheHydrationBatch() = runBlocking {
        val fixture = seedAllSources()
        database.openHelper.writableDatabase.execSQL(
            "UPDATE story_entity SET archived_at = 50 WHERE entity_id = '$STORY_ID'",
        )
        database.memoryDao().createBibleRevision(bible(BIBLE_V2, revision = 2, parent = BIBLE_V1))
        commitChapter(VERSION_V2, expected = VERSION_V1, content = "replacement")
        database.openHelper.writableDatabase.execSQL(
            "UPDATE foreshadow_item SET foreshadow_status = 'RESOLVED' " +
                "WHERE foreshadow_item_id = '$GLOBAL_FORESHADOW_ID'",
        )

        val result = repository.hydrate(
            bookId = BOOK_ID,
            targetChapterIndex = TARGET_CHAPTER_INDEX,
            recallResult = recallResult(fixture.documents.values.toList()),
        )

        assertTrue(result.hits.isEmpty())
        assertEquals(8, result.inputPointerCount)
        assertEquals(8, result.rejectedPointerCount)
        assertTrue(result.indexRebuildRequired)
    }

    @Test
    fun mismatchedDerivedPointerIsRejectedWhileOtherHitsRemainInOrder() = runBlocking {
        val fixture = seedAllSources()
        val requested = listOf(
            fixture.document(STORY_ID),
            fixture.document(CHAPTER_FACT_ID).copy(sourceContentHash = "b".repeat(64)),
            fixture.document(TIMELINE_ID),
        )

        val result = repository.hydrate(
            bookId = BOOK_ID,
            targetChapterIndex = TARGET_CHAPTER_INDEX,
            recallResult = recallResult(requested),
        )

        assertEquals(listOf(STORY_ID, TIMELINE_ID), result.hits.map { it.recallHit.document.sourceId })
        assertEquals(1, result.rejectedPointerCount)
        assertTrue(result.indexRebuildRequired)
    }

    @Test
    fun emptyRecallResultIsAValidNoMemoryOutcome() = runBlocking {
        val result = repository.hydrate(
            bookId = BOOK_ID,
            targetChapterIndex = TARGET_CHAPTER_INDEX,
            recallResult = recallResult(emptyList()),
        )

        assertTrue(result.hits.isEmpty())
        assertEquals(0, result.inputPointerCount)
        assertEquals(0, result.rejectedPointerCount)
        assertFalse(result.indexRebuildRequired)
    }

    @Test
    fun structurallyInvalidPointersFailClosedWithoutEchoingPrivateInput() = runBlocking {
        val fixture = seedAllSources()
        val pointer = fixture.document(STORY_ID)

        val duplicateError = expectFailure {
            repository.hydrate(
                bookId = BOOK_ID,
                targetChapterIndex = TARGET_CHAPTER_INDEX,
                recallResult = recallResult(listOf(pointer, pointer)),
            )
        }
        assertEquals("Hydration pointers contain duplicate document identities.", duplicateError.message)

        val crossBookError = expectFailure {
            repository.hydrate(
                bookId = BOOK_ID,
                targetChapterIndex = TARGET_CHAPTER_INDEX,
                recallResult = recallResult(
                    listOf(pointer.copy(bookId = PRIVATE_CROSS_BOOK_TEXT)),
                ),
            )
        }
        assertEquals("Hydration pointer belongs to another book.", crossBookError.message)
        assertFalse((duplicateError.toString() + crossBookError).contains(PRIVATE_CROSS_BOOK_TEXT))
        PRIVATE_VALUES.forEach { value ->
            assertFalse((duplicateError.toString() + crossBookError).contains(value))
        }
    }

    private suspend fun seedAllSources(): HydrationFixture {
        createChapter()
        commitChapter(VERSION_V1, expected = null, content = "first version")

        val story = StoryEntity(
            entityId = STORY_ID,
            bookId = BOOK_ID,
            entityType = StoryEntityType.CHARACTER,
            canonicalName = PRIVATE_STORY_TEXT,
            aliasesJson = """["PRIVATE_STORY_ALIAS"]""",
            stableDefinitionJson = """{"role":"PRIVATE_STORY_ROLE"}""",
            adultStatus = AdultStatus.CONFIRMED_ADULT,
            ageYears = 25,
            sourceBibleRevisionId = BIBLE_V1,
            createdAt = 10,
            updatedAt = 10,
        )
        val bibleFact = CanonFactEntity(
            canonFactId = BIBLE_FACT_ID,
            bookId = BOOK_ID,
            entityId = STORY_ID,
            factText = PRIVATE_BIBLE_FACT_TEXT,
            factPayloadJson = "{}",
            canonLevel = CanonLevel.HARD_CANON,
            scopeJson = "{}",
            sourceChapterVersionId = null,
            sourceBibleRevisionId = BIBLE_V1,
            validFromStoryOrder = null,
            validToStoryOrder = null,
            conflictGroupId = null,
            status = DerivedDataStatus.VALID,
            createdAt = 11,
        )
        val summary = ChapterSummaryEntity(
            chapterSummaryId = SUMMARY_ID,
            bookId = BOOK_ID,
            chapterVersionId = VERSION_V1,
            chapterIndex = 1,
            schemaVersion = 1,
            summaryJson = """{"summary":"$PRIVATE_SUMMARY_TEXT"}""",
            importance = 60,
            status = DerivedDataStatus.VALID,
            modelSnapshotJson = null,
            createdAt = 12,
            updatedAt = 12,
        )
        val event = EntityEventEntity(
            entityEventId = EVENT_ID,
            bookId = BOOK_ID,
            entityId = STORY_ID,
            sourceChapterVersionId = VERSION_V1,
            storyOrder = 1,
            attributeKey = PRIVATE_EVENT_TEXT,
            oldValueJson = null,
            newValueJson = """{"state":"awake"}""",
            storyTimeExpression = "day one",
            confidenceMicros = 900_000,
            canonLevel = CanonLevel.STORY_CANON,
            evidenceJson = "{}",
            status = DerivedDataStatus.VALID,
            createdAt = 13,
        )
        val chapterFact = CanonFactEntity(
            canonFactId = CHAPTER_FACT_ID,
            bookId = BOOK_ID,
            entityId = STORY_ID,
            factText = PRIVATE_CHAPTER_FACT_TEXT,
            factPayloadJson = "{}",
            canonLevel = CanonLevel.STORY_CANON,
            scopeJson = "{}",
            sourceChapterVersionId = VERSION_V1,
            sourceBibleRevisionId = null,
            validFromStoryOrder = 1,
            validToStoryOrder = null,
            conflictGroupId = null,
            status = DerivedDataStatus.VALID,
            createdAt = 14,
        )
        val timeline = TimelineEventEntity(
            timelineEventId = TIMELINE_ID,
            bookId = BOOK_ID,
            name = PRIVATE_TIMELINE_TEXT,
            participantsJson = "[]",
            locationEntityId = null,
            storyTimeExpression = "day one",
            storyOrder = 1,
            constraintsJson = "{}",
            sourceChapterVersionId = VERSION_V1,
            status = DerivedDataStatus.VALID,
            createdAt = 15,
        )
        val sourceForeshadow = foreshadow(
            id = SOURCE_FORESHADOW_ID,
            description = PRIVATE_SOURCE_FORESHADOW_TEXT,
            sourceVersionId = VERSION_V1,
        )
        val globalForeshadow = foreshadow(
            id = GLOBAL_FORESHADOW_ID,
            description = PRIVATE_GLOBAL_FORESHADOW_TEXT,
            sourceVersionId = null,
        )

        database.withTransaction {
            val memory = database.memoryDao()
            val search = database.memorySearchDao()
            memory.createBibleRevision(bible(BIBLE_V1, revision = 1, parent = null))
            memory.insertStoryEntity(story)
            memory.insertCanonFacts(listOf(bibleFact))
            MemorySearchIndexWriterV1.replaceStoryBible(search, listOf(story), listOf(bibleFact))

            memory.insertSummary(summary)
            memory.insertEntityEvents(listOf(event))
            memory.insertCanonFacts(listOf(chapterFact))
            MemorySearchIndexWriterV1.replaceChapterMemory(
                search = search,
                summary = summary,
                entityEvents = listOf(event),
                canonFacts = listOf(chapterFact),
            )

            memory.insertTimelineEvents(listOf(timeline))
            memory.insertForeshadows(listOf(sourceForeshadow, globalForeshadow))
            MemorySearchIndexWriterV1.replaceStoryTracking(
                search = search,
                chapterIndex = 1,
                timelineEvents = listOf(timeline),
                foreshadows = listOf(sourceForeshadow, globalForeshadow),
            )
        }

        val documents = database.memorySearchDao().documentsForBook(BOOK_ID)
            .associateBy(MemorySearchDocumentEntity::sourceId)
        assertEquals(8, documents.size)
        return HydrationFixture(documents)
    }

    private fun foreshadow(
        id: String,
        description: String,
        sourceVersionId: String?,
    ) = ForeshadowItemEntity(
        foreshadowItemId = id,
        bookId = BOOK_ID,
        description = description,
        foreshadowStatus = ForeshadowStatus.PLANTED,
        memoryStatus = DerivedDataStatus.VALID,
        targetStartChapterIndex = 2,
        targetEndChapterIndex = 4,
        sourceChapterVersionId = sourceVersionId,
        plantedChapterVersionId = sourceVersionId,
        resolvedChapterVersionId = null,
        visibleEntityIdsJson = "[]",
        importance = 80,
        source = if (sourceVersionId == null) MemorySource.USER else MemorySource.CHAPTER_EXTRACTION,
        createdAt = 16,
        updatedAt = 16,
    )

    private fun recallResult(documents: List<MemorySearchDocumentEntity>): MemorySearchRecallResultV1 {
        val probeCount = if (documents.isEmpty()) 0 else 1
        return MemorySearchRecallResultV1(
            hits = documents.map { document ->
                MemorySearchRecallHitV1(
                    document = document,
                    targetChapterProbeHits = 1,
                    userAdditionProbeHits = 0,
                    targetArcProbeHits = 0,
                )
            },
            queryFingerprint = "a".repeat(64),
            compiledProbeCount = probeCount,
            omittedCompiledProbeCount = 0,
            executedProbeCount = probeCount,
            executedTargetChapterProbeCount = probeCount,
            executedUserAdditionProbeCount = 0,
            executedTargetArcProbeCount = 0,
            omittedExecutionProbeCount = 0,
            omittedRankedDocumentCount = 0,
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

    private suspend fun createChapter() {
        database.libraryDao().createChapter(
            ChapterEntity(
                chapterId = CHAPTER_ID,
                bookId = BOOK_ID,
                chapterIndex = 1,
                plannedTitle = "Chapter 1",
                displayTitle = "Chapter 1",
                status = ChapterStatus.PLANNED,
                consistencyStatus = ConsistencyStatus.UNKNOWN,
                createdAt = 2,
                updatedAt = 2,
            ),
        )
    }

    private suspend fun commitChapter(
        versionId: String,
        expected: String?,
        content: String,
    ) {
        database.libraryDao().commitChapterVersion(
            CommitChapterVersionCommand(
                chapterVersionId = versionId,
                chapterId = CHAPTER_ID,
                expectedCurrentVersionId = expected,
                content = content,
                contentHash = "hash-$versionId",
                source = ChapterVersionSource.USER_EDIT,
                generationStageId = null,
                modelSnapshotJson = null,
                createdAt = if (expected == null) 3 else 50,
            ),
        )
    }

    private fun bible(
        id: String,
        revision: Int,
        parent: String?,
    ) = StoryBibleRevisionEntity(
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

    private suspend fun expectFailure(block: suspend () -> Unit): Throwable = try {
        block()
        throw AssertionError("Expected operation to fail.")
    } catch (error: Throwable) {
        if (error is AssertionError && error.message == "Expected operation to fail.") throw error
        error
    }

    private data class HydrationFixture(
        val documents: Map<String, MemorySearchDocumentEntity>,
    ) {
        fun document(sourceId: String): MemorySearchDocumentEntity =
            requireNotNull(documents[sourceId])
    }

    private companion object {
        const val BOOK_ID = "hydration-book-1"
        const val CHAPTER_ID = "hydration-chapter-1"
        const val VERSION_V1 = "hydration-version-1"
        const val VERSION_V2 = "hydration-version-2"
        const val BIBLE_V1 = "hydration-bible-1"
        const val BIBLE_V2 = "hydration-bible-2"
        const val STORY_ID = "hydration-story"
        const val BIBLE_FACT_ID = "hydration-bible-fact"
        const val SUMMARY_ID = "hydration-summary"
        const val EVENT_ID = "hydration-event"
        const val CHAPTER_FACT_ID = "hydration-chapter-fact"
        const val TIMELINE_ID = "hydration-timeline"
        const val SOURCE_FORESHADOW_ID = "hydration-source-foreshadow"
        const val GLOBAL_FORESHADOW_ID = "hydration-global-foreshadow"
        const val TARGET_CHAPTER_INDEX = 2
        const val PRIVATE_CROSS_BOOK_TEXT = "PRIVATE_CROSS_BOOK_CANARY"
        const val PRIVATE_STORY_TEXT = "PRIVATE_STORY_CANARY"
        const val PRIVATE_BIBLE_FACT_TEXT = "PRIVATE_BIBLE_FACT_CANARY"
        const val PRIVATE_SUMMARY_TEXT = "PRIVATE_SUMMARY_CANARY"
        const val PRIVATE_EVENT_TEXT = "PRIVATE_EVENT_CANARY"
        const val PRIVATE_CHAPTER_FACT_TEXT = "PRIVATE_CHAPTER_FACT_CANARY"
        const val PRIVATE_TIMELINE_TEXT = "PRIVATE_TIMELINE_CANARY"
        const val PRIVATE_SOURCE_FORESHADOW_TEXT = "PRIVATE_SOURCE_FORESHADOW_CANARY"
        const val PRIVATE_GLOBAL_FORESHADOW_TEXT = "PRIVATE_GLOBAL_FORESHADOW_CANARY"
        val PRIVATE_VALUES = listOf(
            PRIVATE_STORY_TEXT,
            PRIVATE_BIBLE_FACT_TEXT,
            PRIVATE_SUMMARY_TEXT,
            PRIVATE_EVENT_TEXT,
            PRIVATE_CHAPTER_FACT_TEXT,
            PRIVATE_TIMELINE_TEXT,
            PRIVATE_SOURCE_FORESHADOW_TEXT,
            PRIVATE_GLOBAL_FORESHADOW_TEXT,
        )
    }
}

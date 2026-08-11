package app.zhijuan.core.database

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.zhijuan.core.database.library.BookCreationSnapshotEntity
import app.zhijuan.core.database.library.BookEntity
import app.zhijuan.core.database.library.ChapterEntity
import app.zhijuan.core.database.library.ChapterUserEditCommand
import app.zhijuan.core.database.library.ChapterUserEditRepository
import app.zhijuan.core.database.library.CommitChapterVersionCommand
import app.zhijuan.core.database.memory.CanonFactEntity
import app.zhijuan.core.database.memory.ChapterSummaryEntity
import app.zhijuan.core.database.memory.EntityEventEntity
import app.zhijuan.core.database.memory.ForeshadowItemEntity
import app.zhijuan.core.database.memory.StoryBibleRevisionEntity
import app.zhijuan.core.database.memory.StoryEntity
import app.zhijuan.core.database.memory.TimelineEventEntity
import app.zhijuan.core.database.search.MemoryContextRouteSelectionRepositoryV1
import app.zhijuan.core.database.search.MemoryContextRouteV1
import app.zhijuan.core.database.search.MemoryContextSelectionStatusV1
import app.zhijuan.core.database.search.MemorySearchIndexWriterV1
import app.zhijuan.core.database.search.ChapterSummarySourceV1
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
class MemoryContextRouteSelectionDatabaseTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: ZhijuanDatabase
    private lateinit var repository: MemoryContextRouteSelectionRepositoryV1

    @Before
    fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(context, ZhijuanDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(LibraryDatabaseGuards.callback)
            .build()
            .also { it.openHelper.writableDatabase }
        createBook()
        repository = MemoryContextRouteSelectionRepositoryV1(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun mergesMandatoryRecentAndFtsRoutesWithoutMovingCoreItems() = runBlocking {
        createChapter(CHAPTER_1_ID, 1)
        commitChapter(CHAPTER_1_ID, VERSION_1_ID, expected = null)
        val hero = story(STORY_ID, PRIVATE_STORY_TEXT)
        val hardFact = fact(
            id = HARD_FACT_ID,
            text = PRIVATE_HARD_FACT_TEXT,
            canonLevel = CanonLevel.HARD_CANON,
            bibleVersionId = BIBLE_ID,
        )
        val storyFact = fact(
            id = STORY_FACT_ID,
            text = PRIVATE_STORY_FACT_TEXT,
            canonLevel = CanonLevel.STORY_CANON,
            chapterVersionId = VERSION_1_ID,
        )
        val summary = summary(PRIVATE_SUMMARY_TEXT)
        val due = foreshadow(
            id = DUE_FORESHADOW_ID,
            description = PRIVATE_DUE_TEXT,
            sourceVersionId = VERSION_1_ID,
            targetStart = 2,
        )

        database.withTransaction {
            val memory = database.memoryDao()
            val search = database.memorySearchDao()
            memory.createBibleRevision(bible())
            memory.insertStoryEntity(hero)
            memory.insertCanonFacts(listOf(hardFact))
            MemorySearchIndexWriterV1.replaceStoryBible(search, listOf(hero), listOf(hardFact))
            memory.insertSummary(summary)
            memory.insertCanonFacts(listOf(storyFact))
            MemorySearchIndexWriterV1.replaceChapterMemory(
                search = search,
                summary = summary,
                entityEvents = emptyList(),
                canonFacts = listOf(storyFact),
            )
            memory.insertForeshadows(listOf(due))
            MemorySearchIndexWriterV1.replaceStoryTracking(
                search = search,
                chapterIndex = 1,
                timelineEvents = emptyList(),
                foreshadows = listOf(due),
            )
        }

        val result = select(
            targetChapterTitle = "alpha",
            targetArcTitle = "arc",
            userAddition = "hero",
        )

        assertEquals(MemoryContextSelectionStatusV1.OK, result.status)
        assertEquals(
            listOf(HARD_FACT_ID, DUE_FORESHADOW_ID, SUMMARY_ID, STORY_ID),
            result.items.map { it.source.sourceId },
        )
        assertEquals(
            setOf(MemoryContextRouteV1.MANDATORY_HARD_FACT, MemoryContextRouteV1.FTS),
            result.items[0].routes,
        )
        assertEquals(
            setOf(MemoryContextRouteV1.MANDATORY_DUE_FORESHADOW, MemoryContextRouteV1.FTS),
            result.items[1].routes,
        )
        assertEquals(
            setOf(MemoryContextRouteV1.RECENT_SUMMARY, MemoryContextRouteV1.FTS),
            result.items[2].routes,
        )
        assertEquals(setOf(MemoryContextRouteV1.FTS), result.items[3].routes)
        assertTrue(requireNotNull(result.items[3].ftsEvidence).userAdditionProbeHits > 0)
        assertEquals(4, result.evidence.hydratedFtsHitCount)
        assertEquals(3, result.evidence.mergedFtsHitCount)
        assertEquals(1, result.evidence.retainedNewFtsHitCount)
        assertEquals(0, result.evidence.boundedOmittedFtsHitCount)
        assertFalse(result.indexRebuildRequired)

        val ordinary = select(targetChapterTitle = "ordinary")
        val selectedStoryFact = ordinary.items.single { it.source.sourceId == STORY_FACT_ID }
        assertEquals(setOf(MemoryContextRouteV1.FTS), selectedStoryFact.routes)

        val rendered = result.toString() + result.items.joinToString()
        PRIVATE_VALUES.forEach { value -> assertFalse(rendered.contains(value)) }
        listOf(HARD_FACT_ID, DUE_FORESHADOW_ID, SUMMARY_ID, STORY_ID).forEach { id ->
            assertFalse(rendered.contains(id))
        }
        assertTrue(rendered.contains("redacted"))
    }

    @Test
    fun dueRouteExcludesNotDueResolvedFutureAndReplacedChapterSources() = runBlocking {
        database.memoryDao().createBibleRevision(bible())
        createChapter(CHAPTER_1_ID, 1)
        createChapter(CHAPTER_2_ID, 2)
        commitChapter(CHAPTER_1_ID, VERSION_1_OLD_ID, expected = null)
        commitChapter(CHAPTER_1_ID, VERSION_1_ID, expected = VERSION_1_OLD_ID)
        commitChapter(CHAPTER_2_ID, VERSION_2_ID, expected = null)
        database.memoryDao().insertForeshadows(
            listOf(
                foreshadow("due-global", "global due", null, 2),
                foreshadow("due-old", "old due", VERSION_1_OLD_ID, 2),
                foreshadow("due-future", "future due", VERSION_2_ID, 2),
                foreshadow("not-due", "not due", null, 3),
                foreshadow("resolved", "resolved due", null, 2).copy(
                    foreshadowStatus = ForeshadowStatus.RESOLVED,
                ),
            ),
        )

        val result = select(targetChapterTitle = "，。")

        assertEquals(listOf("due-global"), result.items.map { it.source.sourceId })
        assertEquals(1, result.evidence.mandatoryDueForeshadowCount)
        assertEquals(0, result.evidence.compiledProbeCount)
    }

    @Test
    fun userEditedChapterContextSelectsOnlyTheReplacementSummary() = runBlocking {
        createChapter(CHAPTER_1_ID, 1)
        commitChapter(CHAPTER_1_ID, VERSION_1_ID, expected = null)
        val oldSummary = ChapterSummaryEntity(
            chapterSummaryId = OLD_EDIT_SUMMARY_ID,
            bookId = BOOK_ID,
            chapterVersionId = VERSION_1_ID,
            chapterIndex = 1,
            schemaVersion = 1,
            summaryJson = """{"summary":"old context canary"}""",
            importance = 80,
            status = DerivedDataStatus.VALID,
            modelSnapshotJson = null,
            createdAt = 4L,
            updatedAt = 4L,
        )
        database.withTransaction {
            database.memoryDao().createBibleRevision(bible())
            database.memoryDao().insertSummary(oldSummary)
            MemorySearchIndexWriterV1.replaceChapterMemory(
                search = database.memorySearchDao(),
                summary = oldSummary,
                entityEvents = emptyList(),
                canonFacts = emptyList(),
            )
        }

        ChapterUserEditRepository(database).commit(
            ChapterUserEditCommand(
                bookId = BOOK_ID,
                chapterId = CHAPTER_1_ID,
                expectedCurrentVersionId = VERSION_1_ID,
                newVersionId = EDITED_VERSION_ID,
                content = EDITED_CONTENT,
                editedAt = 10L,
            ),
        )
        val replacementSummary = ChapterSummaryEntity(
            chapterSummaryId = REPLACEMENT_EDIT_SUMMARY_ID,
            bookId = BOOK_ID,
            chapterVersionId = EDITED_VERSION_ID,
            chapterIndex = 1,
            schemaVersion = 1,
            summaryJson = """{"summary":"replacement context canary"}""",
            importance = 90,
            status = DerivedDataStatus.VALID,
            modelSnapshotJson = null,
            createdAt = 11L,
            updatedAt = 11L,
        )
        database.withTransaction {
            database.memoryDao().insertSummary(replacementSummary)
            MemorySearchIndexWriterV1.replaceChapterMemory(
                search = database.memorySearchDao(),
                summary = replacementSummary,
                entityEvents = emptyList(),
                canonFacts = emptyList(),
            )
        }

        val result = select(targetChapterTitle = "replacement context")
        val selectedSummaries = result.items.mapNotNull { item ->
            (item.source as? ChapterSummarySourceV1)?.summary
        }

        assertEquals(MemoryContextSelectionStatusV1.OK, result.status)
        assertEquals(listOf(REPLACEMENT_EDIT_SUMMARY_ID), selectedSummaries.map { it.chapterSummaryId })
        assertEquals(listOf(EDITED_VERSION_ID), selectedSummaries.map { it.chapterVersionId })
        assertFalse(result.items.any { it.source.sourceId == OLD_EDIT_SUMMARY_ID })
        assertEquals(
            "STALE",
            scalarString("SELECT status FROM chapter_summary WHERE chapter_summary_id = '$OLD_EDIT_SUMMARY_ID'"),
        )
        assertEquals(
            0L,
            scalarLong(
                "SELECT COUNT(*) FROM memory_search_document " +
                    "WHERE source_type = 'CHAPTER_SUMMARY' AND source_id = '$OLD_EDIT_SUMMARY_ID'",
            ),
        )
    }

    @Test
    fun mandatoryOverflowReturnsNoPartialSelectionAndSkipsFts() = runBlocking {
        val hardFacts = (1..4).map { index ->
            fact(
                id = "overflow-fact-$index",
                text = "PRIVATE_OVERFLOW_FACT_$index",
                canonLevel = CanonLevel.HARD_CANON,
                bibleVersionId = BIBLE_ID,
            )
        }
        database.withTransaction {
            database.memoryDao().createBibleRevision(bible())
            database.memoryDao().insertCanonFacts(hardFacts)
        }

        val result = select(targetChapterTitle = "overflow", hardLimit = 3)

        assertEquals(MemoryContextSelectionStatusV1.MANDATORY_OVERFLOW, result.status)
        assertTrue(result.items.isEmpty())
        assertEquals(4, result.evidence.mandatoryHardFactCount)
        assertEquals(1, result.evidence.overflowCoreCount)
        assertEquals(0, result.evidence.executedProbeCount)
        assertEquals(0, result.evidence.hydratedFtsHitCount)
        assertNull(result.queryFingerprint)
        assertFalse(result.indexRebuildRequired)
        hardFacts.forEach { fact ->
            assertFalse(result.toString().contains(fact.factText))
            assertFalse(result.toString().contains(fact.canonFactId))
        }
    }

    @Test
    fun optionalFtsHitsAreBoundedOnlyAfterCoreRoutes() = runBlocking {
        val stories = (1..4).map { index ->
            story("bounded-story-$index", "common route $index")
        }
        database.withTransaction {
            val memory = database.memoryDao()
            memory.createBibleRevision(bible())
            stories.forEach { memory.insertStoryEntity(it) }
            MemorySearchIndexWriterV1.replaceStoryBible(
                database.memorySearchDao(),
                stories,
                emptyList(),
            )
        }

        val result = select(targetChapterTitle = "common", hardLimit = 2)

        assertEquals(MemoryContextSelectionStatusV1.OK, result.status)
        assertEquals(2, result.items.size)
        assertTrue(result.items.all { it.routes == setOf(MemoryContextRouteV1.FTS) })
        assertEquals(4, result.evidence.hydratedFtsHitCount)
        assertEquals(2, result.evidence.retainedNewFtsHitCount)
        assertEquals(2, result.evidence.boundedOmittedFtsHitCount)
        assertEquals(0, result.evidence.overflowCoreCount)
    }

    @Test
    fun staleFtsPointerIsRejectedAndRebuildEvidenceIsPropagated() = runBlocking {
        val story = story("stale-story", "stale searchable canary")
        database.withTransaction {
            database.memoryDao().createBibleRevision(bible())
            database.memoryDao().insertStoryEntity(story)
            MemorySearchIndexWriterV1.replaceStoryBible(
                database.memorySearchDao(),
                listOf(story),
                emptyList(),
            )
        }
        database.openHelper.writableDatabase.execSQL(
            "UPDATE story_entity SET archived_at = 30 WHERE entity_id = 'stale-story'",
        )

        val result = select(targetChapterTitle = "searchable")

        assertEquals(MemoryContextSelectionStatusV1.OK, result.status)
        assertTrue(result.items.isEmpty())
        assertEquals(1, result.evidence.rejectedPointerCount)
        assertTrue(result.indexRebuildRequired)
        assertFalse(result.toString().contains("stale searchable canary"))
        assertFalse(result.toString().contains("stale-story"))
    }

    @Test
    fun fixedChineseRecallSetCoversAllSixAuthoritativeMemoryTypes() = runBlocking {
        createChapter(CHAPTER_1_ID, 1)
        commitChapter(CHAPTER_1_ID, VERSION_1_ID, expected = null)
        val hero = story(CHINESE_STORY_ID, "顾南舟").copy(
            aliasesJson = "[\"南舟\",\"阿舟\"]",
            stableDefinitionJson = "{\"weapon\":\"玄铁剑\",\"home\":\"长安旧城\"}",
        )
        val summary = summary("沈知意记得白鹭客栈的暗号是春潮。")
        val event = EntityEventEntity(
            entityEventId = CHINESE_EVENT_ID,
            bookId = BOOK_ID,
            entityId = CHINESE_STORY_ID,
            sourceChapterVersionId = VERSION_1_ID,
            storyOrder = 101L,
            attributeKey = "LOCATION",
            oldValueJson = null,
            newValueJson = "{\"place\":\"银杏巷\"}",
            storyTimeExpression = "初雪之后",
            confidenceMicros = 950_000,
            canonLevel = CanonLevel.STORY_CANON,
            evidenceJson = "{\"object\":\"乌金钥\"}",
            status = DerivedDataStatus.VALID,
            createdAt = 6L,
        )
        val storyFact = fact(
            id = CHINESE_FACT_ID,
            text = "松烟墨遇水后显出第二层字迹。",
            canonLevel = CanonLevel.STORY_CANON,
            chapterVersionId = VERSION_1_ID,
        )
        val timeline = TimelineEventEntity(
            timelineEventId = CHINESE_TIMELINE_ID,
            bookId = BOOK_ID,
            name = "惊鸿宴座次异动",
            participantsJson = "[\"$CHINESE_STORY_ID\"]",
            locationEntityId = null,
            storyTimeExpression = "冬至前夜",
            storyOrder = 102L,
            constraintsJson = "{\"record\":\"枕星阁观测册\"}",
            sourceChapterVersionId = VERSION_1_ID,
            status = DerivedDataStatus.VALID,
            createdAt = 7L,
        )
        val openForeshadow = foreshadow(
            id = CHINESE_FORESHADOW_ID,
            description = "霜叶帖末尾的印章来自失传门派。",
            sourceVersionId = VERSION_1_ID,
            targetStart = 8,
        )

        database.withTransaction {
            val memory = database.memoryDao()
            val search = database.memorySearchDao()
            memory.createBibleRevision(bible())
            memory.insertStoryEntity(hero)
            MemorySearchIndexWriterV1.replaceStoryBible(search, listOf(hero), emptyList())
            memory.insertSummary(summary)
            memory.insertEntityEvents(listOf(event))
            memory.insertCanonFacts(listOf(storyFact))
            MemorySearchIndexWriterV1.replaceChapterMemory(
                search = search,
                summary = summary,
                entityEvents = listOf(event),
                canonFacts = listOf(storyFact),
            )
            memory.insertTimelineEvents(listOf(timeline))
            memory.insertForeshadows(listOf(openForeshadow))
            MemorySearchIndexWriterV1.replaceStoryTracking(
                search = search,
                chapterIndex = 1,
                timelineEvents = listOf(timeline),
                foreshadows = listOf(openForeshadow),
            )
        }

        val fixedCases = linkedMapOf(
            "南舟" to CHINESE_STORY_ID,
            "白鹭客栈" to SUMMARY_ID,
            "银杏巷" to CHINESE_EVENT_ID,
            "松烟墨" to CHINESE_FACT_ID,
            "惊鸿宴" to CHINESE_TIMELINE_ID,
            "霜叶帖" to CHINESE_FORESHADOW_ID,
        )
        fixedCases.forEach { (query, expectedSourceId) ->
            val result = select(targetChapterTitle = query)
            val hit = result.items.single { it.source.sourceId == expectedSourceId }
            assertTrue(MemoryContextRouteV1.FTS in hit.routes)
            assertTrue(requireNotNull(hit.ftsEvidence).targetChapterProbeHits >= 1)
            assertFalse(result.indexRebuildRequired)
        }

        val unrelated = select(targetChapterTitle = "不存在的琉璃鲸")
        assertTrue(unrelated.items.none { MemoryContextRouteV1.FTS in it.routes })
        val first = select(targetChapterTitle = "松烟墨")
        val replay = select(targetChapterTitle = "松烟墨")
        assertEquals(first.queryFingerprint, replay.queryFingerprint)
        assertEquals(
            first.items.map { Triple(it.source.sourceType, it.source.sourceId, it.routes) },
            replay.items.map { Triple(it.source.sourceType, it.source.sourceId, it.routes) },
        )
        assertEquals(first.evidence, replay.evidence)
    }

    private suspend fun select(
        targetChapterTitle: String,
        targetArcTitle: String = "",
        userAddition: String? = null,
        hardLimit: Int = 512,
    ) = repository.select(
        bookId = BOOK_ID,
        targetChapterIndex = 2,
        targetChapterTitle = targetChapterTitle,
        targetChapterPlanJson = "{}",
        targetArcTitle = targetArcTitle,
        targetArcPlanJson = "{}",
        userAddition = userAddition,
        hardLimit = hardLimit,
    )

    private fun bible(): StoryBibleRevisionEntity {
        val payload = "{}"
        return StoryBibleRevisionEntity(
            bibleRevisionId = BIBLE_ID,
            bookId = BOOK_ID,
            revisionNo = 1,
            parentRevisionId = null,
            source = RevisionSource.USER,
            schemaVersion = 1,
            contentControlSchemaVersion = 1,
            payloadJson = payload,
            contentHash = sha256(payload),
            generationStageId = null,
            createdAt = 1,
        )
    }

    private fun story(id: String, name: String) = StoryEntity(
        entityId = id,
        bookId = BOOK_ID,
        entityType = StoryEntityType.CHARACTER,
        canonicalName = name,
        aliasesJson = "[]",
        stableDefinitionJson = "{}",
        adultStatus = AdultStatus.CONFIRMED_ADULT,
        ageYears = 25,
        sourceBibleRevisionId = BIBLE_ID,
        createdAt = 2,
        updatedAt = 2,
    )

    private fun fact(
        id: String,
        text: String,
        canonLevel: CanonLevel,
        bibleVersionId: String? = null,
        chapterVersionId: String? = null,
    ) = CanonFactEntity(
        canonFactId = id,
        bookId = BOOK_ID,
        entityId = null,
        factText = text,
        factPayloadJson = "{}",
        canonLevel = canonLevel,
        scopeJson = "{}",
        sourceChapterVersionId = chapterVersionId,
        sourceBibleRevisionId = bibleVersionId,
        validFromStoryOrder = null,
        validToStoryOrder = null,
        conflictGroupId = null,
        status = DerivedDataStatus.VALID,
        createdAt = 3,
    )

    private fun summary(text: String) = ChapterSummaryEntity(
        chapterSummaryId = SUMMARY_ID,
        bookId = BOOK_ID,
        chapterVersionId = VERSION_1_ID,
        chapterIndex = 1,
        schemaVersion = 1,
        summaryJson = """{"summary":"$text"}""",
        importance = 90,
        status = DerivedDataStatus.VALID,
        modelSnapshotJson = null,
        createdAt = 4,
        updatedAt = 4,
    )

    private fun foreshadow(
        id: String,
        description: String,
        sourceVersionId: String?,
        targetStart: Int,
    ) = ForeshadowItemEntity(
        foreshadowItemId = id,
        bookId = BOOK_ID,
        description = description,
        foreshadowStatus = ForeshadowStatus.PLANTED,
        memoryStatus = DerivedDataStatus.VALID,
        targetStartChapterIndex = targetStart,
        targetEndChapterIndex = targetStart + 1,
        sourceChapterVersionId = sourceVersionId,
        plantedChapterVersionId = sourceVersionId,
        resolvedChapterVersionId = null,
        visibleEntityIdsJson = "[]",
        importance = 10,
        source = if (sourceVersionId == null) MemorySource.USER else MemorySource.CHAPTER_EXTRACTION,
        createdAt = 5,
        updatedAt = 5,
    )

    private suspend fun createBook() {
        val snapshotId = "snapshot-$BOOK_ID"
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
                bookId = BOOK_ID,
                creationSnapshotId = snapshotId,
                title = "Route selection book",
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

    private suspend fun createChapter(chapterId: String, chapterIndex: Int) {
        database.libraryDao().createChapter(
            ChapterEntity(
                chapterId = chapterId,
                bookId = BOOK_ID,
                chapterIndex = chapterIndex,
                plannedTitle = "Chapter $chapterIndex",
                displayTitle = "Chapter $chapterIndex",
                status = ChapterStatus.PLANNED,
                consistencyStatus = ConsistencyStatus.UNKNOWN,
                createdAt = 2,
                updatedAt = 2,
            ),
        )
    }

    private suspend fun commitChapter(
        chapterId: String,
        versionId: String,
        expected: String?,
    ) {
        database.libraryDao().commitChapterVersion(
            CommitChapterVersionCommand(
                chapterVersionId = versionId,
                chapterId = chapterId,
                expectedCurrentVersionId = expected,
                content = "content-$versionId",
                contentHash = "hash-$versionId",
                source = ChapterVersionSource.USER_EDIT,
                generationStageId = null,
                modelSnapshotJson = null,
                createdAt = if (expected == null) 3 else 4,
            ),
        )
    }

    private fun scalarString(query: String): String? =
        database.openHelper.readableDatabase.query(query).use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
        }

    private fun scalarLong(query: String): Long? =
        database.openHelper.readableDatabase.query(query).use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
        }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private companion object {
        const val BOOK_ID = "route-selection-book"
        const val BIBLE_ID = "route-selection-bible"
        const val CHAPTER_1_ID = "route-chapter-1"
        const val CHAPTER_2_ID = "route-chapter-2"
        const val VERSION_1_OLD_ID = "route-version-1-old"
        const val VERSION_1_ID = "route-version-1"
        const val VERSION_2_ID = "route-version-2"
        const val EDITED_VERSION_ID = "route-version-1-edited"
        const val EDITED_CONTENT = "Replacement chapter content for context authority verification."
        const val OLD_EDIT_SUMMARY_ID = "route-summary-edit-old"
        const val REPLACEMENT_EDIT_SUMMARY_ID = "route-summary-edit-replacement"
        const val STORY_ID = "route-story"
        const val HARD_FACT_ID = "route-hard-fact"
        const val STORY_FACT_ID = "route-story-fact"
        const val SUMMARY_ID = "route-summary"
        const val DUE_FORESHADOW_ID = "route-due-foreshadow"
        const val CHINESE_STORY_ID = "route-chinese-story"
        const val CHINESE_EVENT_ID = "route-chinese-event"
        const val CHINESE_FACT_ID = "route-chinese-fact"
        const val CHINESE_TIMELINE_ID = "route-chinese-timeline"
        const val CHINESE_FORESHADOW_ID = "route-chinese-foreshadow"
        const val PRIVATE_STORY_TEXT = "alpha arc hero"
        const val PRIVATE_HARD_FACT_TEXT = "alpha arc hard canary"
        const val PRIVATE_STORY_FACT_TEXT = "ordinary memory canary"
        const val PRIVATE_SUMMARY_TEXT = "alpha arc summary canary"
        const val PRIVATE_DUE_TEXT = "alpha arc due canary"
        val PRIVATE_VALUES = listOf(
            PRIVATE_STORY_TEXT,
            PRIVATE_HARD_FACT_TEXT,
            PRIVATE_STORY_FACT_TEXT,
            PRIVATE_SUMMARY_TEXT,
            PRIVATE_DUE_TEXT,
        )
    }
}

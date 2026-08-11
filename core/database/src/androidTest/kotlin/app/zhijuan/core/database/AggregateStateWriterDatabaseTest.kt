package app.zhijuan.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.zhijuan.core.database.generation.GenerationJobEntity
import app.zhijuan.core.database.generation.GenerationStageEntity
import app.zhijuan.core.database.library.AggregateStateSnapshotCodecV1
import app.zhijuan.core.database.library.AggregateStateWriteCommand
import app.zhijuan.core.database.library.AggregateStateWriterRepository
import app.zhijuan.core.database.library.BookCreationSnapshotEntity
import app.zhijuan.core.database.library.BookEntity
import app.zhijuan.core.database.library.ChapterEditRebuildBlocker
import app.zhijuan.core.database.library.ChapterEditRebuildPlanRequest
import app.zhijuan.core.database.library.ChapterEditRebuildPlanRepository
import app.zhijuan.core.database.library.ChapterEditRebuildStepState
import app.zhijuan.core.database.library.ChapterEditRebuildStepType
import app.zhijuan.core.database.library.ChapterEntity
import app.zhijuan.core.database.library.ChapterUserEditCommand
import app.zhijuan.core.database.library.ChapterUserEditRepository
import app.zhijuan.core.database.library.CommitChapterVersionCommand
import app.zhijuan.core.database.memory.AggregateStateProjectionEntity
import app.zhijuan.core.database.memory.ChapterSummaryEntity
import app.zhijuan.core.database.memory.ChapterTrackingProjectionEntity
import app.zhijuan.core.database.memory.EntityEventEntity
import app.zhijuan.core.database.memory.ForeshadowItemEntity
import app.zhijuan.core.database.memory.StoryEntity
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
import app.zhijuan.core.model.StoryEntityType
import app.zhijuan.core.model.TitleSource
import java.security.MessageDigest
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
class AggregateStateWriterDatabaseTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: ZhijuanDatabase
    private lateinit var planner: ChapterEditRebuildPlanRepository
    private lateinit var writer: AggregateStateWriterRepository

    @Before
    fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(context, ZhijuanDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(LibraryDatabaseGuards.callback)
            .build()
            .also { it.openHelper.writableDatabase }
        planner = ChapterEditRebuildPlanRepository(database)
        writer = AggregateStateWriterRepository(database)
        createBook()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun canonicalWriterKeepsLatestStateExcludesFutureAndSupportsReplay() = runBlocking {
        seedCommittedChapters(3)
        editChapter(2)
        seedSatisfiedMemoryAndTracking(2)
        database.memoryDao().insertStoryEntity(character())
        database.memoryDao().insertEntityEvents(
            listOf(
                event("event-old", "chapter-1-v1", 1_000_001, "mood", """{"z":1,"a":"old"}""", 20),
                event("event-current", "chapter-2-v2", 2_000_001, "mood", """{"z":2,"a":"calm"}""", 104),
                event("event-future", "chapter-3-v1", 3_000_001, "mood", """{"z":3,"a":"future"}""", 30),
            ),
        )
        val plan = planner.plan(request(2))
        assertEquals(
            ChapterEditRebuildStepState.READY,
            plan.step(ChapterEditRebuildStepType.REBUILD_AGGREGATE_STATE, 2).state,
        )

        val first = writer.write(AggregateStateWriteCommand(plan, chapterIndex = 2, generatedAt = 120))
        val replay = writer.write(AggregateStateWriteCommand(plan, chapterIndex = 2, generatedAt = 120))

        assertFalse(first.replayed)
        assertTrue(replay.replayed)
        assertEquals(first.aggregateStateId, replay.aggregateStateId)
        assertEquals(1, scalarInt("SELECT COUNT(*) FROM aggregate_state_projection"))
        val row = requireNotNull(database.memoryDao().findAggregateState(first.aggregateStateId))
        assertEquals(sha256(row.stateJson), row.contentHash)
        assertTrue(row.stateJson.contains("event-current"))
        assertTrue(row.stateJson.contains("\"a\":\"calm\",\"z\":2"))
        assertFalse(row.stateJson.contains("event-old"))
        assertFalse(row.stateJson.contains("event-future"))
        assertFalse(row.stateJson.contains("修改后的正文"))
        val decoded = AggregateStateSnapshotCodecV1.decodeAndVerify(row.stateJson, row.contentHash)
        assertEquals(2, decoded.throughChapterIndex)
        assertEquals("chapter-2-v2", decoded.sourceChapterVersionId)
        assertEquals(1, decoded.entityStates.size)
        assertEquals(
            ChapterEditRebuildStepState.ALREADY_SATISFIED,
            planner.plan(request(2)).step(ChapterEditRebuildStepType.REBUILD_AGGREGATE_STATE, 2).state,
        )
        assertTrue(expectFailure { planner.requireCurrentMatches(plan) } is IllegalArgumentException)
        assertFalse(first.toString().contains(first.aggregateStateId))
        assertFalse(first.toString().contains(first.contentHash))
    }

    @Test
    fun futureForeshadowProjectionFailsClosedWithoutWriting() = runBlocking {
        seedCommittedChapters(3)
        editChapter(2)
        seedSatisfiedMemoryAndTracking(2)
        database.memoryDao().insertForeshadows(
            listOf(
                ForeshadowItemEntity(
                    foreshadowItemId = "future-clue",
                    bookId = BOOK_ID,
                    description = "只存在于后续章节的线索",
                    foreshadowStatus = ForeshadowStatus.PLANTED,
                    memoryStatus = DerivedDataStatus.VALID,
                    targetStartChapterIndex = 5,
                    targetEndChapterIndex = 8,
                    sourceChapterVersionId = "chapter-3-v1",
                    plantedChapterVersionId = "chapter-3-v1",
                    resolvedChapterVersionId = null,
                    visibleEntityIdsJson = "[]",
                    importance = 70,
                    source = MemorySource.CHAPTER_EXTRACTION,
                    createdAt = 30,
                    updatedAt = 30,
                ),
            ),
        )
        val plan = planner.plan(request(2))

        val error = expectFailure {
            writer.write(AggregateStateWriteCommand(plan, chapterIndex = 2, generatedAt = 120))
        }

        assertTrue(error is IllegalArgumentException)
        assertEquals(0, scalarInt("SELECT COUNT(*) FROM aggregate_state_projection"))
        assertFalse(error.message.orEmpty().contains("future-clue"))
    }

    @Test
    fun oldVersionValidHeadIsStaledBeforeCanonicalCurrentHead() = runBlocking {
        seedCommittedChapters(2)
        editChapter(2)
        seedSatisfiedMemoryAndTracking(2)
        database.memoryDao().insertAggregateState(
            AggregateStateProjectionEntity(
                aggregateStateId = "legacy-old-version-head",
                bookId = BOOK_ID,
                throughChapterIndex = 2,
                sourceThroughChapterVersionId = "chapter-2-v1",
                schemaVersion = 1,
                stateJson = "{}",
                contentHash = "legacy-hash",
                status = DerivedDataStatus.VALID,
                createdAt = 105,
                updatedAt = 105,
            ),
        )
        val plan = planner.plan(request(2))
        assertEquals(
            ChapterEditRebuildStepState.READY,
            plan.step(ChapterEditRebuildStepType.REBUILD_AGGREGATE_STATE, 2).state,
        )

        val result = writer.write(AggregateStateWriteCommand(plan, chapterIndex = 2, generatedAt = 120))

        assertFalse(result.replayed)
        val history = database.memoryDao().aggregateStateHistoryForChapter(BOOK_ID, 2)
        assertEquals(2, history.size)
        assertEquals(listOf(DerivedDataStatus.STALE, DerivedDataStatus.VALID), history.map { it.status })
        assertEquals(1, scalarInt("SELECT COUNT(*) FROM aggregate_state_projection WHERE status = 'VALID'"))
    }

    @Test
    fun malformedCurrentHeadBlocksPlanAndCannotBeSilentlyReplaced() = runBlocking {
        seedCommittedChapters(2)
        editChapter(2)
        seedSatisfiedMemoryAndTracking(2)
        database.memoryDao().insertAggregateState(
            AggregateStateProjectionEntity(
                aggregateStateId = "malformed-current-head",
                bookId = BOOK_ID,
                throughChapterIndex = 2,
                sourceThroughChapterVersionId = "chapter-2-v2",
                schemaVersion = 1,
                stateJson = "{}",
                contentHash = "not-a-canonical-hash",
                status = DerivedDataStatus.VALID,
                createdAt = 110,
                updatedAt = 110,
            ),
        )
        val plan = planner.plan(request(2))
        val aggregate = plan.step(ChapterEditRebuildStepType.REBUILD_AGGREGATE_STATE, 2)
        assertEquals(ChapterEditRebuildStepState.BLOCKED, aggregate.state)
        assertEquals(ChapterEditRebuildBlocker.DERIVED_VERSION_SLOT_OCCUPIED, aggregate.blocker)

        val error = expectFailure {
            writer.write(AggregateStateWriteCommand(plan, chapterIndex = 2, generatedAt = 120))
        }

        assertTrue(error is IllegalArgumentException)
        assertEquals(1, scalarInt("SELECT COUNT(*) FROM aggregate_state_projection"))
        assertEquals("VALID", database.memoryDao().aggregateStatus("malformed-current-head"))
    }

    @Test
    fun concurrentSameEvidenceCommitsOneGeneration() = runBlocking {
        seedCommittedChapters(2)
        editChapter(2)
        seedSatisfiedMemoryAndTracking(2)
        val plan = planner.plan(request(2))
        val command = AggregateStateWriteCommand(plan, chapterIndex = 2, generatedAt = 120)

        val results = coroutineScope {
            listOf(async { writer.write(command) }, async { writer.write(command) }).map { it.await() }
        }

        assertEquals(1, results.count { it.replayed })
        assertEquals(1, results.map { it.aggregateStateId }.distinct().size)
        assertEquals(1, scalarInt("SELECT COUNT(*) FROM aggregate_state_projection"))
    }

    @Test
    fun changedTrackingGenerationCannotReuseOldAggregateHead() = runBlocking {
        seedCommittedChapters(2)
        editChapter(2)
        seedSatisfiedMemoryAndTracking(2)
        val firstPlan = planner.plan(request(2))
        val first = writer.write(AggregateStateWriteCommand(firstPlan, chapterIndex = 2, generatedAt = 120))
        assertEquals(1, database.memoryDao().staleTrackingProjection("chapter-2-v2", 130))
        insertTrackingProjection(chapterIndex = 2, suffix = "replacement", createdAt = 131, outputHashChar = 'f')

        val changedPlan = planner.plan(request(2))
        val aggregate = changedPlan.step(ChapterEditRebuildStepType.REBUILD_AGGREGATE_STATE, 2)

        assertEquals(ChapterEditRebuildStepState.BLOCKED, aggregate.state)
        assertEquals(ChapterEditRebuildBlocker.DERIVED_VERSION_SLOT_OCCUPIED, aggregate.blocker)
        assertEquals("VALID", database.memoryDao().aggregateStatus(first.aggregateStateId))
        assertTrue(
            expectFailure {
                writer.write(AggregateStateWriteCommand(changedPlan, chapterIndex = 2, generatedAt = 140))
            } is IllegalArgumentException,
        )
        assertEquals(1, scalarInt("SELECT COUNT(*) FROM aggregate_state_projection"))
    }

    @Test
    fun generationTimeBeforeAuthorityFailsAndDiagnosticsStayRedacted() = runBlocking {
        seedCommittedChapters(2)
        editChapter(2)
        seedSatisfiedMemoryAndTracking(2)
        val plan = planner.plan(request(2))
        val command = AggregateStateWriteCommand(plan, chapterIndex = 2, generatedAt = 100)

        val error = expectFailure { writer.write(command) }

        assertTrue(error is IllegalArgumentException)
        assertEquals(0, scalarInt("SELECT COUNT(*) FROM aggregate_state_projection"))
        assertFalse(command.toString().contains(BOOK_ID))
        assertFalse(command.toString().contains(plan.planHash))
        assertFalse(error.message.orEmpty().contains(BOOK_ID))
    }

    private suspend fun seedSatisfiedMemoryAndTracking(chapterIndex: Int) {
        val versionId = "chapter-$chapterIndex-v2"
        database.memoryDao().insertSummary(
            ChapterSummaryEntity(
                chapterSummaryId = "summary-$chapterIndex-v2",
                bookId = BOOK_ID,
                chapterVersionId = versionId,
                chapterIndex = chapterIndex,
                schemaVersion = 1,
                summaryJson = "{}",
                importance = 80,
                status = DerivedDataStatus.VALID,
                modelSnapshotJson = null,
                createdAt = 101,
                updatedAt = 101,
            ),
        )
        insertTrackingProjection(chapterIndex, suffix = "initial", createdAt = 102, outputHashChar = 'd')
    }

    private suspend fun insertTrackingProjection(
        chapterIndex: Int,
        suffix: String,
        createdAt: Long,
        outputHashChar: Char,
    ) {
        val versionId = "chapter-$chapterIndex-v2"
        val contentHash = requireNotNull(database.libraryDao().findChapterVersion(versionId)).contentHash
        val jobId = "aggregate-job-$chapterIndex-$suffix"
        val stageId = "aggregate-tracking-stage-$chapterIndex-$suffix"
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
                    targetId = "chapter-$chapterIndex",
                    status = GenerationStageStatus.PENDING,
                    inputVersionHash = sha256("input-$suffix"),
                    idempotencyKey = "aggregate-tracking-$chapterIndex-$suffix",
                    maxAttempts = 2,
                    inputSourcesJson = "[]",
                    createdAt = createdAt,
                    updatedAt = createdAt,
                ),
            ),
        )
        database.memoryDao().insertTrackingProjection(
            ChapterTrackingProjectionEntity(
                projectionId = "tracking-$chapterIndex-v2-$suffix",
                bookId = BOOK_ID,
                chapterVersionId = versionId,
                chapterIndex = chapterIndex,
                generationStageId = stageId,
                sourceChapterContentHash = contentHash,
                sourceMemorySnapshotHash = "b".repeat(64),
                priorForeshadowSnapshotHash = "c".repeat(64),
                outputContentHash = outputHashChar.toString().repeat(64),
                payloadHash = "e".repeat(64),
                status = DerivedDataStatus.VALID,
                modelSnapshotJson = "{}",
                timelineEventCount = 0,
                foreshadowTransitionCount = 0,
                createdAt = createdAt + 1,
                updatedAt = createdAt + 1,
            ),
        )
    }

    private suspend fun seedCommittedChapters(count: Int) {
        for (index in 1..count) {
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
            commitVersion(index, "v1", null, "原始正文-$index", 10L + index)
        }
    }

    private suspend fun editChapter(index: Int) {
        ChapterUserEditRepository(database).commit(
            ChapterUserEditCommand(
                bookId = BOOK_ID,
                chapterId = "chapter-$index",
                expectedCurrentVersionId = "chapter-$index-v1",
                newVersionId = "chapter-$index-v2",
                content = "修改后的正文-$index",
                editedAt = 100,
            ),
        )
    }

    private suspend fun commitVersion(
        chapterIndex: Int,
        suffix: String,
        expectedCurrentVersionId: String?,
        content: String,
        createdAt: Long,
    ) {
        database.libraryDao().commitChapterVersion(
            CommitChapterVersionCommand(
                chapterVersionId = "chapter-$chapterIndex-$suffix",
                chapterId = "chapter-$chapterIndex",
                expectedCurrentVersionId = expectedCurrentVersionId,
                content = content,
                contentHash = sha256(content),
                source = ChapterVersionSource.IMPORTED,
                generationStageId = null,
                modelSnapshotJson = null,
                createdAt = createdAt,
            ),
        )
    }

    private fun character() = StoryEntity(
        entityId = "hero",
        bookId = BOOK_ID,
        entityType = StoryEntityType.CHARACTER,
        canonicalName = "主角",
        aliasesJson = "[]",
        stableDefinitionJson = "{}",
        adultStatus = AdultStatus.CONFIRMED_ADULT,
        ageYears = 25,
        sourceBibleRevisionId = null,
        createdAt = 1,
        updatedAt = 1,
    )

    private fun event(
        id: String,
        sourceVersionId: String,
        storyOrder: Long,
        attributeKey: String,
        newValueJson: String,
        createdAt: Long,
    ) = EntityEventEntity(
        entityEventId = id,
        bookId = BOOK_ID,
        entityId = "hero",
        sourceChapterVersionId = sourceVersionId,
        storyOrder = storyOrder,
        attributeKey = attributeKey,
        oldValueJson = null,
        newValueJson = newValueJson,
        storyTimeExpression = "第${storyOrder / 1_000_000}章",
        confidenceMicros = 900_000,
        canonLevel = CanonLevel.STORY_CANON,
        evidenceJson = "{}",
        status = DerivedDataStatus.VALID,
        createdAt = createdAt,
    )

    private fun request(index: Int) = ChapterEditRebuildPlanRequest(
        bookId = BOOK_ID,
        editedChapterId = "chapter-$index",
        editedVersionId = "chapter-$index-v2",
    )

    private suspend fun createBook() {
        database.libraryDao().createBook(
            BookCreationSnapshotEntity(
                snapshotId = "aggregate-snapshot",
                rawInputJson = "{}",
                normalizedInputJson = "{}",
                inferenceProvenanceJson = "{}",
                genrePayloadJson = "{}",
                presentationProfileJson = "{}",
                modelPreferenceJson = "{}",
                schemaVersion = 1,
                promptBundleVersion = "prompt-1",
                contentControlSchemaVersion = 1,
                contentHash = sha256("aggregate-snapshot"),
                createdAt = 1,
            ),
            BookEntity(
                bookId = BOOK_ID,
                creationSnapshotId = "aggregate-snapshot",
                title = "Aggregate test",
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

    private fun app.zhijuan.core.database.library.ChapterEditRebuildPlan.step(
        type: ChapterEditRebuildStepType,
        chapterIndex: Int,
    ) = steps.single { it.type == type && it.chapterIndex == chapterIndex }

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

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }

    private companion object {
        const val BOOK_ID = "aggregate-writer-book"
    }
}

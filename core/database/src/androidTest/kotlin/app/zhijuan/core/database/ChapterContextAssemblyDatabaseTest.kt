package app.zhijuan.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.zhijuan.core.database.generation.ChapterContextAssemblyJobFactory
import app.zhijuan.core.database.generation.ChapterContextAssemblyJobSpec
import app.zhijuan.core.database.generation.ChapterContextAssemblyRepository
import app.zhijuan.core.database.generation.ChapterContextAssemblyStageIds
import app.zhijuan.core.database.generation.ChapterProgressionAuthorization
import app.zhijuan.core.database.generation.ChapterProgressionGateRepository
import app.zhijuan.core.database.generation.GenerationJobSetup
import app.zhijuan.core.database.generation.GenerationJobSetupRepository
import app.zhijuan.core.database.generation.GenerationLeaseToken
import app.zhijuan.core.database.generation.GenerationRunnerCurrentStageRouteSnapshot
import app.zhijuan.core.database.generation.GenerationRunnerExecutionLeaseRepository
import app.zhijuan.core.database.generation.GenerationRunnerExecutionLeaseSnapshot
import app.zhijuan.core.database.generation.GenerationStageSetup
import app.zhijuan.core.database.generation.GenerationStateRepository
import app.zhijuan.core.database.generation.PersistedChapterContextAssemblyResult
import app.zhijuan.core.database.generation.PromptBundleBindingRepository
import app.zhijuan.core.database.generation.StaleGenerationStateException
import app.zhijuan.core.database.library.BookCreationRepository
import app.zhijuan.core.database.library.BookCreationSnapshotEntity
import app.zhijuan.core.database.library.BookEntity
import app.zhijuan.core.database.library.ChapterEntity
import app.zhijuan.core.database.library.ChapterVersionEntity
import app.zhijuan.core.database.memory.ChapterSummaryEntity
import app.zhijuan.core.database.memory.CanonFactEntity
import app.zhijuan.core.database.memory.OutlineNodeEntity
import app.zhijuan.core.database.memory.OutlineRevisionEntity
import app.zhijuan.core.database.memory.StoryBibleRevisionEntity
import app.zhijuan.core.database.memory.StoryEntity
import app.zhijuan.core.database.memory.TimelineEventEntity
import app.zhijuan.core.database.search.MemorySearchBackfillRepositoryV1
import app.zhijuan.core.database.search.MemorySearchSourceTypeV1
import app.zhijuan.core.model.AdultStatus
import app.zhijuan.core.model.BookLengthMode
import app.zhijuan.core.model.BookLengthPolicy
import app.zhijuan.core.model.BookStatus
import app.zhijuan.core.model.CanonLevel
import app.zhijuan.core.model.ChapterStatus
import app.zhijuan.core.model.ChapterVersionSource
import app.zhijuan.core.model.ConsistencyStatus
import app.zhijuan.core.model.DerivedDataStatus
import app.zhijuan.core.model.GenerationJobStatus
import app.zhijuan.core.model.GenerationJobType
import app.zhijuan.core.model.GenerationPhase
import app.zhijuan.core.model.GenerationStageStatus
import app.zhijuan.core.model.GenerationTargetType
import app.zhijuan.core.model.OutlineNodeType
import app.zhijuan.core.model.RevisionSource
import app.zhijuan.core.model.StandardErrorCode
import app.zhijuan.core.model.StoryEntityType
import app.zhijuan.core.model.TitleSource
import app.zhijuan.core.task.ChapterContextBlockReason
import app.zhijuan.core.task.ChapterContextBudgetSpec
import app.zhijuan.core.task.ChapterContextLimitSource
import app.zhijuan.core.task.FirstChapterGenerationMode
import app.zhijuan.core.task.JobEvent
import app.zhijuan.core.task.PromptBundleCatalogV1
import app.zhijuan.core.task.StageEvent
import java.security.MessageDigest
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
class ChapterContextAssemblyDatabaseTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: ZhijuanDatabase
    private lateinit var states: GenerationStateRepository

    @Before
    fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(context, ZhijuanDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(LibraryDatabaseGuards.callback)
            .build()
            .also { it.openHelper.writableDatabase }
        states = GenerationStateRepository(database)
        seedBookAndPlanning()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun assemblesImmutableContextWithoutRequestAttemptAndRejectsStaleReplayAtProviderOpen() = runBlocking {
        createContextJob(
            jobId = CONTEXT_JOB,
            stageIds = ChapterContextAssemblyStageIds(CONTEXT_STAGE, PLAN_STAGE),
            budget = ChapterContextBudgetSpec(
                contextLimitTokens = 32_768,
                maximumOutputTokens = 8_192,
                requestedOutputTokens = 4_096,
                limitSource = ChapterContextLimitSource.OFFICIAL_METADATA,
                unknownLimitConfirmed = false,
                tokenizerFamily = "conservative-utf8-v1",
            ),
            createdAt = 100L,
        )
        prepareContextJob(CONTEXT_JOB, CONTEXT_STAGE, 101L)
        val token = requireNotNull(states.findStage(CONTEXT_STAGE)?.leaseToken)

        val result = ChapterContextAssemblyRepository(database).assemble(CONTEXT_STAGE, token, 104L)
            as PersistedChapterContextAssemblyResult.Ready

        assertFalse(result.context.replayed)
        assertTrue(result.context.providerPayloadJson.contains("Lin Lan restores historical archives"))
        assertTrue(result.context.providerPayloadJson.contains("CONFIRMED_ADULT"))
        assertTrue(result.context.providerPayloadJson.contains("PREVIOUS_CHAPTER_SUMMARY"))
        assertFalse(result.context.providerPayloadJson.contains(LARGE_OPTIONAL_MARKER))
        assertTrue(
            database.memoryDao().findContextSnapshotForStage(CONTEXT_STAGE)?.sourceManifestJson
                ?.contains("\"memorySelection\"") == true,
        )
        assertEquals(0, database.generationDao().attemptsForStage(CONTEXT_STAGE).size)
        assertEquals(GenerationStageStatus.SUCCEEDED, states.findStage(CONTEXT_STAGE)?.status)
        assertEquals(GenerationStageStatus.READY, states.findStage(PLAN_STAGE)?.status)
        assertEquals(PLAN_STAGE, states.findJob(CONTEXT_JOB)?.currentStageId)
        val snapshot = database.memoryDao().findContextSnapshotForStage(CONTEXT_STAGE)
        assertNotNull(snapshot)
        assertEquals(DerivedDataStatus.VALID, snapshot?.status)
        assertTrue(snapshot?.sourceManifestJson?.contains("\"omitted\"") == true)

        val replay = ChapterContextAssemblyRepository(database).assemble(CONTEXT_STAGE, token, 105L)
            as PersistedChapterContextAssemblyResult.Ready
        assertTrue(replay.context.replayed)
        assertEquals(result.context.contentHash, replay.context.contentHash)
        assertEquals(result.context.providerPayloadJson, replay.context.providerPayloadJson)

        states.acquireStageLease(PLAN_STAGE, WORKER, 105L)
        val loaded = ChapterContextAssemblyRepository(database).loadForChapterPlanStage(PLAN_STAGE, 106L)
        assertEquals(result.context.providerPayloadJson, loaded.providerPayloadJson)

        assertEquals(
            1,
            database.memoryDao().setCurrentOutlineRevision(
                BOOK_ID,
                MASTER_OUTLINE_REVISION,
                107L,
            ),
        )
        val failure = expectFailure {
            ChapterContextAssemblyRepository(database).loadForChapterPlanStage(PLAN_STAGE, 108L)
        }
        assertTrue(failure.message.orEmpty().contains("outline"))
    }

    @Test
    fun boundContextUsesExactDualLeaseAndDurablyReplaysWithoutDuplicateWrites() = runBlocking {
        val snapshot = createBoundContextRoute(
            jobId = BOUND_JOB,
            stageIds = ChapterContextAssemblyStageIds(BOUND_CONTEXT_STAGE, BOUND_PLAN_STAGE),
            baseAt = 500L,
        )
        val repository = ChapterContextAssemblyRepository(database)

        val first = repository.assembleBound(snapshot, assembledAt = 505L)
            as PersistedChapterContextAssemblyResult.Ready

        assertFalse(first.context.replayed)
        assertEquals(GenerationStageStatus.SUCCEEDED, states.findStage(BOUND_CONTEXT_STAGE)?.status)
        assertEquals(GenerationStageStatus.READY, states.findStage(BOUND_PLAN_STAGE)?.status)
        assertEquals(BOUND_PLAN_STAGE, states.findJob(BOUND_JOB)?.currentStageId)
        assertEquals(0, database.generationDao().attemptsForStage(BOUND_CONTEXT_STAGE).size)
        val durableSnapshot = requireNotNull(
            database.memoryDao().findContextSnapshotForStage(BOUND_CONTEXT_STAGE),
        )
        val durableJob = states.findJob(BOUND_JOB)
        val durableContextStage = states.findStage(BOUND_CONTEXT_STAGE)
        val durablePlanStage = states.findStage(BOUND_PLAN_STAGE)

        val replay = repository.assembleBound(snapshot, assembledAt = 506L)
            as PersistedChapterContextAssemblyResult.Ready

        assertTrue(replay.context.replayed)
        assertEquals(first.context.contentHash, replay.context.contentHash)
        assertEquals(durableSnapshot, database.memoryDao().findContextSnapshotForStage(BOUND_CONTEXT_STAGE))
        assertEquals(durableJob, states.findJob(BOUND_JOB))
        assertEquals(durableContextStage, states.findStage(BOUND_CONTEXT_STAGE))
        assertEquals(durablePlanStage, states.findStage(BOUND_PLAN_STAGE))
    }

    @Test
    fun boundContextRejectsChangedJobOrStageTokenWithoutBusinessWrites() = runBlocking {
        val snapshot = createBoundContextRoute(
            jobId = BOUND_STALE_JOB,
            stageIds = ChapterContextAssemblyStageIds(BOUND_STALE_CONTEXT_STAGE, BOUND_STALE_PLAN_STAGE),
            baseAt = 600L,
        )
        val repository = ChapterContextAssemblyRepository(database)
        val beforeJob = states.findJob(BOUND_STALE_JOB)
        val beforeStage = states.findStage(BOUND_STALE_CONTEXT_STAGE)
        val lease = snapshot.executionLease
        val wrongJob = snapshot.withExecutionLease(
            lease.copy(
                jobLeaseToken = GenerationLeaseToken(
                    lease.jobLeaseToken.ownerId,
                    lease.jobLeaseToken.acquiredAt + 1L,
                ),
            ),
        )
        val wrongStage = snapshot.withExecutionLease(
            lease.copy(
                stageLeaseToken = GenerationLeaseToken(
                    lease.stageLeaseToken.ownerId,
                    lease.stageLeaseToken.acquiredAt + 1L,
                ),
            ),
        )

        assertTrue(expectFailure { repository.assembleBound(wrongJob, 605L) } is StaleGenerationStateException)
        assertTrue(expectFailure { repository.assembleBound(wrongStage, 605L) } is StaleGenerationStateException)
        assertEquals(beforeJob, states.findJob(BOUND_STALE_JOB))
        assertEquals(beforeStage, states.findStage(BOUND_STALE_CONTEXT_STAGE))
        assertNull(database.memoryDao().findContextSnapshotForStage(BOUND_STALE_CONTEXT_STAGE))
        assertEquals(0, database.generationDao().attemptsForStage(BOUND_STALE_CONTEXT_STAGE).size)
    }

    @Test
    fun boundContextRejectsStatusOrCursorChangeWithoutAssembly() = runBlocking {
        val statusSnapshot = createBoundContextRoute(
            jobId = BOUND_STATUS_JOB,
            stageIds = ChapterContextAssemblyStageIds(BOUND_STATUS_CONTEXT_STAGE, BOUND_STATUS_PLAN_STAGE),
            baseAt = 700L,
        )
        database.openHelper.writableDatabase.execSQL(
            "UPDATE generation_job SET status = 'PAUSING', updated_at = 705 " +
                "WHERE job_id = '$BOUND_STATUS_JOB'",
        )
        val statusJob = states.findJob(BOUND_STATUS_JOB)
        val statusStage = states.findStage(BOUND_STATUS_CONTEXT_STAGE)
        assertTrue(
            expectFailure {
                ChapterContextAssemblyRepository(database).assembleBound(statusSnapshot, 706L)
            } is StaleGenerationStateException,
        )
        assertEquals(statusJob, states.findJob(BOUND_STATUS_JOB))
        assertEquals(statusStage, states.findStage(BOUND_STATUS_CONTEXT_STAGE))
        assertNull(database.memoryDao().findContextSnapshotForStage(BOUND_STATUS_CONTEXT_STAGE))

        val cursorSnapshot = createBoundContextRoute(
            jobId = BOUND_CURSOR_JOB,
            stageIds = ChapterContextAssemblyStageIds(BOUND_CURSOR_CONTEXT_STAGE, BOUND_CURSOR_PLAN_STAGE),
            baseAt = 800L,
        )
        database.openHelper.writableDatabase.execSQL(
            "UPDATE generation_job SET current_stage_id = '$BOUND_CURSOR_PLAN_STAGE', " +
                "updated_at = 805 WHERE job_id = '$BOUND_CURSOR_JOB'",
        )
        val cursorJob = states.findJob(BOUND_CURSOR_JOB)
        val cursorStage = states.findStage(BOUND_CURSOR_CONTEXT_STAGE)
        assertTrue(
            expectFailure {
                ChapterContextAssemblyRepository(database).assembleBound(cursorSnapshot, 806L)
            } is StaleGenerationStateException,
        )
        assertEquals(cursorJob, states.findJob(BOUND_CURSOR_JOB))
        assertEquals(cursorStage, states.findStage(BOUND_CURSOR_CONTEXT_STAGE))
        assertNull(database.memoryDao().findContextSnapshotForStage(BOUND_CURSOR_CONTEXT_STAGE))
    }

    @Test
    fun boundContextRejectsLeaseAtTimeoutBoundaryWithoutAssembly() = runBlocking {
        val snapshot = createBoundContextRoute(
            jobId = BOUND_TIMEOUT_JOB,
            stageIds = ChapterContextAssemblyStageIds(BOUND_TIMEOUT_CONTEXT_STAGE, BOUND_TIMEOUT_PLAN_STAGE),
            baseAt = 900L,
        )
        val beforeJob = states.findJob(BOUND_TIMEOUT_JOB)
        val beforeStage = states.findStage(BOUND_TIMEOUT_CONTEXT_STAGE)
        val expiredAt = maxOf(
            snapshot.executionLease.jobHeartbeatAt,
            snapshot.executionLease.stageHeartbeatAt,
        ) + 60_000L

        val failure = expectFailure {
            ChapterContextAssemblyRepository(database).assembleBound(snapshot, expiredAt)
        }

        assertTrue(failure is StaleGenerationStateException)
        assertEquals(beforeJob, states.findJob(BOUND_TIMEOUT_JOB))
        assertEquals(beforeStage, states.findStage(BOUND_TIMEOUT_CONTEXT_STAGE))
        assertNull(database.memoryDao().findContextSnapshotForStage(BOUND_TIMEOUT_CONTEXT_STAGE))
        assertEquals(0, database.generationDao().attemptsForStage(BOUND_TIMEOUT_CONTEXT_STAGE).size)
    }

    @Test
    fun routesOnlyRelevantStoryCanonAndRejectsChangedMemoryBeforeProviderOpen() = runBlocking {
        database.memoryDao().insertCanonFacts(
            listOf(
                chapterFact(
                    id = "fact.story.relevant",
                    text = "The physical trace proves the archive was opened.",
                ),
                chapterFact(
                    id = "fact.story.irrelevant",
                    text = "A distant orchard has blue roof tiles.",
                ),
            ),
        )
        createContextJob(
            jobId = ROUTED_JOB,
            stageIds = ChapterContextAssemblyStageIds(ROUTED_CONTEXT_STAGE, ROUTED_PLAN_STAGE),
            budget = knownBudget(),
            createdAt = 300L,
        )
        prepareContextJob(ROUTED_JOB, ROUTED_CONTEXT_STAGE, 301L)
        val token = requireNotNull(states.findStage(ROUTED_CONTEXT_STAGE)?.leaseToken)

        val result = ChapterContextAssemblyRepository(database).assemble(
            ROUTED_CONTEXT_STAGE,
            token,
            304L,
        ) as PersistedChapterContextAssemblyResult.Ready

        assertTrue(result.context.providerPayloadJson.contains("physical trace proves"))
        assertFalse(result.context.providerPayloadJson.contains("blue roof tiles"))
        val manifest = requireNotNull(
            database.memoryDao().findContextSnapshotForStage(ROUTED_CONTEXT_STAGE),
        ).sourceManifestJson
        assertTrue(manifest.contains("\"routes\":[\"FTS\"]"))

        states.acquireStageLease(ROUTED_PLAN_STAGE, WORKER, 305L)
        database.openHelper.writableDatabase.execSQL(
            "UPDATE canon_fact SET status = 'STALE' WHERE canon_fact_id = 'fact.story.relevant'",
        )
        assertEquals(
            1,
            database.memorySearchDao().deleteBySource(
                BOOK_ID,
                MemorySearchSourceTypeV1.CANON_FACT.name,
                "fact.story.relevant",
            ),
        )
        val failure = expectFailure {
            ChapterContextAssemblyRepository(database).loadForChapterPlanStage(
                ROUTED_PLAN_STAGE,
                306L,
            )
        }
        assertTrue(failure.message.orEmpty().contains("dynamic memory"))
        assertEquals(0, database.generationDao().attemptsForStage(ROUTED_PLAN_STAGE).size)
    }

    @Test
    fun mandatoryMemoryOverflowBlocksBeforeSnapshotOrProviderAttempt() = runBlocking {
        database.memoryDao().insertCanonFacts(
            (1..511).map { index ->
                CanonFactEntity(
                    canonFactId = "fact.overflow.${index.toString().padStart(3, '0')}",
                    bookId = BOOK_ID,
                    entityId = "char.lin",
                    factText = "Immutable rule $index.",
                    factPayloadJson = "{\"index\":$index}",
                    canonLevel = CanonLevel.HARD_CANON,
                    scopeJson = "{}",
                    sourceChapterVersionId = null,
                    sourceBibleRevisionId = BIBLE_REVISION,
                    validFromStoryOrder = null,
                    validToStoryOrder = null,
                    conflictGroupId = null,
                    status = DerivedDataStatus.VALID,
                    createdAt = 20L + index,
                )
            },
        )
        createContextJob(
            jobId = OVERFLOW_JOB,
            stageIds = ChapterContextAssemblyStageIds(OVERFLOW_CONTEXT_STAGE, OVERFLOW_PLAN_STAGE),
            budget = knownBudget(),
            createdAt = 900L,
        )
        prepareContextJob(OVERFLOW_JOB, OVERFLOW_CONTEXT_STAGE, 901L)
        val token = requireNotNull(states.findStage(OVERFLOW_CONTEXT_STAGE)?.leaseToken)

        val result = ChapterContextAssemblyRepository(database).assemble(
            OVERFLOW_CONTEXT_STAGE,
            token,
            904L,
        ) as PersistedChapterContextAssemblyResult.Blocked

        assertEquals(
            ChapterContextBlockReason.MANDATORY_MEMORY_SELECTION_EXCEEDS_LIMIT,
            result.reason,
        )
        assertEquals(StandardErrorCode.CONTEXT_TOO_LARGE, result.standardErrorCode)
        assertEquals(GenerationStageStatus.BLOCKED, states.findStage(OVERFLOW_CONTEXT_STAGE)?.status)
        assertEquals(GenerationJobStatus.NEEDS_ACTION, states.findJob(OVERFLOW_JOB)?.status)
        assertEquals(GenerationStageStatus.PENDING, states.findStage(OVERFLOW_PLAN_STAGE)?.status)
        assertNull(database.memoryDao().findContextSnapshotForStage(OVERFLOW_CONTEXT_STAGE))
        assertEquals(0, database.generationDao().attemptsForStage(OVERFLOW_CONTEXT_STAGE).size)
        assertEquals(0, database.generationDao().attemptsForStage(OVERFLOW_PLAN_STAGE).size)
    }

    @Test
    fun repairsStaleSearchPointerOnceBeforeAssemblingContext() = runBlocking {
        database.memoryDao().insertCanonFacts(
            listOf(
                chapterFact(
                    id = "fact.story.repair",
                    text = "The physical trace is preserved under glass.",
                ),
            ),
        )
        MemorySearchBackfillRepositoryV1(database).ensureReady(BOOK_ID, 390L)
        val stale = requireNotNull(
            database.memorySearchDao().findBySource(
                BOOK_ID,
                MemorySearchSourceTypeV1.CANON_FACT.name,
                "fact.story.repair",
            ),
        )
        assertEquals(
            1,
            database.memorySearchDao().update(stale.copy(sourceContentHash = "0".repeat(64))),
        )
        createContextJob(
            jobId = REPAIR_JOB,
            stageIds = ChapterContextAssemblyStageIds(REPAIR_CONTEXT_STAGE, REPAIR_PLAN_STAGE),
            budget = knownBudget(),
            createdAt = 400L,
        )
        prepareContextJob(REPAIR_JOB, REPAIR_CONTEXT_STAGE, 401L)
        val token = requireNotNull(states.findStage(REPAIR_CONTEXT_STAGE)?.leaseToken)

        val result = ChapterContextAssemblyRepository(database).assemble(
            REPAIR_CONTEXT_STAGE,
            token,
            404L,
        ) as PersistedChapterContextAssemblyResult.Ready

        assertTrue(result.context.providerPayloadJson.contains("preserved under glass"))
        val repaired = requireNotNull(
            database.memorySearchDao().findBySource(
                BOOK_ID,
                MemorySearchSourceTypeV1.CANON_FACT.name,
                "fact.story.repair",
            ),
        )
        assertFalse(repaired.sourceContentHash == "0".repeat(64))
        assertEquals(GenerationStageStatus.SUCCEEDED, states.findStage(REPAIR_CONTEXT_STAGE)?.status)
        assertEquals(GenerationStageStatus.READY, states.findStage(REPAIR_PLAN_STAGE)?.status)
    }

    @Test
    fun unknownContextLimitBlocksStageAndParentJobBeforeAnyProviderAttempt() = runBlocking {
        createContextJob(
            jobId = UNKNOWN_JOB,
            stageIds = ChapterContextAssemblyStageIds(UNKNOWN_CONTEXT_STAGE, UNKNOWN_PLAN_STAGE),
            budget = ChapterContextBudgetSpec(
                contextLimitTokens = null,
                maximumOutputTokens = null,
                requestedOutputTokens = 2_048,
                limitSource = ChapterContextLimitSource.UNKNOWN,
                unknownLimitConfirmed = false,
                tokenizerFamily = "unknown-provider",
            ),
            createdAt = 200L,
        )
        prepareContextJob(UNKNOWN_JOB, UNKNOWN_CONTEXT_STAGE, 201L)
        val token = requireNotNull(states.findStage(UNKNOWN_CONTEXT_STAGE)?.leaseToken)

        val result = ChapterContextAssemblyRepository(database).assemble(
            UNKNOWN_CONTEXT_STAGE,
            token,
            204L,
        ) as PersistedChapterContextAssemblyResult.Blocked

        assertEquals(ChapterContextBlockReason.UNKNOWN_CONTEXT_LIMIT_REQUIRES_CONFIRMATION, result.reason)
        assertEquals(StandardErrorCode.CONTEXT_TOO_LARGE, result.standardErrorCode)
        assertEquals(GenerationStageStatus.BLOCKED, states.findStage(UNKNOWN_CONTEXT_STAGE)?.status)
        assertEquals(
            StandardErrorCode.CONTEXT_TOO_LARGE,
            states.findStage(UNKNOWN_CONTEXT_STAGE)?.standardErrorCode,
        )
        assertEquals(GenerationJobStatus.NEEDS_ACTION, states.findJob(UNKNOWN_JOB)?.status)
        assertTrue(
            states.findJob(UNKNOWN_JOB)?.pauseOrStopReason.orEmpty()
                .contains(ChapterContextBlockReason.UNKNOWN_CONTEXT_LIMIT_REQUIRES_CONFIRMATION.name),
        )
        assertEquals(GenerationStageStatus.PENDING, states.findStage(UNKNOWN_PLAN_STAGE)?.status)
        assertNull(database.memoryDao().findContextSnapshotForStage(UNKNOWN_CONTEXT_STAGE))
        assertEquals(0, database.generationDao().attemptsForStage(UNKNOWN_CONTEXT_STAGE).size)
        assertEquals(0, database.generationDao().attemptsForStage(UNKNOWN_PLAN_STAGE).size)
    }

    private suspend fun createContextJob(
        jobId: String,
        stageIds: ChapterContextAssemblyStageIds,
        budget: ChapterContextBudgetSpec,
        createdAt: Long,
    ) {
        val progressionAccess = ChapterProgressionGateRepository(database).authorize(
            bookId = BOOK_ID,
            chapterId = CHAPTER_2,
            mode = FirstChapterGenerationMode.FULL_PLANNING,
        ) as ChapterProgressionAuthorization.Ready
        val binding = PromptBundleBindingRepository(database).bindForBook(BOOK_ID)
        GenerationJobSetupRepository(database).create(
            ChapterContextAssemblyJobFactory.create(
                ChapterContextAssemblyJobSpec(
                    jobId = jobId,
                    bookId = BOOK_ID,
                    chapterId = CHAPTER_2,
                    chapterIndex = 2,
                    userIntentJson = "{}",
                    budgetSnapshotJson = "{\"fixture\":true}",
                    promptBindingHash = binding.bindingHash,
                    contextBudget = budget,
                    progressionPermit = progressionAccess.permit,
                    stageIds = stageIds,
                    userAddition = "Keep the physical evidence visible.",
                    createdAt = createdAt,
                ),
            ),
        )
    }

    private suspend fun createBoundContextRoute(
        jobId: String,
        stageIds: ChapterContextAssemblyStageIds,
        baseAt: Long,
    ): GenerationRunnerCurrentStageRouteSnapshot {
        createContextJob(
            jobId = jobId,
            stageIds = stageIds,
            budget = knownBudget(),
            createdAt = baseAt,
        )
        prepareContextJob(jobId, stageIds.contextStageId, baseAt + 1L)
        val jobToken = requireNotNull(states.findJob(jobId)?.leaseToken)
        val stageToken = requireNotNull(states.findStage(stageIds.contextStageId)?.leaseToken)
        return GenerationRunnerExecutionLeaseRepository(database).resolveCurrentStageRoute(
            jobId = jobId,
            jobLeaseToken = jobToken,
            stageId = stageIds.contextStageId,
            stageLeaseToken = stageToken,
            observedAt = baseAt + 4L,
        )
    }

    private fun GenerationRunnerCurrentStageRouteSnapshot.withExecutionLease(
        lease: GenerationRunnerExecutionLeaseSnapshot,
    ) = GenerationRunnerCurrentStageRouteSnapshot(
        route = route,
        executionLease = lease,
        attemptCount = attemptCount,
        maxAttempts = maxAttempts,
    )

    private fun knownBudget() = ChapterContextBudgetSpec(
        contextLimitTokens = 32_768,
        maximumOutputTokens = 8_192,
        requestedOutputTokens = 4_096,
        limitSource = ChapterContextLimitSource.OFFICIAL_METADATA,
        unknownLimitConfirmed = false,
        tokenizerFamily = "conservative-utf8-v1",
    )

    private fun chapterFact(id: String, text: String) = CanonFactEntity(
        canonFactId = id,
        bookId = BOOK_ID,
        entityId = "char.lin",
        factText = text,
        factPayloadJson = "{\"kind\":\"story\"}",
        canonLevel = CanonLevel.STORY_CANON,
        scopeJson = "{}",
        sourceChapterVersionId = CHAPTER_1_VERSION,
        sourceBibleRevisionId = null,
        validFromStoryOrder = 101L,
        validToStoryOrder = null,
        conflictGroupId = null,
        status = DerivedDataStatus.VALID,
        createdAt = 20L,
    )

    private suspend fun prepareContextJob(jobId: String, stageId: String, readyAt: Long) {
        states.transitionJob(jobId, GenerationJobStatus.CREATED, JobEvent.VALIDATION_PASSED, readyAt)
        states.transitionStage(
            stageId,
            GenerationStageStatus.PENDING,
            StageEvent.DEPENDENCIES_SATISFIED,
            updatedAt = readyAt,
        )
        states.acquireJobLease(jobId, WORKER, readyAt + 1L)
        states.acquireStageLease(stageId, WORKER, readyAt + 2L)
    }

    private suspend fun seedBookAndPlanning() {
        BookCreationRepository(database).create(
            snapshot = BookCreationSnapshotEntity(
                snapshotId = SNAPSHOT_ID,
                rawInputJson = "{\"storyIdea\":\"fixture\"}",
                normalizedInputJson = "{\"storyIdea\":\"fixture\"}",
                inferenceProvenanceJson = "{\"schemaVersion\":1}",
                genrePayloadJson =
                    "{\"contentDimensionBaseline\":{" +
                        "\"conflictDetailLevel\":1," +
                        "\"graphicInjuryLevel\":0," +
                        "\"languageIntensityLevel\":2," +
                        "\"emotionalPressureLevel\":3}}",
                presentationProfileJson = presentationJson(),
                modelPreferenceJson = "{\"connectionId\":\"fixture\",\"modelId\":\"fixture\"}",
                schemaVersion = 1,
                promptBundleVersion = PromptBundleCatalogV1.UNASSIGNED_CREATION_BUNDLE_VERSION,
                contentControlSchemaVersion = 1,
                contentHash = "c".repeat(64),
                createdAt = 1L,
            ),
            book = BookEntity(
                bookId = BOOK_ID,
                creationSnapshotId = SNAPSHOT_ID,
                title = "Fixture",
                titleSource = TitleSource.USER,
                status = BookStatus.DRAFT,
                lengthMode = BookLengthMode.SHORT,
                targetCharacters = null,
                targetChapters = BookLengthPolicy.SHORT_MINIMUM_CHAPTERS,
                minimumChapters = BookLengthPolicy.SHORT_MINIMUM_CHAPTERS,
                lengthPolicySchemaVersion = BookLengthPolicy.SCHEMA_VERSION,
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )
        database.libraryDao().insertChapter(chapter(CHAPTER_1, 1))
        database.libraryDao().insertChapter(chapter(CHAPTER_2, 2))
        val chapterContent = "Committed first chapter."
        val chapterHash = sha256(chapterContent)
        database.libraryDao().insertChapterVersion(
            ChapterVersionEntity(
                chapterVersionId = CHAPTER_1_VERSION,
                chapterId = CHAPTER_1,
                versionNo = 1,
                content = chapterContent,
                characterCount = chapterContent.length,
                contentHash = chapterHash,
                source = ChapterVersionSource.USER_EDIT,
                parentVersionId = null,
                generationStageId = null,
                modelSnapshotJson = null,
                createdAt = 2L,
            ),
        )
        database.openHelper.writableDatabase.execSQL(
            "UPDATE chapter SET current_version_id = '$CHAPTER_1_VERSION', " +
                "status = 'READY', consistency_status = 'VALID', updated_at = 2 " +
                "WHERE chapter_id = '$CHAPTER_1'",
        )
        createEvidenceStage(BIBLE_JOB, BIBLE_STAGE, GenerationPhase.BUILD_BIBLE, GenerationTargetType.STORY_BIBLE)
        createEvidenceStage(
            MASTER_OUTLINE_JOB,
            MASTER_OUTLINE_STAGE,
            GenerationPhase.BUILD_MASTER_OUTLINE,
            GenerationTargetType.OUTLINE,
        )
        createEvidenceStage(
            WINDOW_JOB,
            WINDOW_STAGE,
            GenerationPhase.BUILD_ARC_PLAN,
            GenerationTargetType.OUTLINE,
        )

        val biblePayload =
            "{\"schemaVersion\":1,\"characters\":[{\"entityId\":\"char.lin\"}]," +
                "\"worldRules\":[{\"ruleId\":\"rule.memory\",\"text\":\"Altered memories leave physical marks.\"}]," +
                "\"hardFacts\":[{\"factId\":\"fact.job\",\"entityId\":\"char.lin\",\"text\":\"Lin Lan restores historical archives.\"}]," +
                "\"themes\":[\"memory and identity\"]," +
                "\"writingStyle\":[\"limited viewpoint\"]," +
                "\"forbiddenChanges\":[\"Do not reset established physical evidence.\"]}"
        val bibleHash = sha256(biblePayload)
        database.memoryDao().createBibleRevision(
            StoryBibleRevisionEntity(
                bibleRevisionId = BIBLE_REVISION,
                bookId = BOOK_ID,
                revisionNo = 1,
                parentRevisionId = null,
                source = RevisionSource.AI_GENERATED,
                schemaVersion = 1,
                contentControlSchemaVersion = 1,
                payloadJson = biblePayload,
                contentHash = bibleHash,
                generationStageId = BIBLE_STAGE,
                createdAt = 3L,
            ),
        )
        database.memoryDao().insertStoryEntity(
            StoryEntity(
                entityId = "char.lin",
                bookId = BOOK_ID,
                entityType = StoryEntityType.CHARACTER,
                canonicalName = "Lin Lan",
                aliasesJson = "[]",
                stableDefinitionJson = "{\"role\":\"archive restorer\"}",
                adultStatus = AdultStatus.CONFIRMED_ADULT,
                ageYears = 22,
                sourceBibleRevisionId = BIBLE_REVISION,
                createdAt = 3L,
                updatedAt = 3L,
            ),
        )
        database.memoryDao().insertCanonFacts(
            listOf(
                CanonFactEntity(
                    canonFactId = "fact.job",
                    bookId = BOOK_ID,
                    entityId = "char.lin",
                    factText = "Lin Lan restores historical archives.",
                    factPayloadJson = "{\"kind\":\"occupation\"}",
                    canonLevel = CanonLevel.HARD_CANON,
                    scopeJson = "{}",
                    sourceChapterVersionId = null,
                    sourceBibleRevisionId = BIBLE_REVISION,
                    validFromStoryOrder = null,
                    validToStoryOrder = null,
                    conflictGroupId = null,
                    status = DerivedDataStatus.VALID,
                    createdAt = 3L,
                ),
            ),
        )
        markEvidenceStageSucceeded(
            BIBLE_STAGE,
            "{\"outputSchemaId\":\"story-bible.v1\",\"committedObjectId\":\"$BIBLE_REVISION\",\"contentHash\":\"$bibleHash\"}",
        )

        val masterPayload = "{\"title\":\"Master plan\"}"
        val masterHash = sha256(masterPayload)
        database.memoryDao().createOutlineRevision(
            OutlineRevisionEntity(
                outlineRevisionId = MASTER_OUTLINE_REVISION,
                bookId = BOOK_ID,
                revisionNo = 1,
                parentRevisionId = null,
                source = RevisionSource.AI_GENERATED,
                schemaVersion = 1,
                summaryJson = masterPayload,
                contentHash = masterHash,
                generationStageId = MASTER_OUTLINE_STAGE,
                createdAt = 4L,
            ),
            listOf(
                outlineNode(
                    "node.master.book",
                    MASTER_OUTLINE_REVISION,
                    null,
                    OutlineNodeType.BOOK,
                    0L,
                    null,
                    "Master",
                    masterPayload,
                    4L,
                ),
            ),
        )
        markEvidenceStageSucceeded(
            MASTER_OUTLINE_STAGE,
            "{\"outputSchemaId\":\"master-outline.v1\",\"committedObjectId\":\"$MASTER_OUTLINE_REVISION\",\"contentHash\":\"$masterHash\"}",
        )

        val windowPayload = "{\"window\":\"1-2\"}"
        val windowHash = sha256(windowPayload)
        database.memoryDao().createOutlineRevision(
            OutlineRevisionEntity(
                outlineRevisionId = WINDOW_REVISION,
                bookId = BOOK_ID,
                revisionNo = 2,
                parentRevisionId = MASTER_OUTLINE_REVISION,
                source = RevisionSource.AI_GENERATED,
                schemaVersion = 1,
                summaryJson = windowPayload,
                contentHash = windowHash,
                generationStageId = WINDOW_STAGE,
                createdAt = 5L,
            ),
            listOf(
                outlineNode(
                    "node.window.book",
                    WINDOW_REVISION,
                    null,
                    OutlineNodeType.BOOK,
                    0L,
                    null,
                    "Window",
                    windowPayload,
                    5L,
                ),
                outlineNode(
                    "node.window.arc",
                    WINDOW_REVISION,
                    "node.window.book",
                    OutlineNodeType.ARC,
                    1L,
                    null,
                    "Arc",
                    "{\"goal\":\"Preserve evidence\"}",
                    5L,
                ),
                outlineNode(
                    "node.window.chapter.1",
                    WINDOW_REVISION,
                    "node.window.arc",
                    OutlineNodeType.CHAPTER,
                    2L,
                    1,
                    "Chapter 1",
                    "{\"goal\":\"Find the anomaly\"}",
                    5L,
                ),
                outlineNode(
                    "node.window.chapter.2",
                    WINDOW_REVISION,
                    "node.window.arc",
                    OutlineNodeType.CHAPTER,
                    3L,
                    2,
                    "Chapter 2",
                    "{\"goal\":\"Verify the physical trace\"}",
                    5L,
                ),
            ),
        )
        markEvidenceStageSucceeded(
            WINDOW_STAGE,
            "{\"outputSchemaId\":\"arc-plan.v1\",\"outlineRevisionId\":\"$WINDOW_REVISION\",\"contentHash\":\"$windowHash\"}",
        )
        database.memoryDao().insertSummary(
            ChapterSummaryEntity(
                chapterSummaryId = "summary.chapter.1",
                bookId = BOOK_ID,
                chapterVersionId = CHAPTER_1_VERSION,
                chapterIndex = 1,
                schemaVersion = 1,
                summaryJson = "{\"summary\":\"Lin preserved a physical copy of the impossible record.\"}",
                importance = 95,
                status = DerivedDataStatus.VALID,
                modelSnapshotJson = null,
                createdAt = 6L,
                updatedAt = 6L,
            ),
        )
        database.memoryDao().insertTimelineEvents(
            listOf(
                TimelineEventEntity(
                    timelineEventId = "timeline.optional.large",
                    bookId = BOOK_ID,
                    name = "Verbose optional archive",
                    participantsJson = "[\"char.lin\"]",
                    locationEntityId = null,
                    storyTimeExpression = "before chapter two",
                    storyOrder = 100L,
                    constraintsJson = "{\"detail\":\"$LARGE_OPTIONAL_MARKER\"}",
                    sourceChapterVersionId = CHAPTER_1_VERSION,
                    status = DerivedDataStatus.VALID,
                    createdAt = 6L,
                ),
            ),
        )
    }

    private suspend fun createEvidenceStage(
        jobId: String,
        stageId: String,
        phase: GenerationPhase,
        targetType: GenerationTargetType,
    ) {
        GenerationJobSetupRepository(database).create(
            GenerationJobSetup(
                jobId = jobId,
                bookId = BOOK_ID,
                jobType = GenerationJobType.CREATE_BOOK,
                userIntentJson = "{}",
                budgetSnapshotJson = "{}",
                promptBundleVersion = PromptBundleCatalogV1.BUNDLE_VERSION,
                stages = listOf(
                    GenerationStageSetup(
                        stageId = stageId,
                        phase = phase,
                        targetType = targetType,
                        targetId = BOOK_ID,
                        inputVersionHash = sha256(stageId),
                        idempotencyKey = "idem.$stageId",
                        maxAttempts = 1,
                        inputSourcesJson = "{}",
                    ),
                ),
                createdAt = 2L,
            ),
        )
    }

    private fun markEvidenceStageSucceeded(stageId: String, output: String) {
        val escaped = output.replace("'", "''")
        database.openHelper.writableDatabase.execSQL(
            "UPDATE generation_stage SET status = 'SUCCEEDED', output_reference_json = '$escaped', " +
                "updated_at = 10 WHERE stage_id = '$stageId'",
        )
    }

    private fun chapter(id: String, index: Int) = ChapterEntity(
        chapterId = id,
        bookId = BOOK_ID,
        chapterIndex = index,
        plannedTitle = "Chapter $index",
        displayTitle = "Chapter $index",
        status = ChapterStatus.PLANNED,
        currentVersionId = null,
        consistencyStatus = ConsistencyStatus.UNKNOWN,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private fun outlineNode(
        id: String,
        revisionId: String,
        parentId: String?,
        type: OutlineNodeType,
        order: Long,
        chapterIndex: Int?,
        title: String,
        plan: String,
        createdAt: Long,
    ) = OutlineNodeEntity(
        outlineNodeId = id,
        outlineRevisionId = revisionId,
        parentNodeId = parentId,
        nodeType = type,
        orderKey = order,
        plannedChapterIndex = chapterIndex,
        title = title,
        planJson = plan,
        contentHash = sha256(plan),
        createdAt = createdAt,
    )

    private fun presentationJson(): String =
        "{\"directive\":{" +
            "\"preset\":\"DETAILED\"," +
            "\"narrativeDetailLevel\":4," +
            "\"intimacyDetailLevel\":4," +
            "\"fadePolicy\":\"AVOID\"," +
            "\"conflictDetailOverride\":null," +
            "\"graphicInjuryOverride\":null," +
            "\"languageIntensityOverride\":null," +
            "\"emotionalPressureOverride\":null," +
            "\"presentationMappingSchemaVersion\":1," +
            "\"contentControlSchemaVersion\":1}," +
            "\"resolvedProfile\":{" +
            "\"preset\":\"DETAILED\"," +
            "\"narrativeDetailLevel\":4," +
            "\"intimacyDetailLevel\":4," +
            "\"conflictDetailLevel\":1," +
            "\"graphicInjuryLevel\":0," +
            "\"languageIntensityLevel\":2," +
            "\"emotionalPressureLevel\":3," +
            "\"fadePolicy\":\"AVOID\"," +
            "\"presentationMappingSchemaVersion\":1," +
            "\"contentControlSchemaVersion\":1}}"

    private suspend fun expectFailure(block: suspend () -> Unit): Throwable = try {
        block()
        throw AssertionError("Expected operation to fail.")
    } catch (error: Throwable) {
        if (error is AssertionError && error.message == "Expected operation to fail.") throw error
        error
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private companion object {
        const val BOOK_ID = "book.context"
        const val SNAPSHOT_ID = "snapshot.context"
        const val CHAPTER_1 = "chapter.context.1"
        const val CHAPTER_2 = "chapter.context.2"
        const val CHAPTER_1_VERSION = "chapter.context.1.version.1"
        const val BIBLE_JOB = "job.evidence.bible"
        const val BIBLE_STAGE = "stage.evidence.bible"
        const val BIBLE_REVISION = "bible.context.1"
        const val MASTER_OUTLINE_JOB = "job.evidence.master"
        const val MASTER_OUTLINE_STAGE = "stage.evidence.master"
        const val MASTER_OUTLINE_REVISION = "outline.context.master.1"
        const val WINDOW_JOB = "job.evidence.window"
        const val WINDOW_STAGE = "stage.evidence.window"
        const val WINDOW_REVISION = "outline.context.window.2"
        const val CONTEXT_JOB = "job.context.success"
        const val CONTEXT_STAGE = "stage.z.context.success"
        const val PLAN_STAGE = "stage.a.plan.success"
        const val UNKNOWN_JOB = "job.context.unknown"
        const val UNKNOWN_CONTEXT_STAGE = "stage.z.context.unknown"
        const val UNKNOWN_PLAN_STAGE = "stage.a.plan.unknown"
        const val BOUND_JOB = "job.context.bound"
        const val BOUND_CONTEXT_STAGE = "stage.z.context.bound"
        const val BOUND_PLAN_STAGE = "stage.a.plan.bound"
        const val BOUND_STALE_JOB = "job.context.bound.stale"
        const val BOUND_STALE_CONTEXT_STAGE = "stage.z.context.bound.stale"
        const val BOUND_STALE_PLAN_STAGE = "stage.a.plan.bound.stale"
        const val BOUND_STATUS_JOB = "job.context.bound.status"
        const val BOUND_STATUS_CONTEXT_STAGE = "stage.z.context.bound.status"
        const val BOUND_STATUS_PLAN_STAGE = "stage.a.plan.bound.status"
        const val BOUND_CURSOR_JOB = "job.context.bound.cursor"
        const val BOUND_CURSOR_CONTEXT_STAGE = "stage.z.context.bound.cursor"
        const val BOUND_CURSOR_PLAN_STAGE = "stage.a.plan.bound.cursor"
        const val BOUND_TIMEOUT_JOB = "job.context.bound.timeout"
        const val BOUND_TIMEOUT_CONTEXT_STAGE = "stage.z.context.bound.timeout"
        const val BOUND_TIMEOUT_PLAN_STAGE = "stage.a.plan.bound.timeout"
        const val ROUTED_JOB = "job.context.routed"
        const val ROUTED_CONTEXT_STAGE = "stage.z.context.routed"
        const val ROUTED_PLAN_STAGE = "stage.a.plan.routed"
        const val OVERFLOW_JOB = "job.context.overflow"
        const val OVERFLOW_CONTEXT_STAGE = "stage.z.context.overflow"
        const val OVERFLOW_PLAN_STAGE = "stage.a.plan.overflow"
        const val REPAIR_JOB = "job.context.repair"
        const val REPAIR_CONTEXT_STAGE = "stage.z.context.repair"
        const val REPAIR_PLAN_STAGE = "stage.a.plan.repair"
        const val WORKER = "worker.context"
        val LARGE_OPTIONAL_MARKER = "optional-history-" + "x".repeat(40_000)
    }
}

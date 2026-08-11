package app.zhijuan.feature.generation

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.zhijuan.core.database.LibraryDatabaseGuards
import app.zhijuan.core.database.ZhijuanDatabase
import app.zhijuan.core.database.generation.ChapterEditRebuildRetainedTrackingStageCommand
import app.zhijuan.core.database.generation.ChapterEditRebuildStageRepository
import app.zhijuan.core.database.generation.ChapterEditRebuildTrackingStageCommand
import app.zhijuan.core.database.generation.ChapterTrackingProjectionCommitDraft
import app.zhijuan.core.database.generation.ChapterTrackingProjectionCommitRepository
import app.zhijuan.core.database.generation.ChapterTrackingProjectionInputs
import app.zhijuan.core.database.generation.ChapterTrackingProjectionJobFactory
import app.zhijuan.core.database.generation.ChapterTrackingProjectionJobSpec
import app.zhijuan.core.database.generation.ChapterTrackingProjectionSourceRepository
import app.zhijuan.core.database.generation.ChapterTrackingProjectionSourceV1
import app.zhijuan.core.database.generation.FinalUsageCommit
import app.zhijuan.core.database.generation.GenerationJobSetupRepository
import app.zhijuan.core.database.generation.GenerationOutputValidationRepository
import app.zhijuan.core.database.generation.GenerationStateRepository
import app.zhijuan.core.database.generation.GenerationStreamingDraftRepository
import app.zhijuan.core.database.generation.RequestIntentDraft
import app.zhijuan.core.database.library.ChapterEditRebuildExecutionPrepareCommand
import app.zhijuan.core.database.library.ChapterEditRebuildExecutionRepository
import app.zhijuan.core.database.library.ChapterEditRebuildPlanRequest
import app.zhijuan.core.database.library.ChapterEditRebuildPlanRepository
import app.zhijuan.core.database.library.ChapterEditRebuildStepState
import app.zhijuan.core.database.library.ChapterEditRebuildStepType
import app.zhijuan.core.database.library.ChapterUserEditCommand
import app.zhijuan.core.database.library.ChapterUserEditRepository
import app.zhijuan.core.database.memory.ChapterSummaryEntity
import app.zhijuan.core.database.memory.ForeshadowItemEntity
import app.zhijuan.core.database.memory.StoryEntity
import app.zhijuan.core.model.AdultStatus
import app.zhijuan.core.model.DerivedDataStatus
import app.zhijuan.core.model.ForeshadowStatus
import app.zhijuan.core.model.GenerationJobStatus
import app.zhijuan.core.model.GenerationStageStatus
import app.zhijuan.core.model.MemorySource
import app.zhijuan.core.model.StoryEntityType
import app.zhijuan.core.model.UsageSource
import app.zhijuan.core.security.AndroidProtectedArtifactStore
import app.zhijuan.core.task.JobEvent
import app.zhijuan.core.task.StageEvent
import app.zhijuan.provider.common.CapabilityResult
import app.zhijuan.provider.common.ConnectionTestResult
import app.zhijuan.provider.common.GenerationRequest
import app.zhijuan.provider.common.ModelListResult
import app.zhijuan.provider.common.ProviderAdapter
import app.zhijuan.provider.common.ProviderCancellationResult
import app.zhijuan.provider.common.ProviderConnectionProfile
import app.zhijuan.provider.common.ProviderFinishReason
import app.zhijuan.provider.common.ProviderModelId
import app.zhijuan.provider.common.ProviderProtocol
import app.zhijuan.provider.common.ProviderStreamEvent
import app.zhijuan.provider.common.ProviderTimeoutPolicy
import app.zhijuan.provider.common.ProviderUsage
import app.zhijuan.provider.common.ProviderUsageQuality
import app.zhijuan.provider.common.SensitiveProviderText
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChapterTrackingProjectionEndToEndTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: ZhijuanDatabase
    private lateinit var artifactStore: AndroidProtectedArtifactStore
    private lateinit var states: GenerationStateRepository
    private lateinit var drafts: GenerationStreamingDraftRepository
    private lateinit var outputs: GenerationOutputValidationRepository

    @Before
    fun setUp() = runBlocking {
        artifactStore = AndroidProtectedArtifactStore(context)
        cleanArtifacts()
        database = Room.inMemoryDatabaseBuilder(context, ZhijuanDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(LibraryDatabaseGuards.callback)
            .build()
            .also { it.openHelper.writableDatabase }
        states = GenerationStateRepository(database)
        drafts = GenerationStreamingDraftRepository(database, artifactStore)
        outputs = GenerationOutputValidationRepository(database, artifactStore)
        seedBookMemoryAndPriorForeshadow()
        BudgetedGenerationTestSupport.seedBudgetedRequestEnvironment(
            database = database,
            bookId = BOOK_ID,
            connectionId = "connection.tracking",
        )
    }

    @After
    fun tearDown() {
        runCatching { cleanArtifacts() }
        database.close()
    }

    @Test
    fun validProjectionCommitsTimelineAndAppendOnlyForeshadowTransitionsThenReplays() = runBlocking {
        val runtime = createRunningJob("job.tracking.valid", "stage.tracking.valid", 100L)
        val prepared = prepare(runtime, "attempt.tracking.valid", "ledger.tracking.valid", 103L)
        val result = coordinator(110L).execute(
            persistedRequest = prepared,
            adapter = TrackingFakeAdapter(successfulEvents(validTrackingJson(runtime.inputs.source))),
            profile = profile(),
            boundRequest = request(runtime, "attempt.tracking.valid"),
        )

        assertTrue(result is ChapterTrackingProjectionResult.Accepted)
        result as ChapterTrackingProjectionResult.Accepted
        val mapped = ChapterTrackingProjectionPersistenceMapper.map(
            result.tracking,
            ChapterTrackingProjectionMappingSpec(
                bookId = BOOK_ID,
                generationStageId = runtime.stageId,
                modelSnapshotJson = MODEL_SNAPSHOT,
                createdAt = 160L,
            ),
        )
        val draft = ChapterTrackingProjectionCommitDraft(
            source = runtime.inputs.source,
            trackingContentHash = mapped.trackingContentHash,
            projection = mapped.projection,
            timelineEvents = mapped.timelineEvents,
            newForeshadows = mapped.newForeshadows,
            existingForeshadowUpdates = mapped.existingForeshadowUpdates,
            foreshadowTransitions = mapped.foreshadowTransitions,
            usage = FinalUsageCommit(
                source = UsageSource.PROVIDER_REPORTED,
                inputTokens = 140L,
                outputTokens = 90L,
                cachedTokens = null,
                reasoningTokens = null,
                totalTokens = 230L,
            ),
            committedAt = 160L,
        )
        val repository = ChapterTrackingProjectionCommitRepository(database, artifactStore)
        val committed = repository.commit(result.commitPermit, draft)
        val replayed = repository.commit(result.commitPermit, draft)

        assertEquals(false, committed.replayed)
        assertEquals(true, replayed.replayed)
        assertEquals(1L, scalarLong("SELECT COUNT(*) FROM chapter_tracking_projection"))
        assertEquals(1L, scalarLong("SELECT COUNT(*) FROM timeline_event"))
        assertEquals(2L, scalarLong("SELECT COUNT(*) FROM foreshadow_transition"))
        assertEquals(2L, scalarLong("SELECT COUNT(*) FROM foreshadow_item"))
        assertEquals(3L, scalarLong("SELECT COUNT(*) FROM memory_search_document"))
        assertEquals("DEVELOPING", scalarString("SELECT foreshadow_status FROM foreshadow_item WHERE foreshadow_item_id = '$PRIOR_FORESHADOW_ID'"))
        assertEquals(VERSION_2_ID, scalarString("SELECT source_chapter_version_id FROM foreshadow_item WHERE foreshadow_item_id = '$PRIOR_FORESHADOW_ID'"))
        assertEquals("PLANTED", scalarString("SELECT foreshadow_status FROM foreshadow_item WHERE foreshadow_item_id != '$PRIOR_FORESHADOW_ID'"))
        assertEquals("SUCCEEDED", scalarString("SELECT status FROM generation_stage WHERE stage_id = '${runtime.stageId}'"))
        assertEquals("COMPLETED", scalarString("SELECT status FROM generation_job WHERE job_id = '${runtime.jobId}'"))
        assertEquals("FINAL", scalarString("SELECT status FROM usage_ledger WHERE attempt_id = 'attempt.tracking.valid'"))
        assertEquals(230L, scalarLong("SELECT total_tokens FROM usage_ledger WHERE attempt_id = 'attempt.tracking.valid'"))
    }

    @Test
    fun rebuildTrackingCommitAtomicallyCreatesAggregateAndReplays() = runBlocking {
        val runtime = createRunningRebuildTrackingJob()
        val prepared = prepare(runtime, "attempt.tracking.rebuild", "ledger.tracking.rebuild", 133L)
        val accepted = coordinator(140L).execute(
            persistedRequest = prepared,
            adapter = TrackingFakeAdapter(successfulEvents(validTrackingJson(runtime.inputs.source))),
            profile = profile(),
            boundRequest = request(runtime, "attempt.tracking.rebuild"),
        )
        assertTrue(accepted is ChapterTrackingProjectionResult.Accepted)
        accepted as ChapterTrackingProjectionResult.Accepted
        val mapped = ChapterTrackingProjectionPersistenceMapper.map(
            accepted.tracking,
            ChapterTrackingProjectionMappingSpec(
                bookId = BOOK_ID,
                generationStageId = runtime.stageId,
                modelSnapshotJson = MODEL_SNAPSHOT,
                createdAt = 160L,
            ),
        )
        val draft = ChapterTrackingProjectionCommitDraft(
            source = runtime.inputs.source,
            trackingContentHash = mapped.trackingContentHash,
            projection = mapped.projection,
            timelineEvents = mapped.timelineEvents,
            newForeshadows = mapped.newForeshadows,
            existingForeshadowUpdates = mapped.existingForeshadowUpdates,
            foreshadowTransitions = mapped.foreshadowTransitions,
            usage = FinalUsageCommit(
                source = UsageSource.PROVIDER_REPORTED,
                inputTokens = 140L,
                outputTokens = 90L,
                cachedTokens = null,
                reasoningTokens = null,
                totalTokens = 230L,
            ),
            committedAt = 160L,
        )
        val repository = ChapterTrackingProjectionCommitRepository(database, artifactStore)

        val committed = repository.commit(accepted.commitPermit, draft)
        val replayed = repository.commit(accepted.commitPermit, draft)

        assertEquals(false, committed.replayed)
        assertEquals(true, replayed.replayed)
        assertEquals(1L, scalarLong("SELECT COUNT(*) FROM chapter_tracking_projection WHERE status = 'VALID'"))
        assertEquals(1L, scalarLong("SELECT COUNT(*) FROM aggregate_state_projection WHERE status = 'VALID'"))
        assertEquals(1L, scalarLong("SELECT COUNT(*) FROM aggregate_state_projection"))
        assertEquals(2L, scalarLong("SELECT COUNT(*) FROM foreshadow_transition"))
        assertEquals("SUCCEEDED", scalarString("SELECT status FROM generation_stage WHERE stage_id = '${runtime.stageId}'"))
        assertEquals("COMPLETED", scalarString("SELECT status FROM generation_job WHERE job_id = '${runtime.jobId}'"))
        assertEquals("FINAL", scalarString("SELECT status FROM usage_ledger WHERE attempt_id = 'attempt.tracking.rebuild'"))
        val currentPlan = ChapterEditRebuildPlanRepository(database).plan(
            ChapterEditRebuildPlanRequest(
                bookId = BOOK_ID,
                editedChapterId = CHAPTER_2_ID,
                editedVersionId = EDITED_VERSION_2_ID,
            ),
        )
        assertEquals(
            ChapterEditRebuildStepState.ALREADY_SATISFIED,
            currentPlan.steps.single {
                it.type == ChapterEditRebuildStepType.REBUILD_AGGREGATE_STATE && it.chapterIndex == 2
            }.state,
        )
    }

    @Test
    fun retainedChapterReplacementRunsFakeProviderCommitsAggregateAndReplays() = runBlocking {
        val setup = prepareRetainedTrackingReplacement(withFutureAggregateBlocker = false)
        commitSuccessfulTracking(
            runtime = setup.runtime,
            attemptId = "attempt.tracking.retained.new",
            ledgerId = "ledger.tracking.retained.new",
            preparedAt = 173L,
            providerAt = 180L,
            committedAt = 200L,
            replay = true,
        )
        val stageReplay = setup.stageRepository.createNextRetainedTrackingStage(setup.command)
        val currentPlan = ChapterEditRebuildPlanRepository(database).plan(
            ChapterEditRebuildPlanRequest(
                bookId = BOOK_ID,
                editedChapterId = CHAPTER_1_ID,
                editedVersionId = EDITED_VERSION_1_ID,
            ),
        )

        assertEquals(true, stageReplay.replayed)
        assertEquals("STALE", scalarString("SELECT status FROM chapter_tracking_projection WHERE projection_id = '${setup.oldProjectionId}'"))
        assertEquals("STALE", scalarString("SELECT status FROM timeline_event WHERE timeline_event_id = '${setup.oldTimelineId}'"))
        assertEquals(1L, scalarLong("SELECT COUNT(*) FROM chapter_edit_rebuild_tracking_retirement"))
        assertEquals(2L, scalarLong("SELECT COUNT(*) FROM chapter_tracking_projection WHERE status = 'VALID'"))
        assertEquals(1L, scalarLong("SELECT COUNT(*) FROM chapter_tracking_projection WHERE status = 'STALE'"))
        assertEquals(2L, scalarLong("SELECT COUNT(*) FROM aggregate_state_projection WHERE status = 'VALID'"))
        assertEquals(
            ChapterEditRebuildStepState.ALREADY_SATISFIED,
            currentPlan.steps.single {
                it.type == ChapterEditRebuildStepType.REBUILD_STORY_TRACKING && it.chapterIndex == 2
            }.state,
        )
        assertEquals(
            ChapterEditRebuildStepState.ALREADY_SATISFIED,
            currentPlan.steps.single {
                it.type == ChapterEditRebuildStepType.REBUILD_AGGREGATE_STATE && it.chapterIndex == 2
            }.state,
        )
        assertEquals("SUCCEEDED", scalarString("SELECT status FROM generation_stage WHERE stage_id = '${setup.runtime.stageId}'"))
        assertEquals("COMPLETED", scalarString("SELECT status FROM generation_job WHERE job_id = '${setup.runtime.jobId}'"))
    }

    @Test
    fun retainedChapterAggregateFailureRollsBackNewProjectionButKeepsRetirement() = runBlocking {
        val setup = prepareRetainedTrackingReplacement(withFutureAggregateBlocker = true)
        val prepared = prepare(
            setup.runtime,
            "attempt.tracking.retained.rollback",
            "ledger.tracking.retained.rollback",
            173L,
        )
        val accepted = coordinator(180L).execute(
            persistedRequest = prepared,
            adapter = TrackingFakeAdapter(
                successfulEvents(validTrackingWithoutForeshadowOperations(setup.runtime.inputs.source)),
            ),
            profile = profile(),
            boundRequest = request(setup.runtime, "attempt.tracking.retained.rollback"),
        )
        assertTrue(accepted is ChapterTrackingProjectionResult.Accepted)
        accepted as ChapterTrackingProjectionResult.Accepted
        val mapped = ChapterTrackingProjectionPersistenceMapper.map(
            accepted.tracking,
            ChapterTrackingProjectionMappingSpec(
                bookId = BOOK_ID,
                generationStageId = setup.runtime.stageId,
                modelSnapshotJson = MODEL_SNAPSHOT,
                createdAt = 200L,
            ),
        )
        val draft = ChapterTrackingProjectionCommitDraft(
            source = setup.runtime.inputs.source,
            trackingContentHash = mapped.trackingContentHash,
            projection = mapped.projection,
            timelineEvents = mapped.timelineEvents,
            newForeshadows = mapped.newForeshadows,
            existingForeshadowUpdates = mapped.existingForeshadowUpdates,
            foreshadowTransitions = mapped.foreshadowTransitions,
            usage = FinalUsageCommit(
                source = UsageSource.PROVIDER_REPORTED,
                inputTokens = 140L,
                outputTokens = 90L,
                cachedTokens = null,
                reasoningTokens = null,
                totalTokens = 230L,
            ),
            committedAt = 200L,
        )

        val failure = expectFailure {
            ChapterTrackingProjectionCommitRepository(database, artifactStore).commit(
                accepted.commitPermit,
                draft,
            )
        }

        assertTrue(failure is IllegalArgumentException)
        assertEquals(1L, scalarLong("SELECT COUNT(*) FROM chapter_edit_rebuild_tracking_retirement"))
        assertEquals("STALE", scalarString("SELECT status FROM chapter_tracking_projection WHERE projection_id = '${setup.oldProjectionId}'"))
        assertEquals(0L, scalarLong("SELECT COUNT(*) FROM chapter_tracking_projection WHERE chapter_version_id = '$VERSION_2_ID' AND status = 'VALID'"))
        assertEquals(0L, scalarLong("SELECT COUNT(*) FROM timeline_event WHERE source_chapter_version_id = '$VERSION_2_ID' AND status = 'VALID'"))
        assertEquals(1L, scalarLong("SELECT COUNT(*) FROM aggregate_state_projection WHERE through_chapter_index = 1 AND status = 'VALID'"))
        assertEquals(0L, scalarLong("SELECT COUNT(*) FROM aggregate_state_projection WHERE through_chapter_index = 2 AND status = 'VALID'"))
        assertEquals("VALID", scalarString("SELECT memory_status FROM foreshadow_item WHERE foreshadow_item_id = '$HIDDEN_FUTURE_FORESHADOW_ID'"))
        assertEquals("COMMITTING", scalarString("SELECT status FROM generation_stage WHERE stage_id = '${setup.runtime.stageId}'"))
        assertEquals("RUNNING", scalarString("SELECT status FROM generation_job WHERE job_id = '${setup.runtime.jobId}'"))
        assertEquals("PROVISIONAL", scalarString("SELECT status FROM usage_ledger WHERE attempt_id = 'attempt.tracking.retained.rollback'"))
    }

    @Test
    fun thirdRetainedChapterRunsFakeProviderAfterDirectPredecessorAggregateAndReplays() = runBlocking {
        val oldSecond = createRunningJob("job.tracking.retained.old2", "stage.tracking.retained.old2", 20L)
        commitSuccessfulTracking(
            runtime = oldSecond,
            attemptId = "attempt.tracking.retained.old2",
            ledgerId = "ledger.tracking.retained.old2",
            preparedAt = 23L,
            providerAt = 30L,
            committedAt = 50L,
        )
        val oldSecondProjectionId = requireNotNull(
            scalarString("SELECT projection_id FROM chapter_tracking_projection WHERE chapter_version_id = '$VERSION_2_ID'"),
        )
        insertThirdChapterAndMemory()
        val oldThird = createRunningJob(
            jobId = "job.tracking.retained.old3",
            stageId = "stage.tracking.retained.old3",
            createdAt = 55L,
            chapterId = CHAPTER_3_ID,
        )
        commitSuccessfulTracking(
            runtime = oldThird,
            attemptId = "attempt.tracking.retained.old3",
            ledgerId = "ledger.tracking.retained.old3",
            preparedAt = 58L,
            providerAt = 65L,
            committedAt = 95L,
        )
        val oldThirdProjectionId = requireNotNull(
            scalarString("SELECT projection_id FROM chapter_tracking_projection WHERE chapter_version_id = '$VERSION_3_ID'"),
        )
        ChapterUserEditRepository(database).commit(
            ChapterUserEditCommand(
                bookId = BOOK_ID,
                chapterId = CHAPTER_1_ID,
                expectedCurrentVersionId = VERSION_1_ID,
                newVersionId = EDITED_VERSION_1_ID,
                content = EDITED_CHAPTER_1_CONTENT,
                editedAt = 100L,
            ),
        )
        insertEditedChapterOneMemory()
        val execution = ChapterEditRebuildExecutionRepository(database).prepare(
            ChapterEditRebuildExecutionPrepareCommand(
                plan = ChapterEditRebuildPlanRepository(database).plan(
                    ChapterEditRebuildPlanRequest(
                        bookId = BOOK_ID,
                        editedChapterId = CHAPTER_1_ID,
                        editedVersionId = EDITED_VERSION_1_ID,
                    ),
                ),
                rewindId = "rewind.tracking.retained.third.e2e",
                preparedAt = 120L,
            ),
        )
        val stageRepository = ChapterEditRebuildStageRepository(database)
        val first = stageRepository.createFirstTrackingStage(
            ChapterEditRebuildTrackingStageCommand(
                executionId = execution.executionId,
                userIntentJson = "{\"mode\":\"automatic\"}",
                budgetSnapshotJson = "{\"mode\":\"fixture\"}",
                createdAt = 130L,
            ),
        )
        val firstRuntime = activateRebuildTrackingStage(
            jobId = first.jobId,
            stageId = first.stageId,
            inputs = stageRepository.loadTrackingInputsForBoundStage(first.stageId, 131L),
            owner = "third-first",
            activatedAt = 131L,
        )
        commitSuccessfulTracking(
            runtime = firstRuntime,
            attemptId = "attempt.tracking.retained.third.first",
            ledgerId = "ledger.tracking.retained.third.first",
            preparedAt = 133L,
            providerAt = 140L,
            committedAt = 160L,
        )
        val second = stageRepository.createNextRetainedTrackingStage(
            ChapterEditRebuildTrackingStageCommand(
                executionId = execution.executionId,
                userIntentJson = "{\"mode\":\"automatic\"}",
                budgetSnapshotJson = "{\"mode\":\"fixture\"}",
                createdAt = 170L,
            ),
        )
        val secondRuntime = activateRebuildTrackingStage(
            jobId = second.jobId,
            stageId = second.stageId,
            inputs = stageRepository.loadTrackingInputsForBoundStage(second.stageId, 171L),
            owner = "third-second",
            activatedAt = 171L,
        )
        commitSuccessfulTracking(
            runtime = secondRuntime,
            attemptId = "attempt.tracking.retained.third.second",
            ledgerId = "ledger.tracking.retained.third.second",
            preparedAt = 173L,
            providerAt = 180L,
            committedAt = 200L,
        )
        val thirdCommand = ChapterEditRebuildRetainedTrackingStageCommand(
            executionId = execution.executionId,
            targetStepOrdinal = 6,
            userIntentJson = "{\"mode\":\"automatic\"}",
            budgetSnapshotJson = "{\"mode\":\"fixture\"}",
            createdAt = 210L,
        )
        val third = stageRepository.createRetainedTrackingStage(thirdCommand)
        val thirdRuntime = activateRebuildTrackingStage(
            jobId = third.jobId,
            stageId = third.stageId,
            inputs = stageRepository.loadTrackingInputsForBoundStage(third.stageId, 211L),
            owner = "third-target",
            activatedAt = 211L,
        )
        commitSuccessfulTracking(
            runtime = thirdRuntime,
            attemptId = "attempt.tracking.retained.third.target",
            ledgerId = "ledger.tracking.retained.third.target",
            preparedAt = 213L,
            providerAt = 220L,
            committedAt = 240L,
            replay = true,
        )
        val stageReplay = stageRepository.createRetainedTrackingStage(thirdCommand)
        val currentPlan = ChapterEditRebuildPlanRepository(database).plan(
            ChapterEditRebuildPlanRequest(
                bookId = BOOK_ID,
                editedChapterId = CHAPTER_1_ID,
                editedVersionId = EDITED_VERSION_1_ID,
            ),
        )

        assertTrue(stageReplay.replayed)
        assertEquals("STALE", scalarString("SELECT status FROM chapter_tracking_projection WHERE projection_id = '$oldSecondProjectionId'"))
        assertEquals("STALE", scalarString("SELECT status FROM chapter_tracking_projection WHERE projection_id = '$oldThirdProjectionId'"))
        assertEquals(2L, scalarLong("SELECT COUNT(*) FROM chapter_edit_rebuild_tracking_retirement"))
        assertEquals(3L, scalarLong("SELECT COUNT(*) FROM chapter_tracking_projection WHERE status = 'VALID'"))
        assertEquals(3L, scalarLong("SELECT COUNT(*) FROM aggregate_state_projection WHERE status = 'VALID'"))
        assertEquals(
            ChapterEditRebuildStepState.ALREADY_SATISFIED,
            currentPlan.steps.single {
                it.type == ChapterEditRebuildStepType.REBUILD_STORY_TRACKING && it.chapterIndex == 3
            }.state,
        )
        assertEquals(
            ChapterEditRebuildStepState.ALREADY_SATISFIED,
            currentPlan.steps.single {
                it.type == ChapterEditRebuildStepType.REBUILD_AGGREGATE_STATE && it.chapterIndex == 3
            }.state,
        )
        assertEquals("SUCCEEDED", scalarString("SELECT status FROM generation_stage WHERE stage_id = '${third.stageId}'"))
        assertEquals("COMPLETED", scalarString("SELECT status FROM generation_job WHERE job_id = '${third.jobId}'"))
    }

    @Test
    fun tenChapterEditAtThreeRebuildsEveryAffectedTrackingAndAggregateInOrder() = runBlocking {
        val oldProjectionIds = linkedMapOf<Int, String>()
        val oldSecond = createRunningJob(
            jobId = "job.tracking.range.old.2",
            stageId = "stage.tracking.range.old.2",
            createdAt = 20L,
        )
        commitSuccessfulTracking(
            runtime = oldSecond,
            attemptId = "attempt.tracking.range.old.2",
            ledgerId = "ledger.tracking.range.old.2",
            preparedAt = 23L,
            providerAt = 30L,
            committedAt = 60L,
        )
        oldProjectionIds[2] = requireNotNull(
            scalarString(
                "SELECT projection_id FROM chapter_tracking_projection " +
                    "WHERE chapter_version_id = '$VERSION_2_ID' AND status = 'VALID'",
            ),
        )

        for (chapterIndex in 3..10) {
            val base = chapterIndex * 100L
            insertChapterAndMemory(chapterIndex, base)
            val runtime = createRunningJob(
                jobId = "job.tracking.range.old.$chapterIndex",
                stageId = "stage.tracking.range.old.$chapterIndex",
                createdAt = base + 10L,
                chapterId = trackingChapterId(chapterIndex),
            )
            commitSuccessfulTracking(
                runtime = runtime,
                attemptId = "attempt.tracking.range.old.$chapterIndex",
                ledgerId = "ledger.tracking.range.old.$chapterIndex",
                preparedAt = base + 13L,
                providerAt = base + 20L,
                committedAt = base + 50L,
            )
            oldProjectionIds[chapterIndex] = requireNotNull(
                scalarString(
                    "SELECT projection_id FROM chapter_tracking_projection " +
                        "WHERE chapter_version_id = '${trackingVersionId(chapterIndex)}' AND status = 'VALID'",
                ),
            )
        }

        ChapterUserEditRepository(database).commit(
            ChapterUserEditCommand(
                bookId = BOOK_ID,
                chapterId = CHAPTER_3_ID,
                expectedCurrentVersionId = VERSION_3_ID,
                newVersionId = EDITED_VERSION_3_ID,
                content = EDITED_CHAPTER_3_CONTENT,
                editedAt = 1_100L,
            ),
        )
        insertEditedChapterThreeMemory()
        val execution = ChapterEditRebuildExecutionRepository(database).prepare(
            ChapterEditRebuildExecutionPrepareCommand(
                plan = ChapterEditRebuildPlanRepository(database).plan(
                    ChapterEditRebuildPlanRequest(
                        bookId = BOOK_ID,
                        editedChapterId = CHAPTER_3_ID,
                        editedVersionId = EDITED_VERSION_3_ID,
                    ),
                ),
                rewindId = "rewind.tracking.range.edit3",
                preparedAt = 1_120L,
            ),
        )
        val stageRepository = ChapterEditRebuildStageRepository(database)
        val replacementStages = linkedMapOf<Int, String>()
        val first = stageRepository.createFirstTrackingStage(
            ChapterEditRebuildTrackingStageCommand(
                executionId = execution.executionId,
                userIntentJson = "{\"mode\":\"automatic\"}",
                budgetSnapshotJson = "{\"mode\":\"fixture\"}",
                createdAt = 1_130L,
            ),
        )
        replacementStages[3] = first.stageId
        val firstRuntime = activateRebuildTrackingStage(
            jobId = first.jobId,
            stageId = first.stageId,
            inputs = stageRepository.loadTrackingInputsForBoundStage(first.stageId, 1_131L),
            owner = "range-3",
            activatedAt = 1_131L,
        )
        commitSuccessfulTracking(
            runtime = firstRuntime,
            attemptId = "attempt.tracking.range.new.3",
            ledgerId = "ledger.tracking.range.new.3",
            preparedAt = 1_133L,
            providerAt = 1_140L,
            committedAt = 1_170L,
        )

        var lastCommand: ChapterEditRebuildRetainedTrackingStageCommand? = null
        var lastStageId: String? = null
        for (chapterIndex in 4..10) {
            val base = 1_200L + (chapterIndex - 4) * 100L
            val command = ChapterEditRebuildRetainedTrackingStageCommand(
                executionId = execution.executionId,
                targetStepOrdinal = 2 * (chapterIndex - 2),
                userIntentJson = "{\"mode\":\"automatic\"}",
                budgetSnapshotJson = "{\"mode\":\"fixture\"}",
                createdAt = base,
            )
            val created = stageRepository.createRetainedTrackingStage(command)
            replacementStages[chapterIndex] = created.stageId
            val runtime = activateRebuildTrackingStage(
                jobId = created.jobId,
                stageId = created.stageId,
                inputs = stageRepository.loadTrackingInputsForBoundStage(created.stageId, base + 1L),
                owner = "range-$chapterIndex",
                activatedAt = base + 1L,
            )
            commitSuccessfulTracking(
                runtime = runtime,
                attemptId = "attempt.tracking.range.new.$chapterIndex",
                ledgerId = "ledger.tracking.range.new.$chapterIndex",
                preparedAt = base + 3L,
                providerAt = base + 10L,
                committedAt = base + 40L,
            )
            lastCommand = command
            lastStageId = created.stageId
        }

        val replay = stageRepository.createRetainedTrackingStage(requireNotNull(lastCommand))
        val currentPlan = ChapterEditRebuildPlanRepository(database).plan(
            ChapterEditRebuildPlanRequest(
                bookId = BOOK_ID,
                editedChapterId = CHAPTER_3_ID,
                editedVersionId = EDITED_VERSION_3_ID,
            ),
        )

        assertTrue(replay.replayed)
        assertEquals(lastStageId, replay.stageId)
        assertEquals(7L, scalarLong("SELECT COUNT(*) FROM chapter_edit_rebuild_tracking_retirement"))
        assertEquals(
            8L,
            scalarLong(
                "SELECT COUNT(*) FROM chapter_tracking_projection " +
                    "WHERE status = 'STALE' AND chapter_index BETWEEN 3 AND 10",
            ),
        )
        assertEquals(9L, scalarLong("SELECT COUNT(*) FROM chapter_tracking_projection WHERE status = 'VALID'"))
        assertEquals(
            8L,
            scalarLong(
                "SELECT COUNT(*) FROM aggregate_state_projection " +
                    "WHERE status = 'VALID' AND through_chapter_index BETWEEN 3 AND 10",
            ),
        )
        assertEquals(
            oldProjectionIds[2],
            scalarString(
                "SELECT projection_id FROM chapter_tracking_projection " +
                    "WHERE chapter_index = 2 AND status = 'VALID'",
            ),
        )
        for (chapterIndex in 3..10) {
            assertEquals(
                "STALE",
                scalarString(
                    "SELECT status FROM chapter_tracking_projection " +
                        "WHERE projection_id = '${oldProjectionIds.getValue(chapterIndex)}'",
                ),
            )
            assertEquals(
                replacementStages.getValue(chapterIndex),
                scalarString(
                    "SELECT generation_stage_id FROM chapter_tracking_projection " +
                        "WHERE chapter_index = $chapterIndex AND status = 'VALID'",
                ),
            )
            assertEquals(
                ChapterEditRebuildStepState.ALREADY_SATISFIED,
                currentPlan.steps.single {
                    it.type == ChapterEditRebuildStepType.REBUILD_STORY_TRACKING &&
                        it.chapterIndex == chapterIndex
                }.state,
            )
            assertEquals(
                ChapterEditRebuildStepState.ALREADY_SATISFIED,
                currentPlan.steps.single {
                    it.type == ChapterEditRebuildStepType.REBUILD_AGGREGATE_STATE &&
                        it.chapterIndex == chapterIndex
                }.state,
            )
        }
        for (chapterIndex in 4..10) {
            assertEquals(
                trackingVersionId(chapterIndex),
                scalarString(
                    "SELECT current_version_id FROM chapter " +
                        "WHERE chapter_id = '${trackingChapterId(chapterIndex)}'",
                ),
            )
        }
    }

    @Test
    fun rebuildTrackingAndAggregateRollBackTogetherWhenAggregateSourceIsInvalid() = runBlocking {
        val runtime = createFirstChapterRebuildWithFutureForeshadow()
        val prepared = prepare(runtime, "attempt.tracking.rollback", "ledger.tracking.rollback", 133L)
        val accepted = coordinator(140L).execute(
            persistedRequest = prepared,
            adapter = TrackingFakeAdapter(
                successfulEvents(validTrackingWithoutForeshadowOperations(runtime.inputs.source)),
            ),
            profile = profile(),
            boundRequest = request(runtime, "attempt.tracking.rollback"),
        )
        assertTrue(accepted is ChapterTrackingProjectionResult.Accepted)
        accepted as ChapterTrackingProjectionResult.Accepted
        val mapped = ChapterTrackingProjectionPersistenceMapper.map(
            accepted.tracking,
            ChapterTrackingProjectionMappingSpec(
                bookId = BOOK_ID,
                generationStageId = runtime.stageId,
                modelSnapshotJson = MODEL_SNAPSHOT,
                createdAt = 160L,
            ),
        )
        val draft = ChapterTrackingProjectionCommitDraft(
            source = runtime.inputs.source,
            trackingContentHash = mapped.trackingContentHash,
            projection = mapped.projection,
            timelineEvents = mapped.timelineEvents,
            newForeshadows = mapped.newForeshadows,
            existingForeshadowUpdates = mapped.existingForeshadowUpdates,
            foreshadowTransitions = mapped.foreshadowTransitions,
            usage = FinalUsageCommit(
                source = UsageSource.PROVIDER_REPORTED,
                inputTokens = 140L,
                outputTokens = 90L,
                cachedTokens = null,
                reasoningTokens = null,
                totalTokens = 230L,
            ),
            committedAt = 160L,
        )

        val failure = expectFailure {
            ChapterTrackingProjectionCommitRepository(database, artifactStore).commit(
                accepted.commitPermit,
                draft,
            )
        }

        assertTrue(failure is IllegalArgumentException)
        assertEquals(0L, scalarLong("SELECT COUNT(*) FROM chapter_tracking_projection WHERE chapter_version_id = '$EDITED_VERSION_1_ID'"))
        assertEquals(0L, scalarLong("SELECT COUNT(*) FROM timeline_event WHERE source_chapter_version_id = '$EDITED_VERSION_1_ID'"))
        assertEquals(0L, scalarLong("SELECT COUNT(*) FROM foreshadow_transition WHERE source_chapter_version_id = '$EDITED_VERSION_1_ID'"))
        assertEquals(0L, scalarLong("SELECT COUNT(*) FROM aggregate_state_projection"))
        assertEquals("VALID", scalarString("SELECT memory_status FROM foreshadow_item WHERE foreshadow_item_id = '$FUTURE_FORESHADOW_ID'"))
        assertEquals("COMMITTING", scalarString("SELECT status FROM generation_stage WHERE stage_id = '${runtime.stageId}'"))
        assertEquals("RUNNING", scalarString("SELECT status FROM generation_job WHERE job_id = '${runtime.jobId}'"))
        assertEquals("PROVISIONAL", scalarString("SELECT status FROM usage_ledger WHERE attempt_id = 'attempt.tracking.rollback'"))
    }

    @Test
    fun priorForeshadowChangeAfterIntentBlocksBeforeProviderOpen() = runBlocking {
        val runtime = createRunningJob("job.tracking.stale", "stage.tracking.stale", 200L)
        val prepared = prepare(runtime, "attempt.tracking.stale", "ledger.tracking.stale", 203L)
        database.openHelper.writableDatabase.execSQL(
            "UPDATE foreshadow_item SET importance = 79, updated_at = 205 WHERE foreshadow_item_id = '$PRIOR_FORESHADOW_ID'",
        )
        val calls = AtomicInteger(0)

        val failure = expectFailure {
            coordinator(210L).execute(
                persistedRequest = prepared,
                adapter = TrackingFakeAdapter(
                    successfulEvents(validTrackingJson(runtime.inputs.source)),
                    onGenerate = { calls.incrementAndGet() },
                ),
                profile = profile(),
                boundRequest = request(runtime, "attempt.tracking.stale"),
            )
        }

        assertTrue(failure.message.orEmpty().contains("source", ignoreCase = true) || failure.message.orEmpty().contains("snapshot", ignoreCase = true))
        assertEquals(0, calls.get())
        assertEquals("INTENT_RECORDED", scalarString("SELECT status FROM request_attempt WHERE attempt_id = 'attempt.tracking.stale'"))
        assertEquals(0L, scalarLong("SELECT COUNT(*) FROM chapter_tracking_projection"))
        assertEquals(0L, scalarLong("SELECT COUNT(*) FROM timeline_event"))
        assertEquals(0L, scalarLong("SELECT COUNT(*) FROM foreshadow_transition"))
    }

    @Test
    fun unknownForeshadowTransitionRequiresRepairAndCannotMutateLedger() = runBlocking {
        val runtime = createRunningJob("job.tracking.invalid", "stage.tracking.invalid", 300L)
        val prepared = prepare(runtime, "attempt.tracking.invalid", "ledger.tracking.invalid", 303L)
        val invalid = validTrackingJson(runtime.inputs.source).replace(PRIOR_FORESHADOW_ID, "clue.unknown")

        val result = coordinator(310L).execute(
            persistedRequest = prepared,
            adapter = TrackingFakeAdapter(successfulEvents(invalid)),
            profile = profile(),
            boundRequest = request(runtime, "attempt.tracking.invalid"),
        )

        assertTrue(result is ChapterTrackingProjectionResult.RepairRequired)
        result as ChapterTrackingProjectionResult.RepairRequired
        assertTrue(result.report.issues.any { it.path.contains("foreshadowItemId") })
        assertEquals("RETRY_WAIT", scalarString("SELECT status FROM generation_stage WHERE stage_id = '${runtime.stageId}'"))
        assertEquals("FINAL", scalarString("SELECT status FROM usage_ledger WHERE attempt_id = 'attempt.tracking.invalid'"))
        assertEquals("PLANTED", scalarString("SELECT foreshadow_status FROM foreshadow_item WHERE foreshadow_item_id = '$PRIOR_FORESHADOW_ID'"))
        assertEquals(0L, scalarLong("SELECT COUNT(*) FROM chapter_tracking_projection"))
        assertEquals(0L, scalarLong("SELECT COUNT(*) FROM timeline_event"))
        assertEquals(0L, scalarLong("SELECT COUNT(*) FROM foreshadow_transition"))
    }

    private suspend fun createRunningJob(
        jobId: String,
        stageId: String,
        createdAt: Long,
        chapterId: String = CHAPTER_2_ID,
    ): TrackingRuntime {
        val inputs = ChapterTrackingProjectionSourceRepository(database).loadCurrentVersion(chapterId)
        val setup = ChapterTrackingProjectionJobFactory.create(
            ChapterTrackingProjectionJobSpec(
                jobId = jobId,
                stageId = stageId,
                bookId = BOOK_ID,
                userIntentJson = "{\"mode\":\"automatic\"}",
                budgetSnapshotJson = "{\"mode\":\"fixture\"}",
                source = inputs.source,
                createdAt = createdAt,
            ),
        )
        GenerationJobSetupRepository(database).create(setup)
        states.transitionJob(jobId, GenerationJobStatus.CREATED, JobEvent.VALIDATION_PASSED, createdAt + 1L)
        states.transitionStage(stageId, GenerationStageStatus.PENDING, StageEvent.DEPENDENCIES_SATISFIED, createdAt + 1L)
        states.acquireJobLease(jobId, "worker.$jobId", createdAt + 2L)
        states.acquireStageLease(stageId, "worker.$stageId", createdAt + 2L)
        return TrackingRuntime(jobId, stageId, inputs, setup.stages.single().inputVersionHash)
    }

    private suspend fun createRunningRebuildTrackingJob(): TrackingRuntime {
        ChapterUserEditRepository(database).commit(
            ChapterUserEditCommand(
                bookId = BOOK_ID,
                chapterId = CHAPTER_2_ID,
                expectedCurrentVersionId = VERSION_2_ID,
                newVersionId = EDITED_VERSION_2_ID,
                content = EDITED_CHAPTER_2_CONTENT,
                editedAt = 100L,
            ),
        )
        insertEditedChapterMemory()
        val plan = ChapterEditRebuildPlanRepository(database).plan(
            ChapterEditRebuildPlanRequest(
                bookId = BOOK_ID,
                editedChapterId = CHAPTER_2_ID,
                editedVersionId = EDITED_VERSION_2_ID,
            ),
        )
        val execution = ChapterEditRebuildExecutionRepository(database).prepare(
            ChapterEditRebuildExecutionPrepareCommand(
                plan = plan,
                rewindId = "rewind.tracking.e2e",
                preparedAt = 120L,
            ),
        )
        val inputs = ChapterTrackingProjectionSourceRepository(database).loadCurrentVersion(CHAPTER_2_ID)
        val created = ChapterEditRebuildStageRepository(database).createFirstTrackingStage(
            ChapterEditRebuildTrackingStageCommand(
                executionId = execution.executionId,
                userIntentJson = "{\"mode\":\"automatic\"}",
                budgetSnapshotJson = "{\"mode\":\"fixture\"}",
                createdAt = 130L,
            ),
        )
        states.transitionJob(created.jobId, GenerationJobStatus.CREATED, JobEvent.VALIDATION_PASSED, 131L)
        states.transitionStage(
            created.stageId,
            GenerationStageStatus.PENDING,
            StageEvent.DEPENDENCIES_SATISFIED,
            131L,
        )
        states.acquireJobLease(created.jobId, "worker.tracking.rebuild", 132L)
        states.acquireStageLease(created.stageId, "worker.tracking.rebuild.stage", 132L)
        val inputVersionHash = requireNotNull(
            scalarString(
                "SELECT input_version_hash FROM generation_stage WHERE stage_id = '${created.stageId}'",
            ),
        )
        return TrackingRuntime(created.jobId, created.stageId, inputs, inputVersionHash)
    }

    private suspend fun prepareRetainedTrackingReplacement(
        withFutureAggregateBlocker: Boolean,
    ): RetainedTrackingSetup {
        val old = createRunningJob("job.tracking.retained.old", "stage.tracking.retained.old", 20L)
        commitSuccessfulTracking(
            runtime = old,
            attemptId = "attempt.tracking.retained.old",
            ledgerId = "ledger.tracking.retained.old",
            preparedAt = 23L,
            providerAt = 30L,
            committedAt = 50L,
        )
        val oldProjectionId = requireNotNull(
            scalarString("SELECT projection_id FROM chapter_tracking_projection WHERE chapter_version_id = '$VERSION_2_ID'"),
        )
        val oldTimelineId = requireNotNull(
            scalarString("SELECT timeline_event_id FROM timeline_event WHERE source_chapter_version_id = '$VERSION_2_ID'"),
        )
        ChapterUserEditRepository(database).commit(
            ChapterUserEditCommand(
                bookId = BOOK_ID,
                chapterId = CHAPTER_1_ID,
                expectedCurrentVersionId = VERSION_1_ID,
                newVersionId = EDITED_VERSION_1_ID,
                content = EDITED_CHAPTER_1_CONTENT,
                editedAt = 100L,
            ),
        )
        insertEditedChapterOneMemory()
        val plan = ChapterEditRebuildPlanRepository(database).plan(
            ChapterEditRebuildPlanRequest(
                bookId = BOOK_ID,
                editedChapterId = CHAPTER_1_ID,
                editedVersionId = EDITED_VERSION_1_ID,
            ),
        )
        val execution = ChapterEditRebuildExecutionRepository(database).prepare(
            ChapterEditRebuildExecutionPrepareCommand(
                plan = plan,
                rewindId = "rewind.tracking.retained.e2e",
                preparedAt = 120L,
            ),
        )
        val stageRepository = ChapterEditRebuildStageRepository(database)
        val first = stageRepository.createFirstTrackingStage(
            ChapterEditRebuildTrackingStageCommand(
                executionId = execution.executionId,
                userIntentJson = "{\"mode\":\"automatic\"}",
                budgetSnapshotJson = "{\"mode\":\"fixture\"}",
                createdAt = 130L,
            ),
        )
        val firstRuntime = activateRebuildTrackingStage(
            jobId = first.jobId,
            stageId = first.stageId,
            inputs = stageRepository.loadTrackingInputsForBoundStage(first.stageId, 131L),
            owner = "first",
            activatedAt = 131L,
        )
        commitSuccessfulTracking(
            runtime = firstRuntime,
            attemptId = "attempt.tracking.retained.first",
            ledgerId = "ledger.tracking.retained.first",
            preparedAt = 133L,
            providerAt = 140L,
            committedAt = 160L,
        )
        if (withFutureAggregateBlocker) insertHiddenFutureAggregateBlocker()
        val command = ChapterEditRebuildTrackingStageCommand(
            executionId = execution.executionId,
            userIntentJson = "{\"mode\":\"automatic\"}",
            budgetSnapshotJson = "{\"mode\":\"fixture\"}",
            createdAt = 170L,
        )
        val retained = stageRepository.createNextRetainedTrackingStage(command)
        val runtime = activateRebuildTrackingStage(
            jobId = retained.jobId,
            stageId = retained.stageId,
            inputs = stageRepository.loadTrackingInputsForBoundStage(retained.stageId, 171L),
            owner = "retained",
            activatedAt = 171L,
        )
        return RetainedTrackingSetup(
            stageRepository = stageRepository,
            command = command,
            runtime = runtime,
            oldProjectionId = oldProjectionId,
            oldTimelineId = oldTimelineId,
        )
    }

    private suspend fun activateRebuildTrackingStage(
        jobId: String,
        stageId: String,
        inputs: ChapterTrackingProjectionInputs,
        owner: String,
        activatedAt: Long,
    ): TrackingRuntime {
        states.transitionJob(jobId, GenerationJobStatus.CREATED, JobEvent.VALIDATION_PASSED, activatedAt)
        states.transitionStage(
            stageId,
            GenerationStageStatus.PENDING,
            StageEvent.DEPENDENCIES_SATISFIED,
            activatedAt,
        )
        states.acquireJobLease(jobId, "worker.tracking.$owner", activatedAt + 1L)
        states.acquireStageLease(stageId, "worker.tracking.$owner.stage", activatedAt + 1L)
        val inputVersionHash = requireNotNull(
            scalarString("SELECT input_version_hash FROM generation_stage WHERE stage_id = '$stageId'"),
        )
        return TrackingRuntime(jobId, stageId, inputs, inputVersionHash)
    }

    private suspend fun commitSuccessfulTracking(
        runtime: TrackingRuntime,
        attemptId: String,
        ledgerId: String,
        preparedAt: Long,
        providerAt: Long,
        committedAt: Long,
        replay: Boolean = false,
    ) {
        val prepared = prepare(runtime, attemptId, ledgerId, preparedAt)
        val accepted = coordinator(providerAt).execute(
            persistedRequest = prepared,
            adapter = TrackingFakeAdapter(
                successfulEvents(validTrackingWithoutForeshadowOperations(runtime.inputs.source)),
            ),
            profile = profile(),
            boundRequest = request(runtime, attemptId),
        )
        assertTrue(accepted is ChapterTrackingProjectionResult.Accepted)
        accepted as ChapterTrackingProjectionResult.Accepted
        val mapped = ChapterTrackingProjectionPersistenceMapper.map(
            accepted.tracking,
            ChapterTrackingProjectionMappingSpec(
                bookId = BOOK_ID,
                generationStageId = runtime.stageId,
                modelSnapshotJson = MODEL_SNAPSHOT,
                createdAt = committedAt,
            ),
        )
        val draft = ChapterTrackingProjectionCommitDraft(
            source = runtime.inputs.source,
            trackingContentHash = mapped.trackingContentHash,
            projection = mapped.projection,
            timelineEvents = mapped.timelineEvents,
            newForeshadows = mapped.newForeshadows,
            existingForeshadowUpdates = mapped.existingForeshadowUpdates,
            foreshadowTransitions = mapped.foreshadowTransitions,
            usage = FinalUsageCommit(
                source = UsageSource.PROVIDER_REPORTED,
                inputTokens = 140L,
                outputTokens = 90L,
                cachedTokens = null,
                reasoningTokens = null,
                totalTokens = 230L,
            ),
            committedAt = committedAt,
        )
        val repository = ChapterTrackingProjectionCommitRepository(database, artifactStore)
        assertEquals(false, repository.commit(accepted.commitPermit, draft).replayed)
        if (replay) assertEquals(true, repository.commit(accepted.commitPermit, draft).replayed)
    }

    private suspend fun createFirstChapterRebuildWithFutureForeshadow(): TrackingRuntime {
        ChapterUserEditRepository(database).commit(
            ChapterUserEditCommand(
                bookId = BOOK_ID,
                chapterId = CHAPTER_1_ID,
                expectedCurrentVersionId = VERSION_1_ID,
                newVersionId = EDITED_VERSION_1_ID,
                content = EDITED_CHAPTER_1_CONTENT,
                editedAt = 100L,
            ),
        )
        val summary = ChapterSummaryEntity(
            chapterSummaryId = "summary.tracking.edit1",
            bookId = BOOK_ID,
            chapterVersionId = EDITED_VERSION_1_ID,
            chapterIndex = 1,
            schemaVersion = 1,
            summaryJson = "{}",
            importance = 80,
            status = DerivedDataStatus.VALID,
            modelSnapshotJson = MODEL_SNAPSHOT,
            createdAt = 110L,
            updatedAt = 110L,
        )
        val futureForeshadow = ForeshadowItemEntity(
            foreshadowItemId = FUTURE_FORESHADOW_ID,
            bookId = BOOK_ID,
            description = "第二章来源的未来线索",
            foreshadowStatus = ForeshadowStatus.PLANTED,
            memoryStatus = DerivedDataStatus.VALID,
            targetStartChapterIndex = 3,
            targetEndChapterIndex = 6,
            sourceChapterVersionId = VERSION_2_ID,
            plantedChapterVersionId = VERSION_2_ID,
            resolvedChapterVersionId = null,
            visibleEntityIdsJson = "[\"char.lin\"]",
            importance = 70,
            source = MemorySource.CHAPTER_EXTRACTION,
            createdAt = 111L,
            updatedAt = 111L,
        )
        val sql = database.openHelper.writableDatabase
        sql.execSQL(
            """
            INSERT INTO chapter_summary VALUES (
              '${summary.chapterSummaryId}','$BOOK_ID','$EDITED_VERSION_1_ID',1,1,'{}',80,
              'VALID','$MODEL_SNAPSHOT',110,110
            )
            """.trimIndent(),
        )
        sql.execSQL(
            """
            INSERT INTO foreshadow_item VALUES (
              '$FUTURE_FORESHADOW_ID','$BOOK_ID','第二章来源的未来线索','PLANTED','VALID',3,6,
              '$VERSION_2_ID','$VERSION_2_ID',NULL,'["char.lin"]',70,'CHAPTER_EXTRACTION',111,111
            )
            """.trimIndent(),
        )
        val knownEntities = listOf(
            StoryEntity(
                entityId = "char.lin",
                bookId = BOOK_ID,
                entityType = StoryEntityType.CHARACTER,
                canonicalName = "林澜",
                aliasesJson = "[]",
                stableDefinitionJson = "{\"ageYears\":22,\"adultStatus\":\"CONFIRMED_ADULT\",\"realIdentifiablePerson\":false}",
                adultStatus = AdultStatus.CONFIRMED_ADULT,
                ageYears = 22,
                sourceBibleRevisionId = BIBLE_ID,
                createdAt = 5L,
                updatedAt = 5L,
            ),
            StoryEntity(
                entityId = "loc.hall",
                bookId = BOOK_ID,
                entityType = StoryEntityType.LOCATION,
                canonicalName = "旧厅",
                aliasesJson = "[]",
                stableDefinitionJson = "{}",
                adultStatus = AdultStatus.NOT_APPLICABLE,
                ageYears = null,
                sourceBibleRevisionId = BIBLE_ID,
                createdAt = 5L,
                updatedAt = 5L,
            ),
        )
        val source = ChapterTrackingProjectionSourceV1(
            chapterVersionId = EDITED_VERSION_1_ID,
            chapterContentHash = sha256(EDITED_CHAPTER_1_CONTENT),
            chapterId = CHAPTER_1_ID,
            chapterIndex = 1,
            memorySnapshotHash = ChapterTrackingProjectionSourceRepository.memorySnapshotHash(
                summary,
                emptyList(),
                emptyList(),
            ),
            priorForeshadowSnapshotHash = ChapterTrackingProjectionSourceRepository.foreshadowSnapshotHash(
                listOf(futureForeshadow),
            ),
            knownEntitySnapshotHash = ChapterTrackingProjectionSourceRepository.entitySnapshotHash(knownEntities),
        )
        val inputs = ChapterTrackingProjectionInputs(
            source = source,
            chapterContent = EDITED_CHAPTER_1_CONTENT,
            summary = summary,
            entityEvents = emptyList(),
            canonFacts = emptyList(),
            knownEntities = knownEntities,
            priorForeshadows = listOf(futureForeshadow),
        )
        val plan = ChapterEditRebuildPlanRepository(database).plan(
            ChapterEditRebuildPlanRequest(
                bookId = BOOK_ID,
                editedChapterId = CHAPTER_1_ID,
                editedVersionId = EDITED_VERSION_1_ID,
            ),
        )
        val execution = ChapterEditRebuildExecutionRepository(database).prepare(
            ChapterEditRebuildExecutionPrepareCommand(
                plan = plan,
                rewindId = "rewind.tracking.rollback",
                preparedAt = 120L,
            ),
        )
        val created = ChapterEditRebuildStageRepository(database).createFirstTrackingStage(
            ChapterEditRebuildTrackingStageCommand(
                executionId = execution.executionId,
                userIntentJson = "{\"mode\":\"automatic\"}",
                budgetSnapshotJson = "{\"mode\":\"fixture\"}",
                createdAt = 130L,
            ),
        )
        states.transitionJob(created.jobId, GenerationJobStatus.CREATED, JobEvent.VALIDATION_PASSED, 131L)
        states.transitionStage(
            created.stageId,
            GenerationStageStatus.PENDING,
            StageEvent.DEPENDENCIES_SATISFIED,
            131L,
        )
        states.acquireJobLease(created.jobId, "worker.tracking.rollback", 132L)
        states.acquireStageLease(created.stageId, "worker.tracking.rollback.stage", 132L)
        val inputVersionHash = requireNotNull(
            scalarString(
                "SELECT input_version_hash FROM generation_stage WHERE stage_id = '${created.stageId}'",
            ),
        )
        return TrackingRuntime(created.jobId, created.stageId, inputs, inputVersionHash)
    }

    private fun insertEditedChapterMemory() {
        val sql = database.openHelper.writableDatabase
        val editedHash = sha256(EDITED_CHAPTER_2_CONTENT)
        sql.execSQL(
            """
            INSERT INTO chapter_summary VALUES (
              'summary.tracking.edit','$BOOK_ID','$EDITED_VERSION_2_ID',2,1,
              '{"schemaVersion":1,"sourceChapterContentHash":"$editedHash","objectiveOutcome":"重新打开夹层门","keyEvents":["听见银铃","打开夹层门"],"decisions":[],"relationshipChanges":[],"endingState":"林澜持有双层封蜡信封","unresolvedQuestions":["第二层封蜡来自谁"]}',
              88,'VALID','$MODEL_SNAPSHOT',110,110
            )
            """.trimIndent(),
        )
        sql.execSQL(
            """
            INSERT INTO entity_event VALUES (
              'event.tracking.edit','$BOOK_ID','char.lin','$EDITED_VERSION_2_ID',2000001,'knowledge',NULL,
              '{"value":"银铃与夹层门机关相关","relatedEntityId":null}',
              '第二夜',1000000,'STORY_CANON','{"source":"chapter-memory.v1","evidence":"重建开门顺序"}','VALID',110
            )
            """.trimIndent(),
        )
        sql.execSQL(
            """
            INSERT INTO canon_fact VALUES (
              'fact.tracking.edit','$BOOK_ID','char.lin','林澜持有双层封蜡信封',
              '{"schemaVersion":1,"kind":"POSSESSION","confidenceMicros":1000000}',
              'STORY_CANON','{"fromChapter":2,"throughChapter":null}','$EDITED_VERSION_2_ID',NULL,2000002,NULL,NULL,'VALID',110
            )
            """.trimIndent(),
        )
    }

    private fun insertEditedChapterOneMemory() {
        val editedHash = sha256(EDITED_CHAPTER_1_CONTENT)
        database.openHelper.writableDatabase.execSQL(
            """
            INSERT INTO chapter_summary VALUES (
              'summary.tracking.retained.edit1','$BOOK_ID','$EDITED_VERSION_1_ID',1,1,
              '{"schemaVersion":1,"sourceChapterContentHash":"$editedHash","objectiveOutcome":"重新检查旧厅","keyEvents":["检查银铃机关"],"decisions":[],"relationshipChanges":[],"endingState":"林澜离开旧厅","unresolvedQuestions":[]}',
              80,'VALID','$MODEL_SNAPSHOT',110,110
            )
            """.trimIndent(),
        )
    }

    private fun insertHiddenFutureAggregateBlocker() {
        val sql = database.openHelper.writableDatabase
        val content = "尚未正式提交的未来章节夹具。"
        val contentHash = sha256(content)
        sql.execSQL(
            "INSERT INTO chapter VALUES ('$HIDDEN_FUTURE_CHAPTER_ID','$BOOK_ID',3,'隐藏未来章','隐藏未来章','READY',NULL,'VALID',165,165)",
        )
        sql.execSQL(
            """
            INSERT INTO chapter_version VALUES (
              '$HIDDEN_FUTURE_VERSION_ID','$HIDDEN_FUTURE_CHAPTER_ID',1,?,?, '$contentHash',
              'AI_GENERATED',NULL,NULL,'$MODEL_SNAPSHOT',165
            )
            """.trimIndent(),
            arrayOf<Any>(content, content.length),
        )
        sql.execSQL(
            """
            INSERT INTO foreshadow_item VALUES (
              '$HIDDEN_FUTURE_FORESHADOW_ID','$BOOK_ID','隐藏未来来源不得进入第二章聚合','PLANTED','VALID',4,8,
              '$HIDDEN_FUTURE_VERSION_ID','$HIDDEN_FUTURE_VERSION_ID',NULL,'["char.lin"]',75,
              'CHAPTER_EXTRACTION',165,165
            )
            """.trimIndent(),
        )
    }

    private suspend fun prepare(runtime: TrackingRuntime, attemptId: String, ledgerId: String, createdAt: Long) =
        drafts.prepareBeforeSend(
            RequestIntentDraft(
                attemptId = attemptId,
                usageLedgerId = ledgerId,
                stageId = runtime.stageId,
                retryParentAttemptId = null,
                connectionSnapshotJson = "{\"referenceId\":\"fixture-connection\"}",
                modelSnapshotJson = MODEL_SNAPSHOT,
                protocolSnapshotJson = "{\"protocol\":\"fixture\"}",
                inputHash = runtime.inputVersionHash,
                streamDraftRef = null,
                createdAt = createdAt,
            ),
            BudgetedGenerationTestSupport.budgetedDraft(
                attemptId = attemptId,
                connectionId = "connection.tracking",
            ),
            requireNotNull(states.findStage(runtime.stageId)?.leaseToken),
        )

    private fun request(runtime: TrackingRuntime, attemptId: String) =
        ChapterTrackingProjectionRequestFactory.create(
            ChapterTrackingProjectionRequestSpec(
                requestId = "request.$attemptId",
                generationId = runtime.jobId,
                stageId = runtime.stageId,
                attemptId = attemptId,
                modelId = ProviderModelId.from("local-fake"),
                inputs = runtime.inputs,
                maximumOutputTokens = 2_048,
                timeouts = ProviderTimeoutPolicy(1_000, 1_000, 1_000, 2_000),
                idempotencyKey = "provider.$attemptId",
            ),
        )

    private fun coordinator(startAt: Long) = ChapterTrackingProjectionCoordinator(
        executor = AuditedStreamingProviderExecutor(drafts, outputs, TrackingClock(startAt)),
        validation = StructuredOutputValidationCoordinator(outputs),
        clock = TrackingClock(startAt + 20L),
    )

    private fun successfulEvents(json: String) = listOf(
        ProviderStreamEvent.Started(),
        ProviderStreamEvent.StructuredDelta(SensitiveProviderText.from(json)),
        ProviderStreamEvent.UsageUpdate(
            ProviderUsage(
                inputTokens = 140L,
                outputTokens = 90L,
                cachedInputTokens = null,
                cachedWriteTokens = null,
                reasoningTokens = null,
                totalTokens = 230L,
                quality = ProviderUsageQuality.PROVIDER_REPORTED,
            ),
        ),
        ProviderStreamEvent.Completed(ProviderFinishReason.STOP),
    )

    private fun validTrackingJson(source: ChapterTrackingProjectionSourceV1): String = """
        {
          "schemaVersion":1,
          "sourceChapterVersionId":"${source.chapterVersionId}",
          "sourceChapterContentHash":"${source.chapterContentHash}",
          "chapterId":"${source.chapterId}",
          "chapterIndex":${source.chapterIndex},
          "memorySnapshotHash":"${source.memorySnapshotHash}",
          "priorForeshadowSnapshotHash":"${source.priorForeshadowSnapshotHash}",
          "knownEntitySnapshotHash":"${source.knownEntitySnapshotHash}",
          "timelineEvents":[{
            "name":"林澜在旧厅打开夹层门",
            "participantEntityIds":["char.lin"],
            "locationEntityId":"loc.hall",
            "storyTimeExpression":"第二夜子时前",
            "constraints":["开门发生在银铃第二次响起之后"],
            "evidence":"正文明确写出听铃后开门的顺序"
          }],
          "foreshadowOperations":[{
            "operation":"DEVELOP",
            "foreshadowItemId":"$PRIOR_FORESHADOW_ID",
            "description":"旧厅夹层门后的银铃声",
            "targetStartChapterIndex":null,
            "targetEndChapterIndex":null,
            "visibleEntityIds":["char.lin"],
            "importance":85,
            "fromStatus":"PLANTED",
            "confidenceMicros":960000,
            "evidence":"银铃与夹层门的机关形成直接关联"
          },{
            "operation":"PLANT",
            "foreshadowItemId":null,
            "description":"信封上的两层不同封蜡",
            "targetStartChapterIndex":3,
            "targetEndChapterIndex":8,
            "visibleEntityIds":["char.lin"],
            "importance":72,
            "fromStatus":null,
            "confidenceMicros":930000,
            "evidence":"正文用近距离观察明确写出两层印记"
          }]
        }
    """.trimIndent()

    private fun validTrackingWithoutForeshadowOperations(source: ChapterTrackingProjectionSourceV1): String = """
        {
          "schemaVersion":1,
          "sourceChapterVersionId":"${source.chapterVersionId}",
          "sourceChapterContentHash":"${source.chapterContentHash}",
          "chapterId":"${source.chapterId}",
          "chapterIndex":${source.chapterIndex},
          "memorySnapshotHash":"${source.memorySnapshotHash}",
          "priorForeshadowSnapshotHash":"${source.priorForeshadowSnapshotHash}",
          "knownEntitySnapshotHash":"${source.knownEntitySnapshotHash}",
          "timelineEvents":[{
            "name":"林澜再次检查旧厅",
            "participantEntityIds":["char.lin"],
            "locationEntityId":"loc.hall",
            "storyTimeExpression":"第一夜",
            "constraints":["检查发生在离开旧厅前"],
            "evidence":"正文明确写出检查顺序"
          }],
          "foreshadowOperations":[]
        }
    """.trimIndent()

    private fun seedBookMemoryAndPriorForeshadow() {
        val sql = database.openHelper.writableDatabase
        sql.execSQL("INSERT INTO book_creation_snapshot VALUES ('snapshot.tracking','{}','{}','{}','{}','{}','{}',1,'prompt-tracking',1,'snapshot-hash',1)")
        sql.execSQL(
            """
            INSERT INTO book (
              book_id,creation_snapshot_id,title,title_source,status,length_mode,target_characters,target_chapters,
              minimum_chapters,length_policy_schema_version,branched_from_book_id,branched_from_chapter_version_id,
              completed_chapter_count,generation_status_summary,archived_at,deleted_at,created_at,updated_at
            ) VALUES ('$BOOK_ID','snapshot.tracking','投影测试','USER','DRAFT','SHORT',80000,80,80,1,NULL,NULL,2,'tracking',NULL,NULL,1,1)
            """.trimIndent(),
        )
        insertChapter(sql, CHAPTER_1_ID, 1, VERSION_1_ID, CHAPTER_1_CONTENT, 2L)
        insertChapter(sql, CHAPTER_2_ID, 2, VERSION_2_ID, CHAPTER_2_CONTENT, 3L)
        sql.execSQL(
            """
            INSERT INTO story_bible_revision (
              bible_revision_id,book_id,revision_no,parent_revision_id,source,schema_version,
              content_control_schema_version,payload_json,content_hash,generation_stage_id,created_at
            ) VALUES ('$BIBLE_ID','$BOOK_ID',1,NULL,'AI_GENERATED',1,1,'{}','bible-hash',NULL,4)
            """.trimIndent(),
        )
        sql.execSQL("INSERT INTO book_memory_head VALUES ('$BOOK_ID','$BIBLE_ID',NULL,4)")
        sql.execSQL(
            """
            INSERT INTO story_entity VALUES (
              'char.lin','$BOOK_ID','CHARACTER','林澜','[]','{"ageYears":22,"adultStatus":"CONFIRMED_ADULT","realIdentifiablePerson":false}',
              'CONFIRMED_ADULT',22,'$BIBLE_ID',5,5,NULL
            )
            """.trimIndent(),
        )
        sql.execSQL(
            """
            INSERT INTO story_entity VALUES (
              'loc.hall','$BOOK_ID','LOCATION','旧厅','[]','{}','NOT_APPLICABLE',NULL,'$BIBLE_ID',5,5,NULL
            )
            """.trimIndent(),
        )
        sql.execSQL(
            """
            INSERT INTO chapter_summary VALUES (
              'summary.tracking','$BOOK_ID','$VERSION_2_ID',2,1,
              '{"schemaVersion":1,"sourceChapterContentHash":"${sha256(CHAPTER_2_CONTENT)}","objectiveOutcome":"打开夹层门","keyEvents":["听见银铃","打开夹层门"],"decisions":[],"relationshipChanges":[],"endingState":"林澜持有双层封蜡信封","unresolvedQuestions":["第二层封蜡来自谁"]}',
              88,'VALID','$MODEL_SNAPSHOT',6,6
            )
            """.trimIndent(),
        )
        sql.execSQL(
            """
            INSERT INTO entity_event VALUES (
              'event.tracking','$BOOK_ID','char.lin','$VERSION_2_ID',2000001,'knowledge',NULL,
              '{"value":"银铃与夹层门机关相关","relatedEntityId":null}',
              '第二夜',1000000,'STORY_CANON','{"source":"chapter-memory.v1","evidence":"开门顺序"}','VALID',6
            )
            """.trimIndent(),
        )
        sql.execSQL(
            """
            INSERT INTO canon_fact VALUES (
              'fact.tracking','$BOOK_ID','char.lin','林澜持有双层封蜡信封',
              '{"schemaVersion":1,"kind":"POSSESSION","confidenceMicros":1000000}',
              'STORY_CANON','{"fromChapter":2,"throughChapter":null}','$VERSION_2_ID',NULL,2000002,NULL,NULL,'VALID',6
            )
            """.trimIndent(),
        )
        sql.execSQL(
            """
            INSERT INTO foreshadow_item VALUES (
              '$PRIOR_FORESHADOW_ID','$BOOK_ID','旧厅夹层门后的银铃声','PLANTED','VALID',2,6,
              '$VERSION_1_ID','$VERSION_1_ID',NULL,'["char.lin"]',85,'CHAPTER_EXTRACTION',5,5
            )
            """.trimIndent(),
        )
    }

    private fun insertThirdChapterAndMemory() {
        val sql = database.openHelper.writableDatabase
        insertChapter(sql, CHAPTER_3_ID, 3, VERSION_3_ID, CHAPTER_3_CONTENT, 7L)
        sql.execSQL("UPDATE book SET completed_chapter_count = 3, updated_at = 7 WHERE book_id = '$BOOK_ID'")
        sql.execSQL(
            """
            INSERT INTO chapter_summary VALUES (
              'summary.tracking.3','$BOOK_ID','$VERSION_3_ID',3,1,
              '{"schemaVersion":1,"sourceChapterContentHash":"${sha256(CHAPTER_3_CONTENT)}","objectiveOutcome":"确认旧厅暗门通向钟楼","keyEvents":["穿过暗门","看见钟楼"],"decisions":[],"relationshipChanges":[],"endingState":"林澜抵达钟楼下层","unresolvedQuestions":["钟楼上层是谁"]}',
              86,'VALID','$MODEL_SNAPSHOT',8,8
            )
            """.trimIndent(),
        )
    }

    private fun insertChapterAndMemory(chapterIndex: Int, createdAt: Long) {
        val sql = database.openHelper.writableDatabase
        val chapterId = trackingChapterId(chapterIndex)
        val versionId = trackingVersionId(chapterIndex)
        val content = trackingChapterContent(chapterIndex)
        insertChapter(sql, chapterId, chapterIndex, versionId, content, createdAt)
        sql.execSQL(
            "UPDATE book SET completed_chapter_count = $chapterIndex, " +
                "updated_at = $createdAt WHERE book_id = '$BOOK_ID'",
        )
        sql.execSQL(
            """
            INSERT INTO chapter_summary VALUES (
              'summary.tracking.$chapterIndex','$BOOK_ID','$versionId',$chapterIndex,1,
              '{"schemaVersion":1,"sourceChapterContentHash":"${sha256(content)}","objectiveOutcome":"chapter $chapterIndex completed","keyEvents":["chapter $chapterIndex event"],"decisions":[],"relationshipChanges":[],"endingState":"chapter $chapterIndex ending","unresolvedQuestions":[]}',
              86,'VALID','$MODEL_SNAPSHOT',$createdAt,$createdAt
            )
            """.trimIndent(),
        )
    }

    private fun insertEditedChapterThreeMemory() {
        val editedHash = sha256(EDITED_CHAPTER_3_CONTENT)
        database.openHelper.writableDatabase.execSQL(
            """
            INSERT INTO chapter_summary VALUES (
              'summary.tracking.range.edit3','$BOOK_ID','$EDITED_VERSION_3_ID',3,1,
              '{"schemaVersion":1,"sourceChapterContentHash":"$editedHash","objectiveOutcome":"edited chapter 3 completed","keyEvents":["edited chapter 3 event"],"decisions":[],"relationshipChanges":[],"endingState":"edited chapter 3 ending","unresolvedQuestions":[]}',
              90,'VALID','$MODEL_SNAPSHOT',1110,1110
            )
            """.trimIndent(),
        )
    }

    private fun trackingChapterId(chapterIndex: Int): String = "chapter.tracking.$chapterIndex"

    private fun trackingVersionId(chapterIndex: Int): String = "version.tracking.$chapterIndex"

    private fun trackingChapterContent(chapterIndex: Int): String = when (chapterIndex) {
        3 -> CHAPTER_3_CONTENT
        else -> "Chapter $chapterIndex preserves its committed body while derived state is rebuilt."
    }

    private fun insertChapter(
        sql: androidx.sqlite.db.SupportSQLiteDatabase,
        chapterId: String,
        chapterIndex: Int,
        versionId: String,
        content: String,
        createdAt: Long,
    ) {
        val hash = sha256(content)
        sql.execSQL(
            "INSERT INTO chapter VALUES ('$chapterId','$BOOK_ID',$chapterIndex,'第${chapterIndex}章','第${chapterIndex}章','READY',NULL,'VALID',$createdAt,$createdAt)",
        )
        sql.execSQL(
            """
            INSERT INTO chapter_version VALUES (
              '$versionId','$chapterId',1,?,?, '$hash','AI_GENERATED',NULL,NULL,'$MODEL_SNAPSHOT',$createdAt
            )
            """.trimIndent(),
            arrayOf<Any>(content, content.length),
        )
        sql.execSQL("UPDATE chapter SET current_version_id = '$versionId' WHERE chapter_id = '$chapterId'")
    }

    private fun profile() = ProviderConnectionProfile.create(
        connectionId = "connection.tracking",
        protocol = ProviderProtocol.OPENAI_CHAT_COMPAT,
        baseUrl = "https://example.invalid",
    )

    private fun scalarString(query: String): String? =
        database.openHelper.readableDatabase.query(query).use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
        }

    private fun scalarLong(query: String): Long? =
        database.openHelper.readableDatabase.query(query).use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
        }

    private fun cleanArtifacts() {
        artifactStore.unlockAfterAuthentication()
        artifactStore.listArtifactReferenceIds().forEach(artifactStore::delete)
    }

    private suspend fun expectFailure(block: suspend () -> Unit): Throwable = try {
        block()
        throw AssertionError("Expected operation to fail.")
    } catch (error: Throwable) {
        if (error is AssertionError && error.message == "Expected operation to fail.") throw error
        error
    }

    private data class TrackingRuntime(
        val jobId: String,
        val stageId: String,
        val inputs: ChapterTrackingProjectionInputs,
        val inputVersionHash: String,
    )

    private data class RetainedTrackingSetup(
        val stageRepository: ChapterEditRebuildStageRepository,
        val command: ChapterEditRebuildTrackingStageCommand,
        val runtime: TrackingRuntime,
        val oldProjectionId: String,
        val oldTimelineId: String,
    )

    private companion object {
        const val BOOK_ID = "book.tracking"
        const val BIBLE_ID = "bible.tracking"
        const val CHAPTER_1_ID = "chapter.tracking.1"
        const val CHAPTER_2_ID = "chapter.tracking.2"
        const val CHAPTER_3_ID = "chapter.tracking.3"
        const val VERSION_1_ID = "version.tracking.1"
        const val VERSION_2_ID = "version.tracking.2"
        const val VERSION_3_ID = "version.tracking.3"
        const val EDITED_VERSION_1_ID = "version.tracking.1.edit"
        const val EDITED_VERSION_2_ID = "version.tracking.2.edit"
        const val EDITED_VERSION_3_ID = "version.tracking.3.edit"
        const val PRIOR_FORESHADOW_ID = "clue.prior"
        const val FUTURE_FORESHADOW_ID = "clue.future.invalid"
        const val HIDDEN_FUTURE_CHAPTER_ID = "chapter.tracking.hidden.3"
        const val HIDDEN_FUTURE_VERSION_ID = "version.tracking.hidden.3"
        const val HIDDEN_FUTURE_FORESHADOW_ID = "clue.future.retained.invalid"
        const val CHAPTER_1_CONTENT = "林澜在旧厅外第一次听见夹层后的银铃声。"
        const val CHAPTER_2_CONTENT = "第二夜，银铃再次响起。林澜确认响声来自夹层门的机关，随后打开门并发现一封带有两层不同封蜡的信。"
        const val CHAPTER_3_CONTENT = "林澜穿过夹层暗门抵达钟楼下层，确认银铃来自上方。"
        const val EDITED_CHAPTER_1_CONTENT = "林澜回到旧厅，再次检查夹层外的银铃机关后离开。"
        const val EDITED_CHAPTER_2_CONTENT = "第二夜，银铃再次响起。林澜重新确认机关位置，打开夹层门并带走一封有两层不同封蜡的信。"
        const val EDITED_CHAPTER_3_CONTENT =
            "Edited chapter 3 replaces its prior committed body and invalidates derived state."
        const val MODEL_SNAPSHOT = "{\"model\":\"local-fake\"}"

        fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}

private class TrackingClock(startAt: Long) : GenerationExecutionClock {
    private val next = AtomicLong(startAt)
    override fun nowMillis(): Long = next.getAndIncrement()
}

private class TrackingFakeAdapter(
    private val events: List<ProviderStreamEvent>,
    private val onGenerate: () -> Unit = {},
) : ProviderAdapter {
    override val protocol = ProviderProtocol.OPENAI_CHAT_COMPAT
    override val adapterVersion = "tracking-test-1"

    override suspend fun testConnection(profile: ProviderConnectionProfile): ConnectionTestResult = error("Not used")
    override suspend fun listModels(profile: ProviderConnectionProfile): ModelListResult = error("Not used")
    override suspend fun getCapabilities(profile: ProviderConnectionProfile, modelId: ProviderModelId): CapabilityResult = error("Not used")
    override fun generate(profile: ProviderConnectionProfile, request: GenerationRequest): Flow<ProviderStreamEvent> = flow {
        onGenerate()
        events.forEach { emit(it) }
    }
    override suspend fun cancel(profile: ProviderConnectionProfile, requestId: String): ProviderCancellationResult =
        ProviderCancellationResult.ALREADY_TERMINAL
}

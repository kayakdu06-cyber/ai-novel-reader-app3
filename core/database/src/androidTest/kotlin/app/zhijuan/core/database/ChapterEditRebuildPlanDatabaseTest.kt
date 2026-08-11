package app.zhijuan.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.zhijuan.core.database.generation.ChapterEditRebuildEditedMemoryStageCommand
import app.zhijuan.core.database.generation.ChapterEditRebuildStageBindingV1
import app.zhijuan.core.database.generation.ChapterEditRebuildStageRepository
import app.zhijuan.core.database.generation.ChapterEditRebuildTrackingStageCommand
import app.zhijuan.core.database.generation.ChapterEditRebuildRetainedTrackingStageCommand
import app.zhijuan.core.database.generation.ChapterMemoryExtractionJobFactory
import app.zhijuan.core.database.generation.ChapterTrackingProjectionJobFactory
import app.zhijuan.core.database.generation.ChapterTrackingProjectionJobSpec
import app.zhijuan.core.database.generation.ChapterTrackingProjectionSourceRepository
import app.zhijuan.core.database.generation.GenerationJobSetupRepository
import app.zhijuan.core.database.generation.deterministicJobId
import app.zhijuan.core.database.generation.deterministicStageId
import app.zhijuan.core.database.library.AggregateStateWriteCommand
import app.zhijuan.core.database.library.AggregateStateWriterRepository
import app.zhijuan.core.database.library.BookCreationSnapshotEntity
import app.zhijuan.core.database.library.BookEntity
import app.zhijuan.core.database.library.ChapterEditRebuildBlocker
import app.zhijuan.core.database.library.ChapterEditRebuildExecutionPrepareCommand
import app.zhijuan.core.database.library.ChapterEditRebuildExecutionRepository
import app.zhijuan.core.database.library.ChapterEditRebuildExecutionStepType
import app.zhijuan.core.database.library.ChapterEditRebuildPreparedStepState
import app.zhijuan.core.database.library.ChapterEditRebuildPlanRequest
import app.zhijuan.core.database.library.ChapterEditRebuildPlanRepository
import app.zhijuan.core.database.library.ChapterEditRebuildStepState
import app.zhijuan.core.database.library.ChapterEditRebuildStepType
import app.zhijuan.core.database.library.ChapterEntity
import app.zhijuan.core.database.library.ChapterUserEditCommand
import app.zhijuan.core.database.library.ChapterUserEditRepository
import app.zhijuan.core.database.library.CommitChapterVersionCommand
import app.zhijuan.core.database.library.FutureChapterPolicy
import app.zhijuan.core.database.memory.ChapterSummaryEntity
import app.zhijuan.core.database.memory.ChapterTrackingProjectionEntity
import app.zhijuan.core.database.memory.AggregateStateProjectionEntity
import app.zhijuan.core.database.memory.StoryBibleRevisionEntity
import app.zhijuan.core.database.memory.StoryEntity
import app.zhijuan.core.database.memory.TimelineEventEntity
import app.zhijuan.core.database.search.MemorySearchIndexWriterV1
import app.zhijuan.core.model.AdultStatus
import app.zhijuan.core.model.BookLengthMode
import app.zhijuan.core.model.BookStatus
import app.zhijuan.core.model.ChapterStatus
import app.zhijuan.core.model.ChapterVersionSource
import app.zhijuan.core.model.ConsistencyStatus
import app.zhijuan.core.model.DerivedDataStatus
import app.zhijuan.core.model.GenerationJobStatus
import app.zhijuan.core.model.GenerationStageStatus
import app.zhijuan.core.model.RevisionSource
import app.zhijuan.core.model.StoryEntityType
import app.zhijuan.core.model.TitleSource
import java.security.MessageDigest
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
class ChapterEditRebuildPlanDatabaseTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: ZhijuanDatabase
    private lateinit var planner: ChapterEditRebuildPlanRepository

    @Before
    fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(context, ZhijuanDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(LibraryDatabaseGuards.callback)
            .build()
            .also { it.openHelper.writableDatabase }
        planner = ChapterEditRebuildPlanRepository(database)
        createBook(BOOK_ID)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun tenChapterEditProducesCompleteKeepExistingImpactPlanWithoutWriting() = runBlocking {
        seedCommittedChapters(10)
        editChapter(3)
        val versionCountBefore = scalarInt("SELECT COUNT(*) FROM chapter_version")
        val jobCountBefore = scalarInt("SELECT COUNT(*) FROM generation_job")

        val first = planner.plan(request(3))
        val replay = planner.plan(request(3))

        assertEquals(first.planHash, replay.planHash)
        assertEquals(3, first.editedChapterIndex)
        assertEquals(10, first.highestCommittedChapterIndex)
        assertTrue(first.hasLaterCommittedChapters)
        assertEquals(7, first.laterCommittedChapterCount)
        assertEquals(FutureChapterPolicy.KEEP_EXISTING, first.futureChapterPolicy)
        assertTrue(first.laterBodiesRetained)
        assertEquals(8, first.frozenChapters.size)
        assertEquals(32, first.steps.size)
        assertEquals(17, first.providerStepCount)
        assertEquals(1, first.readyStepCount)
        assertEquals(31, first.blockedStepCount)
        assertEquals(0, first.waitingStepCount)

        val memory = first.step(ChapterEditRebuildStepType.EXTRACT_EDITED_MEMORY, 3)
        assertEquals(ChapterEditRebuildStepState.READY, memory.state)
        assertEquals(ChapterEditRebuildBlocker.NONE, memory.blocker)
        assertTrue(memory.needsProvider)

        val editedTracking = first.step(ChapterEditRebuildStepType.REBUILD_STORY_TRACKING, 3)
        assertEquals(ChapterEditRebuildStepState.BLOCKED, editedTracking.state)
        assertEquals(ChapterEditRebuildBlocker.TRACKING_ORDER_GUARD, editedTracking.blocker)
        assertEquals(listOf(memory.ordinal), editedTracking.dependsOnOrdinals)

        val laterTracking = first.step(ChapterEditRebuildStepType.REBUILD_STORY_TRACKING, 4)
        assertEquals(ChapterEditRebuildBlocker.TRACKING_ORDER_GUARD, laterTracking.blocker)
        val lastTracking = first.step(ChapterEditRebuildStepType.REBUILD_STORY_TRACKING, 10)
        assertEquals(ChapterEditRebuildBlocker.DEPENDENCY_BLOCKED, lastTracking.blocker)

        val context = first.step(ChapterEditRebuildStepType.REASSEMBLE_CONTEXT, 4)
        assertFalse(context.needsProvider)
        assertEquals(ChapterEditRebuildBlocker.DEPENDENCY_BLOCKED, context.blocker)
        val consistency = first.step(ChapterEditRebuildStepType.RECHECK_CONSISTENCY, 4)
        assertTrue(consistency.needsProvider)
        assertEquals(ChapterEditRebuildBlocker.DEPENDENCY_BLOCKED, consistency.blocker)
        first.steps.filter { it.type == ChapterEditRebuildStepType.REBUILD_AGGREGATE_STATE }.forEach { step ->
            assertEquals(ChapterEditRebuildStepState.BLOCKED, step.state)
            assertEquals(ChapterEditRebuildBlocker.DEPENDENCY_BLOCKED, step.blocker)
        }

        assertEquals(versionCountBefore, scalarInt("SELECT COUNT(*) FROM chapter_version"))
        assertEquals(jobCountBefore, scalarInt("SELECT COUNT(*) FROM generation_job"))
        assertEquals(0, scalarInt("SELECT COUNT(*) FROM generation_stage"))
        assertEquals(0, scalarInt("SELECT COUNT(*) FROM request_attempt"))
        assertEquals(0, scalarInt("SELECT COUNT(*) FROM usage_ledger"))
        assertFalse(first.toString().contains(BOOK_ID))
        assertFalse(first.toString().contains("chapter-3-v2"))
        assertFalse(memory.toString().contains(memory.sourceContentHash))
        assertFalse(first.frozenChapters.first().toString().contains(first.frozenChapters.first().contentHash))
    }

    @Test
    fun latestChapterPlanAdvancesOnlyAfterMemoryEvidenceAndInvalidatesOldFence() = runBlocking {
        seedCommittedChapters(1)
        editChapter(1)
        val initial = planner.plan(request(1))

        assertFalse(initial.hasLaterCommittedChapters)
        assertEquals(4, initial.steps.size)
        assertEquals(
            ChapterEditRebuildStepState.READY,
            initial.step(ChapterEditRebuildStepType.EXTRACT_EDITED_MEMORY, 1).state,
        )
        assertEquals(
            ChapterEditRebuildStepState.WAITING_FOR_DEPENDENCY,
            initial.step(ChapterEditRebuildStepType.REBUILD_STORY_TRACKING, 1).state,
        )
        assertEquals(0, initial.blockedStepCount)

        database.memoryDao().insertSummary(summaryForEditedVersion(1))
        val staleFence = expectFailure { planner.requireCurrentMatches(initial) }
        assertTrue(staleFence is IllegalArgumentException)

        val progressed = planner.plan(request(1))
        assertNotEquals(initial.planHash, progressed.planHash)
        assertEquals(
            ChapterEditRebuildStepState.ALREADY_SATISFIED,
            progressed.step(ChapterEditRebuildStepType.EXTRACT_EDITED_MEMORY, 1).state,
        )
        assertEquals(
            ChapterEditRebuildStepState.READY,
            progressed.step(ChapterEditRebuildStepType.REBUILD_STORY_TRACKING, 1).state,
        )
        assertEquals(
            ChapterEditRebuildStepState.WAITING_FOR_DEPENDENCY,
            progressed.step(ChapterEditRebuildStepType.RECHECK_CONSISTENCY, 1).state,
        )
        assertEquals(
            ChapterEditRebuildStepState.WAITING_FOR_DEPENDENCY,
            progressed.step(ChapterEditRebuildStepType.REBUILD_AGGREGATE_STATE, 1).state,
        )
        assertEquals(
            ChapterEditRebuildBlocker.NONE,
            progressed.step(ChapterEditRebuildStepType.REBUILD_AGGREGATE_STATE, 1).blocker,
        )
        planner.requireCurrentMatches(progressed)
    }

    @Test
    fun laterCurrentVersionChangeInvalidatesTheWholeFrozenRange() = runBlocking {
        seedCommittedChapters(4)
        editChapter(3)
        val frozen = planner.plan(request(3))

        commitVersion(
            chapterIndex = 4,
            versionSuffix = "v2",
            expectedCurrentVersionId = "chapter-4-v1",
            content = "第四章稍后又被修改",
            source = ChapterVersionSource.USER_EDIT,
            createdAt = 110,
        )

        val error = expectFailure { planner.requireCurrentMatches(frozen) }
        assertTrue(error is IllegalArgumentException)
        val current = planner.plan(request(3))
        assertNotEquals(frozen.planHash, current.planHash)
        assertEquals("chapter-4-v2", database.libraryDao().findChapter("chapter-4")?.currentVersionId)
    }

    @Test
    fun planningRejectsNonEditCrossBookAndUnsupportedRegenerationPolicy() = runBlocking {
        seedCommittedChapters(2)
        val nonEdit = expectFailure { planner.plan(request(1).copy(editedVersionId = "chapter-1-v1")) }
        assertTrue(nonEdit is IllegalArgumentException)

        editChapter(1)
        val unsupported = expectFailure {
            planner.plan(request(1).copy(futureChapterPolicy = FutureChapterPolicy.REGENERATE_FROM_NEXT))
        }
        assertTrue(unsupported is IllegalArgumentException)

        createBook(SECOND_BOOK_ID)
        val crossBook = expectFailure { planner.plan(request(1).copy(bookId = SECOND_BOOK_ID)) }
        assertTrue(crossBook is IllegalArgumentException)
        assertFalse(request(1).toString().contains(BOOK_ID))
        assertFalse(request(1).toString().contains("chapter-1-v2"))
    }

    @Test
    fun preparedExecutionAtomicallyBindsRewindAndCriticalStepsWithoutGenerationWork() = runBlocking {
        seedCommittedChapters(3)
        editChapter(2)
        val plan = planner.plan(request(2))
        val repository = ChapterEditRebuildExecutionRepository(database)
        val command = ChapterEditRebuildExecutionPrepareCommand(
            plan = plan,
            rewindId = "rewind-prepared-2",
            preparedAt = 120,
        )

        val first = repository.prepare(command)
        val replay = repository.prepare(command)

        assertFalse(first.replayed)
        assertTrue(replay.replayed)
        assertEquals(first.executionId, replay.executionId)
        assertEquals(2, first.firstAffectedChapterIndex)
        assertEquals(3, first.lastAffectedChapterIndex)
        assertEquals(5, first.stepCount)
        assertEquals(5, first.pendingStepCount)
        assertEquals(0, first.satisfiedStepCount)
        assertEquals(1, scalarInt("SELECT COUNT(*) FROM foreshadow_projection_rewind"))
        assertEquals(1, scalarInt("SELECT COUNT(*) FROM chapter_edit_rebuild_execution"))
        assertEquals(5, scalarInt("SELECT COUNT(*) FROM chapter_edit_rebuild_step"))
        assertEquals(0, scalarInt("SELECT COUNT(*) FROM generation_job"))
        assertEquals(0, scalarInt("SELECT COUNT(*) FROM generation_stage"))
        assertEquals(0, scalarInt("SELECT COUNT(*) FROM request_attempt"))
        assertEquals(0, scalarInt("SELECT COUNT(*) FROM usage_ledger"))

        val steps = database.chapterEditRebuildExecutionDao().stepsForExecution(first.executionId)
        assertEquals(
            listOf(
                ChapterEditRebuildExecutionStepType.EDITED_MEMORY,
                ChapterEditRebuildExecutionStepType.TRACKING,
                ChapterEditRebuildExecutionStepType.AGGREGATE,
                ChapterEditRebuildExecutionStepType.TRACKING,
                ChapterEditRebuildExecutionStepType.AGGREGATE,
            ),
            steps.map { it.stepType },
        )
        assertTrue(steps.all { it.preparedState == ChapterEditRebuildPreparedStepState.PENDING })
        assertTrue(steps.all { it.createdAt == 120L })
        assertFalse(first.toString().contains(BOOK_ID))
        assertFalse(first.toString().contains(first.executionId))
        assertFalse(command.toString().contains("rewind-prepared-2"))
    }

    @Test
    fun preparedExecutionCapturesSatisfiedMemoryBaselineAndRejectsAnotherIdentity() = runBlocking {
        seedCommittedChapters(1)
        editChapter(1)
        database.memoryDao().insertSummary(summaryForEditedVersion(1))
        val plan = planner.plan(request(1))
        val repository = ChapterEditRebuildExecutionRepository(database)

        val first = repository.prepare(
            ChapterEditRebuildExecutionPrepareCommand(
                plan = plan,
                rewindId = "rewind-memory-baseline",
                preparedAt = 120,
            ),
        )

        assertEquals(3, first.stepCount)
        assertEquals(1, first.satisfiedStepCount)
        val memory = database.chapterEditRebuildExecutionDao().stepsForExecution(first.executionId).first()
        assertEquals(ChapterEditRebuildExecutionStepType.EDITED_MEMORY, memory.stepType)
        assertEquals(ChapterEditRebuildPreparedStepState.SATISFIED, memory.preparedState)
        assertEquals("edited-summary-1", memory.baselineSummaryId)
        assertTrue(memory.baselineSummaryFingerprint?.matches(Regex("[0-9a-f]{64}")) == true)
        assertFalse(memory.toString().contains("edited-summary-1"))

        val conflict = expectFailure {
            repository.prepare(
                ChapterEditRebuildExecutionPrepareCommand(
                    plan = plan,
                    rewindId = "rewind-memory-baseline-conflict",
                    preparedAt = 121,
                ),
            )
        }
        assertTrue(conflict is IllegalArgumentException)
        assertEquals(1, scalarInt("SELECT COUNT(*) FROM foreshadow_projection_rewind"))
        assertEquals(1, scalarInt("SELECT COUNT(*) FROM chapter_edit_rebuild_execution"))
        assertEquals(3, scalarInt("SELECT COUNT(*) FROM chapter_edit_rebuild_step"))
    }

    @Test
    fun stalePlanRollsBackRewindAndPreparedLedgerTogether() = runBlocking {
        seedCommittedChapters(3)
        editChapter(2)
        val stalePlan = planner.plan(request(2))
        commitVersion(
            chapterIndex = 3,
            versionSuffix = "v2",
            expectedCurrentVersionId = "chapter-3-v1",
            content = "第三章在执行前再次修改",
            source = ChapterVersionSource.USER_EDIT,
            createdAt = 110,
        )

        val error = expectFailure {
            ChapterEditRebuildExecutionRepository(database).prepare(
                ChapterEditRebuildExecutionPrepareCommand(
                    plan = stalePlan,
                    rewindId = "rewind-stale-plan",
                    preparedAt = 120,
                ),
            )
        }

        assertTrue(error is IllegalArgumentException)
        assertEquals(0, scalarInt("SELECT COUNT(*) FROM foreshadow_projection_rewind"))
        assertEquals(0, scalarInt("SELECT COUNT(*) FROM chapter_edit_rebuild_execution"))
        assertEquals(0, scalarInt("SELECT COUNT(*) FROM chapter_edit_rebuild_step"))
    }

    @Test
    fun failureAfterRewindStillRollsBackTheWholePreparationTransaction() = runBlocking {
        seedCommittedChapters(1)
        editChapter(1)
        database.memoryDao().insertSummary(
            summaryForEditedVersion(1).copy(createdAt = 130, updatedAt = 130),
        )
        val plan = planner.plan(request(1))

        val error = expectFailure {
            ChapterEditRebuildExecutionRepository(database).prepare(
                ChapterEditRebuildExecutionPrepareCommand(
                    plan = plan,
                    rewindId = "rewind-post-write-rollback",
                    preparedAt = 120,
                ),
            )
        }

        assertTrue(error is IllegalArgumentException)
        assertEquals(0, scalarInt("SELECT COUNT(*) FROM foreshadow_projection_rewind"))
        assertEquals(0, scalarInt("SELECT COUNT(*) FROM chapter_edit_rebuild_execution"))
        assertEquals(0, scalarInt("SELECT COUNT(*) FROM chapter_edit_rebuild_step"))
        assertEquals(1, scalarInt("SELECT COUNT(*) FROM chapter_summary"))
    }

    @Test
    fun dynamicEditedMemoryStageIsAtomicDeterministicAndExactlyReplayable() = runBlocking {
        seedCommittedChapters(3)
        editChapter(2)
        val prepared = ChapterEditRebuildExecutionRepository(database).prepare(
            ChapterEditRebuildExecutionPrepareCommand(
                plan = planner.plan(request(2)),
                rewindId = "rewind-dynamic-memory",
                preparedAt = 120,
            ),
        )
        val repository = ChapterEditRebuildStageRepository(database)
        val command = ChapterEditRebuildEditedMemoryStageCommand(
            executionId = prepared.executionId,
            userIntentJson = "{\"kind\":\"chapter-edit-rebuild\"}",
            budgetSnapshotJson = "{\"maxTokens\":4096}",
            createdAt = 130,
        )

        val first = repository.createEditedMemoryStage(command)
        val replay = repository.createEditedMemoryStage(command)

        assertFalse(first.replayed)
        assertTrue(replay.replayed)
        assertEquals(first.jobId, replay.jobId)
        assertEquals(first.stageId, replay.stageId)
        assertEquals(1, first.stepOrdinal)
        assertEquals(2, first.chapterIndex)
        assertEquals(1, scalarInt("SELECT COUNT(*) FROM generation_job"))
        assertEquals(1, scalarInt("SELECT COUNT(*) FROM generation_stage"))
        assertEquals(0, scalarInt("SELECT COUNT(*) FROM request_attempt"))
        assertEquals(0, scalarInt("SELECT COUNT(*) FROM usage_ledger"))

        val job = requireNotNull(database.generationDao().findJob(first.jobId))
        val stage = requireNotNull(database.generationDao().findStage(first.stageId))
        assertEquals(GenerationJobStatus.CREATED, job.status)
        assertEquals(first.stageId, job.currentStageId)
        assertEquals(GenerationStageStatus.PENDING, stage.status)
        val binding = requireNotNull(ChapterMemoryExtractionJobFactory.parseRebuildBindingIfPresent(stage))
        assertEquals(prepared.executionId, binding.executionId)
        assertEquals(ChapterEditRebuildExecutionStepType.EDITED_MEMORY, binding.stepType)
        assertEquals("chapter-2-v2", binding.sourceChapterVersionId)
        assertEquals(
            binding,
            ChapterMemoryExtractionJobFactory.parseRebuildBindingIfPresent(stage),
        )
        assertTrue(repository.requireProviderOpenAllowedIfBound(stage, job, observedAt = 131))
        assertTrue(repository.requireCommitAllowedIfBound(stage, job, observedAt = 131))
        assertFalse(first.toString().contains(first.jobId))
        assertFalse(first.toString().contains(first.stageId))
        assertFalse(command.toString().contains(prepared.executionId))
        assertFalse(command.toString().contains("maxTokens"))

        val conflict = expectFailure {
            repository.createEditedMemoryStage(
                command.copy(budgetSnapshotJson = "{\"maxTokens\":8192}"),
            )
        }
        assertTrue(conflict is IllegalArgumentException)
        assertEquals(1, scalarInt("SELECT COUNT(*) FROM generation_job"))
        assertEquals(1, scalarInt("SELECT COUNT(*) FROM generation_stage"))
    }

    @Test
    fun dynamicEditedMemoryStageRejectsChangedRangeAndSatisfiedMemoryWithoutWriting() = runBlocking {
        seedCommittedChapters(3)
        editChapter(2)
        val changedRangeExecution = ChapterEditRebuildExecutionRepository(database).prepare(
            ChapterEditRebuildExecutionPrepareCommand(
                plan = planner.plan(request(2)),
                rewindId = "rewind-dynamic-stale-range",
                preparedAt = 120,
            ),
        )
        commitVersion(
            chapterIndex = 3,
            versionSuffix = "v2",
            expectedCurrentVersionId = "chapter-3-v1",
            content = "第三章在 Stage 创建前变化",
            source = ChapterVersionSource.USER_EDIT,
            createdAt = 125,
        )

        val stale = expectFailure {
            ChapterEditRebuildStageRepository(database).createEditedMemoryStage(
                ChapterEditRebuildEditedMemoryStageCommand(
                    executionId = changedRangeExecution.executionId,
                    userIntentJson = "{}",
                    budgetSnapshotJson = "{}",
                    createdAt = 130,
                ),
            )
        }
        assertTrue(stale is IllegalArgumentException)
        assertEquals(0, scalarInt("SELECT COUNT(*) FROM generation_job"))
        assertEquals(0, scalarInt("SELECT COUNT(*) FROM generation_stage"))
    }

    @Test
    fun dynamicEditedMemoryEntryDoesNotSkipASatisfiedMemoryStepIntoTracking() = runBlocking {
        seedCommittedChapters(1)
        editChapter(1)
        database.memoryDao().insertSummary(summaryForEditedVersion(1))
        val prepared = ChapterEditRebuildExecutionRepository(database).prepare(
            ChapterEditRebuildExecutionPrepareCommand(
                plan = planner.plan(request(1)),
                rewindId = "rewind-dynamic-memory-satisfied",
                preparedAt = 120,
            ),
        )

        val error = expectFailure {
            ChapterEditRebuildStageRepository(database).createEditedMemoryStage(
                ChapterEditRebuildEditedMemoryStageCommand(
                    executionId = prepared.executionId,
                    userIntentJson = "{}",
                    budgetSnapshotJson = "{}",
                    createdAt = 130,
                ),
            )
        }

        assertTrue(error is IllegalArgumentException)
        assertEquals(0, scalarInt("SELECT COUNT(*) FROM generation_job"))
        assertEquals(0, scalarInt("SELECT COUNT(*) FROM generation_stage"))
    }

    @Test
    fun concurrentDynamicEditedMemoryCreationConvergesOnOneAuthoritativeStage() = runBlocking {
        seedCommittedChapters(2)
        editChapter(1)
        val prepared = ChapterEditRebuildExecutionRepository(database).prepare(
            ChapterEditRebuildExecutionPrepareCommand(
                plan = planner.plan(request(1)),
                rewindId = "rewind-dynamic-memory-concurrent",
                preparedAt = 120,
            ),
        )
        val repository = ChapterEditRebuildStageRepository(database)
        val command = ChapterEditRebuildEditedMemoryStageCommand(
            executionId = prepared.executionId,
            userIntentJson = "{}",
            budgetSnapshotJson = "{}",
            createdAt = 130,
        )

        val results = coroutineScope {
            listOf(
                async { repository.createEditedMemoryStage(command) },
                async { repository.createEditedMemoryStage(command) },
            ).awaitAll()
        }

        assertEquals(1, results.count { !it.replayed })
        assertEquals(1, results.count { it.replayed })
        assertEquals(1, results.map { it.jobId }.distinct().size)
        assertEquals(1, results.map { it.stageId }.distinct().size)
        assertEquals(1, scalarInt("SELECT COUNT(*) FROM generation_job"))
        assertEquals(1, scalarInt("SELECT COUNT(*) FROM generation_stage"))
        assertEquals(0, scalarInt("SELECT COUNT(*) FROM request_attempt"))
        assertEquals(0, scalarInt("SELECT COUNT(*) FROM usage_ledger"))
    }

    @Test
    fun firstTrackingStageUsesDedicatedOrderPermitAndIsExactlyReplayable() = runBlocking {
        seedCommittedChapters(2)
        editChapter(1)
        database.memoryDao().insertSummary(summaryForEditedVersion(1))
        seedTrackingPrerequisites()
        val prepared = ChapterEditRebuildExecutionRepository(database).prepare(
            ChapterEditRebuildExecutionPrepareCommand(
                plan = planner.plan(request(1)),
                rewindId = "rewind-dynamic-tracking",
                preparedAt = 120,
            ),
        )
        val ordinaryGuard = expectFailure {
            ChapterTrackingProjectionSourceRepository(database).loadCurrentVersion("chapter-1")
        }
        assertTrue(ordinaryGuard is IllegalArgumentException)

        val repository = ChapterEditRebuildStageRepository(database)
        val command = ChapterEditRebuildTrackingStageCommand(
            executionId = prepared.executionId,
            userIntentJson = "{\"kind\":\"chapter-edit-rebuild\"}",
            budgetSnapshotJson = "{\"maxTokens\":4096}",
            createdAt = 130,
        )
        val first = repository.createFirstTrackingStage(command)
        val replay = repository.createFirstTrackingStage(command)

        assertFalse(first.replayed)
        assertTrue(replay.replayed)
        assertEquals(first.jobId, replay.jobId)
        assertEquals(first.stageId, replay.stageId)
        assertEquals(2, first.stepOrdinal)
        assertEquals(1, first.chapterIndex)
        assertEquals(1, scalarInt("SELECT COUNT(*) FROM generation_job"))
        assertEquals(1, scalarInt("SELECT COUNT(*) FROM generation_stage"))
        assertEquals(0, scalarInt("SELECT COUNT(*) FROM request_attempt"))
        assertEquals(0, scalarInt("SELECT COUNT(*) FROM usage_ledger"))

        val job = requireNotNull(database.generationDao().findJob(first.jobId))
        val stage = requireNotNull(database.generationDao().findStage(first.stageId))
        assertEquals(GenerationJobStatus.CREATED, job.status)
        assertEquals(GenerationStageStatus.PENDING, stage.status)
        val binding = requireNotNull(ChapterTrackingProjectionJobFactory.parseRebuildBindingIfPresent(stage))
        assertEquals(prepared.executionId, binding.executionId)
        assertEquals(ChapterEditRebuildExecutionStepType.TRACKING, binding.stepType)
        assertEquals("chapter-1-v2", binding.sourceChapterVersionId)
        assertEquals(
            ChapterTrackingProjectionSourceRepository(database).loadForEditRebuild("chapter-1").source,
            ChapterTrackingProjectionJobFactory.parseAndVerify(stage),
        )
        assertTrue(repository.requireProviderOpenAllowedIfBound(stage, job, observedAt = 131))
        assertTrue(repository.requireCommitAllowedIfBound(stage, job, observedAt = 131))
        assertFalse(first.toString().contains(first.jobId))
        assertFalse(command.toString().contains(prepared.executionId))

        val conflict = expectFailure {
            repository.createFirstTrackingStage(
                command.copy(budgetSnapshotJson = "{\"maxTokens\":8192}"),
            )
        }
        assertTrue(conflict is IllegalArgumentException)
        assertEquals(1, scalarInt("SELECT COUNT(*) FROM generation_job"))
        assertEquals(1, scalarInt("SELECT COUNT(*) FROM generation_stage"))
    }

    @Test
    fun firstTrackingStageRejectsCurrentRangeChangesWithoutWriting() = runBlocking {
        seedCommittedChapters(2)
        editChapter(1)
        database.memoryDao().insertSummary(summaryForEditedVersion(1))
        seedTrackingPrerequisites()
        val prepared = ChapterEditRebuildExecutionRepository(database).prepare(
            ChapterEditRebuildExecutionPrepareCommand(
                plan = planner.plan(request(1)),
                rewindId = "rewind-dynamic-tracking-stale",
                preparedAt = 120,
            ),
        )
        commitVersion(
            chapterIndex = 2,
            versionSuffix = "v2",
            expectedCurrentVersionId = "chapter-2-v1",
            content = "第二章在 tracking Stage 创建前变化",
            source = ChapterVersionSource.USER_EDIT,
            createdAt = 125,
        )

        val error = expectFailure {
            ChapterEditRebuildStageRepository(database).createFirstTrackingStage(
                ChapterEditRebuildTrackingStageCommand(
                    executionId = prepared.executionId,
                    userIntentJson = "{}",
                    budgetSnapshotJson = "{}",
                    createdAt = 130,
                ),
            )
        }

        assertTrue(error is IllegalArgumentException)
        assertEquals(0, scalarInt("SELECT COUNT(*) FROM generation_job"))
        assertEquals(0, scalarInt("SELECT COUNT(*) FROM generation_stage"))
    }

    @Test
    fun concurrentFirstTrackingStageCreationConvergesOnOneIdentity() = runBlocking {
        seedCommittedChapters(2)
        editChapter(1)
        database.memoryDao().insertSummary(summaryForEditedVersion(1))
        seedTrackingPrerequisites()
        val prepared = ChapterEditRebuildExecutionRepository(database).prepare(
            ChapterEditRebuildExecutionPrepareCommand(
                plan = planner.plan(request(1)),
                rewindId = "rewind-dynamic-tracking-concurrent",
                preparedAt = 120,
            ),
        )
        val repository = ChapterEditRebuildStageRepository(database)
        val command = ChapterEditRebuildTrackingStageCommand(
            executionId = prepared.executionId,
            userIntentJson = "{}",
            budgetSnapshotJson = "{}",
            createdAt = 130,
        )

        val results = coroutineScope {
            listOf(
                async { repository.createFirstTrackingStage(command) },
                async { repository.createFirstTrackingStage(command) },
            ).awaitAll()
        }

        assertEquals(1, results.count { !it.replayed })
        assertEquals(1, results.count { it.replayed })
        assertEquals(1, results.map { it.jobId }.distinct().size)
        assertEquals(1, results.map { it.stageId }.distinct().size)
        assertEquals(1, scalarInt("SELECT COUNT(*) FROM generation_job"))
        assertEquals(1, scalarInt("SELECT COUNT(*) FROM generation_stage"))
    }

    @Test
    fun aggregateSlotChangeAfterTrackingStageCreationBlocksProviderOpen() = runBlocking {
        seedCommittedChapters(1)
        editChapter(1)
        database.memoryDao().insertSummary(summaryForEditedVersion(1))
        seedTrackingPrerequisites()
        val prepared = ChapterEditRebuildExecutionRepository(database).prepare(
            ChapterEditRebuildExecutionPrepareCommand(
                plan = planner.plan(request(1)),
                rewindId = "rewind-dynamic-tracking-aggregate-change",
                preparedAt = 120,
            ),
        )
        val repository = ChapterEditRebuildStageRepository(database)
        val created = repository.createFirstTrackingStage(
            ChapterEditRebuildTrackingStageCommand(
                executionId = prepared.executionId,
                userIntentJson = "{}",
                budgetSnapshotJson = "{}",
                createdAt = 130,
            ),
        )
        database.memoryDao().insertAggregateState(
            AggregateStateProjectionEntity(
                aggregateStateId = "unexpected-aggregate-head",
                bookId = BOOK_ID,
                throughChapterIndex = 1,
                sourceThroughChapterVersionId = "chapter-1-v2",
                schemaVersion = 1,
                stateJson = "{}",
                contentHash = "unexpected-content-hash",
                status = DerivedDataStatus.VALID,
                createdAt = 131,
                updatedAt = 131,
            ),
        )
        val job = requireNotNull(database.generationDao().findJob(created.jobId))
        val stage = requireNotNull(database.generationDao().findStage(created.stageId))

        val error = expectFailure {
            repository.requireProviderOpenAllowedIfBound(stage, job, observedAt = 132)
        }

        assertTrue(error is IllegalArgumentException)
        assertEquals(0, scalarInt("SELECT COUNT(*) FROM request_attempt"))
        assertEquals(0, scalarInt("SELECT COUNT(*) FROM chapter_tracking_projection"))
        assertEquals(1, scalarInt("SELECT COUNT(*) FROM aggregate_state_projection"))
    }

    @Test
    fun secondChapterTrackingRetirementAndReplacementStageAreAtomicAndExactlyReplayable() = runBlocking {
        val fixture = prepareSecondRetainedTrackingBoundary()
        val repository = ChapterEditRebuildStageRepository(database)
        val command = ChapterEditRebuildTrackingStageCommand(
            executionId = fixture.executionId,
            userIntentJson = "{\"kind\":\"retained-tracking-rebuild\"}",
            budgetSnapshotJson = "{\"maxTokens\":4096}",
            createdAt = 130,
        )

        val first = repository.createNextRetainedTrackingStage(command)
        val replay = repository.createNextRetainedTrackingStage(command)

        assertFalse(first.replayed)
        assertTrue(replay.replayed)
        assertEquals(first.jobId, replay.jobId)
        assertEquals(first.stageId, replay.stageId)
        assertEquals(4, first.stepOrdinal)
        assertEquals(2, first.chapterIndex)
        assertEquals(1, scalarInt("SELECT COUNT(*) FROM chapter_edit_rebuild_tracking_retirement"))
        assertEquals(3, scalarInt("SELECT COUNT(*) FROM generation_job"))
        assertEquals(3, scalarInt("SELECT COUNT(*) FROM generation_stage"))
        assertEquals(null, database.memoryDao().findTrackingProjectionForVersion("chapter-2-v1"))
        val retired = requireNotNull(database.memoryDao().findTrackingProjection("old-tracking-2"))
        assertEquals(DerivedDataStatus.STALE, retired.status)
        assertEquals(130, retired.updatedAt)
        val timeline = database.memoryDao().timelineEventHistoryForVersion("chapter-2-v1").single()
        assertEquals(DerivedDataStatus.STALE, timeline.status)
        assertEquals(
            null,
            database.memorySearchDao().findBySource(BOOK_ID, "TIMELINE_EVENT", timeline.timelineEventId),
        )
        val retirement = requireNotNull(
            database.chapterEditRebuildExecutionDao().findTrackingRetirement(fixture.executionId, 4),
        )
        assertEquals("old-tracking-2", retirement.baselineTrackingProjectionId)
        assertEquals(first.jobId, retirement.replacementJobId)
        assertEquals(first.stageId, retirement.replacementStageId)
        assertEquals(1, retirement.baselineTimelineEventCount)
        val stage = requireNotNull(database.generationDao().findStage(first.stageId))
        val binding = requireNotNull(ChapterTrackingProjectionJobFactory.parseRebuildBindingIfPresent(stage))
        assertEquals(fixture.executionId, binding.executionId)
        assertEquals(4, binding.stepOrdinal)
        assertEquals("chapter-2-v1", binding.sourceChapterVersionId)
        assertEquals(
            ChapterTrackingProjectionSourceRepository(database).loadForEditRebuild("chapter-2").source,
            ChapterTrackingProjectionJobFactory.parseAndVerify(stage),
        )
        assertFalse(retirement.toString().contains(retirement.baselineTrackingProjectionId))
        assertFalse(command.toString().contains(fixture.executionId))

        val conflict = expectFailure {
            repository.createNextRetainedTrackingStage(
                command.copy(budgetSnapshotJson = "{\"maxTokens\":8192}"),
            )
        }
        assertTrue(conflict is IllegalArgumentException)
        assertEquals(1, scalarInt("SELECT COUNT(*) FROM chapter_edit_rebuild_tracking_retirement"))
    }

    @Test
    fun concurrentSecondChapterRetirementConvergesOnOneReplacementStage() = runBlocking {
        val fixture = prepareSecondRetainedTrackingBoundary()
        val repository = ChapterEditRebuildStageRepository(database)
        val command = ChapterEditRebuildTrackingStageCommand(
            executionId = fixture.executionId,
            userIntentJson = "{}",
            budgetSnapshotJson = "{}",
            createdAt = 130,
        )

        val results = coroutineScope {
            listOf(
                async { repository.createNextRetainedTrackingStage(command) },
                async { repository.createNextRetainedTrackingStage(command) },
            ).awaitAll()
        }

        assertEquals(1, results.count { !it.replayed })
        assertEquals(1, results.count { it.replayed })
        assertEquals(1, results.map { it.stageId }.distinct().size)
        assertEquals(1, scalarInt("SELECT COUNT(*) FROM chapter_edit_rebuild_tracking_retirement"))
    }

    @Test
    fun replacementIdentityConflictRollsBackTrackingTimelineAndSearchRetirement() = runBlocking {
        val fixture = prepareSecondRetainedTrackingBoundary()
        val oldStage = requireNotNull(database.generationDao().findStage("old-tracking-stage-2"))
        val source = ChapterTrackingProjectionJobFactory.parseAndVerify(oldStage)
        GenerationJobSetupRepository(database).create(
            ChapterTrackingProjectionJobFactory.create(
                ChapterTrackingProjectionJobSpec(
                    jobId = deterministicJobId(fixture.targetBinding),
                    stageId = deterministicStageId(fixture.targetBinding),
                    bookId = BOOK_ID,
                    userIntentJson = "{\"conflict\":true}",
                    budgetSnapshotJson = "{}",
                    source = source,
                    rebuildBinding = fixture.targetBinding,
                    createdAt = 129,
                ),
            ),
        )

        val error = expectFailure {
            ChapterEditRebuildStageRepository(database).createNextRetainedTrackingStage(
                ChapterEditRebuildTrackingStageCommand(
                    executionId = fixture.executionId,
                    userIntentJson = "{}",
                    budgetSnapshotJson = "{}",
                    createdAt = 130,
                ),
            )
        }

        assertTrue(error is IllegalArgumentException)
        assertEquals(DerivedDataStatus.VALID, requireNotNull(database.memoryDao().findTrackingProjection("old-tracking-2")).status)
        val timeline = database.memoryDao().timelineEventsForVersion("chapter-2-v1").single()
        assertEquals(DerivedDataStatus.VALID, timeline.status)
        assertTrue(
            database.memorySearchDao().findBySource(BOOK_ID, "TIMELINE_EVENT", timeline.timelineEventId) != null,
        )
        assertEquals(0, scalarInt("SELECT COUNT(*) FROM chapter_edit_rebuild_tracking_retirement"))
    }

    @Test
    fun explicitThirdChapterRetirementRequiresCompletedDirectPredecessorAndReplaysIndependently() = runBlocking {
        val fixture = prepareThirdRetainedTrackingBoundary(completeSecondChapter = true)
        val repository = ChapterEditRebuildStageRepository(database)
        val command = ChapterEditRebuildRetainedTrackingStageCommand(
            executionId = fixture.executionId,
            targetStepOrdinal = 6,
            userIntentJson = "{\"kind\":\"retained-tracking-rebuild\"}",
            budgetSnapshotJson = "{\"maxTokens\":4096}",
            createdAt = 150,
        )

        val created = repository.createRetainedTrackingStage(command)
        val replay = repository.createRetainedTrackingStage(command)
        val earlierReplay = repository.createNextRetainedTrackingStage(fixture.secondChapterCommand)

        assertFalse(created.replayed)
        assertTrue(replay.replayed)
        assertTrue(earlierReplay.replayed)
        assertEquals(6, created.stepOrdinal)
        assertEquals(3, created.chapterIndex)
        assertEquals(created.stageId, replay.stageId)
        assertEquals(2, scalarInt("SELECT COUNT(*) FROM chapter_edit_rebuild_tracking_retirement"))
        assertEquals(DerivedDataStatus.STALE, requireNotNull(database.memoryDao().findTrackingProjection("old-tracking-3")).status)
        assertEquals(1, scalarInt("SELECT COUNT(*) FROM aggregate_state_projection WHERE through_chapter_index = 2 AND status = 'VALID'"))
        assertEquals(0, scalarInt("SELECT COUNT(*) FROM aggregate_state_projection WHERE through_chapter_index = 3 AND status = 'VALID'"))
        val retirement = requireNotNull(
            database.chapterEditRebuildExecutionDao().findTrackingRetirement(fixture.executionId, 6),
        )
        assertEquals(created.stageId, retirement.replacementStageId)
        assertFalse(command.toString().contains(fixture.executionId))
    }

    @Test
    fun explicitThirdChapterRetirementRejectsIncompleteDirectPredecessor() = runBlocking {
        val incomplete = prepareThirdRetainedTrackingBoundary(completeSecondChapter = false)
        val repository = ChapterEditRebuildStageRepository(database)
        val incompleteError = expectFailure {
            repository.createRetainedTrackingStage(
                ChapterEditRebuildRetainedTrackingStageCommand(
                    executionId = incomplete.executionId,
                    targetStepOrdinal = 6,
                    userIntentJson = "{}",
                    budgetSnapshotJson = "{}",
                    createdAt = 150,
                ),
            )
        }

        assertTrue(incompleteError is IllegalArgumentException)
        assertEquals(DerivedDataStatus.VALID, requireNotNull(database.memoryDao().findTrackingProjection("old-tracking-3")).status)
        assertEquals(1, scalarInt("SELECT COUNT(*) FROM chapter_edit_rebuild_tracking_retirement"))
        assertEquals(0, scalarInt("SELECT COUNT(*) FROM chapter_edit_rebuild_tracking_retirement WHERE step_ordinal = 6"))
    }

    @Test
    fun explicitThirdChapterRetirementRejectsTimeBeforeDirectPredecessorAggregate() = runBlocking {
        val fixture = prepareThirdRetainedTrackingBoundary(completeSecondChapter = true)

        val error = expectFailure {
            ChapterEditRebuildStageRepository(database).createRetainedTrackingStage(
                ChapterEditRebuildRetainedTrackingStageCommand(
                    executionId = fixture.executionId,
                    targetStepOrdinal = 6,
                    userIntentJson = "{}",
                    budgetSnapshotJson = "{}",
                    createdAt = 140,
                ),
            )
        }

        assertTrue(error is IllegalArgumentException)
        assertEquals(DerivedDataStatus.VALID, requireNotNull(database.memoryDao().findTrackingProjection("old-tracking-3")).status)
        assertEquals(1, scalarInt("SELECT COUNT(*) FROM chapter_edit_rebuild_tracking_retirement"))
        assertEquals(0, scalarInt("SELECT COUNT(*) FROM chapter_edit_rebuild_tracking_retirement WHERE step_ordinal = 6"))
    }

    private fun request(index: Int) = ChapterEditRebuildPlanRequest(
        bookId = BOOK_ID,
        editedChapterId = "chapter-$index",
        editedVersionId = "chapter-$index-v2",
    )

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
            commitVersion(
                chapterIndex = index,
                versionSuffix = "v1",
                expectedCurrentVersionId = null,
                content = "原始正文-$index",
                source = ChapterVersionSource.IMPORTED,
                createdAt = 10L + index,
            )
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
        versionSuffix: String,
        expectedCurrentVersionId: String?,
        content: String,
        source: ChapterVersionSource,
        createdAt: Long,
    ) {
        database.libraryDao().commitChapterVersion(
            CommitChapterVersionCommand(
                chapterVersionId = "chapter-$chapterIndex-$versionSuffix",
                chapterId = "chapter-$chapterIndex",
                expectedCurrentVersionId = expectedCurrentVersionId,
                content = content,
                contentHash = sha256(content),
                source = source,
                generationStageId = null,
                modelSnapshotJson = null,
                createdAt = createdAt,
            ),
        )
    }

    private fun summaryForEditedVersion(index: Int) = ChapterSummaryEntity(
        chapterSummaryId = "edited-summary-$index",
        bookId = BOOK_ID,
        chapterVersionId = "chapter-$index-v2",
        chapterIndex = index,
        schemaVersion = 1,
        summaryJson = "{}",
        importance = 80,
        status = DerivedDataStatus.VALID,
        modelSnapshotJson = null,
        createdAt = 101,
        updatedAt = 101,
    )

    private suspend fun seedTrackingPrerequisites() {
        database.memoryDao().createBibleRevision(
            StoryBibleRevisionEntity(
                bibleRevisionId = "rebuild-bible-1",
                bookId = BOOK_ID,
                revisionNo = 1,
                parentRevisionId = null,
                source = RevisionSource.USER,
                schemaVersion = 1,
                contentControlSchemaVersion = 1,
                payloadJson = "{}",
                contentHash = sha256("rebuild-bible-1"),
                generationStageId = null,
                createdAt = 105,
            ),
        )
        database.memoryDao().insertStoryEntity(
            StoryEntity(
                entityId = "rebuild-hero",
                bookId = BOOK_ID,
                entityType = StoryEntityType.CHARACTER,
                canonicalName = "重建主角",
                aliasesJson = "[]",
                stableDefinitionJson = "{}",
                adultStatus = AdultStatus.CONFIRMED_ADULT,
                ageYears = 25,
                sourceBibleRevisionId = "rebuild-bible-1",
                createdAt = 106,
                updatedAt = 106,
            ),
        )
    }

    private suspend fun prepareSecondRetainedTrackingBoundary(): RetainedTrackingFixture {
        seedCommittedChapters(2)
        seedTrackingPrerequisites()
        database.memoryDao().insertSummary(summaryForCurrentVersion(2))
        seedCompletedTracking(
            chapterIndex = 2,
            projectionId = "old-tracking-2",
            jobId = "old-tracking-job-2",
            stageId = "old-tracking-stage-2",
            timelineId = "old-timeline-2",
            createdAt = 60,
            rebuildBinding = null,
        )
        editChapter(1)
        database.memoryDao().insertSummary(summaryForEditedVersion(1))
        val prepared = ChapterEditRebuildExecutionRepository(database).prepare(
            ChapterEditRebuildExecutionPrepareCommand(
                plan = planner.plan(request(1)),
                rewindId = "rewind-second-retained-tracking",
                preparedAt = 120,
            ),
        )
        val execution = requireNotNull(database.chapterEditRebuildExecutionDao().findExecution(prepared.executionId))
        val firstStep = database.chapterEditRebuildExecutionDao().stepsForExecution(prepared.executionId).single {
            it.stepType == ChapterEditRebuildExecutionStepType.TRACKING && it.chapterIndex == 1
        }
        val binding = ChapterEditRebuildStageBindingV1(
            executionId = execution.executionId,
            stableFenceHash = execution.stableFenceHash,
            stepOrdinal = firstStep.stepOrdinal,
            stepType = firstStep.stepType,
            chapterIndex = firstStep.chapterIndex,
            sourceChapterVersionId = firstStep.sourceChapterVersionId,
            sourceContentHash = firstStep.sourceContentHash,
        )
        seedCompletedTracking(
            chapterIndex = 1,
            projectionId = "rebuilt-tracking-1",
            jobId = deterministicJobId(binding),
            stageId = deterministicStageId(binding),
            timelineId = null,
            createdAt = 126,
            rebuildBinding = binding,
        )
        AggregateStateWriterRepository(database).write(
            AggregateStateWriteCommand(
                plan = planner.plan(request(1)),
                chapterIndex = 1,
                generatedAt = 127,
            ),
        )
        val targetStep = database.chapterEditRebuildExecutionDao().stepsForExecution(prepared.executionId).single {
            it.stepType == ChapterEditRebuildExecutionStepType.TRACKING && it.chapterIndex == 2
        }
        val targetBinding = ChapterEditRebuildStageBindingV1(
            executionId = execution.executionId,
            stableFenceHash = execution.stableFenceHash,
            stepOrdinal = targetStep.stepOrdinal,
            stepType = targetStep.stepType,
            chapterIndex = targetStep.chapterIndex,
            sourceChapterVersionId = targetStep.sourceChapterVersionId,
            sourceContentHash = targetStep.sourceContentHash,
        )
        return RetainedTrackingFixture(prepared.executionId, targetBinding)
    }

    private suspend fun prepareThirdRetainedTrackingBoundary(
        completeSecondChapter: Boolean,
    ): ThirdRetainedTrackingFixture {
        seedCommittedChapters(3)
        seedTrackingPrerequisites()
        database.memoryDao().insertSummary(summaryForCurrentVersion(2))
        database.memoryDao().insertSummary(summaryForCurrentVersion(3))
        seedCompletedTracking(
            chapterIndex = 2,
            projectionId = "old-tracking-2",
            jobId = "old-tracking-job-2",
            stageId = "old-tracking-stage-2",
            timelineId = "old-timeline-2",
            createdAt = 60,
            rebuildBinding = null,
        )
        seedCompletedTracking(
            chapterIndex = 3,
            projectionId = "old-tracking-3",
            jobId = "old-tracking-job-3",
            stageId = "old-tracking-stage-3",
            timelineId = "old-timeline-3",
            createdAt = 70,
            rebuildBinding = null,
        )
        editChapter(1)
        database.memoryDao().insertSummary(summaryForEditedVersion(1))
        val prepared = ChapterEditRebuildExecutionRepository(database).prepare(
            ChapterEditRebuildExecutionPrepareCommand(
                plan = planner.plan(request(1)),
                rewindId = "rewind-third-retained-tracking",
                preparedAt = 120,
            ),
        )
        val execution = requireNotNull(database.chapterEditRebuildExecutionDao().findExecution(prepared.executionId))
        val steps = database.chapterEditRebuildExecutionDao().stepsForExecution(prepared.executionId)
        val firstStep = steps.single {
            it.stepType == ChapterEditRebuildExecutionStepType.TRACKING && it.chapterIndex == 1
        }
        val firstBinding = ChapterEditRebuildStageBindingV1(
            executionId = execution.executionId,
            stableFenceHash = execution.stableFenceHash,
            stepOrdinal = firstStep.stepOrdinal,
            stepType = firstStep.stepType,
            chapterIndex = firstStep.chapterIndex,
            sourceChapterVersionId = firstStep.sourceChapterVersionId,
            sourceContentHash = firstStep.sourceContentHash,
        )
        seedCompletedTracking(
            chapterIndex = 1,
            projectionId = "rebuilt-tracking-1",
            jobId = deterministicJobId(firstBinding),
            stageId = deterministicStageId(firstBinding),
            timelineId = null,
            createdAt = 126,
            rebuildBinding = firstBinding,
        )
        AggregateStateWriterRepository(database).write(
            AggregateStateWriteCommand(
                plan = planner.plan(request(1)),
                chapterIndex = 1,
                generatedAt = 127,
            ),
        )
        val repository = ChapterEditRebuildStageRepository(database)
        val secondCommand = ChapterEditRebuildTrackingStageCommand(
            executionId = prepared.executionId,
            userIntentJson = "{}",
            budgetSnapshotJson = "{}",
            createdAt = 130,
        )
        val second = repository.createNextRetainedTrackingStage(secondCommand)
        if (completeSecondChapter) {
            completeBoundTrackingStage(
                stageId = second.stageId,
                jobId = second.jobId,
                projectionId = "rebuilt-tracking-2",
                completedAt = 140,
            )
            AggregateStateWriterRepository(database).write(
                AggregateStateWriteCommand(
                    plan = planner.plan(request(1)),
                    chapterIndex = 2,
                    generatedAt = 141,
                ),
            )
        }
        return ThirdRetainedTrackingFixture(prepared.executionId, secondCommand)
    }

    private suspend fun completeBoundTrackingStage(
        stageId: String,
        jobId: String,
        projectionId: String,
        completedAt: Long,
    ) {
        val stage = requireNotNull(database.generationDao().findStage(stageId))
        val source = ChapterTrackingProjectionJobFactory.parseAndVerify(stage)
        val sql = database.openHelper.writableDatabase
        sql.execSQL(
            "UPDATE generation_stage SET status = 'SUCCEEDED', output_reference_json = '{}', " +
                "updated_at = $completedAt WHERE stage_id = '$stageId'",
        )
        sql.execSQL(
            "UPDATE generation_job SET status = 'COMPLETED', finished_at = $completedAt, " +
                "updated_at = $completedAt WHERE job_id = '$jobId'",
        )
        database.memoryDao().insertTrackingProjection(
            ChapterTrackingProjectionEntity(
                projectionId = projectionId,
                bookId = BOOK_ID,
                chapterVersionId = source.chapterVersionId,
                chapterIndex = source.chapterIndex,
                generationStageId = stageId,
                sourceChapterContentHash = source.chapterContentHash,
                sourceMemorySnapshotHash = source.memorySnapshotHash,
                priorForeshadowSnapshotHash = source.priorForeshadowSnapshotHash,
                outputContentHash = sha256("tracking-output-$projectionId"),
                payloadHash = sha256("tracking-payload-$projectionId"),
                status = DerivedDataStatus.VALID,
                modelSnapshotJson = "{}",
                timelineEventCount = 0,
                foreshadowTransitionCount = 0,
                createdAt = completedAt,
                updatedAt = completedAt,
            ),
        )
    }

    private suspend fun seedCompletedTracking(
        chapterIndex: Int,
        projectionId: String,
        jobId: String,
        stageId: String,
        timelineId: String?,
        createdAt: Long,
        rebuildBinding: ChapterEditRebuildStageBindingV1?,
    ) {
        val chapterId = "chapter-$chapterIndex"
        val versionId = if (chapterIndex == 1 && rebuildBinding != null) "chapter-1-v2" else "chapter-$chapterIndex-v1"
        val inputs = ChapterTrackingProjectionSourceRepository(database).loadForEditRebuild(chapterId)
        val setup = ChapterTrackingProjectionJobFactory.create(
            ChapterTrackingProjectionJobSpec(
                jobId = jobId,
                stageId = stageId,
                bookId = BOOK_ID,
                userIntentJson = "{}",
                budgetSnapshotJson = "{}",
                source = inputs.source,
                rebuildBinding = rebuildBinding,
                createdAt = createdAt - 1,
            ),
        )
        GenerationJobSetupRepository(database).create(setup)
        val sql = database.openHelper.writableDatabase
        sql.execSQL(
            "UPDATE generation_stage SET status = 'SUCCEEDED', output_reference_json = '{}', " +
                "updated_at = $createdAt WHERE stage_id = '$stageId'",
        )
        sql.execSQL(
            "UPDATE generation_job SET status = 'COMPLETED', finished_at = $createdAt, " +
                "updated_at = $createdAt WHERE job_id = '$jobId'",
        )
        val timeline = timelineId?.let { id ->
            TimelineEventEntity(
                timelineEventId = id,
                bookId = BOOK_ID,
                name = "旧时间线-$chapterIndex",
                participantsJson = "[]",
                locationEntityId = null,
                storyTimeExpression = "当天",
                storyOrder = chapterIndex.toLong(),
                constraintsJson = "{}",
                sourceChapterVersionId = versionId,
                status = DerivedDataStatus.VALID,
                createdAt = createdAt,
            )
        }
        if (timeline != null) database.memoryDao().insertTimelineEvents(listOf(timeline))
        database.memoryDao().insertTrackingProjection(
            ChapterTrackingProjectionEntity(
                projectionId = projectionId,
                bookId = BOOK_ID,
                chapterVersionId = versionId,
                chapterIndex = chapterIndex,
                generationStageId = stageId,
                sourceChapterContentHash = inputs.source.chapterContentHash,
                sourceMemorySnapshotHash = inputs.source.memorySnapshotHash,
                priorForeshadowSnapshotHash = inputs.source.priorForeshadowSnapshotHash,
                outputContentHash = sha256("tracking-output-$projectionId"),
                payloadHash = sha256("tracking-payload-$projectionId"),
                status = DerivedDataStatus.VALID,
                modelSnapshotJson = "{}",
                timelineEventCount = if (timeline == null) 0 else 1,
                foreshadowTransitionCount = 0,
                createdAt = createdAt,
                updatedAt = createdAt,
            ),
        )
        if (timeline != null) {
            MemorySearchIndexWriterV1.replaceStoryTrackingTimelines(
                database.memorySearchDao(),
                chapterIndex,
                listOf(timeline),
            )
        }
    }

    private fun summaryForCurrentVersion(index: Int) = ChapterSummaryEntity(
        chapterSummaryId = "current-summary-$index",
        bookId = BOOK_ID,
        chapterVersionId = "chapter-$index-v1",
        chapterIndex = index,
        schemaVersion = 1,
        summaryJson = "{}",
        importance = 70,
        status = DerivedDataStatus.VALID,
        modelSnapshotJson = null,
        createdAt = 30,
        updatedAt = 30,
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
                contentHash = sha256("snapshot-$bookId"),
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
        const val BOOK_ID = "rebuild-book"
        const val SECOND_BOOK_ID = "other-rebuild-book"
    }

    private data class RetainedTrackingFixture(
        val executionId: String,
        val targetBinding: ChapterEditRebuildStageBindingV1,
    )

    private data class ThirdRetainedTrackingFixture(
        val executionId: String,
        val secondChapterCommand: ChapterEditRebuildTrackingStageCommand,
    )
}

package app.zhijuan.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.zhijuan.core.database.generation.ChapterGenerationCommitDraft
import app.zhijuan.core.database.generation.ChapterGenerationCommitRepository
import app.zhijuan.core.database.generation.ChapterMemoryExtractionJobFactory
import app.zhijuan.core.database.generation.ChapterMemoryExtractionJobSpec
import app.zhijuan.core.database.generation.ChapterMemoryExtractionSourceV1
import app.zhijuan.core.database.generation.FinalUsageCommit
import app.zhijuan.core.database.generation.GenerationControlDisposition
import app.zhijuan.core.database.generation.GenerationControlReason
import app.zhijuan.core.database.generation.GenerationControlRepository
import app.zhijuan.core.database.generation.GenerationExecutionControl
import app.zhijuan.core.database.generation.GenerationDao
import app.zhijuan.core.database.generation.GenerationJobEntity
import app.zhijuan.core.database.generation.GenerationJobSetupRepository
import app.zhijuan.core.database.generation.GenerationLeaseToken
import app.zhijuan.core.database.generation.GenerationMaintenanceRepository
import app.zhijuan.core.database.generation.GenerationOutputValidationRepository
import app.zhijuan.core.database.generation.GenerationRequestAuditRepository
import app.zhijuan.core.database.generation.GenerationRecoveryDisposition
import app.zhijuan.core.database.generation.GenerationRunnerExecutionLeaseRepository
import app.zhijuan.core.database.generation.GenerationRunnerQueueRepository
import app.zhijuan.core.database.generation.GenerationRunnerStageRoute
import app.zhijuan.core.database.generation.GenerationStreamingDraftRepository
import app.zhijuan.core.database.generation.GenerationStageEntity
import app.zhijuan.core.database.generation.GenerationStateRepository
import app.zhijuan.core.database.generation.GenerationUnknownResultRecoveryRepository
import app.zhijuan.core.database.generation.ExpiredStageLeaseDisposition
import app.zhijuan.core.database.generation.NewRequestIntent
import app.zhijuan.core.database.generation.PersistedRequestSendPermit
import app.zhijuan.core.database.generation.PersistedStreamingRequest
import app.zhijuan.core.database.generation.RequestIntentDraft
import app.zhijuan.core.database.generation.StaleGenerationStateException
import app.zhijuan.core.database.generation.StreamingDraftRecoveryDisposition
import app.zhijuan.core.database.generation.StructuredOutputInvalidAction
import app.zhijuan.core.database.generation.UsageUpdate
import app.zhijuan.core.database.generation.ValidatedOutputCommitPermit
import app.zhijuan.core.database.library.BookCreationSnapshotEntity
import app.zhijuan.core.database.library.BookEntity
import app.zhijuan.core.database.library.ChapterEntity
import app.zhijuan.core.database.library.CommitChapterVersionCommand
import app.zhijuan.core.database.library.LibraryDao
import app.zhijuan.core.database.memory.CanonFactEntity
import app.zhijuan.core.database.memory.ChapterSummaryEntity
import app.zhijuan.core.database.memory.EntityEventEntity
import app.zhijuan.core.database.memory.ForeshadowItemEntity
import app.zhijuan.core.database.memory.MemoryDao
import app.zhijuan.core.database.memory.StoryEntity
import app.zhijuan.core.database.memory.TimelineEventEntity
import app.zhijuan.core.security.AndroidProtectedArtifactStore
import app.zhijuan.core.security.ProtectedArtifactType
import app.zhijuan.core.model.BookLengthMode
import app.zhijuan.core.model.BookStatus
import app.zhijuan.core.model.AdultStatus
import app.zhijuan.core.model.BudgetDailyPeriodKeyV1
import app.zhijuan.core.model.BudgetReservationStatus
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
import app.zhijuan.core.model.RequestAttemptStatus
import app.zhijuan.core.model.StandardErrorCode
import app.zhijuan.core.model.StoryEntityType
import app.zhijuan.core.model.TitleSource
import app.zhijuan.core.model.UsageLedgerStatus
import app.zhijuan.core.model.UsageSource
import app.zhijuan.core.task.AttemptEvent
import app.zhijuan.core.task.JobEvent
import app.zhijuan.core.task.ProviderRecoveryEvidence
import app.zhijuan.core.task.StageEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class GenerationDatabaseTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: ZhijuanDatabase
    private lateinit var libraryDao: LibraryDao
    private lateinit var generationDao: GenerationDao
    private lateinit var memoryDao: MemoryDao
    private lateinit var artifactStore: AndroidProtectedArtifactStore

    @Before
    fun setUp() = runBlocking {
        artifactStore = AndroidProtectedArtifactStore(context)
        cleanProtectedArtifacts()
        database = Room.inMemoryDatabaseBuilder(context, ZhijuanDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(LibraryDatabaseGuards.callback)
            .build()
            .also { it.openHelper.writableDatabase }
        libraryDao = database.libraryDao()
        generationDao = database.generationDao()
        memoryDao = database.memoryDao()
        seedBook()
        BudgetedRequestTestSupport.seedBudgetedRequestEnvironment(database, BOOK_ID)
    }

    @After
    fun tearDown() {
        runCatching { cleanProtectedArtifacts() }
        database.close()
    }

    @Test
    fun createJobFreezesStagesAndRejectsDuplicateIdempotencyKey() = runBlocking {
        val stages = listOf(stage("stage-1", "idem-1", 1L), stage("stage-2", "idem-2", 2L))
        generationDao.createJob(job(), stages)

        assertEquals("stage-1", generationDao.findJob(JOB_ID)?.currentStageId)
        assertEquals(listOf("stage-1", "stage-2"), generationDao.stagesForJob(JOB_ID).map { it.stageId })

        val secondJob = job("job-2")
        expectFailure {
            generationDao.createJob(secondJob, listOf(stage("stage-3", "idem-1", 3L, "job-2")))
        }
        assertEquals(null, generationDao.findJob("job-2"))
    }

    @Test
    fun currentStageCannotPointToAnotherJob() = runBlocking {
        generationDao.createJob(job(), listOf(stage("stage-1", "idem-1", 1L)))
        generationDao.createJob(
            job("job-2"),
            listOf(stage("stage-2", "idem-2", 2L, "job-2")),
        )

        expectFailure {
            database.openHelper.writableDatabase.execSQL(
                "UPDATE generation_job SET current_stage_id = 'stage-2' WHERE job_id = '$JOB_ID'",
            )
        }
        assertEquals("stage-1", generationDao.findJob(JOB_ID)?.currentStageId)
    }

    @Test
    fun jobAndStageTransitionsRejectStaleWritersAndLeaseRaces() = runBlocking {
        generationDao.createJob(job(), listOf(stage("stage-1", "idem-1", 1L)))
        val readyJob = generationDao.transitionJob(
            JOB_ID,
            GenerationJobStatus.CREATED,
            JobEvent.VALIDATION_PASSED,
            2L,
        )
        assertEquals(GenerationJobStatus.READY, readyJob.status)
        val staleJobError = expectFailure {
            generationDao.transitionJob(
                JOB_ID,
                GenerationJobStatus.CREATED,
                JobEvent.VALIDATION_PASSED,
                3L,
            )
        }
        assertTrue(staleJobError is StaleGenerationStateException)
        val runningJob = generationDao.acquireJobLease(JOB_ID, "job-worker-a", 4L)
        assertEquals(GenerationJobStatus.RUNNING, runningJob.status)
        assertEquals("job-worker-a", runningJob.leaseOwnerId)
        expectFailure { generationDao.acquireJobLease(JOB_ID, "job-worker-b", 5L) }

        generationDao.transitionStage(
            "stage-1",
            GenerationStageStatus.PENDING,
            StageEvent.DEPENDENCIES_SATISFIED,
            updatedAt = 2L,
        )
        val leased = generationDao.acquireStageLease("stage-1", "worker-a", 3L)
        assertEquals(GenerationStageStatus.PREPARING, leased.status)
        assertEquals("worker-a", leased.leaseOwnerId)
        expectFailure { generationDao.acquireStageLease("stage-1", "worker-b", 4L) }
        assertEquals("worker-a", generationDao.findStage("stage-1")?.leaseOwnerId)
    }

    @Test
    fun requestIntentAndUnknownUsageAreCreatedBeforeNetworkSend() = runBlocking {
        prepareSingleStage()
        val attempt = generationDao.recordRequestIntent(
            intent("attempt-1", "ledger-1", "stage-1"),
            stageLease("stage-1"),
        )

        assertEquals(RequestAttemptStatus.INTENT_RECORDED, attempt.status)
        assertEquals(1, attempt.attemptNo)
        assertEquals(GenerationStageStatus.REQUEST_INTENT_RECORDED, generationDao.findStage("stage-1")?.status)
        assertEquals(1, generationDao.findStage("stage-1")?.attemptCount)
        val usage = generationDao.findUsageForAttempt("attempt-1")
        assertEquals(UsageSource.UNKNOWN, usage?.source)
        assertEquals(null, usage?.totalTokens)
        assertEquals(null, usage?.estimatedCostMicros)
    }

    @Test
    fun sendAndUnknownResultMoveAttemptAndStageAtomicallyWithoutAutomaticRetry() = runBlocking {
        prepareSingleStage()
        generationDao.recordRequestIntent(
            intent("attempt-1", "ledger-1", "stage-1"),
            stageLease("stage-1"),
        )
        generationDao.recordRequestSent("attempt-1", "provider-request-1", 4L, stageLease("stage-1"))
        generationDao.recordStreamStarted("attempt-1", 5L, stageLease("stage-1"))
        val recovery = GenerationUnknownResultRecoveryRepository(database)
        recovery.markLiveAttemptUnknown(
            "attempt-1",
            stageLease("stage-1"),
            FinalUsageCommit.UNKNOWN,
            6L,
        )

        assertEquals(RequestAttemptStatus.UNKNOWN_RESULT, generationDao.findAttempt("attempt-1")?.status)
        assertEquals(GenerationStageStatus.UNKNOWN_RESULT, generationDao.findStage("stage-1")?.status)
        assertEquals(GenerationJobStatus.NEEDS_ACTION, generationDao.findJob(JOB_ID)?.status)
        assertEquals(StandardErrorCode.UNKNOWN_RESULT, generationDao.findStage("stage-1")?.standardErrorCode)
        assertEquals(null, generationDao.findUsageForAttempt("attempt-1")?.totalTokens)
        assertEquals(UsageLedgerStatus.FINAL, generationDao.findUsageForAttempt("attempt-1")?.status)

        expectFailure {
            generationDao.recordRequestIntent(
                intent("attempt-2", "ledger-2", "stage-1"),
                GenerationLeaseToken("worker-a", 3L),
            )
        }
        assertEquals(1, generationDao.attemptsForStage("stage-1").size)

        expectFailure {
            generationDao.transitionStage(
                "stage-1",
                GenerationStageStatus.UNKNOWN_RESULT,
                StageEvent.USER_CONFIRMED_RETRY,
                updatedAt = 7L,
            )
        }
        recovery.confirmRetry("attempt-1", 7L)
        generationDao.acquireJobLease(JOB_ID, "job-worker-retry", 8L)
        generationDao.acquireStageLease("stage-1", "worker-b", 8L)
        generationDao.recordRequestIntent(
            intent(
                "attempt-2",
                "ledger-2",
                "stage-1",
                retryParent = "attempt-1",
                createdAt = 8L,
            ),
            stageLease("stage-1"),
        )
        assertEquals("attempt-1", generationDao.findAttempt("attempt-2")?.retryParentAttemptId)
    }

    @Test
    fun expiredIntentOnlyIsUnknownBecauseProviderOpenMayHaveStartedBeforeCrash() = runBlocking {
        prepareSingleStage()
        val drafts = GenerationStreamingDraftRepository(database, artifactStore)
        drafts.prepareBeforeSend(
            publicIntent(
                "attempt-crash-window",
                "ledger-crash-window",
                "stage-1",
                streamDraftRef = null,
                createdAt = 4L,
            ),
            budget = BudgetedRequestTestSupport.budgetedDraft("attempt-crash-window"),
            stageLease("stage-1"),
        )
        val observedLease = stageLease("stage-1")

        val result = drafts.auditExpiredAttempt(
            attemptId = "attempt-crash-window",
            observedLease = observedLease,
            providerEvidence = ProviderRecoveryEvidence.NOT_AVAILABLE,
            auditedAt = 60_003L,
        )

        assertEquals(GenerationRecoveryDisposition.USER_CONFIRMATION_REQUIRED, result.disposition)
        assertEquals(RequestAttemptStatus.UNKNOWN_RESULT, generationDao.findAttempt("attempt-crash-window")?.status)
        assertEquals(GenerationStageStatus.UNKNOWN_RESULT, generationDao.findStage("stage-1")?.status)
        assertEquals(GenerationJobStatus.NEEDS_ACTION, generationDao.findJob(JOB_ID)?.status)
        assertEquals(UsageLedgerStatus.FINAL, generationDao.findUsageForAttempt("attempt-crash-window")?.status)
        assertEquals(1, generationDao.attemptsForStage("stage-1").size)
    }

    @Test
    fun concurrentUnknownResultConfirmationsReleaseTheStageExactlyOnce() = runBlocking {
        prepareSingleStage()
        generationDao.recordRequestIntent(
            intent("attempt-confirm-race", "ledger-confirm-race", "stage-1"),
            stageLease("stage-1"),
        )
        generationDao.recordRequestSent(
            "attempt-confirm-race",
            "remote-confirm-race",
            4L,
            stageLease("stage-1"),
        )
        generationDao.recordStreamStarted("attempt-confirm-race", 5L, stageLease("stage-1"))
        val recovery = GenerationUnknownResultRecoveryRepository(database)
        recovery.markLiveAttemptUnknown(
            "attempt-confirm-race",
            stageLease("stage-1"),
            FinalUsageCommit.UNKNOWN,
            6L,
        )

        val confirmations = coroutineScope {
            listOf(
                async(Dispatchers.IO) { runCatching { recovery.confirmRetry("attempt-confirm-race", 7L) } },
                async(Dispatchers.IO) { runCatching { recovery.confirmRetry("attempt-confirm-race", 7L) } },
            ).awaitAll()
        }

        assertEquals(1, confirmations.count { it.isSuccess })
        assertEquals(1, confirmations.count { it.isFailure })
        assertEquals(GenerationStageStatus.READY, generationDao.findStage("stage-1")?.status)
        assertEquals(GenerationJobStatus.READY, generationDao.findJob(JOB_ID)?.status)
        assertEquals(1, generationDao.attemptsForStage("stage-1").size)
    }

    @Test
    fun remoteInProgressWaitsWithoutRequestAndLaterInconclusiveCompletionNeedsConfirmation() = runBlocking {
        prepareSingleStage()
        val drafts = GenerationStreamingDraftRepository(database, artifactStore)
        val prepared = drafts.prepareBeforeSend(
            publicIntent(
                "attempt-pending",
                "ledger-pending",
                "stage-1",
                streamDraftRef = null,
                createdAt = 4L,
            ),
            budget = BudgetedRequestTestSupport.budgetedDraft("attempt-pending"),
            stageLease("stage-1"),
        )
        val claimed = drafts.claimForProviderOpen(prepared, 5L)
        drafts.markRequestSent(claimed, "remote-pending", 6L)
        drafts.markStreamStarted(claimed, 7L)

        val pending = drafts.auditExpiredAttempt(
            attemptId = "attempt-pending",
            observedLease = stageLease("stage-1"),
            providerEvidence = ProviderRecoveryEvidence.IN_PROGRESS,
            auditedAt = 60_005L,
        )

        assertEquals(GenerationRecoveryDisposition.WAITING_FOR_REMOTE_RESULT, pending.disposition)
        assertEquals(RequestAttemptStatus.STREAMING, generationDao.findAttempt("attempt-pending")?.status)
        assertEquals(GenerationStageStatus.RECOVERY_REQUIRED, generationDao.findStage("stage-1")?.status)
        assertEquals(UsageLedgerStatus.PROVISIONAL, generationDao.findUsageForAttempt("attempt-pending")?.status)
        assertEquals(null, generationDao.findStage("stage-1")?.leaseOwnerId)

        val waitingReservation = requireNotNull(
            database.budgetDao().findReservation("reservation-attempt-pending"),
        )
        assertEquals(BudgetReservationStatus.RESERVED, waitingReservation.status)
        assertEquals(BudgetedRequestTestSupport.BUDGETED_ESTIMATED_TOKENS, waitingReservation.accountedTokens)
        assertNull(waitingReservation.releasedAt)
        assertNull(waitingReservation.settledAt)

        val completedWithoutOutput = drafts.reconcilePendingAttempt(
            attemptId = "attempt-pending",
            providerEvidence = ProviderRecoveryEvidence.COMPLETED_WITHOUT_LOCAL_OUTPUT,
            providerUsage = FinalUsageCommit(
                source = UsageSource.PROVIDER_REPORTED,
                inputTokens = 10,
                outputTokens = 20,
                cachedTokens = null,
                reasoningTokens = null,
                totalTokens = 30,
            ),
            auditedAt = 60_006L,
        )

        assertEquals(GenerationRecoveryDisposition.USER_CONFIRMATION_REQUIRED, completedWithoutOutput.disposition)
        assertEquals(RequestAttemptStatus.UNKNOWN_RESULT, generationDao.findAttempt("attempt-pending")?.status)
        assertEquals(30L, generationDao.findUsageForAttempt("attempt-pending")?.totalTokens)
        assertEquals(UsageLedgerStatus.FINAL, generationDao.findUsageForAttempt("attempt-pending")?.status)
    }

    @Test
    fun authoritativeNotExecutedProofRequeuesOnlyAnEmptyUnchargedRequest() = runBlocking {
        prepareSingleStage()
        val drafts = GenerationStreamingDraftRepository(database, artifactStore)
        val prepared = drafts.prepareBeforeSend(
            publicIntent(
                "attempt-not-executed",
                "ledger-not-executed",
                "stage-1",
                streamDraftRef = null,
                createdAt = 4L,
            ),
            budget = BudgetedRequestTestSupport.budgetedDraft("attempt-not-executed"),
            stageLease("stage-1"),
        )
        val claimed = drafts.claimForProviderOpen(prepared, 5L)
        drafts.markRequestSent(claimed, "remote-not-executed", 6L)
        drafts.markStreamStarted(claimed, 7L)

        val result = drafts.auditExpiredAttempt(
            attemptId = "attempt-not-executed",
            observedLease = stageLease("stage-1"),
            providerEvidence = ProviderRecoveryEvidence.CONFIRMED_NOT_EXECUTED,
            auditedAt = 60_005L,
        )

        assertEquals(GenerationRecoveryDisposition.REQUEUED_AFTER_PROVIDER_PROOF, result.disposition)
        assertEquals(RequestAttemptStatus.FAILED_RETRYABLE, generationDao.findAttempt("attempt-not-executed")?.status)
        assertEquals(GenerationStageStatus.READY, generationDao.findStage("stage-1")?.status)
        assertEquals(GenerationJobStatus.READY, generationDao.findJob(JOB_ID)?.status)
        val usage = requireNotNull(generationDao.findUsageForAttempt("attempt-not-executed"))
        assertEquals(UsageLedgerStatus.FINAL, usage.status)
        assertEquals(UsageSource.UNKNOWN, usage.source)
        assertNull(usage.totalTokens)
        assertNull(usage.estimatedCostMicros)
        assertEquals(60_005L, usage.finalizedAt)

        val reservation = requireNotNull(
            database.budgetDao().findReservation("reservation-attempt-not-executed"),
        )
        assertEquals(BudgetReservationStatus.RELEASED, reservation.status)
        assertEquals(0L, reservation.accountedTokens)
        assertNull(reservation.accountedCostMicros)
        assertNull(reservation.accountedCurrency)
        assertEquals(60_005L, reservation.releasedAt)
        assertNull(reservation.settledAt)
        assertEquals(60_005L, reservation.updatedAt)
    }

    @Test
    fun localContentContradictionBlocksAutomaticRequeueDespiteProviderClaim() = runBlocking {
        prepareSingleStage()
        val drafts = GenerationStreamingDraftRepository(database, artifactStore)
        val prepared = drafts.prepareBeforeSend(
            publicIntent(
                "attempt-contradiction",
                "ledger-contradiction",
                "stage-1",
                streamDraftRef = null,
                createdAt = 4L,
            ),
            budget = BudgetedRequestTestSupport.budgetedDraft("attempt-contradiction"),
            stageLease("stage-1"),
        )
        val claimed = drafts.claimForProviderOpen(prepared, 5L)
        val buffer = drafts.openDraftBuffer(claimed)
        drafts.markRequestSent(claimed, "remote-contradiction", 6L)
        drafts.markStreamStarted(claimed, 7L)
        buffer.appendUtf8("partial", 8L)
        buffer.flush(8L)
        buffer.close()

        val result = drafts.auditExpiredAttempt(
            attemptId = "attempt-contradiction",
            observedLease = stageLease("stage-1"),
            providerEvidence = ProviderRecoveryEvidence.CONFIRMED_NOT_EXECUTED,
            auditedAt = 60_005L,
        )

        assertEquals(GenerationRecoveryDisposition.USER_CONFIRMATION_REQUIRED, result.disposition)
        assertEquals(RequestAttemptStatus.UNKNOWN_RESULT, generationDao.findAttempt("attempt-contradiction")?.status)
        assertEquals(GenerationStageStatus.UNKNOWN_RESULT, generationDao.findStage("stage-1")?.status)

        val reservation = requireNotNull(
            database.budgetDao().findReservation("reservation-attempt-contradiction"),
        )
        assertEquals(BudgetReservationStatus.SETTLED, reservation.status)
        assertEquals(BudgetedRequestTestSupport.BUDGETED_ESTIMATED_TOKENS, reservation.accountedTokens)
        assertNull(reservation.releasedAt)
        assertEquals(60_005L, reservation.settledAt)
    }

    @Test
    fun receivedResponseAfterCrashIsSentToLocalRecoveryWithoutAnotherModelCall() = runBlocking {
        prepareSingleStage()
        val drafts = GenerationStreamingDraftRepository(database, artifactStore)
        val outputs = GenerationOutputValidationRepository(database, artifactStore)
        completeStreamingResponse(
            drafts = drafts,
            outputs = outputs,
            attemptId = "attempt-local-recovery",
            ledgerId = "ledger-local-recovery",
            content = "{\"schemaVersion\":1,\"chapter\":\"recover\"}",
            createdAt = 4L,
        )

        val result = drafts.auditExpiredAttempt(
            attemptId = "attempt-local-recovery",
            observedLease = stageLease("stage-1"),
            providerEvidence = ProviderRecoveryEvidence.NOT_AVAILABLE,
            auditedAt = 60_005L,
        )

        assertEquals(GenerationRecoveryDisposition.LOCAL_RECOVERY_REQUIRED, result.disposition)
        assertEquals(RequestAttemptStatus.SUCCEEDED, generationDao.findAttempt("attempt-local-recovery")?.status)
        assertEquals(GenerationStageStatus.RECOVERY_REQUIRED, generationDao.findStage("stage-1")?.status)
        assertEquals(GenerationJobStatus.NEEDS_ACTION, generationDao.findJob(JOB_ID)?.status)
        assertEquals(1, generationDao.attemptsForStage("stage-1").size)
    }

    @Test
    fun attemptOutcomeAndStageMoveTogether() = runBlocking {
        prepareSingleStage()
        generationDao.recordRequestIntent(
            intent("attempt-1", "ledger-1", "stage-1"),
            stageLease("stage-1"),
        )
        generationDao.recordRequestSent("attempt-1", null, 4L, stageLease("stage-1"))
        val completed = generationDao.recordAttemptOutcome(
            attemptId = "attempt-1",
            event = AttemptEvent.RESPONSE_COMPLETED,
            errorCode = null,
            httpStatus = 200,
            outputHash = "output-hash",
            nextRetryAt = null,
            updatedAt = 5L,
            leaseToken = stageLease("stage-1"),
        )
        assertEquals(RequestAttemptStatus.SUCCEEDED, completed.status)
        assertEquals(GenerationStageStatus.VALIDATING, generationDao.findStage("stage-1")?.status)
        assertEquals("output-hash", completed.outputHash)
    }

    @Test
    fun duplicateAttemptInsertRollsBackLedgerAndStageCounter() = runBlocking {
        generationDao.createJob(
            job(),
            listOf(stage("stage-1", "idem-1", 1L), stage("stage-2", "idem-2", 2L)),
        )
        startJob()
        prepareStage("stage-1")
        prepareStage("stage-2")
        generationDao.recordRequestIntent(
            intent("shared-attempt", "ledger-1", "stage-1"),
            stageLease("stage-1"),
        )

        expectFailure {
            generationDao.recordRequestIntent(
                intent("shared-attempt", "ledger-2", "stage-2"),
                stageLease("stage-2"),
            )
        }

        assertEquals(GenerationStageStatus.PREPARING, generationDao.findStage("stage-2")?.status)
        assertEquals(0, generationDao.findStage("stage-2")?.attemptCount)
        assertEquals(null, generationDao.findUsageLedger("ledger-2"))
    }

    @Test
    fun lateProviderUsageCanCorrectEstimatedFinalExactlyOnce() = runBlocking {
        prepareSingleStage()
        generationDao.recordRequestIntent(
            intent("attempt-1", "ledger-1", "stage-1"),
            stageLease("stage-1"),
        )

        generationDao.recordUsage(
            "attempt-1",
            usage(
                source = UsageSource.ESTIMATED,
                status = UsageLedgerStatus.FINAL,
                total = 120,
                cost = 90,
                now = 4L,
            ),
        )
        val finalUpdate = usage(
            source = UsageSource.PROVIDER_REPORTED,
            status = UsageLedgerStatus.FINAL,
            total = 100,
            cost = 80,
            now = 5L,
        )
        val final = generationDao.recordUsage("attempt-1", finalUpdate)
        assertEquals(100L, final.totalTokens)
        assertEquals(UsageSource.PROVIDER_REPORTED, final.source)
        assertNotNull(final.finalizedAt)
        assertEquals(final, generationDao.recordUsage("attempt-1", finalUpdate.copy(updatedAt = 6L)))

        expectFailure {
            generationDao.recordUsage("attempt-1", finalUpdate.copy(totalTokens = 101, updatedAt = 7L))
        }
        expectFailure {
            database.openHelper.writableDatabase.execSQL(
                "UPDATE usage_ledger SET total_tokens = 0 WHERE attempt_id = 'attempt-1'",
            )
        }
        assertEquals(100L, generationDao.findUsageForAttempt("attempt-1")?.totalTokens)
    }

    @Test
    fun standaloneStageTransitionsCannotBypassAtomicBoundaries() = runBlocking {
        generationDao.createJob(job(), listOf(stage("stage-1", "idem-1", 1L)))
        startJob()
        generationDao.transitionStage(
            "stage-1",
            GenerationStageStatus.PENDING,
            StageEvent.DEPENDENCIES_SATISFIED,
            updatedAt = 2L,
        )

        expectFailure {
            generationDao.transitionStage(
                "stage-1",
                GenerationStageStatus.READY,
                StageEvent.LEASE_ACQUIRED,
                updatedAt = 3L,
            )
        }
        assertEquals(GenerationStageStatus.READY, generationDao.findStage("stage-1")?.status)

        generationDao.acquireStageLease("stage-1", "worker-a", 3L)
        expectFailure {
            generationDao.transitionStage(
                "stage-1",
                GenerationStageStatus.PREPARING,
                StageEvent.INPUT_FROZEN,
                updatedAt = 4L,
                leaseToken = stageLease("stage-1"),
            )
        }
        assertTrue(generationDao.attemptsForStage("stage-1").isEmpty())

        generationDao.recordRequestIntent(
            intent("attempt-1", "ledger-1", "stage-1"),
            stageLease("stage-1"),
        )
        expectFailure {
            generationDao.transitionStage(
                "stage-1",
                GenerationStageStatus.REQUEST_INTENT_RECORDED,
                StageEvent.REQUEST_SENT,
                updatedAt = 4L,
                leaseToken = stageLease("stage-1"),
            )
        }
        assertEquals(RequestAttemptStatus.INTENT_RECORDED, generationDao.findAttempt("attempt-1")?.status)

        generationDao.recordRequestSent("attempt-1", null, 4L, stageLease("stage-1"))
        expectFailure {
            generationDao.transitionStage(
                "stage-1",
                GenerationStageStatus.STREAMING,
                StageEvent.RESPONSE_COMPLETED,
                updatedAt = 5L,
                leaseToken = stageLease("stage-1"),
            )
        }
        assertEquals(GenerationStageStatus.STREAMING, generationDao.findStage("stage-1")?.status)

        generationDao.recordAttemptOutcome(
            attemptId = "attempt-1",
            event = AttemptEvent.RESPONSE_COMPLETED,
            errorCode = null,
            httpStatus = 200,
            outputHash = "output-hash",
            nextRetryAt = null,
            updatedAt = 5L,
            leaseToken = stageLease("stage-1"),
        )
        generationDao.transitionStage(
            "stage-1",
            GenerationStageStatus.VALIDATING,
            StageEvent.OUTPUT_VALID,
            updatedAt = 6L,
            leaseToken = stageLease("stage-1"),
        )
        expectFailure {
            generationDao.transitionStage(
                "stage-1",
                GenerationStageStatus.COMMITTING,
                StageEvent.COMMIT_SUCCEEDED,
                updatedAt = 7L,
                leaseToken = stageLease("stage-1"),
            )
        }
        assertEquals(GenerationStageStatus.COMMITTING, generationDao.findStage("stage-1")?.status)
    }

    @Test
    fun jobCannotCompleteUntilEveryStageSucceededAndTerminalTransitionClearsLease() = runBlocking {
        generationDao.createJob(
            job(),
            listOf(stage("stage-1", "idem-1", 1L), stage("stage-2", "idem-2", 1L)),
        )
        generationDao.transitionJob(
            JOB_ID,
            GenerationJobStatus.CREATED,
            JobEvent.VALIDATION_PASSED,
            2L,
        )
        generationDao.acquireJobLease(JOB_ID, "job-worker", 3L)

        expectFailure {
            generationDao.transitionJob(
                JOB_ID,
                GenerationJobStatus.RUNNING,
                JobEvent.ALL_STAGES_COMPLETED,
                4L,
                jobLease(),
            )
        }
        assertEquals(GenerationJobStatus.RUNNING, generationDao.findJob(JOB_ID)?.status)
        assertEquals(null, generationDao.findJob(JOB_ID)?.finishedAt)

        assertEquals(
            1,
            generationDao.compareAndSetStageStatus(
                "stage-1",
                GenerationStageStatus.PENDING,
                GenerationStageStatus.SUCCEEDED,
                null,
                null,
                4L,
            ),
        )
        assertEquals(
            1,
            generationDao.compareAndSetStageStatus(
                "stage-2",
                GenerationStageStatus.PENDING,
                GenerationStageStatus.SUCCEEDED,
                null,
                null,
                4L,
            ),
        )
        val completed = generationDao.transitionJob(
            JOB_ID,
            GenerationJobStatus.RUNNING,
            JobEvent.ALL_STAGES_COMPLETED,
            5L,
            jobLease(),
        )
        assertEquals(GenerationJobStatus.COMPLETED, completed.status)
        assertEquals(5L, completed.finishedAt)
        assertEquals(null, completed.leaseOwnerId)
        assertEquals(null, completed.leaseAcquiredAt)
        assertEquals(null, completed.leaseHeartbeatAt)
    }

    @Test
    fun waitingStatesClearLeasesAndTimestampsCannotMoveBackwards() = runBlocking {
        generationDao.createJob(job(), listOf(stage("stage-1", "idem-1", 1L)))
        generationDao.transitionJob(
            JOB_ID,
            GenerationJobStatus.CREATED,
            JobEvent.VALIDATION_PASSED,
            2L,
        )
        generationDao.acquireJobLease(JOB_ID, "job-worker", 3L)
        val paused = generationDao.transitionJob(
            JOB_ID,
            GenerationJobStatus.RUNNING,
            JobEvent.AUTO_PAUSED,
            4L,
            jobLease(),
        )
        assertEquals(GenerationJobStatus.PAUSED, paused.status)
        assertEquals(null, paused.leaseOwnerId)
        expectFailure {
            generationDao.transitionJob(
                JOB_ID,
                GenerationJobStatus.PAUSED,
                JobEvent.RESUME_APPROVED,
                3L,
            )
        }

        generationDao.transitionStage(
            "stage-1",
            GenerationStageStatus.PENDING,
            StageEvent.DEPENDENCIES_SATISFIED,
            updatedAt = 2L,
        )
        generationDao.acquireStageLease("stage-1", "stage-worker", 3L)
        val blocked = generationDao.transitionStage(
            "stage-1",
            GenerationStageStatus.PREPARING,
            StageEvent.PRECONDITION_BLOCKED,
            errorCode = StandardErrorCode.NETWORK_OFFLINE,
            updatedAt = 4L,
            leaseToken = stageLease("stage-1"),
        )
        assertEquals(GenerationStageStatus.BLOCKED, blocked.status)
        assertEquals(null, blocked.leaseOwnerId)
        assertEquals(StandardErrorCode.NETWORK_OFFLINE, blocked.standardErrorCode)

        val ready = generationDao.transitionStage(
            "stage-1",
            GenerationStageStatus.BLOCKED,
            StageEvent.CONDITION_RECOVERED,
            updatedAt = 5L,
        )
        assertEquals(GenerationStageStatus.READY, ready.status)
        assertEquals(null, ready.standardErrorCode)
        expectFailure {
            generationDao.acquireStageLease("stage-1", "late-clock", 4L)
        }
        Unit
    }

    @Test
    fun retryAndNeedsActionRecoveryRequirePersistedEvidence() = runBlocking {
        prepareSingleStage()
        generationDao.recordRequestIntent(
            intent("attempt-1", "ledger-1", "stage-1"),
            stageLease("stage-1"),
        )
        generationDao.recordRequestSent("attempt-1", null, 4L, stageLease("stage-1"))
        generationDao.recordAttemptOutcome(
            attemptId = "attempt-1",
            event = AttemptEvent.RESPONSE_COMPLETED,
            errorCode = null,
            httpStatus = 200,
            outputHash = "output-hash",
            nextRetryAt = null,
            updatedAt = 5L,
            leaseToken = stageLease("stage-1"),
        )

        expectFailure {
            generationDao.transitionStage(
                "stage-1",
                GenerationStageStatus.VALIDATING,
                StageEvent.RETRYABLE_FAILURE,
                nextRetryAt = 8L,
                updatedAt = 6L,
                leaseToken = stageLease("stage-1"),
            )
        }
        expectFailure {
            generationDao.transitionStage(
                "stage-1",
                GenerationStageStatus.VALIDATING,
                StageEvent.RETRYABLE_FAILURE,
                errorCode = StandardErrorCode.FORMAT_INVALID,
                nextRetryAt = 5L,
                updatedAt = 6L,
                leaseToken = stageLease("stage-1"),
            )
        }
        val waiting = generationDao.transitionStage(
            "stage-1",
            GenerationStageStatus.VALIDATING,
            StageEvent.RETRYABLE_FAILURE,
            errorCode = StandardErrorCode.FORMAT_INVALID,
            nextRetryAt = 8L,
            updatedAt = 6L,
            leaseToken = stageLease("stage-1"),
        )
        assertEquals(GenerationStageStatus.RETRY_WAIT, waiting.status)
        assertEquals(null, waiting.leaseOwnerId)
        expectFailure {
            generationDao.transitionStage(
                "stage-1",
                GenerationStageStatus.RETRY_WAIT,
                StageEvent.RETRY_DELAY_ELAPSED,
                updatedAt = 7L,
            )
        }
        val retryReady = generationDao.transitionStage(
            "stage-1",
            GenerationStageStatus.RETRY_WAIT,
            StageEvent.RETRY_DELAY_ELAPSED,
            updatedAt = 8L,
        )
        assertEquals(GenerationStageStatus.READY, retryReady.status)
        assertEquals(null, retryReady.standardErrorCode)
        assertEquals(null, retryReady.nextRetryAt)

        generationDao.compareAndSetStageStatus(
            "stage-1",
            GenerationStageStatus.READY,
            GenerationStageStatus.NEEDS_ACTION,
            StandardErrorCode.POLICY_REFUSAL,
            null,
            9L,
        )
        val resolved = generationDao.transitionStage(
            "stage-1",
            GenerationStageStatus.NEEDS_ACTION,
            StageEvent.ISSUE_RESOLVED,
            updatedAt = 10L,
        )
        assertEquals(GenerationStageStatus.READY, resolved.status)
        assertEquals(null, resolved.standardErrorCode)
    }

    @Test
    fun publicStateRepositoryReturnsPersistedSnapshotsWithoutExposingRawEntities() = runBlocking {
        generationDao.createJob(job(), listOf(stage("stage-1", "idem-1", 1L)))
        val repository = GenerationStateRepository(database)

        val readyJob = repository.transitionJob(
            jobId = JOB_ID,
            expectedStatus = GenerationJobStatus.CREATED,
            event = JobEvent.VALIDATION_PASSED,
            updatedAt = 2L,
        )
        val readyStage = repository.transitionStage(
            stageId = "stage-1",
            expectedStatus = GenerationStageStatus.PENDING,
            event = StageEvent.DEPENDENCIES_SATISFIED,
            updatedAt = 2L,
        )

        assertEquals(GenerationJobStatus.READY, readyJob.status)
        assertEquals("stage-1", readyJob.currentStageId)
        assertEquals(GenerationStageStatus.READY, readyStage.status)
        assertEquals(0, readyStage.attemptCount)
        assertEquals(readyJob, repository.findJob(JOB_ID))
        assertEquals(readyStage, repository.findStage("stage-1"))
        assertEquals(null, repository.findJob("missing-job"))
        assertEquals(null, repository.findStage("missing-stage"))
    }

    @Test
    fun parallelExecutorsCannotAcquireTheSameStageLease() = runBlocking {
        generationDao.createJob(job(), listOf(stage("stage-1", "idem-1", 1L)))
        val repository = GenerationStateRepository(database)
        repository.transitionStage(
            stageId = "stage-1",
            expectedStatus = GenerationStageStatus.PENDING,
            event = StageEvent.DEPENDENCIES_SATISFIED,
            updatedAt = 2L,
        )

        val attempts = coroutineScope {
            listOf("executor-a", "executor-b").map { owner ->
                async(Dispatchers.IO) {
                    runCatching { repository.acquireStageLease("stage-1", owner, 3L) }
                }
            }.awaitAll()
        }

        assertEquals(1, attempts.count { it.isSuccess })
        assertEquals(1, attempts.count { it.isFailure })
        val stored = requireNotNull(repository.findStage("stage-1"))
        assertEquals(GenerationStageStatus.PREPARING, stored.status)
        assertNotNull(stored.leaseToken)
        assertEquals(3L, stored.leaseHeartbeatAt)
    }

    @Test
    fun heartbeatExpiryRequeuesOnlyPreRequestWorkAndFencesTheOldExecutor() = runBlocking {
        generationDao.createJob(job(), listOf(stage("stage-1", "idem-1", 1L)))
        val repository = GenerationStateRepository(database)
        repository.transitionStage(
            stageId = "stage-1",
            expectedStatus = GenerationStageStatus.PENDING,
            event = StageEvent.DEPENDENCIES_SATISFIED,
            updatedAt = 2L,
        )
        val firstLease = requireNotNull(
            repository.acquireStageLease("stage-1", "executor-a", 3L).leaseToken,
        )
        val heartbeat = repository.heartbeatStageLease("stage-1", firstLease, 50_000L)
        assertEquals(50_000L, heartbeat.leaseHeartbeatAt)

        val stillActive = repository.reclaimExpiredStageLease("stage-1", firstLease, 109_999L)
        assertEquals(ExpiredStageLeaseDisposition.ACTIVE, stillActive.disposition)
        expectFailure {
            repository.heartbeatStageLease("stage-1", firstLease, 110_000L)
        }

        val recovered = repository.reclaimExpiredStageLease("stage-1", firstLease, 110_000L)
        assertEquals(ExpiredStageLeaseDisposition.REQUEUED_BEFORE_REQUEST, recovered.disposition)
        assertEquals(GenerationStageStatus.READY, recovered.stage.status)
        assertEquals(null, recovered.stage.leaseToken)

        val secondLease = requireNotNull(
            repository.acquireStageLease("stage-1", "executor-b", 110_001L).leaseToken,
        )
        expectFailure {
            repository.transitionStage(
                stageId = "stage-1",
                expectedStatus = GenerationStageStatus.PREPARING,
                event = StageEvent.PRECONDITION_BLOCKED,
                errorCode = StandardErrorCode.NETWORK_OFFLINE,
                updatedAt = 110_002L,
                leaseToken = firstLease,
            )
        }
        val renewed = repository.heartbeatStageLease("stage-1", secondLease, 110_002L)
        assertEquals("executor-b", renewed.leaseToken?.ownerId)
        val blocked = repository.transitionStage(
            stageId = "stage-1",
            expectedStatus = GenerationStageStatus.PREPARING,
            event = StageEvent.PRECONDITION_BLOCKED,
            errorCode = StandardErrorCode.NETWORK_OFFLINE,
            updatedAt = 110_003L,
            leaseToken = secondLease,
        )
        assertEquals(GenerationStageStatus.BLOCKED, blocked.status)
        assertEquals(null, blocked.leaseToken)
    }

    @Test
    fun expiredPostIntentLeaseRequiresRecoveryAuditInsteadOfBlindRetry() = runBlocking {
        prepareSingleStage()
        val lease = stageLease("stage-1")
        generationDao.recordRequestIntent(
            intent("attempt-1", "ledger-1", "stage-1"),
            lease,
        )
        val repository = GenerationStateRepository(database)

        val result = repository.reclaimExpiredStageLease("stage-1", lease, 60_003L)
        assertEquals(ExpiredStageLeaseDisposition.RECOVERY_AUDIT_REQUIRED, result.disposition)
        assertEquals(GenerationStageStatus.REQUEST_INTENT_RECORDED, result.stage.status)
        assertEquals(lease, result.stage.leaseToken)
        expectFailure {
            generationDao.recordRequestSent("attempt-1", null, 60_003L, lease)
        }
        expectFailure {
            repository.acquireStageLease("stage-1", "executor-b", 60_004L)
        }
        assertEquals(1, generationDao.attemptsForStage("stage-1").size)
    }

    @Test
    fun jobHeartbeatAndRunningTransitionsRequireTheCurrentLeaseToken() = runBlocking {
        generationDao.createJob(job(), listOf(stage("stage-1", "idem-1", 1L)))
        val repository = GenerationStateRepository(database)
        repository.transitionJob(
            jobId = JOB_ID,
            expectedStatus = GenerationJobStatus.CREATED,
            event = JobEvent.VALIDATION_PASSED,
            updatedAt = 2L,
        )
        val lease = requireNotNull(
            repository.acquireJobLease(JOB_ID, "job-executor-a", 3L).leaseToken,
        )

        expectFailure {
            repository.transitionJob(
                jobId = JOB_ID,
                expectedStatus = GenerationJobStatus.RUNNING,
                event = JobEvent.AUTO_PAUSED,
                updatedAt = 4L,
            )
        }
        val heartbeat = repository.heartbeatJobLease(JOB_ID, lease, 50L)
        assertEquals(50L, heartbeat.leaseHeartbeatAt)
        val paused = repository.transitionJob(
            jobId = JOB_ID,
            expectedStatus = GenerationJobStatus.RUNNING,
            event = JobEvent.AUTO_PAUSED,
            updatedAt = 51L,
            leaseToken = lease,
        )
        assertEquals(GenerationJobStatus.PAUSED, paused.status)
        assertEquals(null, paused.leaseToken)
        expectFailure { repository.heartbeatJobLease(JOB_ID, lease, 52L) }
        Unit
    }

    @Test
    fun publicRequestAuditIssuesPermitOnlyAfterAttemptUsageAndStageCommit() = runBlocking {
        prepareSingleStage()
        val repository = GenerationRequestAuditRepository(database)
        val audit = repository.persistBeforeSend(
            draft = publicIntent("attempt-public-1", "ledger-public-1", "stage-1"),
            budget = BudgetedRequestTestSupport.budgetedDraft("attempt-public-1"),
            leaseToken = stageLease("stage-1"),
        )

        assertEquals(RequestAttemptStatus.INTENT_RECORDED, audit.attempt.status)
        assertEquals(UsageSource.UNKNOWN, audit.usage.source)
        assertEquals(UsageLedgerStatus.PROVISIONAL, audit.usage.status)
        assertEquals(null, audit.usage.totalTokens)
        assertEquals(GenerationStageStatus.REQUEST_INTENT_RECORDED, generationDao.findStage("stage-1")?.status)
        val expectedDailyKey = BudgetDailyPeriodKeyV1.create(
            epochMillis = 3L,
            zoneId = BudgetedRequestTestSupport.BUDGETED_DAILY_ZONE,
        )
        val persistedAttempt = requireNotNull(generationDao.findAttempt("attempt-public-1"))
        assertEquals(1, persistedAttempt.budgetEnforcementVersion)
        assertEquals("reservation-attempt-public-1", persistedAttempt.budgetReservationId)
        assertEquals("reservation-attempt-public-1", audit.permit.reservationId)
        val reservation = requireNotNull(database.budgetDao().findReservation("reservation-attempt-public-1"))
        assertEquals(BudgetReservationStatus.RESERVED, reservation.status)
        assertEquals("attempt-public-1", reservation.attemptId)
        assertEquals("stage-1", reservation.stageId)
        assertEquals(BOOK_ID, reservation.bookId)
        assertEquals(expectedDailyKey, reservation.dailyPeriodKey)
        assertEquals(
            expectedDailyKey,
            requireNotNull(generationDao.findUsageForAttempt("attempt-public-1")).dailyPeriodKey,
        )
        assertEquals(
            expectedDailyKey,
            requireNotNull(generationDao.findUsageLedger("ledger-public-1")).dailyPeriodKey,
        )
        val claimed = repository.claimForProviderOpen(audit.permit, 4L)
        expectFailure { repository.claimForProviderOpen(audit.permit, 4L) }

        val sent = repository.markRequestSent(
            claimedSend = claimed,
            providerRequestId = "provider-request-fixture",
            sentAt = 5L,
        )
        assertEquals(RequestAttemptStatus.SENT, sent.status)
        assertEquals(GenerationStageStatus.STREAMING, generationDao.findStage("stage-1")?.status)
        assertEquals(sent, repository.findAttempt(sent.attemptId))
        assertEquals(audit.usage, repository.findUsageForAttempt(sent.attemptId))
        assertEquals(
            BudgetReservationStatus.RESERVED,
            database.budgetDao().findReservation("reservation-attempt-public-1")?.status,
        )
    }

    @Test
    fun publicRequestAuditRejectsOverBudgetBeforeLeavingAnyRequestHalfState() = runBlocking {
        prepareSingleStage()
        val repository = GenerationRequestAuditRepository(database)

        expectFailure {
            repository.persistBeforeSend(
                publicIntent("attempt-public-over-budget", "ledger-public-over-budget", "stage-1"),
                BudgetedRequestTestSupport.budgetedDraft(
                    attemptId = "attempt-public-over-budget",
                    requestMaxTokens = 1L,
                    estimatedTokens = 2L,
                ),
                stageLease("stage-1"),
            )
        }

        assertNull(generationDao.findAttempt("attempt-public-over-budget"))
        assertNull(generationDao.findUsageLedger("ledger-public-over-budget"))
        assertNull(database.budgetDao().findReservation("reservation-attempt-public-over-budget"))
        assertEquals(GenerationStageStatus.PREPARING, generationDao.findStage("stage-1")?.status)
        assertEquals(0, generationDao.findStage("stage-1")?.attemptCount)
    }

    @Test
    fun legacyV0AttemptCannotClaimProviderOpen() = runBlocking {
        prepareSingleStage()
        val lease = stageLease("stage-1")
        val legacyIntent = intent("attempt-legacy-v0", "ledger-legacy-v0", "stage-1")
        val attempt = generationDao.recordRequestIntent(legacyIntent, lease)
        assertEquals(0, attempt.budgetEnforcementVersion)
        assertNull(attempt.budgetReservationId)

        val forgedPermit = PersistedRequestSendPermit(
            attemptId = attempt.attemptId,
            stageId = attempt.stageId,
            attemptNo = attempt.attemptNo,
            inputHash = attempt.inputHash,
            leaseToken = lease,
            intentRecordedAt = attempt.requestIntentAt,
            reservationId = "reservation-forged-v0",
        )

        expectFailure {
            GenerationRequestAuditRepository(database).claimForProviderOpen(forgedPermit, 4L)
        }
        assertEquals(RequestAttemptStatus.INTENT_RECORDED, generationDao.findAttempt(attempt.attemptId)?.status)
        assertEquals(
            GenerationStageStatus.REQUEST_INTENT_RECORDED,
            generationDao.findStage("stage-1")?.status,
        )
        assertNull(database.budgetDao().findReservationByAttempt(attempt.attemptId))
    }

    @Test
    fun mismatchedOrReleasedReservationCannotClaimProviderOpen() = runBlocking {
        prepareSingleStage()
        val repository = GenerationRequestAuditRepository(database)
        val audit = repository.persistBeforeSend(
            publicIntent("attempt-budget-evidence", "ledger-budget-evidence", "stage-1"),
            BudgetedRequestTestSupport.budgetedDraft("attempt-budget-evidence"),
            stageLease("stage-1"),
        )
        val forgedPermit = PersistedRequestSendPermit(
            attemptId = audit.permit.attemptId,
            stageId = audit.permit.stageId,
            attemptNo = audit.permit.attemptNo,
            inputHash = audit.permit.inputHash,
            leaseToken = audit.permit.leaseToken,
            intentRecordedAt = audit.permit.intentRecordedAt,
            reservationId = "reservation-wrong-evidence",
        )
        expectFailure { repository.claimForProviderOpen(forgedPermit, 4L) }

        database.openHelper.writableDatabase.execSQL(
            """
            UPDATE request_budget_reservation
            SET status = 'RELEASED',
                accounted_tokens = 0,
                accounted_cost_micros = NULL,
                accounted_currency = NULL,
                updated_at = 4,
                released_at = 4
            WHERE budget_reservation_id = 'reservation-attempt-budget-evidence'
            """.trimIndent(),
        )
        expectFailure { repository.claimForProviderOpen(audit.permit, 4L) }

        assertEquals(RequestAttemptStatus.INTENT_RECORDED, repository.findAttempt(audit.attempt.attemptId)?.status)
        assertEquals(
            GenerationStageStatus.REQUEST_INTENT_RECORDED,
            generationDao.findStage("stage-1")?.status,
        )
        assertEquals(
            BudgetReservationStatus.RELEASED,
            database.budgetDao().findReservation("reservation-attempt-budget-evidence")?.status,
        )
    }

    @Test
    fun requestAuditRejectsSecretBearingSnapshotBeforeAnyDatabaseWrite() = runBlocking {
        prepareSingleStage()
        val repository = GenerationRequestAuditRepository(database)
        val unsafe = publicIntent(
            attemptId = "attempt-unsafe",
            ledgerId = "ledger-unsafe",
            stageId = "stage-1",
            connectionSnapshot = "{\"api_key\":\"must-not-persist\"}",
        )

        expectFailure {
            repository.persistBeforeSend(
                unsafe,
                BudgetedRequestTestSupport.budgetedDraft("attempt-unsafe"),
                stageLease("stage-1"),
            )
        }
        assertEquals(null, repository.findAttempt("attempt-unsafe"))
        assertEquals(null, repository.findUsageForAttempt("attempt-unsafe"))
        assertNull(database.budgetDao().findReservation("reservation-attempt-unsafe"))
        assertNull(database.budgetDao().findReservationByAttempt("attempt-unsafe"))
        assertEquals(0, generationDao.findStage("stage-1")?.attemptCount)
        assertEquals(GenerationStageStatus.PREPARING, generationDao.findStage("stage-1")?.status)
    }

    @Test
    fun requestAuditTransactionRollsBackAttemptAndCounterWhenLedgerConflicts() = runBlocking {
        prepareSingleStage()
        val repository = GenerationRequestAuditRepository(database)
        repository.persistBeforeSend(
            publicIntent("attempt-1", "shared-ledger", "stage-1"),
            BudgetedRequestTestSupport.budgetedDraft("attempt-1"),
            stageLease("stage-1"),
        )
        assertEquals(
            BudgetReservationStatus.RESERVED,
            database.budgetDao().findReservation("reservation-attempt-1")?.status,
        )
        generationDao.recordRequestSent("attempt-1", null, 4L, stageLease("stage-1"))
        val recovery = GenerationUnknownResultRecoveryRepository(database)
        recovery.markLiveAttemptUnknown(
            "attempt-1",
            stageLease("stage-1"),
            FinalUsageCommit.UNKNOWN,
            5L,
        )
        recovery.confirmRetry("attempt-1", 6L)
        generationDao.acquireJobLease(JOB_ID, "job-worker-retry", 7L)
        generationDao.acquireStageLease("stage-1", "worker-retry", 7L)

        expectFailure {
            repository.persistBeforeSend(
                publicIntent(
                    "attempt-2",
                    "shared-ledger",
                    "stage-1",
                    createdAt = 7L,
                    retryParent = "attempt-1",
                ),
                BudgetedRequestTestSupport.budgetedDraft("attempt-2"),
                stageLease("stage-1"),
            )
        }
        assertEquals(null, repository.findAttempt("attempt-2"))
        assertNull(database.budgetDao().findReservation("reservation-attempt-2"))
        assertNull(database.budgetDao().findReservationByAttempt("attempt-2"))
        val settledFirstReservation = requireNotNull(
            database.budgetDao().findReservation("reservation-attempt-1"),
        )
        assertEquals(BudgetReservationStatus.SETTLED, settledFirstReservation.status)
        assertEquals(1L, settledFirstReservation.accountedTokens)
        assertEquals(5L, settledFirstReservation.settledAt)
        assertEquals(1, generationDao.findStage("stage-1")?.attemptCount)
        assertEquals(GenerationStageStatus.PREPARING, generationDao.findStage("stage-1")?.status)
    }

    @Test
    fun expiredPersistedSendPermitCannotAuthorizeALateProviderSend() = runBlocking {
        prepareSingleStage()
        val repository = GenerationRequestAuditRepository(database)
        val audit = repository.persistBeforeSend(
            publicIntent("attempt-expired", "ledger-expired", "stage-1"),
            BudgetedRequestTestSupport.budgetedDraft("attempt-expired"),
            stageLease("stage-1"),
        )
        expectFailure {
            repository.claimForProviderOpen(audit.permit, 60_003L)
        }
        assertEquals(RequestAttemptStatus.INTENT_RECORDED, repository.findAttempt("attempt-expired")?.status)
        assertEquals(UsageLedgerStatus.PROVISIONAL, repository.findUsageForAttempt("attempt-expired")?.status)
        assertEquals(GenerationStageStatus.REQUEST_INTENT_RECORDED, generationDao.findStage("stage-1")?.status)
        assertEquals(1, generationDao.findStage("stage-1")?.attemptCount)
    }

    @Test
    fun streamingDraftRepositoryAllocatesProtectedReferenceAndCleansFailedPreparation() = runBlocking {
        prepareSingleStage()
        val repository = GenerationStreamingDraftRepository(database, artifactStore)
        val unsafe = publicIntent(
            attemptId = "attempt-stream-unsafe",
            ledgerId = "ledger-stream-unsafe",
            stageId = "stage-1",
            connectionSnapshot = "{\"authorization\":\"forbidden\"}",
            streamDraftRef = null,
        )

        expectFailure {
            repository.prepareBeforeSend(
                unsafe,
                BudgetedRequestTestSupport.budgetedDraft("attempt-stream-unsafe"),
                stageLease("stage-1"),
            )
        }
        assertTrue(artifactStore.listArtifactReferenceIds().isEmpty())
        assertEquals(null, generationDao.findAttempt("attempt-stream-unsafe"))
        assertEquals(GenerationStageStatus.PREPARING, generationDao.findStage("stage-1")?.status)

        val prepared = repository.prepareBeforeSend(
            publicIntent(
                attemptId = "attempt-stream-1",
                ledgerId = "ledger-stream-1",
                stageId = "stage-1",
                streamDraftRef = null,
            ),
            BudgetedRequestTestSupport.budgetedDraft("attempt-stream-1"),
            stageLease("stage-1"),
        )
        val artifactRef = requireNotNull(generationDao.findAttempt("attempt-stream-1")?.streamDraftRef)

        assertTrue(artifactRef.matches(Regex("[0-9a-f-]{36}")))
        assertEquals(listOf(artifactRef), artifactStore.listArtifactReferenceIds())
        assertEquals(ProtectedArtifactType.STREAM_DRAFT, artifactStore.descriptor(artifactRef).type)
        assertEquals(1, prepared.initialDraftRevision)
        assertEquals(RequestAttemptStatus.INTENT_RECORDED, prepared.attempt.status)
    }

    @Test
    fun interruptedStreamKeepsEncryptedDraftSeparateFromFormalChapter() = runBlocking {
        seedFormalChapter()
        prepareSingleStage()
        val repository = GenerationStreamingDraftRepository(database, artifactStore)
        val prepared = repository.prepareBeforeSend(
            publicIntent(
                attemptId = "attempt-stream-2",
                ledgerId = "ledger-stream-2",
                stageId = "stage-1",
                streamDraftRef = null,
            ),
            BudgetedRequestTestSupport.budgetedDraft("attempt-stream-2"),
            stageLease("stage-1"),
        )
        val claimed = repository.claimForProviderOpen(prepared, validatedAt = 4L)
        val buffer = repository.openDraftBuffer(claimed)
        repository.markRequestSent(claimed, "remote-fixture", sentAt = 5L)
        repository.markStreamStarted(claimed, startedAt = 6L)

        buffer.appendUtf8("未完成的新正文，不能覆盖已经提交的章节。", now = 7L)
        val checkpoint = buffer.flush(now = 7L)
        buffer.close()

        assertEquals(2, checkpoint.revision)
        assertEquals("chapter-version-baseline", libraryDao.findChapter("chapter-target")?.currentVersionId)
        assertEquals(
            listOf("原有正式正文"),
            libraryDao.versionsForChapter("chapter-target").map { it.content },
        )
        val recovery = repository.inspectAttempt("attempt-stream-2", now = 8L)
        assertEquals(StreamingDraftRecoveryDisposition.RECOVERY_REQUIRED, recovery.disposition)
        assertEquals(checkpoint.plaintextBytes.toLong(), recovery.plaintextBytes)
        expectFailure { repository.openDraftBuffer(claimed) }
        Unit
    }

    @Test
    fun committedDraftRetentionUsesExactOneDayBoundary() = runBlocking {
        prepareSingleStage()
        val repository = GenerationStreamingDraftRepository(database, artifactStore)
        val prepared = repository.prepareBeforeSend(
            publicIntent(
                attemptId = "attempt-retention",
                ledgerId = "ledger-retention",
                stageId = "stage-1",
                streamDraftRef = null,
            ),
            BudgetedRequestTestSupport.budgetedDraft("attempt-retention"),
            stageLease("stage-1"),
        )
        val claimed = repository.claimForProviderOpen(prepared, validatedAt = 4L)
        repository.openDraftBuffer(claimed).use { buffer ->
            repository.markRequestSent(claimed, null, sentAt = 5L)
            repository.markStreamStarted(claimed, startedAt = 6L)
            buffer.appendUtf8("已完整接收但尚未写入正式章的草稿", now = 7L)
            buffer.flush(now = 7L)
        }
        generationDao.recordAttemptOutcome(
            attemptId = "attempt-retention",
            event = AttemptEvent.RESPONSE_COMPLETED,
            errorCode = null,
            httpStatus = 200,
            outputHash = "output-hash-retention",
            nextRetryAt = null,
            updatedAt = 8L,
            leaseToken = stageLease("stage-1"),
        )
        database.openHelper.writableDatabase.execSQL(
            """
            UPDATE generation_stage
            SET status = 'SUCCEEDED', updated_at = 10,
                lease_owner_id = NULL, lease_acquired_at = NULL, lease_heartbeat_at = NULL
            WHERE stage_id = 'stage-1' AND status = 'VALIDATING'
            """.trimIndent(),
        )

        val day = 24L * 60L * 60L * 1_000L
        assertEquals(
            StreamingDraftRecoveryDisposition.RETAINED_AFTER_COMMITTED_SUCCESS,
            repository.inspectAttempt("attempt-retention", now = 10L + day - 1L).disposition,
        )
        assertEquals(
            StreamingDraftRecoveryDisposition.ELIGIBLE_FOR_SUCCESS_CLEANUP,
            repository.inspectAttempt("attempt-retention", now = 10L + day).disposition,
        )
        assertEquals(1, repository.cleanupExpired(now = 10L + day).deletedArtifacts)
        assertEquals(
            StreamingDraftRecoveryDisposition.EXPIRED_AND_REMOVED,
            repository.inspectAttempt("attempt-retention", now = 10L + day).disposition,
        )
    }

    @Test
    fun completedStreamHashAndValidOutputMoveOnlyToCommitting() = runBlocking {
        prepareSingleStage()
        val drafts = GenerationStreamingDraftRepository(database, artifactStore)
        val outputs = GenerationOutputValidationRepository(database, artifactStore)
        val response = completeStreamingResponse(
            drafts = drafts,
            outputs = outputs,
            attemptId = "attempt-valid-structure",
            ledgerId = "ledger-valid-structure",
            content = "{\"schemaVersion\":1,\"title\":\"有效\"}",
            createdAt = 3L,
        )

        assertEquals(RequestAttemptStatus.SUCCEEDED, generationDao.findAttempt("attempt-valid-structure")?.status)
        assertEquals(GenerationStageStatus.VALIDATING, generationDao.findStage("stage-1")?.status)
        outputs.openForValidation(response, maximumBytes = 64 * 1_024).use { lease ->
            lease.withBytes { bytes ->
                assertEquals("{\"schemaVersion\":1,\"title\":\"有效\"}", bytes.toString(Charsets.UTF_8))
            }
        }
        outputs.recordStructuredOutputValid(response, validatedAt = 9L)

        assertEquals(GenerationStageStatus.COMMITTING, generationDao.findStage("stage-1")?.status)
        assertEquals(null, libraryDao.findChapter("chapter-target"))
        assertNotNull(generationDao.findAttempt("attempt-valid-structure")?.outputHash)
    }

    @Test
    fun firstInvalidOutputAllowsOneRepairAndSecondInvalidOutputNeedsAction() = runBlocking {
        prepareSingleStage()
        val drafts = GenerationStreamingDraftRepository(database, artifactStore)
        val outputs = GenerationOutputValidationRepository(database, artifactStore)
        val first = completeStreamingResponse(
            drafts = drafts,
            outputs = outputs,
            attemptId = "attempt-format-1",
            ledgerId = "ledger-format-1",
            content = "{not-json-1",
            createdAt = 3L,
        )
        assertEquals(
            StructuredOutputInvalidAction.REPAIR_REQUIRED,
            outputs.recordStructuredOutputInvalid(first, repairEligible = true, validatedAt = 9L),
        )
        assertEquals(StandardErrorCode.FORMAT_INVALID, generationDao.findAttempt("attempt-format-1")?.standardErrorCode)
        assertEquals(GenerationStageStatus.RETRY_WAIT, generationDao.findStage("stage-1")?.status)
        assertEquals(UsageLedgerStatus.FINAL, generationDao.findUsageForAttempt("attempt-format-1")?.status)
        assertEquals(null, generationDao.findStage("stage-1")?.leaseOwnerId)

        generationDao.transitionStage(
            stageId = "stage-1",
            expectedStatus = GenerationStageStatus.RETRY_WAIT,
            event = StageEvent.RETRY_DELAY_ELAPSED,
            updatedAt = 9L,
        )
        generationDao.acquireStageLease("stage-1", "worker-repair", now = 10L)
        val second = completeStreamingResponse(
            drafts = drafts,
            outputs = outputs,
            attemptId = "attempt-format-2",
            ledgerId = "ledger-format-2",
            content = "{not-json-2",
            createdAt = 10L,
            retryParentAttemptId = "attempt-format-1",
        )
        assertEquals(
            StructuredOutputInvalidAction.NEEDS_ACTION,
            outputs.recordStructuredOutputInvalid(second, repairEligible = true, validatedAt = 16L),
        )

        assertEquals(StandardErrorCode.FORMAT_INVALID, generationDao.findAttempt("attempt-format-2")?.standardErrorCode)
        assertEquals(GenerationStageStatus.NEEDS_ACTION, generationDao.findStage("stage-1")?.status)
        assertEquals(UsageLedgerStatus.FINAL, generationDao.findUsageForAttempt("attempt-format-2")?.status)
        assertEquals(null, generationDao.findStage("stage-1")?.leaseOwnerId)
        assertEquals(2, generationDao.attemptsForStage("stage-1").count {
            it.standardErrorCode == StandardErrorCode.FORMAT_INVALID
        })
    }

    @Test
    fun nonrepairableOrAttemptExhaustedInvalidOutputPausesImmediately() = runBlocking {
        generationDao.createJob(
            job(),
            listOf(stage("stage-1", "idem-1", 1L).copy(maxAttempts = 1)),
        )
        startJob()
        prepareStage("stage-1")
        val drafts = GenerationStreamingDraftRepository(database, artifactStore)
        val outputs = GenerationOutputValidationRepository(database, artifactStore)
        val response = completeStreamingResponse(
            drafts = drafts,
            outputs = outputs,
            attemptId = "attempt-no-repair",
            ledgerId = "ledger-no-repair",
            content = "{not-json",
            createdAt = 3L,
        )

        assertEquals(
            StructuredOutputInvalidAction.NEEDS_ACTION,
            outputs.recordStructuredOutputInvalid(response, repairEligible = true, validatedAt = 9L),
        )
        assertEquals(GenerationStageStatus.NEEDS_ACTION, generationDao.findStage("stage-1")?.status)
        assertEquals(null, generationDao.findStage("stage-1")?.nextRetryAt)
    }

    @Test
    fun concurrentValidationCannotPersistTwoRepairDecisions() = runBlocking {
        prepareSingleStage()
        val drafts = GenerationStreamingDraftRepository(database, artifactStore)
        val outputs = GenerationOutputValidationRepository(database, artifactStore)
        val response = completeStreamingResponse(
            drafts = drafts,
            outputs = outputs,
            attemptId = "attempt-concurrent-validation",
            ledgerId = "ledger-concurrent-validation",
            content = "{not-json",
            createdAt = 3L,
        )
        val results = coroutineScope {
            listOf(
                async(Dispatchers.IO) {
                    runCatching { outputs.recordStructuredOutputInvalid(response, true, 9L) }
                },
                async(Dispatchers.IO) {
                    runCatching { outputs.recordStructuredOutputInvalid(response, true, 9L) }
                },
            ).awaitAll()
        }

        assertEquals(1, results.count(Result<StructuredOutputInvalidAction>::isSuccess))
        assertEquals(GenerationStageStatus.RETRY_WAIT, generationDao.findStage("stage-1")?.status)
        assertEquals(StandardErrorCode.FORMAT_INVALID, generationDao.findAttempt("attempt-concurrent-validation")?.standardErrorCode)
    }

    @Test
    fun chapterCommitAtomicallyPublishesDerivedDataUsageProgressAndNextStage() = runBlocking {
        val permit = prepareValidatedChapterCommit(withNextStage = true)
        val repository = ChapterGenerationCommitRepository(database, artifactStore)
        val draft = generatedChapterCommitDraft(nextStageId = "stage-2", committedAt = 10L)

        val result = repository.commit(permit, draft)

        assertTrue(!result.replayed)
        assertTrue(result.isCurrentVersion)
        assertEquals("chapter-version-generated", libraryDao.findChapter("chapter-target")?.currentVersionId)
        assertEquals(ChapterStatus.READY, libraryDao.findChapter("chapter-target")?.status)
        assertEquals(ConsistencyStatus.VALID, libraryDao.findChapter("chapter-target")?.consistencyStatus)
        assertEquals("正式章节正文-CANARY", libraryDao.findChapterVersion("chapter-version-generated")?.content)
        assertEquals("VALID", memoryDao.latestSummaryHistoryStatus("chapter-version-generated"))
        assertEquals(1L, scalarLong("SELECT COUNT(*) FROM entity_event"))
        assertEquals(1L, scalarLong("SELECT COUNT(*) FROM canon_fact"))
        assertEquals(1L, scalarLong("SELECT COUNT(*) FROM timeline_event"))
        assertEquals(1L, scalarLong("SELECT COUNT(*) FROM foreshadow_item"))
        assertEquals(UsageLedgerStatus.FINAL, generationDao.findUsageForAttempt("attempt-commit")?.status)
        assertEquals(UsageSource.PROVIDER_REPORTED, generationDao.findUsageForAttempt("attempt-commit")?.source)
        assertEquals(30L, generationDao.findUsageForAttempt("attempt-commit")?.totalTokens)
        assertEquals(GenerationStageStatus.SUCCEEDED, generationDao.findStage("stage-1")?.status)
        assertEquals(GenerationStageStatus.READY, generationDao.findStage("stage-2")?.status)
        assertEquals("stage-2", generationDao.findJob(JOB_ID)?.currentStageId)
        assertEquals(GenerationJobStatus.RUNNING, generationDao.findJob(JOB_ID)?.status)
        assertEquals(1, libraryDao.findBook(BOOK_ID)?.completedChapterCount)
        assertEquals("CHAPTER_READY:1", libraryDao.findBook(BOOK_ID)?.generationStatusSummary)
        assertTrue(generationDao.findStage("stage-1")?.outputReferenceJson?.contains("正式章节正文") == false)
    }

    @Test
    fun exactChapterCommitReplayWorksAfterEncryptedDraftCleanupWithoutDuplicatingRows() = runBlocking {
        val permit = prepareValidatedChapterCommit(withNextStage = true)
        val repository = ChapterGenerationCommitRepository(database, artifactStore)
        val draft = generatedChapterCommitDraft(nextStageId = "stage-2", committedAt = 10L)
        repository.commit(permit, draft)
        artifactStore.delete(requireNotNull(generationDao.findAttempt("attempt-commit")?.streamDraftRef))

        val replay = repository.commit(permit, draft)

        assertTrue(replay.replayed)
        assertEquals(1L, libraryDao.versionCount("chapter-target"))
        assertEquals(1L, scalarLong("SELECT COUNT(*) FROM chapter_summary"))
        assertEquals(1L, scalarLong("SELECT COUNT(*) FROM entity_event"))
        assertEquals(1, libraryDao.findBook(BOOK_ID)?.completedChapterCount)
        assertEquals("stage-2", generationDao.findJob(JOB_ID)?.currentStageId)
    }

    @Test
    fun derivedDataForeignKeyFailureRollsBackEveryPartOfChapterCommit() = runBlocking {
        val permit = prepareValidatedChapterCommit(withNextStage = true)
        val repository = ChapterGenerationCommitRepository(database, artifactStore)
        val valid = generatedChapterCommitDraft(nextStageId = "stage-2", committedAt = 10L)
        val broken = valid.copy(
            entityEvents = valid.entityEvents.map { it.copy(entityId = "missing-story-entity") },
        )

        expectFailure { repository.commit(permit, broken) }

        assertEquals(null, libraryDao.findChapter("chapter-target")?.currentVersionId)
        assertEquals(0L, libraryDao.versionCount("chapter-target"))
        assertEquals(0L, scalarLong("SELECT COUNT(*) FROM chapter_summary"))
        assertEquals(0L, scalarLong("SELECT COUNT(*) FROM entity_event"))
        assertEquals(UsageLedgerStatus.PROVISIONAL, generationDao.findUsageForAttempt("attempt-commit")?.status)
        assertEquals(GenerationStageStatus.COMMITTING, generationDao.findStage("stage-1")?.status)
        assertEquals(GenerationStageStatus.PENDING, generationDao.findStage("stage-2")?.status)
        assertEquals("stage-1", generationDao.findJob(JOB_ID)?.currentStageId)
        assertEquals(0, libraryDao.findBook(BOOK_ID)?.completedChapterCount)
    }

    @Test
    fun concurrentIdenticalChapterCommitsCreateOneVersionAndOneReplay() = runBlocking {
        val permit = prepareValidatedChapterCommit(withNextStage = true)
        val repository = ChapterGenerationCommitRepository(database, artifactStore)
        val draft = generatedChapterCommitDraft(nextStageId = "stage-2", committedAt = 10L)

        val results = coroutineScope {
            listOf(
                async(Dispatchers.IO) { runCatching { repository.commit(permit, draft) } },
                async(Dispatchers.IO) { runCatching { repository.commit(permit, draft) } },
            ).awaitAll()
        }

        assertEquals(2, results.count { it.isSuccess })
        assertEquals(1, results.mapNotNull(Result<app.zhijuan.core.database.generation.ChapterGenerationCommitResult>::getOrNull).count { it.replayed })
        assertEquals(1L, libraryDao.versionCount("chapter-target"))
        assertEquals(1L, scalarLong("SELECT COUNT(*) FROM chapter_summary"))
        assertEquals(1, libraryDao.findBook(BOOK_ID)?.completedChapterCount)
    }

    @Test
    fun chapterChangedAfterValidationCannotBeOverwrittenAndLeavesCommitRecoverable() = runBlocking {
        val permit = prepareValidatedChapterCommit(withNextStage = true)
        libraryDao.commitChapterVersion(
            CommitChapterVersionCommand(
                chapterVersionId = "chapter-version-user-edit",
                chapterId = "chapter-target",
                expectedCurrentVersionId = null,
                content = "用户先保存的新正文",
                contentHash = "user-edit-hash",
                source = ChapterVersionSource.USER_EDIT,
                generationStageId = null,
                modelSnapshotJson = null,
                createdAt = 10L,
            ),
        )
        val repository = ChapterGenerationCommitRepository(database, artifactStore)
        val draft = generatedChapterCommitDraft(nextStageId = "stage-2", committedAt = 11L)

        expectFailure { repository.commit(permit, draft) }

        assertEquals("chapter-version-user-edit", libraryDao.findChapter("chapter-target")?.currentVersionId)
        assertEquals(1L, libraryDao.versionCount("chapter-target"))
        assertEquals(0L, scalarLong("SELECT COUNT(*) FROM chapter_summary"))
        assertEquals(UsageLedgerStatus.PROVISIONAL, generationDao.findUsageForAttempt("attempt-commit")?.status)
        assertEquals(GenerationStageStatus.COMMITTING, generationDao.findStage("stage-1")?.status)
    }

    @Test
    fun finalChapterCommitCompletesSingleStageJobInTheSameTransaction() = runBlocking {
        val permit = prepareValidatedChapterCommit(withNextStage = false)
        val result = ChapterGenerationCommitRepository(database, artifactStore).commit(
            permit,
            generatedChapterCommitDraft(nextStageId = null, committedAt = 10L),
        )

        assertTrue(result.jobCompleted)
        assertEquals(GenerationStageStatus.SUCCEEDED, generationDao.findStage("stage-1")?.status)
        assertEquals(GenerationJobStatus.COMPLETED, generationDao.findJob(JOB_ID)?.status)
        assertEquals(10L, generationDao.findJob(JOB_ID)?.finishedAt)
        assertEquals(null, generationDao.findJob(JOB_ID)?.leaseOwnerId)
    }

    @Test
    fun unsuccessfulDraftRetentionUsesExactSevenDayBoundary() = runBlocking {
        prepareSingleStage()
        val repository = GenerationStreamingDraftRepository(database, artifactStore)
        val prepared = repository.prepareBeforeSend(
            publicIntent(
                attemptId = "attempt-failed-retention",
                ledgerId = "ledger-failed-retention",
                stageId = "stage-1",
                streamDraftRef = null,
            ),
            BudgetedRequestTestSupport.budgetedDraft("attempt-failed-retention"),
            stageLease("stage-1"),
        )
        val claimed = repository.claimForProviderOpen(prepared, validatedAt = 4L)
        repository.markRequestSent(claimed, null, sentAt = 5L)
        generationDao.recordAttemptOutcome(
            attemptId = "attempt-failed-retention",
            event = AttemptEvent.FINAL_FAILURE,
            errorCode = StandardErrorCode.FORMAT_INVALID,
            httpStatus = 200,
            outputHash = null,
            nextRetryAt = null,
            updatedAt = 8L,
            leaseToken = stageLease("stage-1"),
        )
        val sevenDays = 7L * 24L * 60L * 60L * 1_000L

        assertEquals(
            StreamingDraftRecoveryDisposition.RETAINED_AFTER_UNSUCCESSFUL_RESULT,
            repository.inspectAttempt("attempt-failed-retention", now = 8L + sevenDays - 1L).disposition,
        )
        assertEquals(
            StreamingDraftRecoveryDisposition.ELIGIBLE_FOR_UNSUCCESSFUL_CLEANUP,
            repository.inspectAttempt("attempt-failed-retention", now = 8L + sevenDays).disposition,
        )
        assertEquals(1, repository.cleanupExpired(now = 8L + sevenDays).deletedArtifacts)
    }

    @Test
    fun orphanDraftIsRetainedForOneDayBeforeCleanup() = runBlocking {
        val repository = GenerationStreamingDraftRepository(database, artifactStore)
        artifactStore.createAndClear(
            ProtectedArtifactType.STREAM_DRAFT,
            "orphan".toByteArray(Charsets.UTF_8),
            now = 10L,
        )
        val day = 24L * 60L * 60L * 1_000L

        assertEquals(
            StreamingDraftRecoveryDisposition.ORPHAN_RETAINED,
            repository.scanRecovery(now = 10L + day - 1L).single().disposition,
        )
        assertEquals(
            StreamingDraftRecoveryDisposition.ORPHAN_ELIGIBLE_FOR_CLEANUP,
            repository.scanRecovery(now = 10L + day).single().disposition,
        )
        assertEquals(1, repository.cleanupExpired(now = 10L + day).deletedArtifacts)
        assertTrue(artifactStore.listArtifactReferenceIds().isEmpty())
    }

    @Test
    fun preSendPauseIsAtomicResumableAndFencesTheOldLease() = runBlocking {
        prepareSingleStage()
        val oldLease = stageLease("stage-1")
        val controls = GenerationControlRepository(database)

        val paused = controls.requestPause(JOB_ID, requestedAt = 4L)

        assertEquals(GenerationControlDisposition.APPLIED, paused.disposition)
        assertEquals(GenerationJobStatus.PAUSED, generationDao.findJob(JOB_ID)?.status)
        assertEquals(GenerationControlReason.USER_PAUSE.name, generationDao.findJob(JOB_ID)?.pauseOrStopReason)
        assertEquals(GenerationStageStatus.READY, generationDao.findStage("stage-1")?.status)
        assertEquals(null, generationDao.findStage("stage-1")?.leaseOwnerId)
        assertTrue(generationDao.attemptsForStage("stage-1").isEmpty())
        expectFailure {
            generationDao.heartbeatStageLease(
                "stage-1",
                oldLease,
                now = 5L,
                policy = app.zhijuan.core.database.generation.GenerationLeasePolicy(),
            )
        }

        val resumed = controls.resume(JOB_ID, resumedAt = 5L)
        assertEquals(GenerationControlDisposition.APPLIED, resumed.disposition)
        assertEquals(GenerationJobStatus.READY, generationDao.findJob(JOB_ID)?.status)
        assertEquals(null, generationDao.findJob(JOB_ID)?.pauseOrStopReason)
        assertEquals(GenerationStageStatus.READY, generationDao.findStage("stage-1")?.status)
        assertTrue(generationDao.attemptsForStage("stage-1").isEmpty())
    }

    @Test
    fun systemForegroundTimeoutPauseHasADistinctResumableReasonAndNeverCreatesAnAttempt() = runBlocking {
        generationDao.createJob(job(), listOf(stage("stage-1", "idem-1", 1L)))
        generationDao.transitionJob(
            JOB_ID,
            GenerationJobStatus.CREATED,
            JobEvent.VALIDATION_PASSED,
            updatedAt = 2L,
        )
        val controls = GenerationControlRepository(database)

        val paused = controls.requestSystemForegroundTimeoutPause(JOB_ID, requestedAt = 3L)

        assertEquals(GenerationControlDisposition.APPLIED, paused.disposition)
        assertEquals(GenerationJobStatus.PAUSED, paused.jobStatus)
        assertEquals(
            GenerationControlReason.SYSTEM_FGS_TIMEOUT.name,
            generationDao.findJob(JOB_ID)?.pauseOrStopReason,
        )
        assertTrue(generationDao.attemptsForStage("stage-1").isEmpty())

        val resumed = controls.resume(JOB_ID, resumedAt = 4L)

        assertEquals(GenerationControlDisposition.APPLIED, resumed.disposition)
        assertEquals(GenerationJobStatus.READY, resumed.jobStatus)
        assertEquals(null, generationDao.findJob(JOB_ID)?.pauseOrStopReason)
        assertTrue(generationDao.attemptsForStage("stage-1").isEmpty())
    }

    @Test
    fun activePauseCancelsAttemptFinalizesUsageAndRejectsDelayedCallbacks() = runBlocking {
        prepareSingleStage()
        val drafts = GenerationStreamingDraftRepository(database, artifactStore)
        val prepared = drafts.prepareBeforeSend(
            publicIntent("attempt-pause", "ledger-pause", "stage-1", streamDraftRef = null),
            BudgetedRequestTestSupport.budgetedDraft("attempt-pause"),
            stageLease("stage-1"),
        )
        val claimed = drafts.claimForProviderOpen(prepared, validatedAt = 4L)
        val controls = GenerationControlRepository(database)

        val requested = controls.requestPause(JOB_ID, requestedAt = 5L)
        assertEquals(GenerationControlDisposition.SAFE_POINT_REQUIRED, requested.disposition)
        assertEquals(GenerationExecutionControl.PAUSE, drafts.executionControl(claimed))
        expectFailure { drafts.markRequestSent(claimed, null, sentAt = 6L) }

        val settled = drafts.settleExecutionControl(
            request = claimed,
            action = GenerationExecutionControl.PAUSE,
            usage = FinalUsageCommit.UNKNOWN,
            settledAt = 6L,
        )
        assertEquals(GenerationControlDisposition.APPLIED, settled.disposition)
        assertEquals(RequestAttemptStatus.CANCELLED, generationDao.findAttempt("attempt-pause")?.status)
        assertEquals(UsageLedgerStatus.FINAL, generationDao.findUsageForAttempt("attempt-pause")?.status)
        assertEquals(UsageSource.UNKNOWN, generationDao.findUsageForAttempt("attempt-pause")?.source)
        assertEquals(GenerationStageStatus.READY, generationDao.findStage("stage-1")?.status)
        assertEquals(GenerationJobStatus.PAUSED, generationDao.findJob(JOB_ID)?.status)
        expectFailure { drafts.markRequestSent(claimed, "late-provider-id", sentAt = 7L) }
        expectFailure { drafts.markStreamStarted(claimed, startedAt = 7L) }
        Unit
    }

    @Test
    fun stopSupersedesPauseAndCancelsEveryUnfinishedStageAtOneSafePoint() = runBlocking {
        generationDao.createJob(
            job(),
            listOf(stage("stage-1", "idem-1", 1L), stage("stage-2", "idem-2", 1L)),
        )
        startJob()
        prepareStage("stage-1")
        val drafts = GenerationStreamingDraftRepository(database, artifactStore)
        val prepared = drafts.prepareBeforeSend(
            publicIntent("attempt-stop", "ledger-stop", "stage-1", streamDraftRef = null),
            BudgetedRequestTestSupport.budgetedDraft("attempt-stop"),
            stageLease("stage-1"),
        )
        val claimed = drafts.claimForProviderOpen(prepared, validatedAt = 4L)
        drafts.markRequestSent(claimed, "remote-stop", sentAt = 5L)
        drafts.markStreamStarted(claimed, startedAt = 6L)
        val controls = GenerationControlRepository(database)

        controls.requestPause(JOB_ID, requestedAt = 7L)
        val stopping = controls.requestStop(JOB_ID, requestedAt = 8L)
        assertEquals(GenerationControlDisposition.SAFE_POINT_REQUIRED, stopping.disposition)
        assertEquals(GenerationExecutionControl.STOP, drafts.executionControl(claimed))
        expectFailure {
            drafts.settleExecutionControl(
                claimed,
                GenerationExecutionControl.PAUSE,
                FinalUsageCommit.UNKNOWN,
                settledAt = 9L,
            )
        }

        val stopped = drafts.settleExecutionControl(
            claimed,
            GenerationExecutionControl.STOP,
            FinalUsageCommit.UNKNOWN,
            settledAt = 9L,
        )
        assertEquals(GenerationControlDisposition.APPLIED, stopped.disposition)
        assertEquals(GenerationJobStatus.STOPPED, generationDao.findJob(JOB_ID)?.status)
        assertEquals(GenerationControlReason.USER_STOP.name, generationDao.findJob(JOB_ID)?.pauseOrStopReason)
        assertEquals(
            listOf(GenerationStageStatus.CANCELLED, GenerationStageStatus.CANCELLED),
            generationDao.stagesForJob(JOB_ID).map { it.status },
        )
        assertEquals(RequestAttemptStatus.CANCELLED, generationDao.findAttempt("attempt-stop")?.status)
        assertEquals(UsageLedgerStatus.FINAL, generationDao.findUsageForAttempt("attempt-stop")?.status)
        assertTrue(artifactStore.listArtifactReferenceIds().isNotEmpty())
    }

    @Test
    fun stoppedPausedJobIsIdempotentAndCannotResume() = runBlocking {
        prepareSingleStage()
        val controls = GenerationControlRepository(database)
        controls.requestPause(JOB_ID, requestedAt = 4L)

        val stopped = controls.requestStop(JOB_ID, requestedAt = 5L)
        val repeated = controls.requestStop(JOB_ID, requestedAt = 6L)

        assertEquals(GenerationControlDisposition.APPLIED, stopped.disposition)
        assertEquals(GenerationControlDisposition.ALREADY_APPLIED, repeated.disposition)
        assertEquals(GenerationJobStatus.STOPPED, generationDao.findJob(JOB_ID)?.status)
        assertEquals(GenerationStageStatus.CANCELLED, generationDao.findStage("stage-1")?.status)
        expectFailure { controls.resume(JOB_ID, resumedAt = 7L) }
        Unit
    }

    @Test
    fun expiredWorkerCanSettlePersistedPauseOnlyAtTheExactLeaseBoundary() = runBlocking {
        val leasePolicy = app.zhijuan.core.database.generation.GenerationLeasePolicy(
            heartbeatIntervalMillis = 100L,
            timeoutMillis = 300L,
        )
        prepareSingleStage()
        val drafts = GenerationStreamingDraftRepository(database, artifactStore, leasePolicy)
        val prepared = drafts.prepareBeforeSend(
            publicIntent("attempt-expired-pause", "ledger-expired-pause", "stage-1", streamDraftRef = null),
            BudgetedRequestTestSupport.budgetedDraft("attempt-expired-pause"),
            stageLease("stage-1"),
        )
        val claimed = drafts.claimForProviderOpen(prepared, validatedAt = 4L)
        val controls = GenerationControlRepository(database, leasePolicy)
        controls.requestPause(JOB_ID, requestedAt = 5L)

        expectFailure {
            controls.settleExpiredControl(
                attemptId = claimed.attemptId,
                observedLease = stageLease("stage-1"),
                now = 303L,
            )
        }
        val recovered = controls.settleExpiredControl(
            attemptId = claimed.attemptId,
            observedLease = stageLease("stage-1"),
            now = 304L,
        )

        assertEquals(GenerationControlDisposition.APPLIED, recovered.disposition)
        assertEquals(GenerationJobStatus.PAUSED, generationDao.findJob(JOB_ID)?.status)
        assertEquals(GenerationStageStatus.READY, generationDao.findStage("stage-1")?.status)
        assertEquals(RequestAttemptStatus.CANCELLED, generationDao.findAttempt(claimed.attemptId)?.status)
    }

    @Test
    fun pauseDuringLocalCommitPublishesCurrentChapterThenStopsBeforeNextStage() = runBlocking {
        val permit = prepareValidatedChapterCommit(withNextStage = true)
        val controls = GenerationControlRepository(database)

        val pausing = controls.requestPause(JOB_ID, requestedAt = 10L)
        assertEquals(GenerationControlDisposition.SAFE_POINT_REQUIRED, pausing.disposition)
        val committed = ChapterGenerationCommitRepository(database, artifactStore).commit(
            permit,
            generatedChapterCommitDraft(nextStageId = "stage-2", committedAt = 11L),
        )

        assertTrue(!committed.jobCompleted)
        assertEquals("chapter-version-generated", libraryDao.findChapter("chapter-target")?.currentVersionId)
        assertEquals(GenerationStageStatus.SUCCEEDED, generationDao.findStage("stage-1")?.status)
        assertEquals(GenerationStageStatus.READY, generationDao.findStage("stage-2")?.status)
        assertEquals(GenerationJobStatus.PAUSED, generationDao.findJob(JOB_ID)?.status)
        assertEquals("stage-2", generationDao.findJob(JOB_ID)?.currentStageId)
    }

    @Test
    fun pauseDuringValidationPersistsFailureEvidenceThenReachesPausedSafePoint() = runBlocking {
        prepareSingleStage()
        val drafts = GenerationStreamingDraftRepository(database, artifactStore)
        val outputs = GenerationOutputValidationRepository(database, artifactStore)
        val response = completeStreamingResponse(
            drafts = drafts,
            outputs = outputs,
            attemptId = "attempt-paused-validation",
            ledgerId = "ledger-paused-validation",
            content = "{invalid-json",
            createdAt = 3L,
        )
        val controls = GenerationControlRepository(database)

        controls.requestPause(JOB_ID, requestedAt = 9L)
        val outcome = outputs.recordStructuredOutputInvalid(
            response = response,
            repairEligible = true,
            validatedAt = 10L,
        )

        assertEquals(StructuredOutputInvalidAction.REPAIR_REQUIRED, outcome)
        assertEquals(StandardErrorCode.FORMAT_INVALID, generationDao.findAttempt(response.attemptId)?.standardErrorCode)
        assertEquals(GenerationStageStatus.RETRY_WAIT, generationDao.findStage("stage-1")?.status)
        assertEquals(GenerationJobStatus.PAUSED, generationDao.findJob(JOB_ID)?.status)
        assertEquals(GenerationControlReason.USER_PAUSE.name, generationDao.findJob(JOB_ID)?.pauseOrStopReason)
    }

    private suspend fun prepareValidatedChapterCommit(withNextStage: Boolean): ValidatedOutputCommitPermit {
        libraryDao.createChapter(
            ChapterEntity(
                chapterId = "chapter-target",
                bookId = BOOK_ID,
                chapterIndex = 1,
                plannedTitle = "第一章",
                displayTitle = "第一章",
                status = ChapterStatus.PLANNED,
                consistencyStatus = ConsistencyStatus.UNKNOWN,
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )
        memoryDao.insertStoryEntity(
            StoryEntity(
                entityId = "story-person",
                bookId = BOOK_ID,
                entityType = StoryEntityType.CHARACTER,
                canonicalName = "测试人物",
                aliasesJson = "[]",
                stableDefinitionJson = "{}",
                adultStatus = AdultStatus.CONFIRMED_ADULT,
                ageYears = 24,
                sourceBibleRevisionId = null,
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )
        val stages = buildList {
            add(stage("stage-1", "idem-1", 1L))
            if (withNextStage) add(stage("stage-2", "idem-2", 1L))
        }
        generationDao.createJob(job(), stages)
        generationDao.transitionJob(
            jobId = JOB_ID,
            expectedStatus = GenerationJobStatus.CREATED,
            event = JobEvent.VALIDATION_PASSED,
            updatedAt = 2L,
        )
        generationDao.acquireJobLease(JOB_ID, "job-worker", now = 3L)
        prepareStage("stage-1")
        val drafts = GenerationStreamingDraftRepository(database, artifactStore)
        val outputs = GenerationOutputValidationRepository(database, artifactStore)
        val response = completeStreamingResponse(
            drafts = drafts,
            outputs = outputs,
            attemptId = "attempt-commit",
            ledgerId = "ledger-commit",
            content = "{\"schemaVersion\":1,\"chapter\":\"validated\"}",
            createdAt = 3L,
        )
        return outputs.recordStructuredOutputValid(response, validatedAt = 9L)
    }

    private fun generatedChapterCommitDraft(
        nextStageId: String?,
        committedAt: Long,
    ): ChapterGenerationCommitDraft {
        val versionId = "chapter-version-generated"
        return ChapterGenerationCommitDraft(
            chapterVersionId = versionId,
            chapterId = "chapter-target",
            expectedCurrentVersionId = null,
            content = "正式章节正文-CANARY",
            summary = ChapterSummaryEntity(
                chapterSummaryId = "summary-generated",
                bookId = BOOK_ID,
                chapterVersionId = versionId,
                chapterIndex = 1,
                schemaVersion = 1,
                summaryJson = "{\"summary\":\"第一章发生的事\"}",
                importance = 80,
                status = DerivedDataStatus.VALID,
                modelSnapshotJson = "{\"model\":\"fixture\"}",
                createdAt = committedAt,
                updatedAt = committedAt,
            ),
            entityEvents = listOf(
                EntityEventEntity(
                    entityEventId = "entity-event-generated",
                    bookId = BOOK_ID,
                    entityId = "story-person",
                    sourceChapterVersionId = versionId,
                    storyOrder = 1L,
                    attributeKey = "location",
                    oldValueJson = null,
                    newValueJson = "{\"place\":\"起点\"}",
                    storyTimeExpression = "第一天",
                    confidenceMicros = 950_000,
                    canonLevel = CanonLevel.STORY_CANON,
                    evidenceJson = "{\"source\":\"chapter\"}",
                    status = DerivedDataStatus.VALID,
                    createdAt = committedAt,
                ),
            ),
            canonFacts = listOf(
                CanonFactEntity(
                    canonFactId = "canon-fact-generated",
                    bookId = BOOK_ID,
                    entityId = "story-person",
                    factText = "人物到达起点",
                    factPayloadJson = "{\"location\":\"起点\"}",
                    canonLevel = CanonLevel.STORY_CANON,
                    scopeJson = "{\"fromChapter\":1}",
                    sourceChapterVersionId = versionId,
                    sourceBibleRevisionId = null,
                    validFromStoryOrder = 1L,
                    validToStoryOrder = null,
                    conflictGroupId = null,
                    status = DerivedDataStatus.VALID,
                    createdAt = committedAt,
                ),
            ),
            timelineEvents = listOf(
                TimelineEventEntity(
                    timelineEventId = "timeline-event-generated",
                    bookId = BOOK_ID,
                    name = "抵达起点",
                    participantsJson = "[\"story-person\"]",
                    locationEntityId = null,
                    storyTimeExpression = "第一天",
                    storyOrder = 1L,
                    constraintsJson = "{}",
                    sourceChapterVersionId = versionId,
                    status = DerivedDataStatus.VALID,
                    createdAt = committedAt,
                ),
            ),
            foreshadows = listOf(
                ForeshadowItemEntity(
                    foreshadowItemId = "foreshadow-generated",
                    bookId = BOOK_ID,
                    description = "尚未打开的信封",
                    foreshadowStatus = ForeshadowStatus.PLANTED,
                    memoryStatus = DerivedDataStatus.VALID,
                    targetStartChapterIndex = 2,
                    targetEndChapterIndex = 20,
                    sourceChapterVersionId = versionId,
                    plantedChapterVersionId = versionId,
                    resolvedChapterVersionId = null,
                    visibleEntityIdsJson = "[\"story-person\"]",
                    importance = 70,
                    source = MemorySource.CHAPTER_EXTRACTION,
                    createdAt = committedAt,
                    updatedAt = committedAt,
                ),
            ),
            usage = FinalUsageCommit(
                source = UsageSource.PROVIDER_REPORTED,
                inputTokens = 10,
                outputTokens = 20,
                cachedTokens = 0,
                reasoningTokens = 0,
                totalTokens = 30,
            ),
            nextStageId = nextStageId,
            committedAt = committedAt,
        )
    }

    private fun scalarLong(sql: String): Long =
        database.openHelper.writableDatabase.query(sql).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        }

    @Test
    fun maintenanceScanUsesExactExpiryAndReturnsOnlyPersistedEvidence() = runBlocking {
        prepareSingleStage()
        val repository = GenerationMaintenanceRepository(database)

        assertEquals(
            emptyList<Any>(),
            repository.scanExpiredExecutionLeases(observedAt = 60_002L).candidates,
        )
        val scan = repository.scanExpiredExecutionLeases(observedAt = 60_003L)
        val candidate = scan.candidates.single()

        assertEquals(JOB_ID, candidate.jobId)
        assertEquals(GenerationJobStatus.RUNNING, candidate.jobStatus)
        assertEquals("stage-1", candidate.stageId)
        assertEquals(GenerationStageStatus.PREPARING, candidate.stageStatus)
        assertEquals(null, candidate.latestAttemptId)
        assertEquals(jobLease(), candidate.observedJobLease)
        assertEquals(3L, candidate.jobLeaseHeartbeatAt)
        assertEquals(stageLease("stage-1"), candidate.observedLease)
        assertEquals(3L, candidate.leaseHeartbeatAt)
        assertEquals(false, scan.hasMore)
        assertEquals(false, candidate.toString().contains(JOB_ID))

        repository.requeueExpiredPreRequestExecution(candidate, observedAt = 60_003L)
        assertEquals(GenerationJobStatus.READY, generationDao.findJob(JOB_ID)?.status)
        assertEquals(GenerationStageStatus.READY, generationDao.findStage("stage-1")?.status)
        assertEquals(null, generationDao.findJob(JOB_ID)?.leaseOwnerId)
        assertEquals(null, generationDao.findStage("stage-1")?.leaseOwnerId)
    }

    @Test
    fun maintenanceScanIsBoundedAndOrderedByOldestHeartbeat() = runBlocking {
        listOf(
            Triple("job-maint-a", "stage-maint-a", 3L),
            Triple("job-maint-b", "stage-maint-b", 4L),
            Triple("job-maint-c", "stage-maint-c", 5L),
        ).forEachIndexed { index, (jobId, stageId, leaseAt) ->
            generationDao.createJob(
                job(jobId),
                listOf(stage(stageId, "idem-maint-$index", 1L, jobId)),
            )
            generationDao.transitionJob(
                jobId,
                GenerationJobStatus.CREATED,
                JobEvent.VALIDATION_PASSED,
                2L,
            )
            generationDao.acquireJobLease(jobId, "job-worker-$index", leaseAt)
            generationDao.transitionStage(
                stageId,
                GenerationStageStatus.PENDING,
                StageEvent.DEPENDENCIES_SATISFIED,
                updatedAt = 2L,
            )
            generationDao.acquireStageLease(stageId, "stage-worker-$index", leaseAt)
        }

        val scan = GenerationMaintenanceRepository(database).scanExpiredExecutionLeases(
            observedAt = 60_005L,
            limit = 2,
        )

        assertEquals(listOf("stage-maint-a", "stage-maint-b"), scan.candidates.map { it.stageId })
        assertEquals(true, scan.hasMore)
    }

    @Test
    fun maintenanceRequeuesExpiredIdleRunningJobLeaseWithoutTouchingTheStage() = runBlocking {
        generationDao.createJob(job(), listOf(stage("stage-1", "idem-1", 1L)))
        startJob()
        generationDao.transitionStage(
            "stage-1",
            GenerationStageStatus.PENDING,
            StageEvent.DEPENDENCIES_SATISFIED,
            updatedAt = 2L,
        )
        val repository = GenerationMaintenanceRepository(database)

        val scan = repository.scanExpiredIdleJobLeases(observedAt = 60_003L)
        val candidate = scan.candidates.single()

        assertEquals(JOB_ID, candidate.jobId)
        assertEquals(GenerationJobStatus.RUNNING, candidate.jobStatus)
        assertEquals("stage-1", candidate.currentStageId)
        assertEquals(GenerationStageStatus.READY, candidate.currentStageStatus)
        assertEquals(jobLease(), candidate.observedJobLease)
        assertEquals(3L, candidate.jobLeaseHeartbeatAt)
        assertEquals(false, scan.hasMore)
        assertEquals(false, candidate.toString().contains(JOB_ID))

        val tamperedHeartbeat = candidate.copy(jobLeaseHeartbeatAt = candidate.jobLeaseHeartbeatAt - 1L)
        val tamperedError = expectFailure {
            repository.requeueExpiredIdleJobLease(tamperedHeartbeat, observedAt = 60_003L)
        }
        assertTrue(tamperedError is StaleGenerationStateException)
        assertEquals(GenerationJobStatus.RUNNING, generationDao.findJob(JOB_ID)?.status)

        repository.requeueExpiredIdleJobLease(candidate, observedAt = 60_003L)

        val jobAfter = requireNotNull(generationDao.findJob(JOB_ID))
        assertEquals(GenerationJobStatus.READY, jobAfter.status)
        assertEquals("stage-1", jobAfter.currentStageId)
        assertEquals(null, jobAfter.leaseOwnerId)
        assertEquals(null, jobAfter.leaseAcquiredAt)
        assertEquals(null, jobAfter.leaseHeartbeatAt)
        val stageAfter = requireNotNull(generationDao.findStage("stage-1"))
        assertEquals(GenerationStageStatus.READY, stageAfter.status)
        assertEquals(0, stageAfter.attemptCount)
        assertEquals(2L, stageAfter.updatedAt)
        assertEquals(null, stageAfter.leaseOwnerId)
        assertEquals(null, stageAfter.leaseAcquiredAt)
        assertEquals(null, stageAfter.leaseHeartbeatAt)
        assertEquals(null, stageAfter.standardErrorCode)
        assertEquals(null, stageAfter.nextRetryAt)
        assertEquals(0, generationDao.attemptsForStage("stage-1").size)
    }

    @Test
    fun maintenanceOnlyScansIdleJobLeaseAtOrPastTheTimeoutBoundary() = runBlocking {
        generationDao.createJob(job(), listOf(stage("stage-1", "idem-1", 1L)))
        startJob()
        generationDao.transitionStage(
            "stage-1",
            GenerationStageStatus.PENDING,
            StageEvent.DEPENDENCIES_SATISFIED,
            updatedAt = 2L,
        )
        val repository = GenerationMaintenanceRepository(database)

        val beforeExpiry = repository.scanExpiredIdleJobLeases(observedAt = 60_002L)
        assertEquals(emptyList<Any>(), beforeExpiry.candidates)
        assertEquals(false, beforeExpiry.hasMore)

        val atBoundary = repository.scanExpiredIdleJobLeases(observedAt = 60_003L)
        assertEquals(1, atBoundary.candidates.size)
        assertEquals(3L, atBoundary.candidates.single().jobLeaseHeartbeatAt)
        assertEquals(false, atBoundary.hasMore)
    }

    @Test
    fun maintenanceRequeueFailsWhenAnotherExecutorClaimedTheIdleStage() = runBlocking {
        generationDao.createJob(job(), listOf(stage("stage-1", "idem-1", 1L)))
        startJob()
        generationDao.transitionStage(
            "stage-1",
            GenerationStageStatus.PENDING,
            StageEvent.DEPENDENCIES_SATISFIED,
            updatedAt = 2L,
        )
        val repository = GenerationMaintenanceRepository(database)
        val candidate = repository.scanExpiredIdleJobLeases(observedAt = 60_003L).candidates.single()
        generationDao.acquireStageLease("stage-1", "worker-b", 60_004L)

        val error = expectFailure {
            repository.requeueExpiredIdleJobLease(candidate, observedAt = 60_003L)
        }
        assertTrue(error is StaleGenerationStateException)

        val jobAfter = requireNotNull(generationDao.findJob(JOB_ID))
        assertEquals(GenerationJobStatus.RUNNING, jobAfter.status)
        assertEquals("job-worker", jobAfter.leaseOwnerId)
        val stageAfter = requireNotNull(generationDao.findStage("stage-1"))
        assertEquals(GenerationStageStatus.PREPARING, stageAfter.status)
        assertEquals("worker-b", stageAfter.leaseOwnerId)
        assertEquals(60_004L, stageAfter.leaseAcquiredAt)
    }

    @Test
    fun concurrentMaintenanceRequeuesOfTheSameIdleJobSucceedExactlyOnce() = runBlocking {
        generationDao.createJob(job(), listOf(stage("stage-1", "idem-1", 1L)))
        startJob()
        generationDao.transitionStage(
            "stage-1",
            GenerationStageStatus.PENDING,
            StageEvent.DEPENDENCIES_SATISFIED,
            updatedAt = 2L,
        )
        val repository = GenerationMaintenanceRepository(database)
        val candidate = repository.scanExpiredIdleJobLeases(observedAt = 60_003L).candidates.single()

        val requeues = coroutineScope {
            listOf(
                async(Dispatchers.IO) {
                    runCatching { repository.requeueExpiredIdleJobLease(candidate, 60_003L) }
                },
                async(Dispatchers.IO) {
                    runCatching { repository.requeueExpiredIdleJobLease(candidate, 60_003L) }
                },
            ).awaitAll()
        }

        assertEquals(1, requeues.count { it.isSuccess })
        assertEquals(1, requeues.count { it.isFailure })
        val jobAfter = requireNotNull(generationDao.findJob(JOB_ID))
        assertEquals(GenerationJobStatus.READY, jobAfter.status)
        assertEquals(null, jobAfter.leaseOwnerId)
        val stageAfter = requireNotNull(generationDao.findStage("stage-1"))
        assertEquals(GenerationStageStatus.READY, stageAfter.status)
        assertEquals(null, stageAfter.leaseOwnerId)
        assertEquals(0, stageAfter.attemptCount)
        assertEquals(0, generationDao.attemptsForStage("stage-1").size)
    }

    @Test
    fun maintenanceIdleJobScanIsBoundedAndOrderedByOldestHeartbeat() = runBlocking {
        listOf(
            Triple("job-idle-a", "stage-idle-a", 3L),
            Triple("job-idle-b", "stage-idle-b", 4L),
            Triple("job-idle-c", "stage-idle-c", 5L),
        ).forEachIndexed { index, (jobId, stageId, leaseAt) ->
            generationDao.createJob(
                job(jobId),
                listOf(stage(stageId, "idem-idle-$index", 1L, jobId)),
            )
            generationDao.transitionJob(
                jobId,
                GenerationJobStatus.CREATED,
                JobEvent.VALIDATION_PASSED,
                2L,
            )
            generationDao.acquireJobLease(jobId, "job-worker-$index", leaseAt)
            generationDao.transitionStage(
                stageId,
                GenerationStageStatus.PENDING,
                StageEvent.DEPENDENCIES_SATISFIED,
                updatedAt = 2L,
            )
        }

        val scan = GenerationMaintenanceRepository(database).scanExpiredIdleJobLeases(
            observedAt = 60_005L,
            limit = 2,
        )

        assertEquals(listOf("job-idle-a", "job-idle-b"), scan.candidates.map { it.jobId })
        assertEquals(listOf(3L, 4L), scan.candidates.map { it.jobLeaseHeartbeatAt })
        assertEquals(true, scan.hasMore)
    }

    @Test
    fun runnerQueueScanIsBoundedStableAndRespectsObservedAt() = runBlocking {
        readyJob("job-run-a", "stage-run-a", "idem-run-a", at = 2L)
        readyJob("job-run-b", "stage-run-b", "idem-run-b", at = 2L)
        readyJob("job-run-c", "stage-run-c", "idem-run-c", at = 3L)
        readyJob("job-run-future", "stage-run-future", "idem-run-future", at = 5L)
        readyJob("job-run-malformed-lease", "stage-run-malformed-lease", "idem-run-malformed-lease", at = 2L)
        database.openHelper.writableDatabase.execSQL(
            """
            UPDATE generation_job
            SET lease_owner_id = 'stale-runner', lease_acquired_at = 2, lease_heartbeat_at = 2
            WHERE job_id = 'job-run-malformed-lease'
            """.trimIndent(),
        )
        generationDao.createJob(
            job("job-run-stage-future"),
            listOf(stage("stage-run-stage-future", "idem-run-stage-future", 1L, "job-run-stage-future")),
        )
        generationDao.transitionJob(
            "job-run-stage-future",
            GenerationJobStatus.CREATED,
            JobEvent.VALIDATION_PASSED,
            3L,
        )
        generationDao.transitionStage(
            "stage-run-stage-future",
            GenerationStageStatus.PENDING,
            StageEvent.DEPENDENCIES_SATISFIED,
            updatedAt = 5L,
        )
        val repository = GenerationRunnerQueueRepository(database)

        val page = repository.scanReadyJobs(observedAt = 4L, limit = 2)
        assertEquals(listOf("job-run-a", "job-run-b"), page.candidates.map { it.jobId })
        assertEquals(listOf(2L, 2L), page.candidates.map { it.jobUpdatedAt })
        assertEquals(true, page.hasMore)

        val full = repository.scanReadyJobs(observedAt = 4L, limit = 10)
        assertEquals(listOf("job-run-a", "job-run-b", "job-run-c"), full.candidates.map { it.jobId })
        assertEquals(false, full.hasMore)

        val first = full.candidates.first()
        assertEquals("job-run-a", first.jobId)
        assertEquals(GenerationJobStatus.READY, first.jobStatus)
        assertEquals("stage-run-a", first.currentStageId)
        assertEquals(GenerationStageStatus.READY, first.currentStageStatus)
        assertEquals(2L, first.jobUpdatedAt)
        assertEquals(2L, first.stageUpdatedAt)
        assertEquals(false, first.toString().contains("job-run-a"))
        assertEquals(false, first.toString().contains("stage-run-a"))

        assertEquals(emptyList<Any>(), repository.scanReadyJobs(observedAt = 1L).candidates)
    }

    @Test
    fun concurrentRunnerClaimsOfTheSameReadyJobSucceedExactlyOnce() = runBlocking {
        readyJob(JOB_ID, "stage-1", "idem-1", at = 2L)
        val repository = GenerationRunnerQueueRepository(database)
        val candidate = repository.scanReadyJobs(observedAt = 4L).candidates.single()

        val claims = coroutineScope {
            listOf(
                async(Dispatchers.IO) {
                    runCatching { repository.claimReadyJob(candidate, "runner-a", claimedAt = 3L) }
                },
                async(Dispatchers.IO) {
                    runCatching { repository.claimReadyJob(candidate, "runner-b", claimedAt = 3L) }
                },
            ).awaitAll()
        }

        assertEquals(1, claims.count { it.isSuccess })
        assertEquals(1, claims.count { it.isFailure })
        val loser = claims.first { it.isFailure }.exceptionOrNull()
        assertTrue(loser is StaleGenerationStateException)

        val job = requireNotNull(generationDao.findJob(JOB_ID))
        assertEquals(GenerationJobStatus.RUNNING, job.status)
        assertTrue(job.leaseOwnerId == "runner-a" || job.leaseOwnerId == "runner-b")
        assertEquals(3L, job.leaseAcquiredAt)
        assertEquals(3L, job.leaseHeartbeatAt)
        assertEquals("stage-1", job.currentStageId)
        val stage = requireNotNull(generationDao.findStage("stage-1"))
        assertEquals(GenerationStageStatus.READY, stage.status)
        assertEquals(0, stage.attemptCount)
        assertEquals(null, stage.leaseOwnerId)
        assertEquals(0, generationDao.attemptsForStage("stage-1").size)
    }

    @Test
    fun runnerClaimStaleFailsWhenJobOrStageEvidenceChangesAfterScan() = runBlocking {
        val repository = GenerationRunnerQueueRepository(database)

        // Job updatedAt changed after the scan.
        readyJob("job-ev-a", "stage-ev-a", "idem-ev-a", at = 2L)
        val candidateA = repository.scanReadyJobs(observedAt = 4L).candidates.single { it.jobId == "job-ev-a" }
        generationDao.setCurrentStage("job-ev-a", "stage-ev-a", 4L)
        val errorA = expectFailure { repository.claimReadyJob(candidateA, "runner-a", claimedAt = 5L) }
        assertTrue(errorA is StaleGenerationStateException)
        assertEquals(GenerationJobStatus.READY, generationDao.findJob("job-ev-a")?.status)
        assertEquals(null, generationDao.findJob("job-ev-a")?.leaseOwnerId)
        assertEquals(4L, generationDao.findJob("job-ev-a")?.updatedAt)

        // Job current stage changed after the scan.
        generationDao.createJob(
            job("job-ev-b"),
            listOf(
                stage("stage-ev-b", "idem-ev-b", 1L, "job-ev-b"),
                stage("stage-ev-b2", "idem-ev-b2", 1L, "job-ev-b"),
            ),
        )
        generationDao.transitionJob("job-ev-b", GenerationJobStatus.CREATED, JobEvent.VALIDATION_PASSED, 2L)
        generationDao.transitionStage(
            "stage-ev-b",
            GenerationStageStatus.PENDING,
            StageEvent.DEPENDENCIES_SATISFIED,
            updatedAt = 2L,
        )
        generationDao.transitionStage(
            "stage-ev-b2",
            GenerationStageStatus.PENDING,
            StageEvent.DEPENDENCIES_SATISFIED,
            updatedAt = 2L,
        )
        val candidateB = repository.scanReadyJobs(observedAt = 4L).candidates.single { it.jobId == "job-ev-b" }
        assertEquals("stage-ev-b", candidateB.currentStageId)
        generationDao.setCurrentStage("job-ev-b", "stage-ev-b2", 4L)
        val errorB = expectFailure { repository.claimReadyJob(candidateB, "runner-a", claimedAt = 5L) }
        assertTrue(errorB is StaleGenerationStateException)
        val jobB = requireNotNull(generationDao.findJob("job-ev-b"))
        assertEquals("stage-ev-b2", jobB.currentStageId)
        assertEquals(GenerationJobStatus.READY, jobB.status)
        assertEquals(null, jobB.leaseOwnerId)

        // Stage status and lease changed after the scan.
        readyJob("job-ev-c", "stage-ev-c", "idem-ev-c", at = 2L)
        val candidateC = repository.scanReadyJobs(observedAt = 4L).candidates.single { it.jobId == "job-ev-c" }
        generationDao.acquireStageLease("stage-ev-c", "worker-b", 3L)
        val errorC = expectFailure { repository.claimReadyJob(candidateC, "runner-a", claimedAt = 5L) }
        assertTrue(errorC is StaleGenerationStateException)
        val jobC = requireNotNull(generationDao.findJob("job-ev-c"))
        assertEquals(GenerationJobStatus.READY, jobC.status)
        assertEquals(null, jobC.leaseOwnerId)
        val stageC = requireNotNull(generationDao.findStage("stage-ev-c"))
        assertEquals(GenerationStageStatus.PREPARING, stageC.status)
        assertEquals("worker-b", stageC.leaseOwnerId)

        // Stage updatedAt changed after the scan while status and lease stayed READY-free.
        readyJob("job-ev-d", "stage-ev-d", "idem-ev-d", at = 2L)
        val candidateD = repository.scanReadyJobs(observedAt = 4L).candidates.single { it.jobId == "job-ev-d" }
        database.openHelper.writableDatabase.execSQL(
            "UPDATE generation_stage SET updated_at = 4 WHERE stage_id = 'stage-ev-d'",
        )
        val errorD = expectFailure { repository.claimReadyJob(candidateD, "runner-a", claimedAt = 5L) }
        assertTrue(errorD is StaleGenerationStateException)
        assertEquals(GenerationJobStatus.READY, generationDao.findJob("job-ev-d")?.status)
        assertEquals(4L, generationDao.findStage("stage-ev-d")?.updatedAt)

        // A hand-crafted candidate that no longer matches the persisted facts also fails closed.
        val tampered = candidateA.copy(jobUpdatedAt = candidateA.jobUpdatedAt - 1L)
        val tamperError = expectFailure { repository.claimReadyJob(tampered, "runner-a", claimedAt = 5L) }
        assertTrue(tamperError is StaleGenerationStateException)
        assertEquals(GenerationJobStatus.READY, generationDao.findJob("job-ev-a")?.status)
    }

    @Test
    fun runnerClaimOnlyChangesTheJobToRunningWithLease() = runBlocking {
        readyJob(JOB_ID, "stage-1", "idem-1", at = 2L)
        val repository = GenerationRunnerQueueRepository(database)
        val candidate = repository.scanReadyJobs(observedAt = 4L).candidates.single()
        assertEquals(false, candidate.toString().contains(JOB_ID))
        assertEquals(false, candidate.toString().contains("stage-1"))

        val result = repository.claimReadyJob(candidate, "runner-a", claimedAt = 3L)

        assertEquals(JOB_ID, result.jobId)
        assertEquals(GenerationJobStatus.RUNNING, result.jobStatus)
        assertEquals(GenerationLeaseToken("runner-a", 3L), result.jobLeaseToken)
        assertEquals("stage-1", result.currentStage.stageId)
        assertEquals(GenerationPhase.DRAFT_CHAPTER, result.currentStage.phase)
        assertEquals(GenerationTargetType.CHAPTER, result.currentStage.targetType)
        assertEquals(GenerationStageStatus.READY, result.currentStage.status)
        assertEquals(0, result.currentStage.attemptCount)
        assertEquals(3, result.currentStage.maxAttempts)
        assertEquals(false, result.toString().contains(JOB_ID))
        assertEquals(false, result.toString().contains("stage-1"))
        assertEquals(false, result.toString().contains("runner-a"))

        val job = requireNotNull(generationDao.findJob(JOB_ID))
        assertEquals(GenerationJobStatus.RUNNING, job.status)
        assertEquals("runner-a", job.leaseOwnerId)
        assertEquals(3L, job.leaseAcquiredAt)
        assertEquals(3L, job.leaseHeartbeatAt)
        assertEquals(3L, job.startedAt)
        assertEquals(3L, job.updatedAt)
        assertEquals("stage-1", job.currentStageId)

        val stage = requireNotNull(generationDao.findStage("stage-1"))
        assertEquals(GenerationStageStatus.READY, stage.status)
        assertEquals(0, stage.attemptCount)
        assertEquals(2L, stage.updatedAt)
        assertEquals(null, stage.leaseOwnerId)
        assertEquals(null, stage.leaseAcquiredAt)
        assertEquals(null, stage.leaseHeartbeatAt)
        assertEquals(null, stage.standardErrorCode)
        assertEquals(null, stage.nextRetryAt)
        assertEquals(0, generationDao.attemptsForStage("stage-1").size)
    }

    @Test
    fun runnerHeartbeatReadsTheCurrentStageAndFollowsBusinessStageHandoff() = runBlocking {
        generationDao.createJob(
            job(),
            listOf(stage("stage-1", "idem-1", 1L), stage("stage-2", "idem-2", 1L)),
        )
        generationDao.transitionJob(JOB_ID, GenerationJobStatus.CREATED, JobEvent.VALIDATION_PASSED, 2L)
        generationDao.transitionStage(
            "stage-1",
            GenerationStageStatus.PENDING,
            StageEvent.DEPENDENCIES_SATISFIED,
            updatedAt = 2L,
        )
        generationDao.transitionStage(
            "stage-2",
            GenerationStageStatus.PENDING,
            StageEvent.DEPENDENCIES_SATISFIED,
            updatedAt = 2L,
        )
        val repository = GenerationRunnerQueueRepository(database)
        val candidate = repository.scanReadyJobs(observedAt = 4L).candidates.single()
        assertEquals("stage-1", candidate.currentStageId)

        val claim = repository.claimReadyJob(candidate, "runner-a", claimedAt = 4L)
        val token = claim.jobLeaseToken
        assertEquals(GenerationLeaseToken("runner-a", 4L), token)

        val before = repository.heartbeatAndLoadCurrentStage(JOB_ID, token, heartbeatAt = 5L)
        assertEquals(GenerationJobStatus.RUNNING, before.jobStatus)
        assertEquals("stage-1", before.currentStage.stageId)
        assertEquals(GenerationStageStatus.READY, before.currentStage.status)
        assertEquals(0, before.currentStage.attemptCount)
        assertEquals(false, before.toString().contains("stage-1"))

        // A business commit marks stage-1 succeeded and advances the cursor to stage-2.
        assertEquals(1, generationDao.compareAndAdvanceJobStage(JOB_ID, "stage-1", "stage-2", 6L))

        val after = repository.heartbeatAndLoadCurrentStage(JOB_ID, token, heartbeatAt = 7L)
        assertEquals(GenerationJobStatus.RUNNING, after.jobStatus)
        assertEquals("stage-2", after.currentStage.stageId)
        assertEquals(GenerationStageStatus.READY, after.currentStage.status)
        assertEquals(0, after.currentStage.attemptCount)

        // The job was resumed by the same token, never re-claimed.
        val job = requireNotNull(generationDao.findJob(JOB_ID))
        assertEquals(GenerationJobStatus.RUNNING, job.status)
        assertEquals("runner-a", job.leaseOwnerId)
        assertEquals(4L, job.leaseAcquiredAt)
        assertEquals(7L, job.leaseHeartbeatAt)
        assertEquals("stage-2", job.currentStageId)
    }

    @Test
    fun runnerHeartbeatRejectsWrongOwnerWrongAcquiredAtAndExpiredLeases() = runBlocking {
        readyJob(JOB_ID, "stage-1", "idem-1", at = 2L)
        val repository = GenerationRunnerQueueRepository(database)
        val claim = repository.claimReadyJob(
            repository.scanReadyJobs(observedAt = 4L).candidates.single(),
            "runner-a",
            claimedAt = 3L,
        )
        val token = claim.jobLeaseToken
        assertEquals(GenerationLeaseToken("runner-a", 3L), token)

        val wrongOwner = GenerationLeaseToken(ownerId = "runner-b", acquiredAt = token.acquiredAt)
        val wrongOwnerError = expectFailure {
            repository.heartbeatAndLoadCurrentStage(JOB_ID, wrongOwner, heartbeatAt = 10L)
        }
        assertTrue(wrongOwnerError is StaleGenerationStateException)

        val wrongAcquiredAt = GenerationLeaseToken(ownerId = token.ownerId, acquiredAt = token.acquiredAt - 1L)
        val wrongAcquiredError = expectFailure {
            repository.heartbeatAndLoadCurrentStage(JOB_ID, wrongAcquiredAt, heartbeatAt = 10L)
        }
        assertTrue(wrongAcquiredError is StaleGenerationStateException)

        val expiredError = expectFailure {
            repository.heartbeatAndLoadCurrentStage(JOB_ID, token, heartbeatAt = 60_003L)
        }
        assertTrue(expiredError is StaleGenerationStateException)

        // Nothing was revived: the lease is still the original one with the original heartbeat.
        val job = requireNotNull(generationDao.findJob(JOB_ID))
        assertEquals(GenerationJobStatus.RUNNING, job.status)
        assertEquals("runner-a", job.leaseOwnerId)
        assertEquals(3L, job.leaseAcquiredAt)
        assertEquals(3L, job.leaseHeartbeatAt)

        // An old token is never adopted by owner string after the lease was requeued and reclaimed.
        val maintenance = GenerationMaintenanceRepository(database)
        val idleCandidate = maintenance.scanExpiredIdleJobLeases(observedAt = 60_003L).candidates.single()
        maintenance.requeueExpiredIdleJobLease(idleCandidate, observedAt = 60_003L)
        val newCandidate = repository.scanReadyJobs(observedAt = 60_004L).candidates.single()
        val newClaim = repository.claimReadyJob(newCandidate, "runner-b", claimedAt = 60_004L)
        assertEquals("runner-b", newClaim.jobLeaseToken.ownerId)

        val adoptionError = expectFailure {
            repository.heartbeatAndLoadCurrentStage(JOB_ID, token, heartbeatAt = 60_005L)
        }
        assertTrue(adoptionError is StaleGenerationStateException)

        val resumed = repository.heartbeatAndLoadCurrentStage(JOB_ID, newClaim.jobLeaseToken, heartbeatAt = 60_005L)
        assertEquals(GenerationJobStatus.RUNNING, resumed.jobStatus)
        assertEquals("stage-1", resumed.currentStage.stageId)
        val jobAfter = requireNotNull(generationDao.findJob(JOB_ID))
        assertEquals("runner-b", jobAfter.leaseOwnerId)
        assertEquals(60_004L, jobAfter.leaseAcquiredAt)
        assertEquals(60_005L, jobAfter.leaseHeartbeatAt)
    }

    @Test
    fun runnerQueueRejectsInvalidOwnerIdsLimitsAndBackwardsTime() = runBlocking {
        readyJob(JOB_ID, "stage-1", "idem-1", at = 2L)
        val repository = GenerationRunnerQueueRepository(database)
        val candidate = repository.scanReadyJobs(observedAt = 4L).candidates.single()

        for (badOwner in listOf("", "a".repeat(129), "runner with space", "runner@host", "runner,one", "跑者")) {
            val error = expectFailure { repository.claimReadyJob(candidate, badOwner, claimedAt = 3L) }
            assertTrue(error is IllegalArgumentException)
        }
        assertEquals(GenerationJobStatus.READY, generationDao.findJob(JOB_ID)?.status)
        assertEquals(null, generationDao.findJob(JOB_ID)?.leaseOwnerId)

        expectFailure { repository.scanReadyJobs(observedAt = -1L) }
        expectFailure { repository.scanReadyJobs(observedAt = 4L, limit = 0) }
        expectFailure { repository.scanReadyJobs(observedAt = 4L, limit = 101) }
        expectFailure { repository.claimReadyJob(candidate, "runner-a", claimedAt = -1L) }
        val backwardsClaim = expectFailure { repository.claimReadyJob(candidate, "runner-a", claimedAt = 2L) }
        assertTrue(backwardsClaim is IllegalArgumentException)
        assertEquals(GenerationJobStatus.READY, generationDao.findJob(JOB_ID)?.status)

        // Boundary owners of one and 128 characters are accepted.
        val oneChar = repository.claimReadyJob(candidate, "r", claimedAt = 3L)
        assertEquals(GenerationLeaseToken("r", 3L), oneChar.jobLeaseToken)
        readyJob("job-max-owner", "stage-max-owner", "idem-max-owner", at = 2L)
        val maxCandidate = repository.scanReadyJobs(observedAt = 4L).candidates.single { it.jobId == "job-max-owner" }
        val maxOwner = repository.claimReadyJob(maxCandidate, "a".repeat(128), claimedAt = 3L)
        assertEquals("a".repeat(128), maxOwner.jobLeaseToken.ownerId)

        // Heartbeat time cannot move backwards.
        val heartbeatBackwards = expectFailure {
            repository.heartbeatAndLoadCurrentStage(JOB_ID, oneChar.jobLeaseToken, heartbeatAt = 1L)
        }
        assertTrue(heartbeatBackwards is IllegalArgumentException)
        val negativeHeartbeat = expectFailure {
            repository.heartbeatAndLoadCurrentStage(JOB_ID, oneChar.jobLeaseToken, heartbeatAt = -1L)
        }
        assertTrue(negativeHeartbeat is IllegalArgumentException)
    }

    @Test
    fun runnerExecutionLeaseAtomicallyAcquiresOnlyTheCurrentStage() = runBlocking {
        readyJob(JOB_ID, "stage-1", "idem-1", at = 2L)
        val queue = GenerationRunnerQueueRepository(database)
        val jobClaim = queue.claimReadyJob(
            queue.scanReadyJobs(observedAt = 3L).candidates.single(),
            runnerOwnerId = "runner-a",
            claimedAt = 3L,
        )
        val repository = GenerationRunnerExecutionLeaseRepository(database)

        val acquired = repository.acquireCurrentStageLease(
            jobId = JOB_ID,
            jobLeaseToken = jobClaim.jobLeaseToken,
            stageId = "stage-1",
            runnerOwnerId = "runner-a",
            acquiredAt = 4L,
        )

        assertEquals(GenerationJobStatus.RUNNING, acquired.jobStatus)
        assertEquals(GenerationLeaseToken("runner-a", 3L), acquired.jobLeaseToken)
        assertEquals(4L, acquired.jobHeartbeatAt)
        assertEquals(GenerationStageStatus.PREPARING, acquired.stageStatus)
        assertEquals(GenerationLeaseToken("runner-a", 4L), acquired.stageLeaseToken)
        assertEquals(4L, acquired.stageHeartbeatAt)
        assertEquals(false, acquired.toString().contains(JOB_ID))
        assertEquals(false, acquired.toString().contains("stage-1"))
        assertEquals(false, acquired.toString().contains("runner-a"))

        val job = requireNotNull(generationDao.findJob(JOB_ID))
        val stage = requireNotNull(generationDao.findStage("stage-1"))
        assertEquals("stage-1", job.currentStageId)
        assertEquals(3L, job.leaseAcquiredAt)
        assertEquals(4L, job.leaseHeartbeatAt)
        assertEquals(0, stage.attemptCount)
        assertEquals(null, stage.standardErrorCode)
        assertEquals(0, generationDao.attemptsForStage("stage-1").size)
    }

    @Test
    fun concurrentRunnerExecutionLeaseAcquisitionSucceedsExactlyOnce() = runBlocking {
        readyJob(JOB_ID, "stage-1", "idem-1", at = 2L)
        val queue = GenerationRunnerQueueRepository(database)
        val jobToken = queue.claimReadyJob(
            queue.scanReadyJobs(observedAt = 3L).candidates.single(),
            "runner-a",
            claimedAt = 3L,
        ).jobLeaseToken
        val repository = GenerationRunnerExecutionLeaseRepository(database)

        val attempts = coroutineScope {
            listOf(
                async(Dispatchers.IO) {
                    runCatching {
                        repository.acquireCurrentStageLease(JOB_ID, jobToken, "stage-1", "runner-a", 4L)
                    }
                },
                async(Dispatchers.IO) {
                    runCatching {
                        repository.acquireCurrentStageLease(JOB_ID, jobToken, "stage-1", "runner-a", 4L)
                    }
                },
            ).awaitAll()
        }

        assertEquals(1, attempts.count { it.isSuccess })
        assertEquals(1, attempts.count { it.isFailure })
        assertTrue(attempts.first { it.isFailure }.exceptionOrNull() is StaleGenerationStateException)
        assertEquals(4L, generationDao.findJob(JOB_ID)?.leaseHeartbeatAt)
        assertEquals(GenerationStageStatus.PREPARING, generationDao.findStage("stage-1")?.status)
        assertEquals(4L, generationDao.findStage("stage-1")?.leaseHeartbeatAt)
    }

    @Test
    fun runnerExecutionLeaseAcquireFailureRollsBackJobHeartbeat() = runBlocking {
        readyJob(JOB_ID, "stage-1", "idem-1", at = 2L)
        database.openHelper.writableDatabase.execSQL(
            "UPDATE generation_stage SET updated_at = 5 WHERE stage_id = 'stage-1'",
        )
        val queue = GenerationRunnerQueueRepository(database)
        val jobToken = queue.claimReadyJob(
            queue.scanReadyJobs(observedAt = 5L).candidates.single(),
            "runner-a",
            claimedAt = 3L,
        ).jobLeaseToken
        val repository = GenerationRunnerExecutionLeaseRepository(database)

        val stageClockAhead = expectFailure {
            repository.acquireCurrentStageLease(JOB_ID, jobToken, "stage-1", "runner-a", acquiredAt = 4L)
        }
        assertTrue(stageClockAhead is IllegalArgumentException)
        assertEquals(3L, generationDao.findJob(JOB_ID)?.leaseHeartbeatAt)
        assertEquals(GenerationStageStatus.READY, generationDao.findStage("stage-1")?.status)
        assertEquals(null, generationDao.findStage("stage-1")?.leaseOwnerId)
        assertEquals(5L, generationDao.findStage("stage-1")?.updatedAt)

        val wrongOwner = expectFailure {
            repository.acquireCurrentStageLease(JOB_ID, jobToken, "stage-1", "runner-b", acquiredAt = 6L)
        }
        assertTrue(wrongOwner is IllegalArgumentException)
        assertEquals(3L, generationDao.findJob(JOB_ID)?.leaseHeartbeatAt)
    }

    @Test
    fun runnerExecutionLeaseHeartbeatIsAtomicForBothTokens() = runBlocking {
        readyJob(JOB_ID, "stage-1", "idem-1", at = 2L)
        val queue = GenerationRunnerQueueRepository(database)
        val jobToken = queue.claimReadyJob(
            queue.scanReadyJobs(observedAt = 3L).candidates.single(),
            "runner-a",
            claimedAt = 3L,
        ).jobLeaseToken
        val repository = GenerationRunnerExecutionLeaseRepository(database)
        val acquired = repository.acquireCurrentStageLease(
            JOB_ID,
            jobToken,
            "stage-1",
            "runner-a",
            acquiredAt = 4L,
        )

        val heartbeat = repository.heartbeatCurrentExecutionLeases(
            JOB_ID,
            jobToken,
            "stage-1",
            acquired.stageLeaseToken,
            heartbeatAt = 10L,
        )
        assertEquals(10L, heartbeat.jobHeartbeatAt)
        assertEquals(10L, heartbeat.stageHeartbeatAt)

        val wrongStageToken = acquired.stageLeaseToken.copy(acquiredAt = 5L)
        val stageFailure = expectFailure {
            repository.heartbeatCurrentExecutionLeases(
                JOB_ID,
                jobToken,
                "stage-1",
                wrongStageToken,
                heartbeatAt = 20L,
            )
        }
        assertTrue(stageFailure is StaleGenerationStateException)
        assertEquals(10L, generationDao.findJob(JOB_ID)?.leaseHeartbeatAt)
        assertEquals(10L, generationDao.findStage("stage-1")?.leaseHeartbeatAt)

        val mixedOwner = expectFailure {
            repository.heartbeatCurrentExecutionLeases(
                JOB_ID,
                jobToken,
                "stage-1",
                GenerationLeaseToken("runner-b", acquired.stageLeaseToken.acquiredAt),
                heartbeatAt = 20L,
            )
        }
        assertTrue(mixedOwner is IllegalArgumentException)
        assertEquals(10L, generationDao.findJob(JOB_ID)?.leaseHeartbeatAt)
        assertEquals(10L, generationDao.findStage("stage-1")?.leaseHeartbeatAt)
    }

    @Test
    fun runnerExecutionLeaseExpiryAndCursorChangesFailWithoutPartialHeartbeat() = runBlocking {
        generationDao.createJob(
            job(),
            listOf(stage("stage-1", "idem-1", 1L), stage("stage-2", "idem-2", 1L)),
        )
        generationDao.transitionJob(JOB_ID, GenerationJobStatus.CREATED, JobEvent.VALIDATION_PASSED, 2L)
        generationDao.transitionStage(
            "stage-1",
            GenerationStageStatus.PENDING,
            StageEvent.DEPENDENCIES_SATISFIED,
            updatedAt = 2L,
        )
        generationDao.transitionStage(
            "stage-2",
            GenerationStageStatus.PENDING,
            StageEvent.DEPENDENCIES_SATISFIED,
            updatedAt = 2L,
        )
        val queue = GenerationRunnerQueueRepository(database)
        val jobToken = queue.claimReadyJob(
            queue.scanReadyJobs(observedAt = 3L).candidates.single(),
            "runner-a",
            claimedAt = 3L,
        ).jobLeaseToken
        val repository = GenerationRunnerExecutionLeaseRepository(database)
        val acquired = repository.acquireCurrentStageLease(
            JOB_ID,
            jobToken,
            "stage-1",
            "runner-a",
            acquiredAt = 4L,
        )

        GenerationStateRepository(database).heartbeatJobLease(JOB_ID, jobToken, now = 50_000L)
        val stageExpired = expectFailure {
            repository.heartbeatCurrentExecutionLeases(
                JOB_ID,
                jobToken,
                "stage-1",
                acquired.stageLeaseToken,
                heartbeatAt = 60_004L,
            )
        }
        assertTrue(stageExpired is StaleGenerationStateException)
        assertEquals(50_000L, generationDao.findJob(JOB_ID)?.leaseHeartbeatAt)
        assertEquals(4L, generationDao.findStage("stage-1")?.leaseHeartbeatAt)

        assertEquals(1, generationDao.compareAndAdvanceJobStage(JOB_ID, "stage-1", "stage-2", 60_005L))
        val cursorChanged = expectFailure {
            repository.heartbeatCurrentExecutionLeases(
                JOB_ID,
                jobToken,
                "stage-1",
                acquired.stageLeaseToken,
                heartbeatAt = 60_006L,
            )
        }
        assertTrue(cursorChanged is StaleGenerationStateException)
        assertEquals(50_000L, generationDao.findJob(JOB_ID)?.leaseHeartbeatAt)
        assertEquals(4L, generationDao.findStage("stage-1")?.leaseHeartbeatAt)
        assertEquals("stage-2", generationDao.findJob(JOB_ID)?.currentStageId)
    }

    @Test
    fun runnerCurrentStageRouteBindsAuthoritativeContractToExactReadOnlyLeaseSnapshot() = runBlocking {
        readyMemoryRouteJob(JOB_ID, "stage-route")
        val queue = GenerationRunnerQueueRepository(database)
        val jobToken = queue.claimReadyJob(
            queue.scanReadyJobs(observedAt = 3L).candidates.single(),
            "runner-route",
            claimedAt = 3L,
        ).jobLeaseToken
        val repository = GenerationRunnerExecutionLeaseRepository(database)
        val acquired = repository.acquireCurrentStageLease(
            JOB_ID,
            jobToken,
            "stage-route",
            "runner-route",
            acquiredAt = 4L,
        )
        val beforeJob = requireNotNull(generationDao.findJob(JOB_ID))
        val beforeStage = requireNotNull(generationDao.findStage("stage-route"))

        val resolved = repository.resolveCurrentStageRoute(
            jobId = JOB_ID,
            jobLeaseToken = jobToken,
            stageId = "stage-route",
            stageLeaseToken = acquired.stageLeaseToken,
            observedAt = 5L,
        )

        assertEquals(GenerationRunnerStageRoute.FORMAL_CHAPTER_MEMORY_V1, resolved.route)
        assertEquals(acquired, resolved.executionLease)
        assertEquals(0, resolved.attemptCount)
        assertEquals(2, resolved.maxAttempts)
        assertEquals(beforeJob, generationDao.findJob(JOB_ID))
        assertEquals(beforeStage, generationDao.findStage("stage-route"))
        assertEquals(0, generationDao.attemptsForStage("stage-route").size)
        assertEquals(false, resolved.toString().contains(JOB_ID))
        assertEquals(false, resolved.toString().contains("stage-route"))
        assertEquals(false, resolved.toString().contains("runner-route"))
    }

    @Test
    fun runnerCurrentStageRouteRejectsWrongTokensMixedOwnerAndNonCurrentStage() = runBlocking {
        readyMemoryRouteJob(JOB_ID, "stage-route")
        val queue = GenerationRunnerQueueRepository(database)
        val jobToken = queue.claimReadyJob(
            queue.scanReadyJobs(observedAt = 3L).candidates.single(),
            "runner-route",
            claimedAt = 3L,
        ).jobLeaseToken
        val repository = GenerationRunnerExecutionLeaseRepository(database)
        val acquired = repository.acquireCurrentStageLease(
            JOB_ID,
            jobToken,
            "stage-route",
            "runner-route",
            acquiredAt = 4L,
        )
        val beforeJob = requireNotNull(generationDao.findJob(JOB_ID))
        val beforeStage = requireNotNull(generationDao.findStage("stage-route"))

        val wrongJobToken = expectFailure {
            repository.resolveCurrentStageRoute(
                JOB_ID,
                jobToken.copy(acquiredAt = jobToken.acquiredAt + 1L),
                "stage-route",
                acquired.stageLeaseToken,
                observedAt = 5L,
            )
        }
        assertTrue(wrongJobToken is StaleGenerationStateException)
        val wrongStageToken = expectFailure {
            repository.resolveCurrentStageRoute(
                JOB_ID,
                jobToken,
                "stage-route",
                acquired.stageLeaseToken.copy(acquiredAt = acquired.stageLeaseToken.acquiredAt + 1L),
                observedAt = 5L,
            )
        }
        assertTrue(wrongStageToken is StaleGenerationStateException)
        val mixedOwner = expectFailure {
            repository.resolveCurrentStageRoute(
                JOB_ID,
                jobToken,
                "stage-route",
                GenerationLeaseToken("runner-other", acquired.stageLeaseToken.acquiredAt),
                observedAt = 5L,
            )
        }
        assertTrue(mixedOwner is IllegalArgumentException)

        readyMemoryRouteJob("job-other-route", "stage-other-route")
        val nonCurrent = expectFailure {
            repository.resolveCurrentStageRoute(
                JOB_ID,
                jobToken,
                "stage-other-route",
                acquired.stageLeaseToken,
                observedAt = 5L,
            )
        }
        assertTrue(nonCurrent is StaleGenerationStateException)
        assertEquals(beforeJob, generationDao.findJob(JOB_ID))
        assertEquals(beforeStage, generationDao.findStage("stage-route"))
        assertEquals(0, generationDao.attemptsForStage("stage-route").size)
    }

    @Test
    fun runnerCurrentStageRouteRejectsBackwardsExpiredAndPausingEvidence() = runBlocking {
        readyMemoryRouteJob(JOB_ID, "stage-route")
        val queue = GenerationRunnerQueueRepository(database)
        val jobToken = queue.claimReadyJob(
            queue.scanReadyJobs(observedAt = 3L).candidates.single(),
            "runner-route",
            claimedAt = 3L,
        ).jobLeaseToken
        val repository = GenerationRunnerExecutionLeaseRepository(database)
        val acquired = repository.acquireCurrentStageLease(
            JOB_ID,
            jobToken,
            "stage-route",
            "runner-route",
            acquiredAt = 4L,
        )

        val backwards = expectFailure {
            repository.resolveCurrentStageRoute(
                JOB_ID,
                jobToken,
                "stage-route",
                acquired.stageLeaseToken,
                observedAt = 3L,
            )
        }
        assertTrue(backwards is IllegalArgumentException)
        val expiredAtBoundary = expectFailure {
            repository.resolveCurrentStageRoute(
                JOB_ID,
                jobToken,
                "stage-route",
                acquired.stageLeaseToken,
                observedAt = 60_004L,
            )
        }
        assertTrue(expiredAtBoundary is StaleGenerationStateException)

        // Fault injection isolates the dispatcher guard: a control request can leave a
        // lease-owned Job in PAUSING while a network-active Stage reaches its safe point.
        database.openHelper.writableDatabase.execSQL(
            "UPDATE generation_job SET status = 'PAUSING', updated_at = 5 WHERE job_id = 'job-1'",
        )
        val pausing = expectFailure {
            repository.resolveCurrentStageRoute(
                JOB_ID,
                jobToken,
                "stage-route",
                acquired.stageLeaseToken,
                observedAt = 5L,
            )
        }
        assertTrue(pausing is StaleGenerationStateException)
        assertEquals(GenerationJobStatus.PAUSING, generationDao.findJob(JOB_ID)?.status)
        assertEquals(GenerationStageStatus.PREPARING, generationDao.findStage("stage-route")?.status)
        assertEquals(0, generationDao.attemptsForStage("stage-route").size)
    }

    @Test
    fun runnerCurrentStageRouteRejectsAlreadyRequestOwnedStageWithoutWrites() = runBlocking {
        readyMemoryRouteJob(JOB_ID, "stage-route")
        val queue = GenerationRunnerQueueRepository(database)
        val jobToken = queue.claimReadyJob(
            queue.scanReadyJobs(observedAt = 3L).candidates.single(),
            "runner-route",
            claimedAt = 3L,
        ).jobLeaseToken
        val repository = GenerationRunnerExecutionLeaseRepository(database)
        val acquired = repository.acquireCurrentStageLease(
            JOB_ID,
            jobToken,
            "stage-route",
            "runner-route",
            acquiredAt = 4L,
        )
        generationDao.recordRequestIntent(
            intent(
                attemptId = "attempt-route",
                ledgerId = "ledger-route",
                stageId = "stage-route",
                createdAt = 5L,
            ),
            acquired.stageLeaseToken,
        )
        val beforeJob = requireNotNull(generationDao.findJob(JOB_ID))
        val beforeStage = requireNotNull(generationDao.findStage("stage-route"))
        val beforeAttempts = generationDao.attemptsForStage("stage-route")

        val error = expectFailure {
            repository.resolveCurrentStageRoute(
                JOB_ID,
                jobToken,
                "stage-route",
                acquired.stageLeaseToken,
                observedAt = 6L,
            )
        }

        assertTrue(error is StaleGenerationStateException)
        assertEquals(beforeJob, generationDao.findJob(JOB_ID))
        assertEquals(beforeStage, generationDao.findStage("stage-route"))
        assertEquals(beforeAttempts, generationDao.attemptsForStage("stage-route"))
    }

    @Test
    fun runnerCurrentStageRouteRejectsMalformedFrozenContractWithoutLeaseMutation() = runBlocking {
        readyMemoryRouteJob(JOB_ID, "stage-route")
        val queue = GenerationRunnerQueueRepository(database)
        val jobToken = queue.claimReadyJob(
            queue.scanReadyJobs(observedAt = 3L).candidates.single(),
            "runner-route",
            claimedAt = 3L,
        ).jobLeaseToken
        val repository = GenerationRunnerExecutionLeaseRepository(database)
        val acquired = repository.acquireCurrentStageLease(
            JOB_ID,
            jobToken,
            "stage-route",
            "runner-route",
            acquiredAt = 4L,
        )
        database.openHelper.writableDatabase.execSQL(
            "UPDATE generation_stage SET input_sources_json = '{}' WHERE stage_id = 'stage-route'",
        )
        val beforeJob = requireNotNull(generationDao.findJob(JOB_ID))
        val beforeStage = requireNotNull(generationDao.findStage("stage-route"))

        val error = expectFailure {
            repository.resolveCurrentStageRoute(
                JOB_ID,
                jobToken,
                "stage-route",
                acquired.stageLeaseToken,
                observedAt = 5L,
            )
        }

        assertTrue(error is IllegalArgumentException)
        assertEquals(beforeJob, generationDao.findJob(JOB_ID))
        assertEquals(beforeStage, generationDao.findStage("stage-route"))
        assertEquals(0, generationDao.attemptsForStage("stage-route").size)
    }

    private suspend fun seedBook() {
        val snapshot = BookCreationSnapshotEntity(
            snapshotId = "snapshot-1",
            rawInputJson = "{}",
            normalizedInputJson = "{}",
            inferenceProvenanceJson = "{}",
            genrePayloadJson = "{}",
            presentationProfileJson = "{}",
            modelPreferenceJson = "{}",
            schemaVersion = 1,
            promptBundleVersion = "prompt-1",
            contentControlSchemaVersion = 1,
            contentHash = "snapshot-hash",
            createdAt = 1L,
        )
        libraryDao.createBook(
            snapshot,
            BookEntity(
                bookId = BOOK_ID,
                creationSnapshotId = snapshot.snapshotId,
                title = "生成测试书",
                titleSource = TitleSource.USER,
                status = BookStatus.DRAFT,
                lengthMode = BookLengthMode.LONG,
                targetCharacters = 500_000,
                targetChapters = 500,
                minimumChapters = 301,
                lengthPolicySchemaVersion = 1,
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )
    }

    private suspend fun seedFormalChapter() {
        libraryDao.createChapter(
            ChapterEntity(
                chapterId = "chapter-target",
                bookId = BOOK_ID,
                chapterIndex = 1,
                plannedTitle = "第一章",
                displayTitle = "第一章",
                status = ChapterStatus.PLANNED,
                consistencyStatus = ConsistencyStatus.UNKNOWN,
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )
        libraryDao.commitChapterVersion(
            CommitChapterVersionCommand(
                chapterVersionId = "chapter-version-baseline",
                chapterId = "chapter-target",
                expectedCurrentVersionId = null,
                content = "原有正式正文",
                contentHash = "baseline-content-hash",
                source = ChapterVersionSource.USER_EDIT,
                generationStageId = null,
                modelSnapshotJson = null,
                createdAt = 2L,
            ),
        )
    }

    private suspend fun prepareSingleStage() {
        generationDao.createJob(job(), listOf(stage("stage-1", "idem-1", 1L)))
        startJob()
        prepareStage("stage-1")
    }

    private suspend fun startJob() {
        generationDao.transitionJob(
            JOB_ID,
            GenerationJobStatus.CREATED,
            JobEvent.VALIDATION_PASSED,
            2L,
        )
        generationDao.acquireJobLease(JOB_ID, "job-worker", 3L)
    }

    private suspend fun readyJob(
        jobId: String,
        stageId: String,
        idempotencyKey: String,
        at: Long,
    ) {
        generationDao.createJob(job(jobId), listOf(stage(stageId, idempotencyKey, 1L, jobId)))
        generationDao.transitionJob(
            jobId,
            GenerationJobStatus.CREATED,
            JobEvent.VALIDATION_PASSED,
            at,
        )
        generationDao.transitionStage(
            stageId,
            GenerationStageStatus.PENDING,
            StageEvent.DEPENDENCIES_SATISFIED,
            updatedAt = at,
        )
    }

    private suspend fun readyMemoryRouteJob(
        jobId: String,
        stageId: String,
    ) {
        GenerationJobSetupRepository(database).create(
            ChapterMemoryExtractionJobFactory.create(
                ChapterMemoryExtractionJobSpec(
                    jobId = jobId,
                    stageId = stageId,
                    bookId = BOOK_ID,
                    userIntentJson = "{}",
                    budgetSnapshotJson = "{\"schema\":1}",
                    source = ChapterMemoryExtractionSourceV1(
                        chapterVersionId = "chapter.version.route",
                        chapterContentHash = "a".repeat(64),
                        chapterId = "chapter.route",
                        chapterIndex = 1,
                    ),
                    createdAt = 1L,
                ),
            ),
        )
        generationDao.transitionJob(
            jobId,
            GenerationJobStatus.CREATED,
            JobEvent.VALIDATION_PASSED,
            2L,
        )
        generationDao.transitionStage(
            stageId,
            GenerationStageStatus.PENDING,
            StageEvent.DEPENDENCIES_SATISFIED,
            updatedAt = 2L,
        )
    }

    private suspend fun completeStreamingResponse(
        drafts: GenerationStreamingDraftRepository,
        outputs: GenerationOutputValidationRepository,
        attemptId: String,
        ledgerId: String,
        content: String,
        createdAt: Long,
        retryParentAttemptId: String? = null,
    ): app.zhijuan.core.database.generation.CompletedStreamingResponse {
        val prepared = drafts.prepareBeforeSend(
            publicIntent(
                attemptId = attemptId,
                ledgerId = ledgerId,
                stageId = "stage-1",
                streamDraftRef = null,
                createdAt = createdAt,
                retryParent = retryParentAttemptId,
            ),
            BudgetedRequestTestSupport.budgetedDraft(attemptId),
            stageLease("stage-1"),
        )
        val claimed = drafts.claimForProviderOpen(prepared, validatedAt = createdAt + 1L)
        val checkpoint = drafts.openDraftBuffer(claimed).use { buffer ->
            drafts.markRequestSent(claimed, null, sentAt = createdAt + 2L)
            drafts.markStreamStarted(claimed, startedAt = createdAt + 3L)
            buffer.appendUtf8(content, now = createdAt + 4L)
            buffer.flush(now = createdAt + 4L)
        }
        return outputs.recordSuccessfulResponse(
            request = claimed,
            checkpoint = checkpoint,
            completedAt = createdAt + 5L,
        )
    }

    private suspend fun prepareStage(stageId: String) {
        generationDao.transitionStage(
            stageId,
            GenerationStageStatus.PENDING,
            StageEvent.DEPENDENCIES_SATISFIED,
            updatedAt = 2L,
        )
        generationDao.acquireStageLease(stageId, "worker-a", 3L)
    }

    private suspend fun stageLease(stageId: String): GenerationLeaseToken {
        val stage = requireNotNull(generationDao.findStage(stageId))
        return GenerationLeaseToken(
            ownerId = requireNotNull(stage.leaseOwnerId),
            acquiredAt = requireNotNull(stage.leaseAcquiredAt),
        )
    }

    private suspend fun jobLease(): GenerationLeaseToken {
        val job = requireNotNull(generationDao.findJob(JOB_ID))
        return GenerationLeaseToken(
            ownerId = requireNotNull(job.leaseOwnerId),
            acquiredAt = requireNotNull(job.leaseAcquiredAt),
        )
    }

    private fun job(id: String = JOB_ID) = GenerationJobEntity(
        jobId = id,
        bookId = BOOK_ID,
        jobType = GenerationJobType.CREATE_BOOK,
        status = GenerationJobStatus.CREATED,
        userIntentJson = "{}",
        budgetSnapshotJson = "{\"schema\":1}",
        promptBundleVersion = "prompt-1",
        createdAt = 1L,
        updatedAt = 1L,
    )

    private fun stage(
        id: String,
        idempotencyKey: String,
        createdAt: Long,
        jobId: String = JOB_ID,
    ) = GenerationStageEntity(
        stageId = id,
        jobId = jobId,
        phase = GenerationPhase.DRAFT_CHAPTER,
        targetType = GenerationTargetType.CHAPTER,
        targetId = "chapter-target",
        status = GenerationStageStatus.PENDING,
        inputVersionHash = "input-$id",
        idempotencyKey = idempotencyKey,
        maxAttempts = 3,
        inputSourcesJson = "[]",
        createdAt = createdAt,
        updatedAt = createdAt,
    )

    private fun intent(
        attemptId: String,
        ledgerId: String,
        stageId: String,
        retryParent: String? = null,
        createdAt: Long = 3L,
    ) = NewRequestIntent(
        attemptId = attemptId,
        usageLedgerId = ledgerId,
        stageId = stageId,
        retryParentAttemptId = retryParent,
        connectionSnapshotJson = "{\"destination\":\"https://example.invalid\"}",
        modelSnapshotJson = "{\"model\":\"fixture\"}",
        protocolSnapshotJson = "{\"protocol\":\"fixture\"}",
        inputHash = "input-hash-$attemptId",
        streamDraftRef = "draft-$attemptId",
        dailyPeriodKey = "2026-08-01|Asia/Shanghai",
        createdAt = createdAt,
    )

    private fun publicIntent(
        attemptId: String,
        ledgerId: String,
        stageId: String,
        connectionSnapshot: String = "{\"secretRefId\":\"fixture-secret-ref\"}",
        streamDraftRef: String? = UUID.nameUUIDFromBytes(attemptId.toByteArray(Charsets.UTF_8)).toString(),
        createdAt: Long = 3L,
        retryParent: String? = null,
    ) = RequestIntentDraft(
        attemptId = attemptId,
        usageLedgerId = ledgerId,
        stageId = stageId,
        retryParentAttemptId = retryParent,
        connectionSnapshotJson = connectionSnapshot,
        modelSnapshotJson = "{\"model\":\"fixture\"}",
        protocolSnapshotJson = "{\"protocol\":\"fixture\"}",
        inputHash = "a".repeat(64),
        streamDraftRef = streamDraftRef,
        createdAt = createdAt,
    )

    private fun cleanProtectedArtifacts() {
        artifactStore.unlockAfterAuthentication()
        artifactStore.listArtifactReferenceIds().forEach(artifactStore::delete)
    }

    private suspend fun GenerationStreamingDraftRepository.claimForProviderOpen(
        request: PersistedStreamingRequest,
        validatedAt: Long,
    ) = claimForProviderOpen(
        request,
        validatedAt,
        BudgetedRequestTestSupport.budgetedDestinationEvidence(),
    )

    private suspend fun GenerationRequestAuditRepository.claimForProviderOpen(
        permit: PersistedRequestSendPermit,
        validatedAt: Long,
    ) = claimForProviderOpen(
        permit,
        validatedAt,
        BudgetedRequestTestSupport.budgetedDestinationEvidence(),
    )

    private fun usage(
        source: UsageSource,
        status: UsageLedgerStatus,
        total: Long,
        cost: Long,
        now: Long,
    ) = UsageUpdate(
        source = source,
        status = status,
        inputTokens = total / 2,
        outputTokens = total - (total / 2),
        cachedTokens = 0,
        reasoningTokens = 0,
        totalTokens = total,
        currency = "CNY",
        estimatedCostMicros = cost,
        priceCatalogVersion = "fixture-price-1",
        updatedAt = now,
    )

    private suspend fun expectFailure(block: suspend () -> Unit): Throwable = try {
        block()
        throw AssertionError("Expected operation to fail.")
    } catch (error: Throwable) {
        if (error is AssertionError && error.message == "Expected operation to fail.") throw error
        error
    }

    private companion object {
        const val BOOK_ID = "book-generation"
        const val JOB_ID = "job-1"
    }
}

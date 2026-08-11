package app.zhijuan.core.database

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.zhijuan.core.database.connection.ConnectionProfileEntity
import app.zhijuan.core.database.generation.BudgetReservationRejectedException
import app.zhijuan.core.database.generation.BudgetReservationRejectionReason
import app.zhijuan.core.database.generation.DailyBudgetPeriodRolloverRequiredException
import app.zhijuan.core.database.generation.GenerationRecoveryDisposition
import app.zhijuan.core.database.generation.GenerationRecoveryReason
import app.zhijuan.core.database.generation.GenerationJobEntity
import app.zhijuan.core.database.generation.GenerationLeasePolicy
import app.zhijuan.core.database.generation.GenerationLeaseToken
import app.zhijuan.core.database.generation.GenerationRequestAuditRepository
import app.zhijuan.core.database.generation.GenerationRunnerExecutionLeaseRepository
import app.zhijuan.core.database.generation.GenerationRunnerExecutionLeaseSnapshot
import app.zhijuan.core.database.generation.GenerationRunnerCurrentStageRouteSnapshot
import app.zhijuan.core.database.generation.GenerationRunnerQueueRepository
import app.zhijuan.core.database.generation.GenerationRunnerStageRoute
import app.zhijuan.core.database.generation.GenerationStageEntity
import app.zhijuan.core.database.generation.GenerationStreamingDraftRepository
import app.zhijuan.core.database.generation.GenerationUnknownResultRecoveryRepository
import app.zhijuan.core.database.generation.NewRequestIntent
import app.zhijuan.core.database.generation.PersistedRequestAudit
import app.zhijuan.core.database.generation.PersistedRequestSendPermit
import app.zhijuan.core.database.generation.PersistentBudgetPolicyRepository
import app.zhijuan.core.database.generation.PersistentBudgetReservationRepository
import app.zhijuan.core.database.generation.ProviderOpenDestinationMismatchException
import app.zhijuan.core.database.generation.ProviderOpenDestinationMismatchReason
import app.zhijuan.core.database.generation.RequestBudgetReservationDraft
import app.zhijuan.core.database.generation.RequestIntentDraft
import app.zhijuan.core.database.generation.StaleGenerationStateException
import app.zhijuan.core.database.generation.UsageUpdate
import app.zhijuan.core.database.generation.leaseTokenOrNull
import app.zhijuan.core.database.library.BookCreationSnapshotEntity
import app.zhijuan.core.database.library.BookEntity
import app.zhijuan.core.model.BookLengthMode
import app.zhijuan.core.model.BookStatus
import app.zhijuan.core.model.BudgetDailyPeriodKeyV1
import app.zhijuan.core.model.BudgetLimit
import app.zhijuan.core.model.BudgetReservationStatus
import app.zhijuan.core.model.BudgetScope
import app.zhijuan.core.model.GenerationJobStatus
import app.zhijuan.core.model.GenerationJobType
import app.zhijuan.core.model.GenerationPhase
import app.zhijuan.core.model.GenerationStageStatus
import app.zhijuan.core.model.GenerationTargetType
import app.zhijuan.core.model.RequestAttemptStatus
import app.zhijuan.core.model.ProviderOpenDestinationEvidence
import app.zhijuan.core.model.StandardErrorCode
import app.zhijuan.core.model.TitleSource
import app.zhijuan.core.model.UsageLedgerStatus
import app.zhijuan.core.model.UsageSource
import app.zhijuan.core.security.AndroidProtectedArtifactStore
import app.zhijuan.core.security.ProtectedArtifactType
import app.zhijuan.core.task.JobEvent
import app.zhijuan.core.task.ChapterContextBudgetPolicyV1
import app.zhijuan.core.task.PromptBundleCatalogV1
import app.zhijuan.core.task.ProviderRecoveryEvidence
import app.zhijuan.core.task.RecoveryDraftEvidence
import app.zhijuan.core.task.StageEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit
import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@RunWith(AndroidJUnit4::class)
class PersistentBudgetReservationDatabaseTest {
    @get:Rule
    val timeout: Timeout = Timeout(30, TimeUnit.SECONDS)

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: ZhijuanDatabase
    private lateinit var reservationRepository: PersistentBudgetReservationRepository
    private lateinit var policyRepository: PersistentBudgetPolicyRepository

    @Before
    fun setUp() {
        runBlocking {
            database = Room.inMemoryDatabaseBuilder(context, ZhijuanDatabase::class.java)
                .allowMainThreadQueries()
                .addCallback(LibraryDatabaseGuards.callback)
                .build()
                .also { it.openHelper.writableDatabase }
            reservationRepository = PersistentBudgetReservationRepository(database)
            policyRepository = PersistentBudgetPolicyRepository(database)
            seedBook()
            seedConnection(CONNECTION_ID, acceptedAt = 4L)
            seedConnection(CONNECTION_ID_NO_DISCLOSURE, acceptedAt = null)
            seedPreparedStage(JOB_ID, STAGE_ID)
            policyRepository.activateBookPolicy(
                policyId = BOOK_POLICY_1,
                bookId = BOOK_ID,
                limit = BudgetLimit(maxTokens = 1_000L),
                activatedAt = 5L,
            )
            policyRepository.activateDailyPolicy(
                policyId = DAILY_POLICY_1,
                zoneId = "Asia/Shanghai",
                limit = BudgetLimit(maxTokens = 2_000L),
                activatedAt = 6L,
            )
        }
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun successfulReservationRecordsAttemptUsageAndStageAtomicallyWithDerivedDailyKey() = runBlocking {
        val expectedKey = BudgetDailyPeriodKeyV1.create(10L, "Asia/Shanghai")
        assertNotEquals("caller-key-ignored", expectedKey)

        val result = reservationRepository.recordBudgetedRequestIntent(
            intent(),
            draft(),
            stageLease(STAGE_ID),
        )

        assertEquals(ATTEMPT_ID, result.attemptId)
        assertEquals(RESERVATION_ID, result.reservationId)
        assertFalse(result.toString().contains(RESERVATION_ID))

        val reservation = requireNotNull(database.budgetDao().findReservation(RESERVATION_ID))
        assertEquals(BudgetReservationStatus.RESERVED, reservation.status)
        assertEquals(ATTEMPT_ID, reservation.attemptId)
        assertEquals(JOB_ID, reservation.jobId)
        assertEquals(STAGE_ID, reservation.stageId)
        assertEquals(BOOK_ID, reservation.bookId)
        assertEquals(100L, reservation.requestMaxTokens)
        assertEquals(100L, reservation.estimatedTokens)
        assertEquals(100L, reservation.accountedTokens)
        assertEquals(expectedKey, reservation.dailyPeriodKey)
        assertNotEquals("caller-key-ignored", reservation.dailyPeriodKey)
        assertEquals(BOOK_POLICY_1, reservation.bookPolicyId)
        assertEquals(DAILY_POLICY_1, reservation.dailyPolicyId)
        assertEquals(CONNECTION_ID, reservation.connectionId)
        assertEquals("https://api.deepseek.com:443", reservation.normalizedDestination)
        assertEquals("OPENAI_CHAT_COMPAT", reservation.protocolId)
        assertEquals(1, reservation.disclosureVersion)
        assertEquals(4L, reservation.disclosureAcceptedAt)
        assertEquals(10L, reservation.createdAt)
        assertNull(reservation.settledAt)
        assertNull(reservation.releasedAt)

        val attempt = requireNotNull(database.generationDao().findAttempt(ATTEMPT_ID))
        assertEquals(1, attempt.budgetEnforcementVersion)
        assertEquals(RESERVATION_ID, attempt.budgetReservationId)

        val ledger = requireNotNull(database.generationDao().findUsageLedger(LEDGER_ID))
        assertEquals(UsageSource.UNKNOWN, ledger.source)
        assertEquals(UsageLedgerStatus.PROVISIONAL, ledger.status)
        assertEquals(expectedKey, ledger.dailyPeriodKey)

        val stage = requireNotNull(database.generationDao().findStage(STAGE_ID))
        assertEquals(GenerationStageStatus.REQUEST_INTENT_RECORDED, stage.status)
        assertEquals(1, stage.attemptCount)

        val bookAggregate = requireNotNull(
            database.budgetDao().aggregateBookReservations(
                BOOK_ID,
                currency = null,
                excludedStatus = BudgetReservationStatus.RELEASED,
            ),
        )
        assertEquals(100L, bookAggregate.tokens)
        val dailyAggregate = requireNotNull(
            database.budgetDao().aggregateDailyReservations(
                expectedKey,
                currency = null,
                excludedStatus = BudgetReservationStatus.RELEASED,
            ),
        )
        assertEquals(100L, dailyAggregate.tokens)
    }

    @Test
    fun chapterPlanBoundPreparationRequiresAndConsumesTheExactRunnerSnapshot() = runBlocking {
        val snapshot = seedBoundChapterPlanStage()
        val audit = GenerationRequestAuditRepository(database)

        val persisted = audit.persistBoundChapterPlanBeforeSend(
            draft = chapterPlanIntent(),
            budget = chapterPlanBudget(),
            snapshot = snapshot,
        )

        assertEquals(CHAPTER_PLAN_ATTEMPT_ID, persisted.attempt.attemptId)
        assertEquals(
            GenerationStageStatus.REQUEST_INTENT_RECORDED,
            database.generationDao().findStage(CHAPTER_PLAN_STAGE_ID)?.status,
        )
        assertEquals(
            BudgetReservationStatus.RESERVED,
            database.budgetDao().findReservation(CHAPTER_PLAN_RESERVATION_ID)?.status,
        )
        assertEquals(1, database.generationDao().attemptsForStage(CHAPTER_PLAN_STAGE_ID).size)
    }

    @Test
    fun chapterPlanGenericStageTokenPreparationIsRejectedWithoutHalfState() = runBlocking {
        val snapshot = seedBoundChapterPlanStage()
        val audit = GenerationRequestAuditRepository(database)

        val failure = captureFailure {
            audit.persistBeforeSend(
                draft = chapterPlanIntent(),
                budget = chapterPlanBudget(),
                leaseToken = snapshot.executionLease.stageLeaseToken,
            )
        }

        assertTrue(failure is StaleGenerationStateException)
        assertBoundChapterPlanZeroState()
    }

    @Test
    fun chapterPlanStreamingPreparationCleansRejectedArtifactAndPersistsBoundArtifact() = runBlocking {
        val snapshot = seedBoundChapterPlanStage()
        val artifactStore = AndroidProtectedArtifactStore(context)
        val baselineRefs = artifactStore.listArtifactReferenceIds().toSet()
        val drafts = GenerationStreamingDraftRepository(database, artifactStore)

        val genericFailure = captureFailure {
            drafts.prepareBeforeSend(
                draft = chapterPlanIntent(streamDraftRef = null),
                budget = chapterPlanBudget(),
                leaseToken = snapshot.executionLease.stageLeaseToken,
            )
        }

        assertTrue(genericFailure is StaleGenerationStateException)
        assertEquals(baselineRefs, artifactStore.listArtifactReferenceIds().toSet())
        assertBoundChapterPlanZeroState()

        var createdArtifactRef: String? = null
        try {
            val persisted = drafts.prepareBoundChapterPlanBeforeSend(
                snapshot = snapshot,
                draft = chapterPlanIntent(streamDraftRef = null),
                budget = chapterPlanBudget(),
            )
            createdArtifactRef = persisted.artifactRefId

            assertTrue(persisted.artifactRefId !in baselineRefs)
            assertEquals(
                ProtectedArtifactType.STREAM_DRAFT,
                artifactStore.descriptor(persisted.artifactRefId).type,
            )
            assertEquals(
                persisted.artifactRefId,
                database.generationDao().findAttempt(CHAPTER_PLAN_ATTEMPT_ID)?.streamDraftRef,
            )
        } finally {
            createdArtifactRef?.let(artifactStore::delete)
        }
    }

    @Test
    fun chapterPlanWrongJobTokenOrAttemptBoundsFailBeforeReservation() = runBlocking {
        val snapshot = seedBoundChapterPlanStage()
        val audit = GenerationRequestAuditRepository(database)
        val wrongJobToken = GenerationRunnerCurrentStageRouteSnapshot(
            route = GenerationRunnerStageRoute.CHAPTER_PLAN_V1,
            executionLease = snapshot.executionLease.copy(
                jobLeaseToken = GenerationLeaseToken(
                    ownerId = "runner.chapter-plan.other",
                    acquiredAt = snapshot.executionLease.jobLeaseToken.acquiredAt,
                ),
            ),
            attemptCount = snapshot.attemptCount,
            maxAttempts = snapshot.maxAttempts,
        )

        assertTrue(
            captureFailure {
                audit.persistBoundChapterPlanBeforeSend(
                    chapterPlanIntent(),
                    chapterPlanBudget(),
                    wrongJobToken,
                )
            } is StaleGenerationStateException,
        )
        assertBoundChapterPlanZeroState()

        val wrongBounds = GenerationRunnerCurrentStageRouteSnapshot(
            route = GenerationRunnerStageRoute.CHAPTER_PLAN_V1,
            executionLease = snapshot.executionLease,
            attemptCount = 1,
            maxAttempts = snapshot.maxAttempts,
        )
        assertTrue(
            captureFailure {
                audit.persistBoundChapterPlanBeforeSend(
                    chapterPlanIntent(),
                    chapterPlanBudget(),
                    wrongBounds,
                )
            } is StaleGenerationStateException,
        )
        assertBoundChapterPlanZeroState()
    }

    @Test
    fun sameDailyPeriodClaimKeepsReservationAndGenerationStateActive() = runBlocking {
        val auditRepository = GenerationRequestAuditRepository(
            database,
            GenerationLeasePolicy(timeoutMillis = 60_000_000L),
        )
        val audit = persistBudgetedV1Audit(auditRepository)
        val lastMillisBeforeShanghaiMidnight = 57_599_999L

        val claimed = auditRepository.claimForProviderOpen(
            audit.permit,
            validatedAt = lastMillisBeforeShanghaiMidnight,
        )

        assertEquals(ATTEMPT_ID, claimed.attemptId)
        assertEquals(RequestAttemptStatus.INTENT_RECORDED, database.generationDao().findAttempt(ATTEMPT_ID)?.status)
        val usage = requireNotNull(database.generationDao().findUsageForAttempt(ATTEMPT_ID))
        assertEquals(UsageSource.UNKNOWN, usage.source)
        assertEquals(UsageLedgerStatus.PROVISIONAL, usage.status)
        assertNull(usage.finalizedAt)
        val reservation = requireNotNull(database.budgetDao().findReservation(RESERVATION_ID))
        assertEquals(BudgetReservationStatus.RESERVED, reservation.status)
        assertEquals(100L, reservation.accountedTokens)
        assertNull(reservation.releasedAt)
        assertEquals(GenerationStageStatus.REQUEST_INTENT_RECORDED, database.generationDao().findStage(STAGE_ID)?.status)
        assertEquals(lastMillisBeforeShanghaiMidnight, database.generationDao().findStage(STAGE_ID)?.leaseHeartbeatAt)
        assertEquals(GenerationJobStatus.RUNNING, database.generationDao().findJob(JOB_ID)?.status)
        assertEquals(
            100L,
            database.budgetDao().aggregateBookReservations(
                BOOK_ID,
                currency = null,
                excludedStatus = BudgetReservationStatus.RELEASED,
            )?.tokens,
        )
        assertEquals(
            100L,
            database.budgetDao().aggregateDailyReservations(
                BudgetDailyPeriodKeyV1.create(10L, "Asia/Shanghai"),
                currency = null,
                excludedStatus = BudgetReservationStatus.RELEASED,
            )?.tokens,
        )
    }

    @Test
    fun providerOpenDestinationMismatchWritesNothingAndSamePermitCanRetry() = runBlocking {
        val auditRepository = GenerationRequestAuditRepository(database)
        val audit = persistBudgetedV1Audit(auditRepository)
        val attemptBefore = database.generationDao().findAttempt(ATTEMPT_ID)
        val usageBefore = database.generationDao().findUsageForAttempt(ATTEMPT_ID)
        val reservationBefore = database.budgetDao().findReservation(RESERVATION_ID)
        val stageBefore = database.generationDao().findStage(STAGE_ID)
        val jobBefore = database.generationDao().findJob(JOB_ID)

        val error = captureFailure {
            auditRepository.claimForProviderOpen(
                permit = audit.permit,
                validatedAt = 11L,
                destination = providerDestinationEvidence(baseUrl = "https://wrong.example.invalid/v1"),
            )
        }

        assertTrue(error is ProviderOpenDestinationMismatchException)
        assertEquals(
            ProviderOpenDestinationMismatchReason.DESTINATION_ORIGIN,
            (error as ProviderOpenDestinationMismatchException).reason,
        )
        assertEquals(attemptBefore, database.generationDao().findAttempt(ATTEMPT_ID))
        assertEquals(usageBefore, database.generationDao().findUsageForAttempt(ATTEMPT_ID))
        assertEquals(reservationBefore, database.budgetDao().findReservation(RESERVATION_ID))
        assertEquals(stageBefore, database.generationDao().findStage(STAGE_ID))
        assertEquals(jobBefore, database.generationDao().findJob(JOB_ID))
        assertFalse(error.toString().contains("wrong.example.invalid"))
        assertFalse(error.toString().contains(CONNECTION_ID))

        val claimed = auditRepository.claimForProviderOpen(
            permit = audit.permit,
            validatedAt = 11L,
            destination = providerDestinationEvidence(),
        )
        assertEquals(ATTEMPT_ID, claimed.attemptId)
    }

    @Test
    fun destinationMismatchWinsBeforeCrossDayReleaseAndKeepsAllState() = runBlocking {
        val auditRepository = GenerationRequestAuditRepository(database)
        val audit = persistBudgetedV1Audit(auditRepository)
        val attemptBefore = database.generationDao().findAttempt(ATTEMPT_ID)
        val usageBefore = database.generationDao().findUsageForAttempt(ATTEMPT_ID)
        val reservationBefore = database.budgetDao().findReservation(RESERVATION_ID)
        val stageBefore = database.generationDao().findStage(STAGE_ID)
        val jobBefore = database.generationDao().findJob(JOB_ID)

        val error = captureFailure {
            auditRepository.claimForProviderOpen(
                permit = audit.permit,
                validatedAt = 57_600_000L,
                destination = providerDestinationEvidence(connectionId = "wrong-connection"),
            )
        }

        assertTrue(error is ProviderOpenDestinationMismatchException)
        assertEquals(
            ProviderOpenDestinationMismatchReason.CONNECTION_ID,
            (error as ProviderOpenDestinationMismatchException).reason,
        )
        assertEquals(attemptBefore, database.generationDao().findAttempt(ATTEMPT_ID))
        assertEquals(usageBefore, database.generationDao().findUsageForAttempt(ATTEMPT_ID))
        assertEquals(reservationBefore, database.budgetDao().findReservation(RESERVATION_ID))
        assertEquals(stageBefore, database.generationDao().findStage(STAGE_ID))
        assertEquals(jobBefore, database.generationDao().findJob(JOB_ID))
    }

    @Test
    fun currentDisclosureDriftFailsClosedWhileLaterSameBindingAcceptanceIsAllowed() = runBlocking {
        val auditRepository = GenerationRequestAuditRepository(database)
        val driftedAudit = persistBudgetedV1Audit(auditRepository)
        val attemptBefore = database.generationDao().findAttempt(ATTEMPT_ID)
        val reservationBefore = database.budgetDao().findReservation(RESERVATION_ID)
        database.openHelper.writableDatabase.execSQL(
            "UPDATE connection_profile SET base_url = ? WHERE connection_id = ?",
            arrayOf("https://changed.example.invalid", CONNECTION_ID),
        )

        val driftError = captureFailure {
            auditRepository.claimForProviderOpen(
                permit = driftedAudit.permit,
                validatedAt = 11L,
                destination = providerDestinationEvidence(),
            )
        }
        assertTrue(driftError is ProviderOpenDestinationMismatchException)
        assertEquals(
            ProviderOpenDestinationMismatchReason.DISCLOSURE_UNAVAILABLE,
            (driftError as ProviderOpenDestinationMismatchException).reason,
        )
        assertEquals(attemptBefore, database.generationDao().findAttempt(ATTEMPT_ID))
        assertEquals(reservationBefore, database.budgetDao().findReservation(RESERVATION_ID))

        database.openHelper.writableDatabase.execSQL(
            "UPDATE connection_profile SET base_url = ? WHERE connection_id = ?",
            arrayOf("https://api.deepseek.com", CONNECTION_ID),
        )
        database.connectionDao().acceptDataDisclosureForCurrentDestination(CONNECTION_ID, acceptedAt = 9L)
        val claimed = auditRepository.claimForProviderOpen(
            permit = driftedAudit.permit,
            validatedAt = 11L,
            destination = providerDestinationEvidence(),
        )
        assertEquals(ATTEMPT_ID, claimed.attemptId)
    }

    @Test
    fun nextDailyPeriodClaimAtomicallyReleasesUnsentAttemptAndAllowsReprepare() = runBlocking {
        val auditRepository = GenerationRequestAuditRepository(database)
        val audit = persistBudgetedV1Audit(auditRepository)
        val validatedAt = 57_600_000L

        val error = captureFailure {
            auditRepository.claimForProviderOpen(audit.permit, validatedAt)
        }

        assertTrue(error is DailyBudgetPeriodRolloverRequiredException)
        assertTrue((error as DailyBudgetPeriodRolloverRequiredException).retryAllowed)
        assertDailyRolloverTerminalState(
            validatedAt = validatedAt,
            expectedStageStatus = GenerationStageStatus.READY,
            expectedJobStatus = GenerationJobStatus.READY,
            expectedJobReason = null,
        )
        val oldDailyKey = BudgetDailyPeriodKeyV1.create(10L, "Asia/Shanghai")
        assertNull(
            database.budgetDao().aggregateBookReservations(
                BOOK_ID,
                currency = null,
                excludedStatus = BudgetReservationStatus.RELEASED,
            )?.tokens,
        )
        assertNull(
            database.budgetDao().aggregateDailyReservations(
                oldDailyKey,
                currency = null,
                excludedStatus = BudgetReservationStatus.RELEASED,
            )?.tokens,
        )

        val replay = captureFailure {
            auditRepository.claimForProviderOpen(audit.permit, validatedAt + 1L)
        }
        assertTrue(replay is StaleGenerationStateException)
        assertDailyRolloverTerminalState(
            validatedAt = validatedAt,
            expectedStageStatus = GenerationStageStatus.READY,
            expectedJobStatus = GenerationJobStatus.READY,
            expectedJobReason = null,
        )
    }

    @Test
    fun nextDailyPeriodClaimAtAttemptLimitRequiresUserAction() = runBlocking {
        database.openHelper.writableDatabase.execSQL(
            "UPDATE generation_stage SET max_attempts = 1 WHERE stage_id = '$STAGE_ID'",
        )
        val auditRepository = GenerationRequestAuditRepository(database)
        val audit = persistBudgetedV1Audit(auditRepository)
        val validatedAt = 86_400_010L

        val error = captureFailure {
            auditRepository.claimForProviderOpen(audit.permit, validatedAt)
        }

        assertTrue(error is DailyBudgetPeriodRolloverRequiredException)
        assertFalse((error as DailyBudgetPeriodRolloverRequiredException).retryAllowed)
        assertDailyRolloverTerminalState(
            validatedAt = validatedAt,
            expectedStageStatus = GenerationStageStatus.NEEDS_ACTION,
            expectedJobStatus = GenerationJobStatus.NEEDS_ACTION,
            expectedJobReason = StandardErrorCode.DAILY_BUDGET_PERIOD_EXPIRED_BEFORE_SEND.name,
        )
    }

    @Test
    fun concurrentNextPeriodClaimsCommitExactlyOneRollover() = runBlocking {
        val auditRepository = GenerationRequestAuditRepository(database)
        val audit = persistBudgetedV1Audit(auditRepository)
        val validatedAt = 57_600_000L

        val first = async(Dispatchers.Default) {
            captureFailure { auditRepository.claimForProviderOpen(audit.permit, validatedAt) }
        }
        val second = async(Dispatchers.Default) {
            captureFailure { auditRepository.claimForProviderOpen(audit.permit, validatedAt) }
        }
        val failures = listOf(first.await(), second.await())

        assertEquals(1, failures.count { it is DailyBudgetPeriodRolloverRequiredException })
        assertEquals(1, failures.count { it is StaleGenerationStateException })
        assertDailyRolloverTerminalState(
            validatedAt = validatedAt,
            expectedStageStatus = GenerationStageStatus.READY,
            expectedJobStatus = GenerationJobStatus.READY,
            expectedJobReason = null,
        )
        assertEquals(
            1L,
            database.openHelper.readableDatabase.query(
                "SELECT COUNT(*) FROM request_budget_reservation WHERE attempt_id = '$ATTEMPT_ID' AND status = 'RELEASED'",
            ).use { cursor ->
                check(cursor.moveToFirst())
                cursor.getLong(0)
            },
        )
    }

    @Test
    fun sentAttemptCannotBeReleasedByDailyRollover() = runBlocking {
        val auditRepository = GenerationRequestAuditRepository(database)
        val audit = persistBudgetedV1Audit(auditRepository)
        val claimed = auditRepository.claimForProviderOpen(audit.permit, validatedAt = 11L)
        auditRepository.markRequestSent(claimed, providerRequestId = "remote-sent", sentAt = 12L)

        val error = captureFailure {
            auditRepository.claimForProviderOpen(audit.permit, validatedAt = 57_600_000L)
        }

        assertTrue(error is StaleGenerationStateException)
        val attempt = requireNotNull(database.generationDao().findAttempt(ATTEMPT_ID))
        assertEquals(RequestAttemptStatus.SENT, attempt.status)
        assertEquals(12L, attempt.sentAt)
        assertEquals("remote-sent", attempt.providerRequestId)
        assertNull(attempt.finishedAt)
        assertNull(attempt.standardErrorCode)
        val usage = requireNotNull(database.generationDao().findUsageForAttempt(ATTEMPT_ID))
        assertEquals(UsageLedgerStatus.PROVISIONAL, usage.status)
        assertNull(usage.finalizedAt)
        val reservation = requireNotNull(database.budgetDao().findReservation(RESERVATION_ID))
        assertEquals(BudgetReservationStatus.RESERVED, reservation.status)
        assertEquals(100L, reservation.accountedTokens)
        assertNull(reservation.releasedAt)
        assertEquals(GenerationStageStatus.STREAMING, database.generationDao().findStage(STAGE_ID)?.status)
        assertEquals(GenerationJobStatus.RUNNING, database.generationDao().findJob(JOB_ID)?.status)
    }

    @Test
    fun dailyRolloverReplacementCreatesNewAttemptAndOnlyResetsDailyUsage() = runBlocking {
        seedPreparedStage(JOB_ID_REV_2, STAGE_ID_REV_2)
        reservationRepository.recordBudgetedRequestIntent(
            intent(
                attemptId = ATTEMPT_REV_2,
                ledgerId = LEDGER_REV_2,
                stageId = STAGE_ID_REV_2,
                createdAt = 9L,
            ),
            draft(
                reservationId = RESERVATION_REV_2,
                requestMaxTokens = 50L,
                estimatedTokens = 50L,
            ),
            stageLease(STAGE_ID_REV_2),
        )
        database.generationDao().recordUsage(
            ATTEMPT_REV_2,
            usage(
                source = UsageSource.PROVIDER_REPORTED,
                status = UsageLedgerStatus.FINAL,
                totalTokens = 50L,
                updatedAt = 12L,
            ),
        )
        val auditRepository = GenerationRequestAuditRepository(database)
        val oldAudit = persistBudgetedV1Audit(auditRepository)
        rollover(oldAudit, 57_600_000L)
        val executionLease = reacquireRolloverExecution(57_600_001L, 57_600_002L)

        val replacement = auditRepository.persistDailyRolloverReplacementBeforeSend(
            draft = rolloverReplacementDraft(
                attemptId = ATTEMPT_ROLLOVER_2,
                ledgerId = LEDGER_ROLLOVER_2,
                streamDraftRef = ROLLOVER_ARTIFACT_2,
                createdAt = 57_600_003L,
            ),
            budget = draft(reservationId = RESERVATION_ROLLOVER_2),
            executionLease = executionLease,
            parentAttemptId = ATTEMPT_ID,
            sourceArtifactRefId = ROLLOVER_ARTIFACT_1,
        )

        assertEquals(ATTEMPT_ROLLOVER_2, replacement.attempt.attemptId)
        assertEquals(2, replacement.attempt.attemptNo)
        assertEquals(ATTEMPT_ID, replacement.attempt.retryParentAttemptId)
        val attempt = requireNotNull(database.generationDao().findAttempt(ATTEMPT_ROLLOVER_2))
        assertEquals(ROLLOVER_ARTIFACT_2, attempt.streamDraftRef)
        assertEquals(RequestAttemptStatus.INTENT_RECORDED, attempt.status)
        val newDailyKey = BudgetDailyPeriodKeyV1.create(57_600_003L, "Asia/Shanghai")
        val reservation = requireNotNull(database.budgetDao().findReservation(RESERVATION_ROLLOVER_2))
        assertEquals(newDailyKey, reservation.dailyPeriodKey)
        assertEquals(BudgetReservationStatus.RESERVED, reservation.status)
        assertEquals(newDailyKey, database.generationDao().findUsageForAttempt(ATTEMPT_ROLLOVER_2)?.dailyPeriodKey)
        assertEquals(BudgetReservationStatus.RELEASED, database.budgetDao().findReservation(RESERVATION_ID)?.status)
        assertEquals(GenerationStageStatus.REQUEST_INTENT_RECORDED, database.generationDao().findStage(STAGE_ID)?.status)
        assertEquals(2, database.generationDao().findStage(STAGE_ID)?.attemptCount)
        assertEquals(
            150L,
            database.budgetDao().aggregateBookReservations(
                BOOK_ID,
                currency = null,
                excludedStatus = BudgetReservationStatus.RELEASED,
            )?.tokens,
        )
        assertEquals(
            50L,
            database.budgetDao().aggregateDailyReservations(
                BudgetDailyPeriodKeyV1.create(10L, "Asia/Shanghai"),
                currency = null,
                excludedStatus = BudgetReservationStatus.RELEASED,
            )?.tokens,
        )
        assertEquals(
            100L,
            database.budgetDao().aggregateDailyReservations(
                newDailyKey,
                currency = null,
                excludedStatus = BudgetReservationStatus.RELEASED,
            )?.tokens,
        )
    }

    @Test
    fun ordinaryPrepareCannotBypassDailyRolloverReplacementEvidence() = runBlocking {
        val auditRepository = GenerationRequestAuditRepository(database)
        val oldAudit = persistBudgetedV1Audit(auditRepository)
        rollover(oldAudit, 57_600_000L)
        val executionLease = reacquireRolloverExecution(57_600_001L, 57_600_002L)

        val error = captureFailure {
            auditRepository.persistBeforeSend(
                draft = rolloverReplacementDraft(
                    attemptId = ATTEMPT_ROLLOVER_2,
                    ledgerId = LEDGER_ROLLOVER_2,
                    streamDraftRef = ROLLOVER_ARTIFACT_2,
                    createdAt = 57_600_003L,
                ),
                budget = draft(reservationId = RESERVATION_ROLLOVER_2),
                leaseToken = executionLease.stageLeaseToken,
            )
        }

        assertTrue(error is StaleGenerationStateException)
        assertNull(database.generationDao().findAttempt(ATTEMPT_ROLLOVER_2))
        assertNull(database.generationDao().findUsageLedger(LEDGER_ROLLOVER_2))
        assertNull(database.budgetDao().findReservation(RESERVATION_ROLLOVER_2))
        val stage = requireNotNull(database.generationDao().findStage(STAGE_ID))
        assertEquals(GenerationStageStatus.PREPARING, stage.status)
        assertEquals(1, stage.attemptCount)
        assertEquals(executionLease.stageLeaseToken, stage.leaseTokenOrNull())
    }

    @Test
    fun dailyRolloverReplacementRejectsNewDayQuotaWithNoHalfState() = runBlocking {
        val auditRepository = GenerationRequestAuditRepository(database)
        val oldAudit = persistBudgetedV1Audit(auditRepository)
        rollover(oldAudit, 57_600_000L)
        val executionLease = reacquireRolloverExecution(57_600_001L, 57_600_002L)
        policyRepository.activateDailyPolicy(
            policyId = DAILY_POLICY_2,
            zoneId = "Asia/Shanghai",
            limit = BudgetLimit(maxTokens = 50L),
            activatedAt = 57_600_002L,
        )

        val error = captureFailure {
            auditRepository.persistDailyRolloverReplacementBeforeSend(
                draft = rolloverReplacementDraft(
                    attemptId = ATTEMPT_ROLLOVER_2,
                    ledgerId = LEDGER_ROLLOVER_2,
                    streamDraftRef = ROLLOVER_ARTIFACT_2,
                    createdAt = 57_600_003L,
                ),
                budget = draft(reservationId = RESERVATION_ROLLOVER_2),
                executionLease = executionLease,
                parentAttemptId = ATTEMPT_ID,
                sourceArtifactRefId = ROLLOVER_ARTIFACT_1,
            )
        }

        assertTrue(error is BudgetReservationRejectedException)
        assertEquals(BudgetScope.DAILY, (error as BudgetReservationRejectedException).scope)
        assertNull(database.generationDao().findAttempt(ATTEMPT_ROLLOVER_2))
        assertNull(database.generationDao().findUsageLedger(LEDGER_ROLLOVER_2))
        assertNull(database.budgetDao().findReservation(RESERVATION_ROLLOVER_2))
        val stage = requireNotNull(database.generationDao().findStage(STAGE_ID))
        assertEquals(GenerationStageStatus.PREPARING, stage.status)
        assertEquals(1, stage.attemptCount)
        assertEquals(executionLease.stageLeaseToken, stage.leaseTokenOrNull())
        assertEquals(BudgetReservationStatus.RELEASED, database.budgetDao().findReservation(RESERVATION_ID)?.status)
    }

    @Test
    fun dailyRolloverReplacementRequiresExactJobAndStageExecutionLease() = runBlocking {
        val auditRepository = GenerationRequestAuditRepository(database)
        val oldAudit = persistBudgetedV1Audit(auditRepository)
        rollover(oldAudit, 57_600_000L)
        val executionLease = reacquireRolloverExecution(57_600_001L, 57_600_002L)
        val wrongExecutionLease = executionLease.copy(
            jobLeaseToken = GenerationLeaseToken(executionLease.jobLeaseToken.ownerId, 57_600_000L),
        )

        val error = captureFailure {
            auditRepository.persistDailyRolloverReplacementBeforeSend(
                draft = rolloverReplacementDraft(
                    attemptId = ATTEMPT_ROLLOVER_2,
                    ledgerId = LEDGER_ROLLOVER_2,
                    streamDraftRef = ROLLOVER_ARTIFACT_2,
                    createdAt = 57_600_003L,
                ),
                budget = draft(reservationId = RESERVATION_ROLLOVER_2),
                executionLease = wrongExecutionLease,
                parentAttemptId = ATTEMPT_ID,
                sourceArtifactRefId = ROLLOVER_ARTIFACT_1,
            )
        }

        assertTrue(error is StaleGenerationStateException)
        assertNull(database.generationDao().findAttempt(ATTEMPT_ROLLOVER_2))
        assertNull(database.budgetDao().findReservation(RESERVATION_ROLLOVER_2))
        assertEquals(GenerationStageStatus.PREPARING, database.generationDao().findStage(STAGE_ID)?.status)
    }

    @Test
    fun concurrentDailyRolloverReplacementsPersistExactlyOneNewAttempt() = runBlocking {
        val auditRepository = GenerationRequestAuditRepository(database)
        val oldAudit = persistBudgetedV1Audit(auditRepository)
        rollover(oldAudit, 57_600_000L)
        val executionLease = reacquireRolloverExecution(57_600_001L, 57_600_002L)

        val first = async(Dispatchers.Default) {
            runCatching {
                auditRepository.persistDailyRolloverReplacementBeforeSend(
                    draft = rolloverReplacementDraft(
                        attemptId = ATTEMPT_ROLLOVER_2,
                        ledgerId = LEDGER_ROLLOVER_2,
                        streamDraftRef = ROLLOVER_ARTIFACT_2,
                        createdAt = 57_600_003L,
                    ),
                    budget = draft(reservationId = RESERVATION_ROLLOVER_2),
                    executionLease = executionLease,
                    parentAttemptId = ATTEMPT_ID,
                    sourceArtifactRefId = ROLLOVER_ARTIFACT_1,
                )
            }
        }
        val second = async(Dispatchers.Default) {
            runCatching {
                auditRepository.persistDailyRolloverReplacementBeforeSend(
                    draft = rolloverReplacementDraft(
                        attemptId = ATTEMPT_ROLLOVER_3,
                        ledgerId = LEDGER_ROLLOVER_3,
                        streamDraftRef = ROLLOVER_ARTIFACT_3,
                        createdAt = 57_600_003L,
                    ),
                    budget = draft(reservationId = RESERVATION_ROLLOVER_3),
                    executionLease = executionLease,
                    parentAttemptId = ATTEMPT_ID,
                    sourceArtifactRefId = ROLLOVER_ARTIFACT_1,
                )
            }
        }
        val outcomes = listOf(first.await(), second.await())

        assertEquals(1, outcomes.count { it.isSuccess })
        assertEquals(1, outcomes.count { it.isFailure })
        assertEquals(2, database.generationDao().attemptsForStage(STAGE_ID).size)
        assertEquals(2, database.generationDao().findStage(STAGE_ID)?.attemptCount)
        assertEquals(GenerationStageStatus.REQUEST_INTENT_RECORDED, database.generationDao().findStage(STAGE_ID)?.status)
        assertEquals(
            1L,
            database.openHelper.readableDatabase.query(
                "SELECT COUNT(*) FROM request_budget_reservation WHERE stage_id = '$STAGE_ID' AND status = 'RESERVED'",
            ).use { cursor ->
                check(cursor.moveToFirst())
                cursor.getLong(0)
            },
        )
    }

    @Test
    fun requestTokenOverLimitRejectsWithZeroHalfState() = runBlocking {
        val error = assertThrows(BudgetReservationRejectedException::class.java) {
            runBlocking {
                reservationRepository.recordBudgetedRequestIntent(
                    intent(),
                    draft(requestMaxTokens = 50L, estimatedTokens = 100L),
                    stageLease(STAGE_ID),
                )
            }
        }
        assertEquals(BudgetScope.REQUEST, error.scope)
        assertEquals(BudgetReservationRejectionReason.LIMIT_EXCEEDED, error.reason)
        assertFalse(error.toString().contains(RESERVATION_ID))
        assertFalse(error.message.orEmpty().contains(RESERVATION_ID))
        assertZeroHalfState(RESERVATION_ID, ATTEMPT_ID, LEDGER_ID, STAGE_ID, GenerationStageStatus.PREPARING)
    }

    @Test
    fun bookTokenOverLimitRejectsWithZeroHalfState() = runBlocking {
        val error = assertThrows(BudgetReservationRejectedException::class.java) {
            runBlocking {
                reservationRepository.recordBudgetedRequestIntent(
                    intent(),
                    draft(requestMaxTokens = 3_000L, estimatedTokens = 2_000L),
                    stageLease(STAGE_ID),
                )
            }
        }
        assertEquals(BudgetScope.BOOK, error.scope)
        assertEquals(BudgetReservationRejectionReason.LIMIT_EXCEEDED, error.reason)
        assertZeroHalfState(RESERVATION_ID, ATTEMPT_ID, LEDGER_ID, STAGE_ID, GenerationStageStatus.PREPARING)
    }

    @Test
    fun dailyTokenOverLimitRejectsWithZeroHalfState() = runBlocking {
        policyRepository.activateBookPolicy(
            policyId = BOOK_POLICY_2,
            bookId = BOOK_ID,
            limit = BudgetLimit(maxTokens = 10_000L),
            activatedAt = 7L,
        )
        val error = assertThrows(BudgetReservationRejectedException::class.java) {
            runBlocking {
                reservationRepository.recordBudgetedRequestIntent(
                    intent(),
                    draft(requestMaxTokens = 6_000L, estimatedTokens = 5_000L),
                    stageLease(STAGE_ID),
                )
            }
        }
        assertEquals(BudgetScope.DAILY, error.scope)
        assertEquals(BudgetReservationRejectionReason.LIMIT_EXCEEDED, error.reason)
        assertZeroHalfState(RESERVATION_ID, ATTEMPT_ID, LEDGER_ID, STAGE_ID, GenerationStageStatus.PREPARING)
    }

    @Test
    fun missingEstimateWithMonetaryLimitRejectsConservatively() = runBlocking {
        policyRepository.activateBookPolicy(
            policyId = BOOK_POLICY_2,
            bookId = BOOK_ID,
            limit = BudgetLimit(maxTokens = 1_000L, maxCostMicros = 500L, currency = "USD"),
            activatedAt = 7L,
        )
        val error = assertThrows(BudgetReservationRejectedException::class.java) {
            runBlocking {
                reservationRepository.recordBudgetedRequestIntent(
                    intent(),
                    draft(),
                    stageLease(STAGE_ID),
                )
            }
        }
        assertEquals(BudgetScope.BOOK, error.scope)
        assertEquals(BudgetReservationRejectionReason.MONETARY_ESTIMATE_UNAVAILABLE, error.reason)
        assertZeroHalfState(RESERVATION_ID, ATTEMPT_ID, LEDGER_ID, STAGE_ID, GenerationStageStatus.PREPARING)
    }

    @Test
    fun currencyMismatchWithMonetaryLimitRejectsConservatively() = runBlocking {
        policyRepository.activateBookPolicy(
            policyId = BOOK_POLICY_2,
            bookId = BOOK_ID,
            limit = BudgetLimit(maxTokens = 1_000L, maxCostMicros = 500L, currency = "USD"),
            activatedAt = 7L,
        )
        val error = assertThrows(BudgetReservationRejectedException::class.java) {
            runBlocking {
                reservationRepository.recordBudgetedRequestIntent(
                    intent(),
                    draft(
                        estimatedCostMicros = 25L,
                        estimatedCurrency = "CNY",
                    ),
                    stageLease(STAGE_ID),
                )
            }
        }
        assertEquals(BudgetScope.BOOK, error.scope)
        assertEquals(BudgetReservationRejectionReason.CURRENCY_MISMATCH, error.reason)
        assertZeroHalfState(RESERVATION_ID, ATTEMPT_ID, LEDGER_ID, STAGE_ID, GenerationStageStatus.PREPARING)
    }

    @Test
    fun missingDisclosureRollsBackCandidate() = runBlocking {
        val error = assertThrows(BudgetReservationRejectedException::class.java) {
            runBlocking {
                reservationRepository.recordBudgetedRequestIntent(
                    intent(),
                    draft(connectionId = CONNECTION_ID_NO_DISCLOSURE),
                    stageLease(STAGE_ID),
                )
            }
        }
        assertEquals(BudgetScope.REQUEST, error.scope)
        assertEquals(BudgetReservationRejectionReason.POLICY_UNAVAILABLE, error.reason)
        assertZeroHalfState(RESERVATION_ID, ATTEMPT_ID, LEDGER_ID, STAGE_ID, GenerationStageStatus.PREPARING)
    }

    @Test
    fun attemptWriteFailureRollsBackCandidate() = runBlocking {
        seedPreparedStage(JOB_ID_UNSTARTED, STAGE_ID_UNSTARTED, startJob = false)
        val error = assertThrows(RuntimeException::class.java) {
            runBlocking {
                reservationRepository.recordBudgetedRequestIntent(
                    intent(
                        attemptId = ATTEMPT_ID_UNSTARTED,
                        ledgerId = LEDGER_ID_UNSTARTED,
                        stageId = STAGE_ID_UNSTARTED,
                    ),
                    draft(reservationId = RESERVATION_ID_UNSTARTED),
                    GenerationLeaseToken(ownerId = "worker-a", acquiredAt = 4L),
                )
            }
        }
        assertNotNull(error)
        assertZeroHalfState(
            RESERVATION_ID_UNSTARTED,
            ATTEMPT_ID_UNSTARTED,
            LEDGER_ID_UNSTARTED,
            STAGE_ID_UNSTARTED,
            GenerationStageStatus.PENDING,
        )
    }

    @Test
    fun aggregationIncludesReservationsFromPreviousPolicyRevision() = runBlocking {
        val expectedKey = BudgetDailyPeriodKeyV1.create(10L, "Asia/Shanghai")
        seedPreparedStage(JOB_ID_REV_2, STAGE_ID_REV_2)
        seedPreparedStage(JOB_ID_REV_3, STAGE_ID_REV_3)

        // First reservation under book revision 1 (max 1000).
        reservationRepository.recordBudgetedRequestIntent(
            intent(attemptId = ATTEMPT_REV_1, ledgerId = LEDGER_REV_1, stageId = STAGE_ID, createdAt = 10L),
            draft(reservationId = RESERVATION_REV_1, estimatedTokens = 100L),
            stageLease(STAGE_ID),
        )

        // Revision 2 lowers the book limit below the accumulated usage
        // (150 < 100 + 200): the second reservation must be rejected, proving
        // the old-revision reservation still counts.
        policyRepository.activateBookPolicy(
            policyId = BOOK_POLICY_2,
            bookId = BOOK_ID,
            limit = BudgetLimit(maxTokens = 150L),
            activatedAt = 20L,
        )
        val rejected = assertThrows(BudgetReservationRejectedException::class.java) {
            runBlocking {
                reservationRepository.recordBudgetedRequestIntent(
                    intent(attemptId = ATTEMPT_REV_2, ledgerId = LEDGER_REV_2, stageId = STAGE_ID_REV_2, createdAt = 30L),
                    draft(reservationId = RESERVATION_REV_2, requestMaxTokens = 500L, estimatedTokens = 200L),
                    stageLease(STAGE_ID_REV_2),
                )
            }
        }
        assertEquals(BudgetScope.BOOK, rejected.scope)
        assertEquals(BudgetReservationRejectionReason.LIMIT_EXCEEDED, rejected.reason)
        assertZeroHalfState(
            RESERVATION_REV_2,
            ATTEMPT_REV_2,
            LEDGER_REV_2,
            STAGE_ID_REV_2,
            GenerationStageStatus.PREPARING,
        )
        assertNotNull(database.budgetDao().findReservation(RESERVATION_REV_1))

        // Revision 3 restores headroom (400 >= 100 + 200); success and the
        // final aggregate prove both the old and new revision tokens count.
        policyRepository.activateBookPolicy(
            policyId = BOOK_POLICY_3,
            bookId = BOOK_ID,
            limit = BudgetLimit(maxTokens = 400L),
            activatedAt = 40L,
        )
        reservationRepository.recordBudgetedRequestIntent(
            intent(attemptId = ATTEMPT_REV_3, ledgerId = LEDGER_REV_3, stageId = STAGE_ID_REV_3, createdAt = 50L),
            draft(reservationId = RESERVATION_REV_3, requestMaxTokens = 500L, estimatedTokens = 200L),
            stageLease(STAGE_ID_REV_3),
        )

        val bookAggregate = requireNotNull(
            database.budgetDao().aggregateBookReservations(
                BOOK_ID,
                currency = null,
                excludedStatus = BudgetReservationStatus.RELEASED,
            ),
        )
        assertEquals(300L, bookAggregate.tokens) // 100 (rev 1) + 200 (rev 3); rev 2 rolled back.
        val dailyAggregate = requireNotNull(
            database.budgetDao().aggregateDailyReservations(
                expectedKey,
                currency = null,
                excludedStatus = BudgetReservationStatus.RELEASED,
            ),
        )
        assertEquals(300L, dailyAggregate.tokens)
    }

    @Test
    fun concurrentReservationsOnSameRoomCannotSpendTheSameBookHeadroomTwice() = runBlocking {
        policyRepository.activateBookPolicy(
            policyId = BOOK_POLICY_2,
            bookId = BOOK_ID,
            limit = BudgetLimit(maxTokens = 150L),
            activatedAt = 7L,
        )
        seedPreparedStage(JOB_ID_CONCURRENT_2, STAGE_ID_CONCURRENT_2)

        val start = CompletableDeferred<Unit>()
        val first = async(Dispatchers.IO) {
            start.await()
            runCatching {
                reservationRepository.recordBudgetedRequestIntent(
                    intent(
                        attemptId = ATTEMPT_CONCURRENT_1,
                        ledgerId = LEDGER_CONCURRENT_1,
                        stageId = STAGE_ID,
                    ),
                    draft(reservationId = RESERVATION_CONCURRENT_1),
                    stageLease(STAGE_ID),
                )
            }
        }
        val second = async(Dispatchers.IO) {
            start.await()
            runCatching {
                reservationRepository.recordBudgetedRequestIntent(
                    intent(
                        attemptId = ATTEMPT_CONCURRENT_2,
                        ledgerId = LEDGER_CONCURRENT_2,
                        stageId = STAGE_ID_CONCURRENT_2,
                    ),
                    draft(reservationId = RESERVATION_CONCURRENT_2),
                    stageLease(STAGE_ID_CONCURRENT_2),
                )
            }
        }
        start.complete(Unit)

        assertSingleWinner(
            targetDatabase = database,
            outcomes = listOf(first.await(), second.await()),
            candidates = listOf(
                ReservationCandidate(
                    RESERVATION_CONCURRENT_1,
                    ATTEMPT_CONCURRENT_1,
                    LEDGER_CONCURRENT_1,
                    STAGE_ID,
                ),
                ReservationCandidate(
                    RESERVATION_CONCURRENT_2,
                    ATTEMPT_CONCURRENT_2,
                    LEDGER_CONCURRENT_2,
                    STAGE_ID_CONCURRENT_2,
                ),
            ),
        )
    }

    @Test
    fun concurrentReservationsAcrossTwoRoomInstancesRemainDeniedAfterReopen() = runBlocking {
        val databaseName = "budget-reservation-race-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        var firstDatabase: ZhijuanDatabase? = null
        var secondDatabase: ZhijuanDatabase? = null
        try {
            val db1 = openFileDatabase(databaseName)
            firstDatabase = db1
            // Open both Room instances before either connection starts the
            // fixture writes. Each onOpen callback installs the same guard
            // triggers; doing that while the first connection is still
            // flushing setup DML can intermittently surface SQLITE_BUSY on
            // API 30 and would test callback timing rather than reservation
            // write contention.
            val db2 = openFileDatabase(databaseName)
            secondDatabase = db2
            seedBook(db1)
            seedConnection(CONNECTION_ID, acceptedAt = 4L, targetDatabase = db1)
            seedPreparedStage(JOB_ID_FILE_1, STAGE_ID_FILE_1, targetDatabase = db1)
            seedPreparedStage(JOB_ID_FILE_2, STAGE_ID_FILE_2, targetDatabase = db1)
            val filePolicyRepository = PersistentBudgetPolicyRepository(db1)
            filePolicyRepository.activateBookPolicy(
                policyId = BOOK_POLICY_FILE,
                bookId = BOOK_ID,
                limit = BudgetLimit(maxTokens = 150L),
                activatedAt = 5L,
            )
            filePolicyRepository.activateDailyPolicy(
                policyId = DAILY_POLICY_FILE,
                zoneId = "Asia/Shanghai",
                limit = BudgetLimit(maxTokens = 1_000L),
                activatedAt = 6L,
            )

            val firstRepository = PersistentBudgetReservationRepository(db1)
            val secondRepository = PersistentBudgetReservationRepository(db2)
            val start = CompletableDeferred<Unit>()
            val first = async(Dispatchers.IO) {
                start.await()
                runCatching {
                    firstRepository.recordBudgetedRequestIntent(
                        intent(
                            attemptId = ATTEMPT_FILE_1,
                            ledgerId = LEDGER_FILE_1,
                            stageId = STAGE_ID_FILE_1,
                        ),
                        draft(reservationId = RESERVATION_FILE_1),
                        stageLease(STAGE_ID_FILE_1, db1),
                    )
                }
            }
            val second = async(Dispatchers.IO) {
                start.await()
                runCatching {
                    secondRepository.recordBudgetedRequestIntent(
                        intent(
                            attemptId = ATTEMPT_FILE_2,
                            ledgerId = LEDGER_FILE_2,
                            stageId = STAGE_ID_FILE_2,
                        ),
                        draft(reservationId = RESERVATION_FILE_2),
                        stageLease(STAGE_ID_FILE_2, db2),
                    )
                }
            }
            start.complete(Unit)

            assertSingleWinner(
                targetDatabase = db1,
                outcomes = listOf(first.await(), second.await()),
                candidates = listOf(
                    ReservationCandidate(RESERVATION_FILE_1, ATTEMPT_FILE_1, LEDGER_FILE_1, STAGE_ID_FILE_1),
                    ReservationCandidate(RESERVATION_FILE_2, ATTEMPT_FILE_2, LEDGER_FILE_2, STAGE_ID_FILE_2),
                ),
            )

            db1.close()
            db2.close()
            firstDatabase = null
            secondDatabase = null

            val reopened = openFileDatabase(databaseName)
            firstDatabase = reopened
            seedPreparedStage(JOB_ID_FILE_3, STAGE_ID_FILE_3, targetDatabase = reopened)
            val rejection = assertThrows(BudgetReservationRejectedException::class.java) {
                runBlocking {
                    PersistentBudgetReservationRepository(reopened).recordBudgetedRequestIntent(
                        intent(
                            attemptId = ATTEMPT_FILE_3,
                            ledgerId = LEDGER_FILE_3,
                            stageId = STAGE_ID_FILE_3,
                            createdAt = 20L,
                        ),
                        draft(
                            reservationId = RESERVATION_FILE_3,
                            requestMaxTokens = 60L,
                            estimatedTokens = 60L,
                        ),
                        stageLease(STAGE_ID_FILE_3, reopened),
                    )
                }
            }
            assertEquals(BudgetScope.BOOK, rejection.scope)
            assertEquals(BudgetReservationRejectionReason.LIMIT_EXCEEDED, rejection.reason)
            assertZeroHalfState(
                RESERVATION_FILE_3,
                ATTEMPT_FILE_3,
                LEDGER_FILE_3,
                STAGE_ID_FILE_3,
                GenerationStageStatus.PREPARING,
                reopened,
            )
            val aggregate = requireNotNull(
                reopened.budgetDao().aggregateBookReservations(
                    BOOK_ID,
                    currency = null,
                    excludedStatus = BudgetReservationStatus.RELEASED,
                ),
            )
            assertEquals(100L, aggregate.tokens)
        } finally {
            firstDatabase?.close()
            secondDatabase?.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun provisionalKnownUsageUpdateKeepsReservationReservedWithEstimate() = runBlocking {
        seedBudgetedV1Attempt()

        val updated = database.generationDao().recordUsage(
            ATTEMPT_ID,
            usage(
                source = UsageSource.ESTIMATED,
                status = UsageLedgerStatus.PROVISIONAL,
                totalTokens = 80L,
                updatedAt = 20L,
            ),
        )

        assertEquals(UsageSource.ESTIMATED, updated.source)
        assertEquals(UsageLedgerStatus.PROVISIONAL, updated.status)
        assertEquals(80L, updated.totalTokens)
        assertNull(updated.finalizedAt)

        val reservation = requireNotNull(database.budgetDao().findReservation(RESERVATION_ID))
        assertEquals(BudgetReservationStatus.RESERVED, reservation.status)
        assertEquals(100L, reservation.accountedTokens)
        assertEquals(100L, reservation.estimatedTokens)
        assertNull(reservation.settledAt)
    }

    @Test
    fun finalUnknownUsageSettlesReservationPreservingEstimate() = runBlocking {
        seedBudgetedV1Attempt()

        val updated = database.generationDao().recordUsage(
            ATTEMPT_ID,
            usage(
                source = UsageSource.UNKNOWN,
                status = UsageLedgerStatus.FINAL,
                updatedAt = 20L,
            ),
        )

        assertEquals(UsageSource.UNKNOWN, updated.source)
        assertEquals(UsageLedgerStatus.FINAL, updated.status)
        assertEquals(20L, updated.finalizedAt)

        val reservation = requireNotNull(database.budgetDao().findReservation(RESERVATION_ID))
        assertEquals(BudgetReservationStatus.SETTLED, reservation.status)
        assertEquals(100L, reservation.accountedTokens)
        assertEquals(100L, reservation.estimatedTokens)
        assertEquals(20L, reservation.settledAt)
        assertEquals(20L, reservation.updatedAt)
    }

    @Test
    fun finalEstimatedUsageReplacesReservationWithActualValuesIncludingAboveReservationTokens() = runBlocking {
        seedBudgetedV1Attempt()

        val updated = database.generationDao().recordUsage(
            ATTEMPT_ID,
            usage(
                source = UsageSource.ESTIMATED,
                status = UsageLedgerStatus.FINAL,
                inputTokens = 100L,
                outputTokens = 150L,
                totalTokens = 250L,
                currency = "USD",
                estimatedCostMicros = 350L,
                priceCatalogVersion = "catalog-v1",
                updatedAt = 20L,
            ),
        )

        assertEquals(250L, updated.totalTokens)
        assertEquals("USD", updated.currency)

        val reservation = requireNotNull(database.budgetDao().findReservation(RESERVATION_ID))
        assertEquals(BudgetReservationStatus.SETTLED, reservation.status)
        assertEquals(250L, reservation.accountedTokens)
        assertEquals(350L, reservation.accountedCostMicros)
        assertEquals("USD", reservation.accountedCurrency)
        assertEquals(20L, reservation.settledAt)
        assertEquals(20L, reservation.updatedAt)
    }

    @Test
    fun finalProviderReportedUsageWithoutMonetaryValuesSettlesWithNullAccountedCost() = runBlocking {
        seedBudgetedV1Attempt(
            budget = draft(estimatedCostMicros = 25L, estimatedCurrency = "CNY"),
        )
        val seeded = requireNotNull(database.budgetDao().findReservation(RESERVATION_ID))
        assertEquals(25L, seeded.accountedCostMicros)
        assertEquals("CNY", seeded.accountedCurrency)

        val updated = database.generationDao().recordUsage(
            ATTEMPT_ID,
            usage(
                source = UsageSource.PROVIDER_REPORTED,
                status = UsageLedgerStatus.FINAL,
                totalTokens = 200L,
                updatedAt = 20L,
            ),
        )

        assertEquals(200L, updated.totalTokens)
        assertNull(updated.currency)
        assertNull(updated.estimatedCostMicros)

        val reservation = requireNotNull(database.budgetDao().findReservation(RESERVATION_ID))
        assertEquals(BudgetReservationStatus.SETTLED, reservation.status)
        assertEquals(200L, reservation.accountedTokens)
        assertNull(reservation.accountedCostMicros)
        assertNull(reservation.accountedCurrency)
        assertEquals(20L, reservation.settledAt)
    }

    @Test
    fun identicalFinalReplayReturnsSameUsageWithoutDoubleCounting() = runBlocking {
        seedBudgetedV1Attempt()

        val finalUpdate = usage(
            source = UsageSource.ESTIMATED,
            status = UsageLedgerStatus.FINAL,
            totalTokens = 120L,
            updatedAt = 20L,
        )
        val first = database.generationDao().recordUsage(ATTEMPT_ID, finalUpdate)
        val replay = database.generationDao().recordUsage(ATTEMPT_ID, finalUpdate)

        assertEquals(first, replay)
        assertEquals(120L, replay.totalTokens)

        val reservation = requireNotNull(database.budgetDao().findReservation(RESERVATION_ID))
        assertEquals(BudgetReservationStatus.SETTLED, reservation.status)
        assertEquals(120L, reservation.accountedTokens)
        assertEquals(20L, reservation.settledAt)

        val bookAggregate = requireNotNull(
            database.budgetDao().aggregateBookReservations(
                BOOK_ID,
                currency = null,
                excludedStatus = BudgetReservationStatus.RELEASED,
            ),
        )
        assertEquals(120L, bookAggregate.tokens)
        val dailyAggregate = requireNotNull(
            database.budgetDao().aggregateDailyReservations(
                BudgetDailyPeriodKeyV1.create(10L, "Asia/Shanghai"),
                currency = null,
                excludedStatus = BudgetReservationStatus.RELEASED,
            ),
        )
        assertEquals(120L, dailyAggregate.tokens)
    }

    @Test
    fun lateProviderReportedUpgradeAfterUnknownFinalKeepsSettledAt() = runBlocking {
        seedBudgetedV1Attempt()

        database.generationDao().recordUsage(
            ATTEMPT_ID,
            usage(
                source = UsageSource.UNKNOWN,
                status = UsageLedgerStatus.FINAL,
                updatedAt = 20L,
            ),
        )

        val upgraded = database.generationDao().recordUsage(
            ATTEMPT_ID,
            usage(
                source = UsageSource.PROVIDER_REPORTED,
                status = UsageLedgerStatus.FINAL,
                totalTokens = 300L,
                currency = "USD",
                estimatedCostMicros = 500L,
                updatedAt = 30L,
            ),
        )

        assertEquals(UsageSource.PROVIDER_REPORTED, upgraded.source)
        assertEquals(300L, upgraded.totalTokens)
        assertEquals(30L, upgraded.finalizedAt)

        val reservation = requireNotNull(database.budgetDao().findReservation(RESERVATION_ID))
        assertEquals(BudgetReservationStatus.SETTLED, reservation.status)
        assertEquals(20L, reservation.settledAt)
        assertEquals(300L, reservation.accountedTokens)
        assertEquals(500L, reservation.accountedCostMicros)
        assertEquals("USD", reservation.accountedCurrency)
        assertEquals(30L, reservation.updatedAt)
    }

    @Test
    fun lateProviderReportedUpgradeAfterEstimatedFinalReplacesFinalValues() = runBlocking {
        seedPreparedStage(JOB_ID_SETTLE_2, STAGE_ID_SETTLE_2)
        reservationRepository.recordBudgetedRequestIntent(
            intent(
                attemptId = ATTEMPT_SETTLE_2,
                ledgerId = LEDGER_SETTLE_2,
                stageId = STAGE_ID_SETTLE_2,
                createdAt = 10L,
            ),
            draft(reservationId = RESERVATION_SETTLE_2),
            stageLease(STAGE_ID_SETTLE_2),
        )

        database.generationDao().recordUsage(
            ATTEMPT_SETTLE_2,
            usage(
                source = UsageSource.ESTIMATED,
                status = UsageLedgerStatus.FINAL,
                totalTokens = 80L,
                currency = "CNY",
                estimatedCostMicros = 60L,
                updatedAt = 20L,
            ),
        )
        val upgraded = database.generationDao().recordUsage(
            ATTEMPT_SETTLE_2,
            usage(
                source = UsageSource.PROVIDER_REPORTED,
                status = UsageLedgerStatus.FINAL,
                totalTokens = 240L,
                currency = "USD",
                estimatedCostMicros = 400L,
                updatedAt = 25L,
            ),
        )

        assertEquals(UsageSource.PROVIDER_REPORTED, upgraded.source)
        assertEquals(240L, upgraded.totalTokens)

        val reservation = requireNotNull(database.budgetDao().findReservation(RESERVATION_SETTLE_2))
        assertEquals(BudgetReservationStatus.SETTLED, reservation.status)
        assertEquals(20L, reservation.settledAt)
        assertEquals(240L, reservation.accountedTokens)
        assertEquals(400L, reservation.accountedCostMicros)
        assertEquals("USD", reservation.accountedCurrency)
        assertEquals(25L, reservation.updatedAt)
    }

    @Test
    fun legacyV0UsageFinalAndReplayRemainUnchangedWithoutReservation() = runBlocking {
        database.generationDao().recordRequestIntent(intent(), stageLease(STAGE_ID))
        val attempt = requireNotNull(database.generationDao().findAttempt(ATTEMPT_ID))
        assertEquals(0, attempt.budgetEnforcementVersion)
        assertNull(attempt.budgetReservationId)
        assertNull(database.budgetDao().findReservationByAttempt(ATTEMPT_ID))

        val finalUpdate = usage(
            source = UsageSource.ESTIMATED,
            status = UsageLedgerStatus.FINAL,
            totalTokens = 90L,
            updatedAt = 20L,
        )
        val first = database.generationDao().recordUsage(ATTEMPT_ID, finalUpdate)
        val replay = database.generationDao().recordUsage(ATTEMPT_ID, finalUpdate)

        assertEquals(first, replay)
        assertEquals(UsageLedgerStatus.FINAL, replay.status)
        assertNull(database.budgetDao().findReservationByAttempt(ATTEMPT_ID))
    }

    @Test
    fun finalUsageWithWrongReservationStateFailsClosedWithoutHalfSettlement() = runBlocking {
        seedBudgetedV1Attempt()
        database.openHelper.writableDatabase.execSQL(
            """
            UPDATE request_budget_reservation
            SET status = 'SETTLED', settled_at = 99, updated_at = 99
            WHERE budget_reservation_id = '$RESERVATION_ID'
            """.trimIndent(),
        )

        val error = assertThrows(StaleGenerationStateException::class.java) {
            runBlocking {
                database.generationDao().recordUsage(
                    ATTEMPT_ID,
                    usage(
                        source = UsageSource.UNKNOWN,
                        status = UsageLedgerStatus.FINAL,
                        updatedAt = 20L,
                    ),
                )
            }
        }
        assertNotNull(error)

        val usage = requireNotNull(database.generationDao().findUsageForAttempt(ATTEMPT_ID))
        assertEquals(UsageSource.UNKNOWN, usage.source)
        assertEquals(UsageLedgerStatus.PROVISIONAL, usage.status)
        assertNull(usage.finalizedAt)
        assertNull(usage.totalTokens)

        val reservation = requireNotNull(database.budgetDao().findReservation(RESERVATION_ID))
        assertEquals(BudgetReservationStatus.SETTLED, reservation.status)
        assertEquals(99L, reservation.settledAt)
        assertEquals(100L, reservation.accountedTokens)
    }

    @Test
    fun finalUsageWithMissingReservationFailsClosedWithoutHalfSettlement() = runBlocking {
        seedBudgetedV1Attempt()
        database.openHelper.writableDatabase.execSQL(
            "DROP TRIGGER IF EXISTS prevent_request_budget_reservation_delete",
        )
        database.openHelper.writableDatabase.execSQL(
            "DELETE FROM request_budget_reservation WHERE budget_reservation_id = '$RESERVATION_ID'",
        )
        assertNull(database.budgetDao().findReservation(RESERVATION_ID))

        val error = assertThrows(RuntimeException::class.java) {
            runBlocking {
                database.generationDao().recordUsage(
                    ATTEMPT_ID,
                    usage(
                        source = UsageSource.UNKNOWN,
                        status = UsageLedgerStatus.FINAL,
                        updatedAt = 20L,
                    ),
                )
            }
        }
        assertNotNull(error)

        val usage = requireNotNull(database.generationDao().findUsageForAttempt(ATTEMPT_ID))
        assertEquals(UsageSource.UNKNOWN, usage.source)
        assertEquals(UsageLedgerStatus.PROVISIONAL, usage.status)
        assertNull(usage.finalizedAt)
    }

    @Test
    fun finalUsageWithMisboundReservationFailsClosedWithoutHalfSettlement() = runBlocking {
        seedBudgetedV1Attempt()
        val original = requireNotNull(database.budgetDao().findReservation(RESERVATION_ID))
        database.openHelper.writableDatabase.execSQL(
            "DROP TRIGGER IF EXISTS prevent_request_budget_reservation_delete",
        )
        database.openHelper.writableDatabase.execSQL(
            "DELETE FROM request_budget_reservation WHERE budget_reservation_id = '$RESERVATION_ID'",
        )
        database.budgetDao().insertReservation(original.copy(budgetReservationId = RESERVATION_MISBOUND))

        val error = assertThrows(RuntimeException::class.java) {
            runBlocking {
                database.generationDao().recordUsage(
                    ATTEMPT_ID,
                    usage(
                        source = UsageSource.UNKNOWN,
                        status = UsageLedgerStatus.FINAL,
                        updatedAt = 20L,
                    ),
                )
            }
        }
        assertNotNull(error)

        val usage = requireNotNull(database.generationDao().findUsageForAttempt(ATTEMPT_ID))
        assertEquals(UsageSource.UNKNOWN, usage.source)
        assertEquals(UsageLedgerStatus.PROVISIONAL, usage.status)
        assertNull(usage.finalizedAt)

        val misbound = requireNotNull(database.budgetDao().findReservation(RESERVATION_MISBOUND))
        assertEquals(BudgetReservationStatus.RESERVED, misbound.status)
        assertEquals(ATTEMPT_ID, misbound.attemptId)
    }

    @Test
    fun finalUsageWithMisboundLedgerPeriodFailsClosedBeforeSettlement() = runBlocking {
        seedBudgetedV1Attempt()
        database.openHelper.writableDatabase.execSQL(
            "DROP TRIGGER IF EXISTS prevent_usage_identity_update",
        )
        database.openHelper.writableDatabase.execSQL(
            """
            UPDATE usage_ledger
            SET daily_period_key = '2099-01-01|Asia/Shanghai'
            WHERE attempt_id = '$ATTEMPT_ID'
            """.trimIndent(),
        )

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                database.generationDao().recordUsage(
                    ATTEMPT_ID,
                    usage(
                        source = UsageSource.UNKNOWN,
                        status = UsageLedgerStatus.FINAL,
                        updatedAt = 20L,
                    ),
                )
            }
        }

        val usage = requireNotNull(database.generationDao().findUsageForAttempt(ATTEMPT_ID))
        assertEquals(UsageLedgerStatus.PROVISIONAL, usage.status)
        assertNull(usage.finalizedAt)
        val reservation = requireNotNull(database.budgetDao().findReservation(RESERVATION_ID))
        assertEquals(BudgetReservationStatus.RESERVED, reservation.status)
        assertEquals(100L, reservation.accountedTokens)
        assertNull(reservation.settledAt)
    }

    @Test
    fun providerConfirmedNotExecutedReleasesV1ReservationAndExcludesAggregates() = runBlocking {
        seedBudgetedV1Attempt()
        database.generationDao().recordRequestSent(ATTEMPT_ID, "remote-not-executed", 11L, stageLease(STAGE_ID))
        database.generationDao().recordStreamStarted(ATTEMPT_ID, 12L, stageLease(STAGE_ID))
        moveToPendingRecovery(updatedAt = 13L)

        val result = GenerationUnknownResultRecoveryRepository(database).reconcilePendingAttempt(
            attemptId = ATTEMPT_ID,
            draftEvidence = RecoveryDraftEvidence.READABLE_EMPTY,
            providerEvidence = ProviderRecoveryEvidence.CONFIRMED_NOT_EXECUTED,
            auditedAt = 60_000L,
        )

        assertEquals(GenerationRecoveryDisposition.REQUEUED_AFTER_PROVIDER_PROOF, result.disposition)
        assertEquals(RequestAttemptStatus.FAILED_RETRYABLE, result.attemptStatus)
        assertEquals(GenerationStageStatus.READY, result.stageStatus)
        assertEquals(GenerationJobStatus.READY, result.jobStatus)

        val attempt = requireNotNull(database.generationDao().findAttempt(ATTEMPT_ID))
        assertEquals(RequestAttemptStatus.FAILED_RETRYABLE, attempt.status)
        val stage = requireNotNull(database.generationDao().findStage(STAGE_ID))
        assertEquals(GenerationStageStatus.READY, stage.status)
        assertNull(stage.leaseOwnerId)
        val job = requireNotNull(database.generationDao().findJob(JOB_ID))
        assertEquals(GenerationJobStatus.READY, job.status)
        assertNull(job.pauseOrStopReason)

        val usage = requireNotNull(database.generationDao().findUsageForAttempt(ATTEMPT_ID))
        assertEquals(UsageLedgerStatus.FINAL, usage.status)
        assertEquals(UsageSource.UNKNOWN, usage.source)
        assertNull(usage.totalTokens)
        assertNull(usage.estimatedCostMicros)
        assertNull(usage.currency)
        assertEquals(60_000L, usage.finalizedAt)

        val reservation = requireNotNull(database.budgetDao().findReservation(RESERVATION_ID))
        assertEquals(BudgetReservationStatus.RELEASED, reservation.status)
        assertEquals(0L, reservation.accountedTokens)
        assertNull(reservation.accountedCostMicros)
        assertNull(reservation.accountedCurrency)
        assertEquals(60_000L, reservation.releasedAt)
        assertNull(reservation.settledAt)
        assertEquals(60_000L, reservation.updatedAt)

        val bookAggregate = requireNotNull(
            database.budgetDao().aggregateBookReservations(
                BOOK_ID,
                currency = null,
                excludedStatus = BudgetReservationStatus.RELEASED,
            ),
        )
        assertNull(bookAggregate.tokens)
        val dailyAggregate = requireNotNull(
            database.budgetDao().aggregateDailyReservations(
                BudgetDailyPeriodKeyV1.create(10L, "Asia/Shanghai"),
                currency = null,
                excludedStatus = BudgetReservationStatus.RELEASED,
            ),
        )
        assertNull(dailyAggregate.tokens)
    }

    @Test
    fun providerProofReleaseReservationConflictRollsBackAllFiveStates() = runBlocking {
        seedBudgetedV1Attempt()
        database.generationDao().recordRequestSent(ATTEMPT_ID, "remote-not-executed", 11L, stageLease(STAGE_ID))
        database.generationDao().recordStreamStarted(ATTEMPT_ID, 12L, stageLease(STAGE_ID))
        moveToPendingRecovery(updatedAt = 13L)
        database.openHelper.writableDatabase.execSQL(
            """
            UPDATE request_budget_reservation
            SET status = 'SETTLED', settled_at = 99, updated_at = 99
            WHERE budget_reservation_id = '$RESERVATION_ID'
            """.trimIndent(),
        )

        val error = assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                GenerationUnknownResultRecoveryRepository(database).reconcilePendingAttempt(
                    attemptId = ATTEMPT_ID,
                    draftEvidence = RecoveryDraftEvidence.READABLE_EMPTY,
                    providerEvidence = ProviderRecoveryEvidence.CONFIRMED_NOT_EXECUTED,
                    auditedAt = 60_000L,
                )
            }
        }
        assertNotNull(error)

        val attempt = requireNotNull(database.generationDao().findAttempt(ATTEMPT_ID))
        assertEquals(RequestAttemptStatus.STREAMING, attempt.status)
        val stage = requireNotNull(database.generationDao().findStage(STAGE_ID))
        assertEquals(GenerationStageStatus.RECOVERY_REQUIRED, stage.status)
        assertNull(stage.leaseOwnerId)
        val job = requireNotNull(database.generationDao().findJob(JOB_ID))
        assertEquals(GenerationJobStatus.NEEDS_ACTION, job.status)
        assertEquals(GenerationRecoveryReason.REMOTE_RESULT_PENDING.name, job.pauseOrStopReason)
        val usage = requireNotNull(database.generationDao().findUsageForAttempt(ATTEMPT_ID))
        assertEquals(UsageSource.UNKNOWN, usage.source)
        assertEquals(UsageLedgerStatus.PROVISIONAL, usage.status)
        assertNull(usage.totalTokens)
        assertNull(usage.finalizedAt)
        val reservation = requireNotNull(database.budgetDao().findReservation(RESERVATION_ID))
        assertEquals(BudgetReservationStatus.SETTLED, reservation.status)
        assertEquals(99L, reservation.settledAt)
        assertEquals(100L, reservation.accountedTokens)
        assertEquals(99L, reservation.updatedAt)
    }

    @Test
    fun providerProofReleaseRejectsAlreadyFinalUsageAndKeepsReservationReserved() = runBlocking {
        seedBudgetedV1Attempt()
        database.generationDao().recordRequestSent(ATTEMPT_ID, "remote-not-executed", 11L, stageLease(STAGE_ID))
        database.generationDao().recordStreamStarted(ATTEMPT_ID, 12L, stageLease(STAGE_ID))
        check(
            database.generationDao().compareAndSetAttemptStatus(
                attemptId = ATTEMPT_ID,
                expectedStatus = RequestAttemptStatus.STREAMING,
                nextStatus = RequestAttemptStatus.FAILED_RETRYABLE,
                providerRequestId = null,
                errorCode = StandardErrorCode.UNKNOWN_RESULT,
                httpStatus = null,
                outputHash = null,
                updatedAt = 20L,
            ) == 1,
        )
        check(
            database.generationDao().updateProvisionalUsage(
                attemptId = ATTEMPT_ID,
                source = UsageSource.UNKNOWN,
                status = UsageLedgerStatus.FINAL,
                inputTokens = null,
                outputTokens = null,
                cachedTokens = null,
                reasoningTokens = null,
                totalTokens = null,
                currency = null,
                estimatedCostMicros = null,
                priceCatalogVersion = null,
                updatedAt = 20L,
            ) == 1,
        )

        val error = assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                database.generationDao().finalizeUsageAndReleaseReservationAfterProviderProof(
                    attemptId = ATTEMPT_ID,
                    update = usage(
                        source = UsageSource.UNKNOWN,
                        status = UsageLedgerStatus.FINAL,
                        updatedAt = 20L,
                    ),
                )
            }
        }
        assertNotNull(error)

        val persistedUsage = requireNotNull(database.generationDao().findUsageForAttempt(ATTEMPT_ID))
        assertEquals(UsageSource.UNKNOWN, persistedUsage.source)
        assertEquals(UsageLedgerStatus.FINAL, persistedUsage.status)
        val reservation = requireNotNull(database.budgetDao().findReservation(RESERVATION_ID))
        assertEquals(BudgetReservationStatus.RESERVED, reservation.status)
        assertEquals(100L, reservation.accountedTokens)
        assertNull(reservation.releasedAt)
        assertNull(reservation.settledAt)
    }

    @Test
    fun lateProviderReportedUsageRestoresReleasedReservationToSettledExactlyOnce() = runBlocking {
        seedBudgetedV1Attempt()
        database.generationDao().recordRequestSent(ATTEMPT_ID, "remote-not-executed", 11L, stageLease(STAGE_ID))
        database.generationDao().recordStreamStarted(ATTEMPT_ID, 12L, stageLease(STAGE_ID))
        moveToPendingRecovery(updatedAt = 13L)
        GenerationUnknownResultRecoveryRepository(database).reconcilePendingAttempt(
            attemptId = ATTEMPT_ID,
            draftEvidence = RecoveryDraftEvidence.READABLE_EMPTY,
            providerEvidence = ProviderRecoveryEvidence.CONFIRMED_NOT_EXECUTED,
            auditedAt = 60_000L,
        )
        val released = requireNotNull(database.budgetDao().findReservation(RESERVATION_ID))
        assertEquals(BudgetReservationStatus.RELEASED, released.status)
        assertEquals(60_000L, released.releasedAt)

        val lateUpdate = usage(
            source = UsageSource.PROVIDER_REPORTED,
            status = UsageLedgerStatus.FINAL,
            totalTokens = 300L,
            currency = "USD",
            estimatedCostMicros = 500L,
            updatedAt = 60_030L,
        )
        val restored = database.generationDao().recordUsage(ATTEMPT_ID, lateUpdate)
        val replay = database.generationDao().recordUsage(ATTEMPT_ID, lateUpdate)

        assertEquals(restored, replay)
        assertEquals(UsageSource.PROVIDER_REPORTED, replay.source)
        assertEquals(UsageLedgerStatus.FINAL, replay.status)
        assertEquals(300L, replay.totalTokens)
        assertEquals(500L, replay.estimatedCostMicros)
        assertEquals("USD", replay.currency)
        assertEquals(60_030L, replay.finalizedAt)

        val reservation = requireNotNull(database.budgetDao().findReservation(RESERVATION_ID))
        assertEquals(BudgetReservationStatus.SETTLED, reservation.status)
        assertEquals(300L, reservation.accountedTokens)
        assertEquals(500L, reservation.accountedCostMicros)
        assertEquals("USD", reservation.accountedCurrency)
        assertEquals(60_030L, reservation.settledAt)
        assertEquals(60_000L, reservation.releasedAt)
        assertEquals(60_030L, reservation.updatedAt)

        val bookAggregate = requireNotNull(
            database.budgetDao().aggregateBookReservations(
                BOOK_ID,
                currency = null,
                excludedStatus = BudgetReservationStatus.RELEASED,
            ),
        )
        assertEquals(300L, bookAggregate.tokens)
    }

    @Test
    fun estimatedAndUnknownFinalUsageCannotReviveReleasedReservation() = runBlocking {
        seedBudgetedV1Attempt()
        database.generationDao().recordRequestSent(ATTEMPT_ID, "remote-not-executed", 11L, stageLease(STAGE_ID))
        database.generationDao().recordStreamStarted(ATTEMPT_ID, 12L, stageLease(STAGE_ID))
        moveToPendingRecovery(updatedAt = 13L)
        GenerationUnknownResultRecoveryRepository(database).reconcilePendingAttempt(
            attemptId = ATTEMPT_ID,
            draftEvidence = RecoveryDraftEvidence.READABLE_EMPTY,
            providerEvidence = ProviderRecoveryEvidence.CONFIRMED_NOT_EXECUTED,
            auditedAt = 60_000L,
        )
        assertEquals(
            BudgetReservationStatus.RELEASED,
            requireNotNull(database.budgetDao().findReservation(RESERVATION_ID)).status,
        )

        val estimatedError = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                database.generationDao().recordUsage(
                    ATTEMPT_ID,
                    usage(
                        source = UsageSource.ESTIMATED,
                        status = UsageLedgerStatus.FINAL,
                        totalTokens = 80L,
                        updatedAt = 60_040L,
                    ),
                )
            }
        }
        assertNotNull(estimatedError)

        val unknownReplayError = assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                database.generationDao().recordUsage(
                    ATTEMPT_ID,
                    usage(
                        source = UsageSource.UNKNOWN,
                        status = UsageLedgerStatus.FINAL,
                        updatedAt = 60_050L,
                    ),
                )
            }
        }
        assertNotNull(unknownReplayError)

        val reservation = requireNotNull(database.budgetDao().findReservation(RESERVATION_ID))
        assertEquals(BudgetReservationStatus.RELEASED, reservation.status)
        assertEquals(0L, reservation.accountedTokens)
        assertNull(reservation.accountedCostMicros)
        assertNull(reservation.accountedCurrency)
        assertNull(reservation.settledAt)
        assertEquals(60_000L, reservation.releasedAt)
        val usage = requireNotNull(database.generationDao().findUsageForAttempt(ATTEMPT_ID))
        assertEquals(UsageSource.UNKNOWN, usage.source)
        assertEquals(UsageLedgerStatus.FINAL, usage.status)
        assertNull(usage.totalTokens)
    }

    @Test
    fun legacyV0ProviderNotExecutedRecoveryFinalizesUsageWithoutReservation() = runBlocking {
        database.generationDao().recordRequestIntent(intent(), stageLease(STAGE_ID))
        database.generationDao().recordRequestSent(ATTEMPT_ID, "remote-v0-not-executed", 11L, stageLease(STAGE_ID))
        database.generationDao().recordStreamStarted(ATTEMPT_ID, 12L, stageLease(STAGE_ID))
        moveToPendingRecovery(updatedAt = 13L)

        val result = GenerationUnknownResultRecoveryRepository(database).reconcilePendingAttempt(
            attemptId = ATTEMPT_ID,
            draftEvidence = RecoveryDraftEvidence.READABLE_EMPTY,
            providerEvidence = ProviderRecoveryEvidence.CONFIRMED_NOT_EXECUTED,
            auditedAt = 60_000L,
        )

        assertEquals(GenerationRecoveryDisposition.REQUEUED_AFTER_PROVIDER_PROOF, result.disposition)
        assertEquals(RequestAttemptStatus.FAILED_RETRYABLE, result.attemptStatus)
        assertEquals(GenerationStageStatus.READY, result.stageStatus)
        assertEquals(GenerationJobStatus.READY, result.jobStatus)
        val attempt = requireNotNull(database.generationDao().findAttempt(ATTEMPT_ID))
        assertEquals(0, attempt.budgetEnforcementVersion)
        assertNull(attempt.budgetReservationId)
        val usage = requireNotNull(database.generationDao().findUsageForAttempt(ATTEMPT_ID))
        assertEquals(UsageSource.UNKNOWN, usage.source)
        assertEquals(UsageLedgerStatus.FINAL, usage.status)
        assertEquals(60_000L, usage.finalizedAt)
        assertNull(database.budgetDao().findReservationByAttempt(ATTEMPT_ID))
        assertNull(database.budgetDao().findReservation(RESERVATION_ID))
    }

    @Test
    fun knownUsageObservedWithProviderNotExecutedClaimIsSettledNotReleased() = runBlocking {
        seedBudgetedV1Attempt()
        database.generationDao().recordRequestSent(ATTEMPT_ID, "remote-known-usage", 11L, stageLease(STAGE_ID))
        database.generationDao().recordStreamStarted(ATTEMPT_ID, 12L, stageLease(STAGE_ID))
        database.generationDao().recordUsage(
            ATTEMPT_ID,
            usage(
                source = UsageSource.ESTIMATED,
                status = UsageLedgerStatus.PROVISIONAL,
                totalTokens = 80L,
                updatedAt = 20L,
            ),
        )

        val result = GenerationUnknownResultRecoveryRepository(database).auditExpiredAttempt(
            attemptId = ATTEMPT_ID,
            observedLease = stageLease(STAGE_ID),
            draftEvidence = RecoveryDraftEvidence.READABLE_EMPTY,
            providerEvidence = ProviderRecoveryEvidence.CONFIRMED_NOT_EXECUTED,
            auditedAt = 60_005L,
        )

        assertEquals(GenerationRecoveryDisposition.USER_CONFIRMATION_REQUIRED, result.disposition)
        assertEquals(RequestAttemptStatus.UNKNOWN_RESULT, result.attemptStatus)
        assertEquals(GenerationStageStatus.UNKNOWN_RESULT, result.stageStatus)
        assertEquals(GenerationJobStatus.NEEDS_ACTION, result.jobStatus)
        val usage = requireNotNull(database.generationDao().findUsageForAttempt(ATTEMPT_ID))
        assertEquals(UsageSource.ESTIMATED, usage.source)
        assertEquals(UsageLedgerStatus.FINAL, usage.status)
        assertEquals(80L, usage.totalTokens)
        val reservation = requireNotNull(database.budgetDao().findReservation(RESERVATION_ID))
        assertEquals(BudgetReservationStatus.SETTLED, reservation.status)
        assertEquals(80L, reservation.accountedTokens)
        assertNull(reservation.releasedAt)
        assertEquals(60_005L, reservation.settledAt)
    }

    private suspend fun moveToPendingRecovery(updatedAt: Long) {
        check(
            database.generationDao().compareAndSetStageStatus(
                STAGE_ID,
                GenerationStageStatus.STREAMING,
                GenerationStageStatus.RECOVERY_REQUIRED,
                StandardErrorCode.UNKNOWN_RESULT,
                null,
                updatedAt,
            ) == 1,
        ) { "Stage did not move to pending recovery." }
        check(
            database.generationDao().compareAndSetJobControlStatus(
                JOB_ID,
                GenerationJobStatus.RUNNING,
                GenerationJobStatus.NEEDS_ACTION,
                GenerationRecoveryReason.REMOTE_RESULT_PENDING.name,
                updatedAt,
            ) == 1,
        ) { "Job did not move to pending recovery." }
    }

    private fun openFileDatabase(databaseName: String): ZhijuanDatabase =
        Room.databaseBuilder(context, ZhijuanDatabase::class.java, databaseName)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .addCallback(LibraryDatabaseGuards.callback)
            .build()
            .also { it.openHelper.writableDatabase }

    private suspend fun assertSingleWinner(
        targetDatabase: ZhijuanDatabase,
        outcomes: List<Result<*>>,
        candidates: List<ReservationCandidate>,
    ) {
        assertEquals(1, outcomes.count { it.isSuccess })
        val failure = requireNotNull(outcomes.single { it.isFailure }.exceptionOrNull())
        assertEquals(BudgetReservationRejectedException::class.java, failure.javaClass)
        failure as BudgetReservationRejectedException
        assertEquals(BudgetScope.BOOK, failure.scope)
        assertEquals(BudgetReservationRejectionReason.LIMIT_EXCEEDED, failure.reason)

        val persisted = candidates.map { candidate ->
            candidate to targetDatabase.budgetDao().findReservation(candidate.reservationId)
        }
        assertEquals(1, persisted.count { (_, reservation) -> reservation != null })
        val winner = persisted.single { (_, reservation) -> reservation != null }.first
        val loser = persisted.single { (_, reservation) -> reservation == null }.first

        assertNotNull(targetDatabase.generationDao().findAttempt(winner.attemptId))
        assertNotNull(targetDatabase.generationDao().findUsageLedger(winner.ledgerId))
        assertEquals(
            GenerationStageStatus.REQUEST_INTENT_RECORDED,
            requireNotNull(targetDatabase.generationDao().findStage(winner.stageId)).status,
        )
        assertZeroHalfState(
            loser.reservationId,
            loser.attemptId,
            loser.ledgerId,
            loser.stageId,
            GenerationStageStatus.PREPARING,
            targetDatabase,
        )
        val aggregate = requireNotNull(
            targetDatabase.budgetDao().aggregateBookReservations(
                BOOK_ID,
                currency = null,
                excludedStatus = BudgetReservationStatus.RELEASED,
            ),
        )
        assertEquals(100L, aggregate.tokens)
    }

    private data class ReservationCandidate(
        val reservationId: String,
        val attemptId: String,
        val ledgerId: String,
        val stageId: String,
    )

    private suspend fun seedBook(targetDatabase: ZhijuanDatabase = database) {
        targetDatabase.libraryDao().createBook(
            BookCreationSnapshotEntity(
                snapshotId = "snapshot-budget-reservation",
                rawInputJson = "{}",
                normalizedInputJson = "{}",
                inferenceProvenanceJson = "{}",
                genrePayloadJson = "{}",
                presentationProfileJson = "{}",
                modelPreferenceJson = "{}",
                schemaVersion = 1,
                promptBundleVersion = "prompt-v1",
                contentControlSchemaVersion = 1,
                contentHash = "snapshot-budget-reservation-hash",
                createdAt = 1L,
            ),
            BookEntity(
                bookId = BOOK_ID,
                creationSnapshotId = "snapshot-budget-reservation",
                title = "Budget reservation test book",
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

    private suspend fun seedConnection(
        connectionId: String,
        acceptedAt: Long?,
        targetDatabase: ZhijuanDatabase = database,
    ) {
        targetDatabase.connectionDao().insertConnection(
            ConnectionProfileEntity(
                connectionId = connectionId,
                displayName = "Budget reservation test connection",
                serviceId = "DEEPSEEK",
                protocolId = "OPENAI_CHAT_COMPAT",
                baseUrl = "https://api.deepseek.com",
                normalizedDestination = "https://api.deepseek.com:443",
                secretRefId = "secret-ref-$connectionId",
                secretLastFour = "1234",
                selectedModelId = "deepseek-chat",
                availableModelsJson = "[\"deepseek-chat\"]",
                modelVerification = "DISCOVERED",
                basicVerifiedAt = 2L,
                fullVerifiedAt = null,
                dataDisclosureVersion = null,
                dataDisclosureAcceptedAt = null,
                dataDisclosureBindingHash = null,
                createdAt = 2L,
                updatedAt = 2L,
            ),
        )
        if (acceptedAt != null) {
            targetDatabase.connectionDao().acceptDataDisclosureForCurrentDestination(connectionId, acceptedAt)
        }
    }

    private suspend fun seedPreparedStage(
        jobId: String,
        stageId: String,
        startJob: Boolean = true,
        targetDatabase: ZhijuanDatabase = database,
    ) {
        targetDatabase.generationDao().createJob(
            GenerationJobEntity(
                jobId = jobId,
                bookId = BOOK_ID,
                jobType = GenerationJobType.CREATE_BOOK,
                status = GenerationJobStatus.CREATED,
                userIntentJson = "{}",
                budgetSnapshotJson = "{\"schema\":1}",
                promptBundleVersion = "prompt-v1",
                createdAt = 2L,
                updatedAt = 2L,
            ),
            listOf(
                GenerationStageEntity(
                    stageId = stageId,
                    jobId = jobId,
                    phase = GenerationPhase.DRAFT_CHAPTER,
                    targetType = GenerationTargetType.CHAPTER,
                    targetId = "chapter-target",
                    status = GenerationStageStatus.PENDING,
                    inputVersionHash = "input-$stageId",
                    idempotencyKey = "idem-$stageId",
                    maxAttempts = 3,
                    inputSourcesJson = "[]",
                    createdAt = 2L,
                    updatedAt = 2L,
                ),
            ),
        )
        if (startJob) {
            targetDatabase.generationDao().transitionJob(
                jobId,
                GenerationJobStatus.CREATED,
                JobEvent.VALIDATION_PASSED,
                3L,
            )
            targetDatabase.generationDao().acquireJobLease(jobId, "job-worker", 4L)
            targetDatabase.generationDao().transitionStage(
                stageId,
                GenerationStageStatus.PENDING,
                StageEvent.DEPENDENCIES_SATISFIED,
                updatedAt = 3L,
            )
            targetDatabase.generationDao().acquireStageLease(stageId, "worker-a", 4L)
        }
    }

    private suspend fun stageLease(
        stageId: String,
        targetDatabase: ZhijuanDatabase = database,
    ): GenerationLeaseToken {
        val stage = requireNotNull(targetDatabase.generationDao().findStage(stageId))
        return GenerationLeaseToken(
            ownerId = requireNotNull(stage.leaseOwnerId),
            acquiredAt = requireNotNull(stage.leaseAcquiredAt),
        )
    }

    private suspend fun seedBoundChapterPlanStage(): GenerationRunnerCurrentStageRouteSnapshot {
        val progressionBase = JsonObject(
            linkedMapOf(
                "schemaVersion" to JsonPrimitive(1),
                "policyVersion" to JsonPrimitive("zhijuan.first-chapter-progression.v1"),
                "chapterId" to JsonPrimitive(CHAPTER_PLAN_TARGET_ID),
                "chapterIndex" to JsonPrimitive(2),
            ),
        )
        val progression = JsonObject(
            progressionBase + ("evidenceHash" to JsonPrimitive(sha256(progressionBase.toString()))),
        )
        val input = JsonObject(
            linkedMapOf(
                "schemaVersion" to JsonPrimitive(1),
                "sourcePolicyVersion" to JsonPrimitive("zhijuan.chapter-plan-source.v1"),
                "promptBundleVersion" to JsonPrimitive(PromptBundleCatalogV1.BUNDLE_VERSION),
                "outputSchemaId" to JsonPrimitive("chapter-plan.v1"),
                "dependencyStageIds" to JsonArray(listOf(JsonPrimitive(CHAPTER_CONTEXT_STAGE_ID))),
                "contextAssemblyStageId" to JsonPrimitive(CHAPTER_CONTEXT_STAGE_ID),
                "contextInputVersionHash" to JsonPrimitive("d".repeat(64)),
                "contextPolicyVersion" to JsonPrimitive(ChapterContextBudgetPolicyV1.POLICY_VERSION),
                "contextManifestSchemaId" to JsonPrimitive(ChapterContextBudgetPolicyV1.MANIFEST_SCHEMA_ID),
                "chapterProgressionGate" to progression,
            ),
        ).toString()
        database.generationDao().createJob(
            GenerationJobEntity(
                jobId = CHAPTER_PLAN_JOB_ID,
                bookId = BOOK_ID,
                jobType = GenerationJobType.CONTINUE_BOOK,
                status = GenerationJobStatus.CREATED,
                userIntentJson = "{}",
                budgetSnapshotJson = "{\"schema\":1}",
                promptBundleVersion = PromptBundleCatalogV1.BUNDLE_VERSION,
                createdAt = 2L,
                updatedAt = 2L,
            ),
            listOf(
                GenerationStageEntity(
                    stageId = CHAPTER_PLAN_STAGE_ID,
                    jobId = CHAPTER_PLAN_JOB_ID,
                    phase = GenerationPhase.BUILD_CHAPTER_PLAN,
                    targetType = GenerationTargetType.CHAPTER,
                    targetId = CHAPTER_PLAN_TARGET_ID,
                    status = GenerationStageStatus.PENDING,
                    inputVersionHash = sha256(input),
                    idempotencyKey = "idem.chapter-plan.bound",
                    maxAttempts = 2,
                    inputSourcesJson = input,
                    createdAt = 2L,
                    updatedAt = 2L,
                ),
            ),
        )
        val dao = database.generationDao()
        dao.transitionJob(
            CHAPTER_PLAN_JOB_ID,
            GenerationJobStatus.CREATED,
            JobEvent.VALIDATION_PASSED,
            3L,
        )
        dao.transitionStage(
            CHAPTER_PLAN_STAGE_ID,
            GenerationStageStatus.PENDING,
            StageEvent.DEPENDENCIES_SATISFIED,
            updatedAt = 3L,
        )
        val job = dao.acquireJobLease(CHAPTER_PLAN_JOB_ID, CHAPTER_PLAN_OWNER_ID, 4L)
        val stage = dao.acquireStageLease(CHAPTER_PLAN_STAGE_ID, CHAPTER_PLAN_OWNER_ID, 4L)
        return GenerationRunnerExecutionLeaseRepository(database).resolveCurrentStageRoute(
            jobId = CHAPTER_PLAN_JOB_ID,
            jobLeaseToken = requireNotNull(job.leaseTokenOrNull()),
            stageId = CHAPTER_PLAN_STAGE_ID,
            stageLeaseToken = requireNotNull(stage.leaseTokenOrNull()),
            observedAt = 5L,
        )
    }

    private fun chapterPlanIntent(
        streamDraftRef: String? = "00000000-0000-0000-0000-000000000091",
    ) = RequestIntentDraft(
        attemptId = CHAPTER_PLAN_ATTEMPT_ID,
        usageLedgerId = CHAPTER_PLAN_LEDGER_ID,
        stageId = CHAPTER_PLAN_STAGE_ID,
        retryParentAttemptId = null,
        connectionSnapshotJson = "{\"connection\":\"chapter-plan\"}",
        modelSnapshotJson = "{\"model\":\"chapter-plan\"}",
        protocolSnapshotJson = "{\"protocol\":\"chapter-plan\"}",
        inputHash = "c".repeat(64),
        streamDraftRef = streamDraftRef,
        createdAt = 10L,
    )

    private fun chapterPlanBudget() = draft(
        reservationId = CHAPTER_PLAN_RESERVATION_ID,
        requestMaxTokens = 100L,
        estimatedTokens = 100L,
    )

    private suspend fun assertBoundChapterPlanZeroState() {
        assertNull(database.budgetDao().findReservation(CHAPTER_PLAN_RESERVATION_ID))
        assertNull(database.generationDao().findAttempt(CHAPTER_PLAN_ATTEMPT_ID))
        assertNull(database.generationDao().findUsageLedger(CHAPTER_PLAN_LEDGER_ID))
        val stage = requireNotNull(database.generationDao().findStage(CHAPTER_PLAN_STAGE_ID))
        assertEquals(GenerationStageStatus.PREPARING, stage.status)
        assertEquals(0, stage.attemptCount)
        assertEquals(
            GenerationJobStatus.RUNNING,
            database.generationDao().findJob(CHAPTER_PLAN_JOB_ID)?.status,
        )
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private suspend fun seedBudgetedV1Attempt(
        budget: RequestBudgetReservationDraft = draft(),
    ) {
        reservationRepository.recordBudgetedRequestIntent(
            intent(),
            budget,
            stageLease(STAGE_ID),
        )
    }

    private suspend fun persistBudgetedV1Audit(
        auditRepository: GenerationRequestAuditRepository,
    ): PersistedRequestAudit = auditRepository.persistBeforeSend(
        RequestIntentDraft(
            attemptId = ATTEMPT_ID,
            usageLedgerId = LEDGER_ID,
            stageId = STAGE_ID,
            retryParentAttemptId = null,
            connectionSnapshotJson = "{\"connection\":\"fixture\"}",
            modelSnapshotJson = "{\"model\":\"fixture\"}",
            protocolSnapshotJson = "{\"protocol\":\"fixture\"}",
            inputHash = "a".repeat(64),
            streamDraftRef = "00000000-0000-0000-0000-000000000001",
            createdAt = 10L,
        ),
        draft(),
        stageLease(STAGE_ID),
    )

    private suspend fun GenerationRequestAuditRepository.claimForProviderOpen(
        permit: PersistedRequestSendPermit,
        validatedAt: Long,
    ) = claimForProviderOpen(
        permit,
        validatedAt,
        providerDestinationEvidence(),
    )

    private fun providerDestinationEvidence(
        connectionId: String = CONNECTION_ID,
        baseUrl: String = "https://api.deepseek.com",
        protocolId: String = "OPENAI_CHAT_COMPAT",
    ): ProviderOpenDestinationEvidence = ProviderOpenDestinationEvidence.create(
        connectionId = connectionId,
        baseUrl = baseUrl,
        protocolId = protocolId,
    )

    private suspend fun rollover(
        audit: PersistedRequestAudit,
        validatedAt: Long,
    ) {
        val error = captureFailure {
            GenerationRequestAuditRepository(database).claimForProviderOpen(
                audit.permit,
                validatedAt,
            )
        }
        assertTrue(error is DailyBudgetPeriodRolloverRequiredException)
        assertTrue((error as DailyBudgetPeriodRolloverRequiredException).retryAllowed)
    }

    private suspend fun reacquireRolloverExecution(
        claimedAt: Long,
        acquiredAt: Long,
    ): GenerationRunnerExecutionLeaseSnapshot {
        val queue = GenerationRunnerQueueRepository(database)
        val candidate = queue.scanReadyJobs(observedAt = claimedAt)
            .candidates
            .single { it.jobId == JOB_ID }
        val claim = queue.claimReadyJob(candidate, "runner-rollover", claimedAt)
        return GenerationRunnerExecutionLeaseRepository(database).acquireCurrentStageLease(
            jobId = JOB_ID,
            jobLeaseToken = claim.jobLeaseToken,
            stageId = STAGE_ID,
            runnerOwnerId = "runner-rollover",
            acquiredAt = acquiredAt,
        )
    }

    private fun rolloverReplacementDraft(
        attemptId: String,
        ledgerId: String,
        streamDraftRef: String,
        createdAt: Long,
    ) = RequestIntentDraft(
        attemptId = attemptId,
        usageLedgerId = ledgerId,
        stageId = STAGE_ID,
        retryParentAttemptId = ATTEMPT_ID,
        connectionSnapshotJson = "{\"connection\":\"fixture\"}",
        modelSnapshotJson = "{\"model\":\"fixture\"}",
        protocolSnapshotJson = "{\"protocol\":\"fixture\"}",
        inputHash = "a".repeat(64),
        streamDraftRef = streamDraftRef,
        createdAt = createdAt,
    )

    private suspend fun assertDailyRolloverTerminalState(
        validatedAt: Long,
        expectedStageStatus: GenerationStageStatus,
        expectedJobStatus: GenerationJobStatus,
        expectedJobReason: String?,
    ) {
        val attempt = requireNotNull(database.generationDao().findAttempt(ATTEMPT_ID))
        assertEquals(RequestAttemptStatus.FAILED_RETRYABLE, attempt.status)
        assertEquals(StandardErrorCode.DAILY_BUDGET_PERIOD_EXPIRED_BEFORE_SEND, attempt.standardErrorCode)
        assertNull(attempt.sentAt)
        assertNull(attempt.providerRequestId)
        assertEquals(validatedAt, attempt.finishedAt)
        assertEquals(validatedAt, attempt.updatedAt)

        val usage = requireNotNull(database.generationDao().findUsageForAttempt(ATTEMPT_ID))
        assertEquals(UsageSource.UNKNOWN, usage.source)
        assertEquals(UsageLedgerStatus.FINAL, usage.status)
        assertNull(usage.inputTokens)
        assertNull(usage.outputTokens)
        assertNull(usage.cachedTokens)
        assertNull(usage.reasoningTokens)
        assertNull(usage.totalTokens)
        assertNull(usage.currency)
        assertNull(usage.estimatedCostMicros)
        assertNull(usage.priceCatalogVersion)
        assertEquals(validatedAt, usage.finalizedAt)
        assertEquals(validatedAt, usage.updatedAt)

        val reservation = requireNotNull(database.budgetDao().findReservation(RESERVATION_ID))
        assertEquals(BudgetReservationStatus.RELEASED, reservation.status)
        assertEquals(0L, reservation.accountedTokens)
        assertNull(reservation.accountedCostMicros)
        assertNull(reservation.accountedCurrency)
        assertNull(reservation.settledAt)
        assertEquals(validatedAt, reservation.releasedAt)
        assertEquals(validatedAt, reservation.updatedAt)

        val stage = requireNotNull(database.generationDao().findStage(STAGE_ID))
        assertEquals(expectedStageStatus, stage.status)
        assertNull(stage.standardErrorCode)
        assertNull(stage.nextRetryAt)
        assertNull(stage.leaseOwnerId)
        assertNull(stage.leaseAcquiredAt)
        assertNull(stage.leaseHeartbeatAt)
        assertEquals(1, stage.attemptCount)
        assertEquals(validatedAt, stage.updatedAt)

        val job = requireNotNull(database.generationDao().findJob(JOB_ID))
        assertEquals(expectedJobStatus, job.status)
        assertEquals(expectedJobReason, job.pauseOrStopReason)
        assertNull(job.leaseOwnerId)
        assertNull(job.leaseAcquiredAt)
        assertNull(job.leaseHeartbeatAt)
        assertEquals(validatedAt, job.updatedAt)
    }

    private suspend fun captureFailure(block: suspend () -> Unit): Throwable = try {
        block()
        throw AssertionError("Expected operation to fail.")
    } catch (error: Throwable) {
        if (error is AssertionError && error.message == "Expected operation to fail.") throw error
        error
    }

    private fun intent(
        attemptId: String = ATTEMPT_ID,
        ledgerId: String = LEDGER_ID,
        stageId: String = STAGE_ID,
        createdAt: Long = 10L,
    ) = NewRequestIntent(
        attemptId = attemptId,
        usageLedgerId = ledgerId,
        stageId = stageId,
        retryParentAttemptId = null,
        connectionSnapshotJson = "{\"destination\":\"caller-claimed.example\"}",
        modelSnapshotJson = "{\"model\":\"fixture\"}",
        protocolSnapshotJson = "{\"protocol\":\"fixture\"}",
        inputHash = "input-hash-$attemptId",
        streamDraftRef = null,
        dailyPeriodKey = "caller-key-ignored",
        createdAt = createdAt,
    )

    private fun draft(
        reservationId: String = RESERVATION_ID,
        requestMaxTokens: Long = 100L,
        requestMaxCostMicros: Long? = null,
        requestCurrency: String? = null,
        estimatedTokens: Long = 100L,
        estimatedCostMicros: Long? = null,
        estimatedCurrency: String? = null,
        connectionId: String = CONNECTION_ID,
    ) = RequestBudgetReservationDraft(
        reservationId = reservationId,
        requestMaxTokens = requestMaxTokens,
        requestMaxCostMicros = requestMaxCostMicros,
        requestCurrency = requestCurrency,
        estimatedTokens = estimatedTokens,
        estimatedCostMicros = estimatedCostMicros,
        estimatedCurrency = estimatedCurrency,
        estimateSourceVersion = "zhijuan.estimate.v1",
        connectionId = connectionId,
    )

    private fun usage(
        source: UsageSource,
        status: UsageLedgerStatus,
        totalTokens: Long? = null,
        currency: String? = null,
        estimatedCostMicros: Long? = null,
        inputTokens: Long? = null,
        outputTokens: Long? = null,
        cachedTokens: Long? = null,
        reasoningTokens: Long? = null,
        priceCatalogVersion: String? = null,
        updatedAt: Long,
    ) = UsageUpdate(
        source = source,
        status = status,
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        cachedTokens = cachedTokens,
        reasoningTokens = reasoningTokens,
        totalTokens = totalTokens,
        currency = currency,
        estimatedCostMicros = estimatedCostMicros,
        priceCatalogVersion = priceCatalogVersion,
        updatedAt = updatedAt,
    )

    private suspend fun assertZeroHalfState(
        reservationId: String,
        attemptId: String,
        ledgerId: String,
        stageId: String,
        expectedStageStatus: GenerationStageStatus,
        targetDatabase: ZhijuanDatabase = database,
    ) {
        assertNull(targetDatabase.budgetDao().findReservation(reservationId))
        assertNull(targetDatabase.budgetDao().findReservationByAttempt(attemptId))
        assertEquals(0, targetDatabase.generationDao().attemptsForStage(stageId).size)
        assertNull(targetDatabase.generationDao().findUsageLedger(ledgerId))
        val stage = requireNotNull(targetDatabase.generationDao().findStage(stageId))
        assertEquals(expectedStageStatus, stage.status)
        assertEquals(0, stage.attemptCount)
    }

    private companion object {
        const val BOOK_ID = "book-budget-reservation"
        const val JOB_ID = "job-budget-reservation"
        const val STAGE_ID = "stage-budget-reservation"
        const val CHAPTER_PLAN_JOB_ID = "job.chapter-plan.bound"
        const val CHAPTER_PLAN_STAGE_ID = "stage.chapter-plan.bound"
        const val CHAPTER_PLAN_TARGET_ID = "chapter.chapter-plan.bound"
        const val CHAPTER_CONTEXT_STAGE_ID = "stage.chapter-context.bound"
        const val CHAPTER_PLAN_OWNER_ID = "runner.chapter-plan.bound"
        const val CHAPTER_PLAN_ATTEMPT_ID = "attempt.chapter-plan.bound"
        const val CHAPTER_PLAN_LEDGER_ID = "ledger.chapter-plan.bound"
        const val CHAPTER_PLAN_RESERVATION_ID = "reservation.chapter-plan.bound"
        const val JOB_ID_UNSTARTED = "job-budget-reservation-unstarted"
        const val STAGE_ID_UNSTARTED = "stage-budget-reservation-unstarted"
        const val JOB_ID_REV_2 = "job-budget-reservation-rev-2"
        const val STAGE_ID_REV_2 = "stage-budget-reservation-rev-2"
        const val JOB_ID_REV_3 = "job-budget-reservation-rev-3"
        const val STAGE_ID_REV_3 = "stage-budget-reservation-rev-3"
        const val BOOK_POLICY_1 = "policy-book-reservation-1"
        const val BOOK_POLICY_2 = "policy-book-reservation-2"
        const val BOOK_POLICY_3 = "policy-book-reservation-3"
        const val DAILY_POLICY_1 = "policy-daily-reservation-1"
        const val DAILY_POLICY_2 = "policy-daily-reservation-2"
        const val CONNECTION_ID = "connection-budget-reservation"
        const val CONNECTION_ID_NO_DISCLOSURE = "connection-budget-reservation-no-disclosure"
        const val RESERVATION_ID = "reservation-budget-reservation"
        const val ATTEMPT_ID = "attempt-budget-reservation"
        const val LEDGER_ID = "ledger-budget-reservation"
        const val RESERVATION_ROLLOVER_2 = "reservation-budget-reservation-rollover-2"
        const val ATTEMPT_ROLLOVER_2 = "attempt-budget-reservation-rollover-2"
        const val LEDGER_ROLLOVER_2 = "ledger-budget-reservation-rollover-2"
        const val RESERVATION_ROLLOVER_3 = "reservation-budget-reservation-rollover-3"
        const val ATTEMPT_ROLLOVER_3 = "attempt-budget-reservation-rollover-3"
        const val LEDGER_ROLLOVER_3 = "ledger-budget-reservation-rollover-3"
        const val ROLLOVER_ARTIFACT_1 = "00000000-0000-0000-0000-000000000001"
        const val ROLLOVER_ARTIFACT_2 = "00000000-0000-0000-0000-000000000002"
        const val ROLLOVER_ARTIFACT_3 = "00000000-0000-0000-0000-000000000003"
        const val RESERVATION_ID_UNSTARTED = "reservation-budget-reservation-unstarted"
        const val ATTEMPT_ID_UNSTARTED = "attempt-budget-reservation-unstarted"
        const val LEDGER_ID_UNSTARTED = "ledger-budget-reservation-unstarted"
        const val RESERVATION_REV_1 = "reservation-budget-reservation-rev-1"
        const val ATTEMPT_REV_1 = "attempt-budget-reservation-rev-1"
        const val LEDGER_REV_1 = "ledger-budget-reservation-rev-1"
        const val RESERVATION_REV_2 = "reservation-budget-reservation-rev-2"
        const val ATTEMPT_REV_2 = "attempt-budget-reservation-rev-2"
        const val LEDGER_REV_2 = "ledger-budget-reservation-rev-2"
        const val RESERVATION_REV_3 = "reservation-budget-reservation-rev-3"
        const val ATTEMPT_REV_3 = "attempt-budget-reservation-rev-3"
        const val LEDGER_REV_3 = "ledger-budget-reservation-rev-3"
        const val JOB_ID_CONCURRENT_2 = "job-budget-reservation-concurrent-2"
        const val STAGE_ID_CONCURRENT_2 = "stage-budget-reservation-concurrent-2"
        const val RESERVATION_CONCURRENT_1 = "reservation-budget-reservation-concurrent-1"
        const val ATTEMPT_CONCURRENT_1 = "attempt-budget-reservation-concurrent-1"
        const val LEDGER_CONCURRENT_1 = "ledger-budget-reservation-concurrent-1"
        const val RESERVATION_CONCURRENT_2 = "reservation-budget-reservation-concurrent-2"
        const val ATTEMPT_CONCURRENT_2 = "attempt-budget-reservation-concurrent-2"
        const val LEDGER_CONCURRENT_2 = "ledger-budget-reservation-concurrent-2"
        const val JOB_ID_FILE_1 = "job-budget-reservation-file-1"
        const val STAGE_ID_FILE_1 = "stage-budget-reservation-file-1"
        const val RESERVATION_FILE_1 = "reservation-budget-reservation-file-1"
        const val ATTEMPT_FILE_1 = "attempt-budget-reservation-file-1"
        const val LEDGER_FILE_1 = "ledger-budget-reservation-file-1"
        const val JOB_ID_FILE_2 = "job-budget-reservation-file-2"
        const val STAGE_ID_FILE_2 = "stage-budget-reservation-file-2"
        const val RESERVATION_FILE_2 = "reservation-budget-reservation-file-2"
        const val ATTEMPT_FILE_2 = "attempt-budget-reservation-file-2"
        const val LEDGER_FILE_2 = "ledger-budget-reservation-file-2"
        const val JOB_ID_FILE_3 = "job-budget-reservation-file-3"
        const val STAGE_ID_FILE_3 = "stage-budget-reservation-file-3"
        const val RESERVATION_FILE_3 = "reservation-budget-reservation-file-3"
        const val ATTEMPT_FILE_3 = "attempt-budget-reservation-file-3"
        const val LEDGER_FILE_3 = "ledger-budget-reservation-file-3"
        const val BOOK_POLICY_FILE = "policy-book-reservation-file"
        const val DAILY_POLICY_FILE = "policy-daily-reservation-file"
        const val JOB_ID_SETTLE_2 = "job-budget-reservation-settle-2"
        const val STAGE_ID_SETTLE_2 = "stage-budget-reservation-settle-2"
        const val ATTEMPT_SETTLE_2 = "attempt-budget-reservation-settle-2"
        const val LEDGER_SETTLE_2 = "ledger-budget-reservation-settle-2"
        const val RESERVATION_SETTLE_2 = "reservation-budget-reservation-settle-2"
        const val RESERVATION_MISBOUND = "reservation-budget-reservation-misbound"
    }
}

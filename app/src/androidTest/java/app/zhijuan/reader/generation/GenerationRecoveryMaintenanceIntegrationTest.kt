package app.zhijuan.reader.generation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.zhijuan.core.database.EncryptedZhijuanDatabaseFactory
import app.zhijuan.core.database.generation.GenerationJobSetup
import app.zhijuan.core.database.generation.GenerationJobSetupRepository
import app.zhijuan.core.database.generation.GenerationStageSetup
import app.zhijuan.core.database.generation.GenerationStateRepository
import app.zhijuan.core.database.generation.GenerationStreamingDraftRepository
import app.zhijuan.core.database.generation.RequestIntentDraft
import app.zhijuan.core.database.library.BookCreationRepository
import app.zhijuan.core.database.library.BookCreationSnapshotEntity
import app.zhijuan.core.database.library.BookEntity
import app.zhijuan.core.model.BookLengthMode
import app.zhijuan.core.model.BookStatus
import app.zhijuan.core.model.GenerationJobStatus
import app.zhijuan.core.model.GenerationJobType
import app.zhijuan.core.model.GenerationPhase
import app.zhijuan.core.model.GenerationStageStatus
import app.zhijuan.core.model.GenerationTargetType
import app.zhijuan.core.model.RequestAttemptStatus
import app.zhijuan.core.model.TitleSource
import app.zhijuan.core.security.AndroidProtectedArtifactStore
import app.zhijuan.core.task.JobEvent
import app.zhijuan.core.task.StageEvent
import app.zhijuan.reader.storage.ZHIJUAN_DATABASE_NAME
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class GenerationRecoveryMaintenanceIntegrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun productionRunnerAtomicallyRequeuesExpiredPreRequestExecutionWithoutAnAttempt() = runBlocking {
        val fixture = seedRunningFixture()

        val report = ProductionGenerationMaintenanceRunner(context)
            .runBatch(fixture.leaseAt + 60_000L)

        EncryptedZhijuanDatabaseFactory(context).open(ZHIJUAN_DATABASE_NAME).use { handle ->
            val states = GenerationStateRepository(handle.database)
            assertEquals(1, report.requeuedBeforeRequest)
            assertEquals(GenerationJobStatus.READY, states.findJob(fixture.jobId)?.status)
            assertEquals(GenerationStageStatus.READY, states.findStage(fixture.stageId)?.status)
            assertEquals(0, states.findStage(fixture.stageId)?.attemptCount)
            assertEquals(null, states.findJob(fixture.jobId)?.leaseToken)
            assertEquals(null, states.findStage(fixture.stageId)?.leaseToken)
        }
    }

    @Test
    fun productionRunnerTreatsExpiredIntentAsUnknownAndNeverCreatesASecondAttempt() = runBlocking {
        val fixture = seedRunningFixture()
        val artifactStore = AndroidProtectedArtifactStore(context)
        val existingArtifacts = artifactStore.listArtifactReferenceIds().toSet()
        try {
            EncryptedZhijuanDatabaseFactory(context).open(ZHIJUAN_DATABASE_NAME).use { handle ->
                val states = GenerationStateRepository(handle.database)
                val drafts = GenerationStreamingDraftRepository(
                    handle.database,
                    AndroidProtectedArtifactStore(context),
                )
                val stage = requireNotNull(states.findStage(fixture.stageId))
                drafts.prepareBeforeSend(
                    RequestIntentDraft(
                        attemptId = "maint-attempt-${fixture.suffix}",
                        usageLedgerId = "maint-ledger-${fixture.suffix}",
                        stageId = fixture.stageId,
                        retryParentAttemptId = null,
                        connectionSnapshotJson = "{}",
                        modelSnapshotJson = "{}",
                        protocolSnapshotJson = "{}",
                        inputHash = "a".repeat(64),
                        streamDraftRef = null,
                        dailyPeriodKey = "2026-08-02|Asia/Shanghai",
                        createdAt = fixture.leaseAt + 1L,
                    ),
                    leaseToken = requireNotNull(stage.leaseToken),
                )
            }

            val report = ProductionGenerationMaintenanceRunner(context)
                .runBatch(fixture.leaseAt + 60_001L)

            EncryptedZhijuanDatabaseFactory(context).open(ZHIJUAN_DATABASE_NAME).use { handle ->
                val states = GenerationStateRepository(handle.database)
                val recovery = GenerationStreamingDraftRepository(
                    handle.database,
                    AndroidProtectedArtifactStore(context),
                ).inspectAttempt("maint-attempt-${fixture.suffix}", fixture.leaseAt + 60_002L)
                assertEquals(1, report.auditedWithoutProvider)
                assertEquals(GenerationJobStatus.NEEDS_ACTION, states.findJob(fixture.jobId)?.status)
                assertEquals(GenerationStageStatus.UNKNOWN_RESULT, states.findStage(fixture.stageId)?.status)
                assertEquals(1, states.findStage(fixture.stageId)?.attemptCount)
                assertEquals(RequestAttemptStatus.UNKNOWN_RESULT, recovery.attemptStatus)
            }
        } finally {
            artifactStore.listArtifactReferenceIds()
                .filterNot(existingArtifacts::contains)
                .forEach { ref ->
                    runCatching { artifactStore.delete(ref) }
                }
        }
    }

    private suspend fun seedRunningFixture(): Fixture {
        val suffix = UUID.randomUUID().toString()
        val createdAt = System.currentTimeMillis().coerceAtLeast(1L)
        val leaseAt = createdAt + 2L
        val snapshotId = "maint-snapshot-$suffix"
        val bookId = "maint-book-$suffix"
        val jobId = "maint-job-$suffix"
        val stageId = "maint-stage-$suffix"
        EncryptedZhijuanDatabaseFactory(context).open(ZHIJUAN_DATABASE_NAME).use { handle ->
            BookCreationRepository(handle.database).create(
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
                    contentHash = "b".repeat(64),
                    createdAt = createdAt,
                ),
                BookEntity(
                    bookId = bookId,
                    creationSnapshotId = snapshotId,
                    title = "维护测试书",
                    titleSource = TitleSource.USER,
                    status = BookStatus.DRAFT,
                    lengthMode = BookLengthMode.SHORT,
                    targetCharacters = null,
                    targetChapters = 80,
                    minimumChapters = 80,
                    lengthPolicySchemaVersion = 1,
                    createdAt = createdAt,
                    updatedAt = createdAt,
                ),
            )
            GenerationJobSetupRepository(handle.database).create(
                GenerationJobSetup(
                    jobId = jobId,
                    bookId = bookId,
                    jobType = GenerationJobType.CREATE_BOOK,
                    userIntentJson = "{}",
                    budgetSnapshotJson = "{\"schema\":1}",
                    promptBundleVersion = "prompt-1",
                    stages = listOf(
                        GenerationStageSetup(
                            stageId = stageId,
                            phase = GenerationPhase.DRAFT_CHAPTER,
                            targetType = GenerationTargetType.CHAPTER,
                            targetId = "maint-target-$suffix",
                            inputVersionHash = "maint-input-$suffix",
                            idempotencyKey = "maint-idempotency-$suffix",
                            maxAttempts = 3,
                            inputSourcesJson = "[]",
                        ),
                    ),
                    createdAt = createdAt,
                ),
            )
            val states = GenerationStateRepository(handle.database)
            states.transitionJob(
                jobId,
                GenerationJobStatus.CREATED,
                JobEvent.VALIDATION_PASSED,
                createdAt + 1L,
            )
            states.acquireJobLease(jobId, "maint-job-worker-$suffix", leaseAt)
            states.transitionStage(
                stageId,
                GenerationStageStatus.PENDING,
                StageEvent.DEPENDENCIES_SATISFIED,
                createdAt + 1L,
            )
            states.acquireStageLease(stageId, "maint-stage-worker-$suffix", leaseAt)
        }
        return Fixture(suffix, jobId, stageId, leaseAt)
    }

    private data class Fixture(
        val suffix: String,
        val jobId: String,
        val stageId: String,
        val leaseAt: Long,
    )
}

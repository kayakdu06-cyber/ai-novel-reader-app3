package app.zhijuan.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.zhijuan.core.database.generation.ChapterCandidateArtifactRoleV1
import app.zhijuan.core.database.generation.ChapterCandidateArtifactSealDraftV1
import app.zhijuan.core.database.generation.ChapterCandidateArtifactSealRepositoryV1
import app.zhijuan.core.database.generation.ChapterCandidateStageBindingV1
import app.zhijuan.core.database.generation.ChapterCandidateStageSourceV1
import app.zhijuan.core.database.generation.ChapterConsistencyOutcomeDraftV1
import app.zhijuan.core.database.generation.ChapterConsistencyOutcomeRepositoryV1
import app.zhijuan.core.database.generation.ChapterConsistencyOutcomeResultV1
import app.zhijuan.core.database.generation.ChapterFinalCandidateArtifactEvidenceV1
import app.zhijuan.core.database.generation.ChapterFinalCandidateCommitDraftV1
import app.zhijuan.core.database.generation.ChapterFinalCandidateCommitRepositoryV1
import app.zhijuan.core.database.generation.ChapterFinalCandidateRecoveryRepository
import app.zhijuan.core.database.generation.ChapterFinalCommitStageBindingV1
import app.zhijuan.core.database.generation.ChapterRevisionOutcomeRepository
import app.zhijuan.core.database.generation.ChapterRevisionCandidateDraftV1
import app.zhijuan.core.database.generation.ChapterRevisionCandidateRepositoryV1
import app.zhijuan.core.database.generation.ChapterTrackingPayloadHasher
import app.zhijuan.core.database.generation.CompletedStreamingResponse
import app.zhijuan.core.database.generation.FinalUsageCommit
import app.zhijuan.core.database.generation.GenerationJobEntity
import app.zhijuan.core.database.generation.GenerationLeaseToken
import app.zhijuan.core.database.generation.GenerationOutputValidationRepository
import app.zhijuan.core.database.generation.GenerationStageEntity
import app.zhijuan.core.database.generation.GenerationStageSetup
import app.zhijuan.core.database.generation.GenerationStreamingDraftRepository
import app.zhijuan.core.database.generation.PersistedStreamingRequest
import app.zhijuan.core.database.generation.RequestIntentDraft
import app.zhijuan.core.database.generation.StaleGenerationStateException
import app.zhijuan.core.database.library.BookCreationSnapshotEntity
import app.zhijuan.core.database.library.BookEntity
import app.zhijuan.core.database.library.ChapterEntity
import app.zhijuan.core.database.memory.CanonFactEntity
import app.zhijuan.core.database.memory.ChapterSummaryEntity
import app.zhijuan.core.database.memory.ChapterTrackingProjectionEntity
import app.zhijuan.core.database.memory.ConsistencyReportEntity
import app.zhijuan.core.database.memory.EntityEventEntity
import app.zhijuan.core.database.memory.ForeshadowItemEntity
import app.zhijuan.core.database.memory.ForeshadowTransitionEntity
import app.zhijuan.core.database.memory.StoryEntity
import app.zhijuan.core.database.memory.TimelineEventEntity
import app.zhijuan.core.database.search.MemorySearchSourceTypeV1
import app.zhijuan.core.security.AndroidProtectedArtifactStore
import app.zhijuan.core.model.AdultStatus
import app.zhijuan.core.model.BookLengthMode
import app.zhijuan.core.model.BookStatus
import app.zhijuan.core.model.CanonLevel
import app.zhijuan.core.model.ChapterStatus
import app.zhijuan.core.model.ConsistencyCriterionV1
import app.zhijuan.core.model.ConsistencyIssueCode
import app.zhijuan.core.model.ConsistencyIssueSeverity
import app.zhijuan.core.model.ConsistencyRepairActionV1
import app.zhijuan.core.model.ConsistencyStatus
import app.zhijuan.core.model.DerivedDataStatus
import app.zhijuan.core.model.ForeshadowStatus
import app.zhijuan.core.model.FadePolicy
import app.zhijuan.core.model.GenerationJobStatus
import app.zhijuan.core.model.GenerationJobType
import app.zhijuan.core.model.GenerationPhase
import app.zhijuan.core.model.GenerationStageStatus
import app.zhijuan.core.model.GenerationTargetType
import app.zhijuan.core.model.MemorySource
import app.zhijuan.core.model.RequestAttemptStatus
import app.zhijuan.core.model.StoryEntityType
import app.zhijuan.core.model.TitleSource
import app.zhijuan.core.model.UsageLedgerStatus
import app.zhijuan.core.task.JobEvent
import app.zhijuan.core.task.ChapterRevisionIssueRefV1
import app.zhijuan.core.task.ChapterRevisionNeedsActionReasonV1
import app.zhijuan.core.task.ChapterRevisionPolicyDecisionV1
import app.zhijuan.core.task.ChapterRevisionPolicyInputV1
import app.zhijuan.core.task.ChapterRevisionPolicyV1
import app.zhijuan.core.task.ChapterSceneConsistencyContractV1
import app.zhijuan.core.task.ChapterSceneConsistencyModeV1
import app.zhijuan.core.task.StageEvent
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChapterFinalCandidateCommitDatabaseTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: ZhijuanDatabase
    private lateinit var artifacts: AndroidProtectedArtifactStore
    private var now = 10L

    @Before
    fun setUp() = runBlocking {
        artifacts = AndroidProtectedArtifactStore(context)
        artifacts.unlockAfterAuthentication()
        artifacts.listArtifactReferenceIds().forEach(artifacts::delete)
        database = Room.inMemoryDatabaseBuilder(context, ZhijuanDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(LibraryDatabaseGuards.callback)
            .build()
            .also { it.openHelper.writableDatabase }
        seedBookChapterAndEntities()
        BudgetedRequestTestSupport.seedBudgetedRequestEnvironment(database, BOOK_ID)
    }

    @After
    fun tearDown() {
        runCatching {
            artifacts.unlockAfterAuthentication()
            artifacts.listArtifactReferenceIds().forEach(artifacts::delete)
        }
        database.close()
    }

    @Test
    fun sealedCandidateChainPublishesBodyMemoryTrackingReportAndStateAtomically() = runBlocking {
        val prepared = prepareAcceptedCandidatePipeline()

        val result = ChapterFinalCandidateCommitRepositoryV1(database, artifacts).commit(
            FINAL_STAGE,
            prepared.finalLease,
            prepared.draft,
        )

        assertTrue(!result.replayed)
        assertEquals(VERSION_ID, database.libraryDao().findChapter(CHAPTER_ID)?.currentVersionId)
        assertEquals(ChapterStatus.READY, database.libraryDao().findChapter(CHAPTER_ID)?.status)
        assertEquals(ConsistencyStatus.VALID, database.libraryDao().findChapter(CHAPTER_ID)?.consistencyStatus)
        assertEquals(BODY, database.libraryDao().findChapterVersion(VERSION_ID)?.content)
        assertEquals(prepared.draft.summary, database.memoryDao().findSummaryForVersion(VERSION_ID))
        assertEquals(prepared.draft.entityEvents, database.memoryDao().entityEventsForVersion(VERSION_ID))
        assertEquals(prepared.draft.canonFacts, database.memoryDao().canonFactsForVersion(VERSION_ID))
        assertEquals(prepared.draft.timelineEvents, database.memoryDao().timelineEventsForVersion(VERSION_ID))
        assertEquals(prepared.draft.trackingProjection, database.memoryDao().findTrackingProjectionForVersion(VERSION_ID))
        assertEquals(prepared.draft.consistencyReport, database.memoryDao().findConsistencyReport(REPORT_ID))
        assertEquals(ForeshadowStatus.PLANTED, database.memoryDao().findForeshadow(FORESHADOW_ID)?.foreshadowStatus)
        assertEquals(1, database.memoryDao().foreshadowTransitionsForStage(TRACKING_STAGE).size)
        assertEquals(1, database.memoryDao().foreshadowProjectionRevisionsForStage(TRACKING_STAGE).size)
        assertEquals(GenerationStageStatus.SUCCEEDED, database.generationDao().findStage(FINAL_STAGE)?.status)
        assertEquals(GenerationJobStatus.COMPLETED, database.generationDao().findJob(JOB_ID)?.status)
        assertEquals(1, database.libraryDao().findBook(BOOK_ID)?.completedChapterCount)
        assertEquals(5L, database.memorySearchDao().count())
    }

    @Test
    fun finalCandidateRecoveryLoadsOneStrictPersistentSnapshotBeforeCommit() = runBlocking {
        prepareAcceptedCandidatePipeline()

        val recovered = ChapterFinalCandidateRecoveryRepository(database).load(FINAL_STAGE)

        assertEquals(FINAL_STAGE, recovered.finalStageId)
        assertEquals(JOB_ID, recovered.jobId)
        assertEquals(BOOK_ID, recovered.bookId)
        assertEquals(GenerationStageStatus.COMMITTING, recovered.finalStageStatus)
        assertEquals(VERSION_ID, recovered.source.candidateChapterVersionId)
        assertEquals(null, recovered.candidateRouteBindingHash)
        assertEquals(listOf(sha256(BODY)), recovered.source.candidateContentHashHistory)
        assertEquals(
            listOf(
                ChapterCandidateArtifactRoleV1.BODY,
                ChapterCandidateArtifactRoleV1.MEMORY,
                ChapterCandidateArtifactRoleV1.TRACKING,
                ChapterCandidateArtifactRoleV1.CONSISTENCY,
            ),
            recovered.artifacts.map { it.role },
        )
        assertEquals(
            listOf(BODY_STAGE, MEMORY_STAGE, TRACKING_STAGE, CHECK_STAGE),
            recovered.artifacts.map { it.stageId },
        )
        assertEquals(
            listOf("attempt.body", "attempt.memory", "attempt.tracking", "attempt.consistency"),
            recovered.artifacts.map { it.attemptId },
        )
        assertEquals(MODEL_SNAPSHOT, recovered.memoryModelSnapshotJson)
        assertEquals(MODEL_SNAPSHOT, recovered.trackingModelSnapshotJson)
        assertEquals(MODEL_SNAPSHOT, recovered.consistencyModelSnapshotJson)
        assertTrue(!recovered.toString().contains("fixture"))
        assertEquals(null, database.libraryDao().findChapter(CHAPTER_ID)?.currentVersionId)
    }

    @Test
    fun acceptedConsistencyRouteRequiresMappingSnapshotBeforeCreatingFinalStage() = runBlocking {
        prepareCandidateJobAndBody()
        seal(
            role = ChapterCandidateArtifactRoleV1.MEMORY,
            stageId = MEMORY_STAGE,
            content = "{}",
            next = stageSetup(TRACKING_STAGE, GenerationPhase.EXTRACT_MEMORY, ChapterCandidateArtifactRoleV1.TRACKING, MEMORY_STAGE),
        )
        seal(
            role = ChapterCandidateArtifactRoleV1.TRACKING,
            stageId = TRACKING_STAGE,
            content = "{}",
            next = stageSetup(CHECK_STAGE, GenerationPhase.CHECK_CONSISTENCY, ChapterCandidateArtifactRoleV1.CONSISTENCY, TRACKING_STAGE),
        )
        val prepared = prepareConsistencyRoute(
            stageId = CHECK_STAGE,
            nextStageId = FINAL_STAGE,
            candidateChapterVersionId = VERSION_ID,
            candidateContent = BODY,
            revisionIndex = 0,
            policyInput = revisionPolicyInput(
                candidateContent = BODY,
                issues = listOf(revisionIssue(ConsistencyIssueSeverity.MINOR)),
            ),
        )

        val failure = expectFailure {
            ChapterConsistencyOutcomeRepositoryV1(database, artifacts).route(
                prepared.permit,
                prepared.draft.copy(
                    consistencyMappingSnapshotJson = null,
                    consistencyMappingSnapshotContentHash = null,
                ),
                prepared.policyInput,
            )
        }

        assertTrue(failure is IllegalArgumentException)
        assertEquals(null, database.generationDao().findStage(FINAL_STAGE))
        assertEquals(GenerationStageStatus.COMMITTING, database.generationDao().findStage(CHECK_STAGE)?.status)
        assertEquals(null, database.libraryDao().findChapter(CHAPTER_ID)?.currentVersionId)
    }

    @Test
    fun acceptedConsistencyRouteRejectsSnapshotBoundToAnotherRequest() = runBlocking {
        prepareCandidateJobAndBody()
        seal(
            role = ChapterCandidateArtifactRoleV1.MEMORY,
            stageId = MEMORY_STAGE,
            content = "{}",
            next = stageSetup(TRACKING_STAGE, GenerationPhase.EXTRACT_MEMORY, ChapterCandidateArtifactRoleV1.TRACKING, MEMORY_STAGE),
        )
        seal(
            role = ChapterCandidateArtifactRoleV1.TRACKING,
            stageId = TRACKING_STAGE,
            content = "{}",
            next = stageSetup(CHECK_STAGE, GenerationPhase.CHECK_CONSISTENCY, ChapterCandidateArtifactRoleV1.CONSISTENCY, TRACKING_STAGE),
        )
        val prepared = prepareConsistencyRoute(
            stageId = CHECK_STAGE,
            nextStageId = FINAL_STAGE,
            candidateChapterVersionId = VERSION_ID,
            candidateContent = BODY,
            revisionIndex = 0,
            policyInput = revisionPolicyInput(
                candidateContent = BODY,
                issues = listOf(revisionIssue(ConsistencyIssueSeverity.MINOR)),
            ),
        )
        val original = Json.parseToJsonElement(requireNotNull(prepared.draft.consistencyMappingSnapshotJson)) as JsonObject
        val tampered = JsonObject(
            original + ("consistencyRequestSourceBindingHash" to JsonPrimitive(sha256("another-request"))),
        ).toString()

        val failure = expectFailure {
            ChapterConsistencyOutcomeRepositoryV1(database, artifacts).route(
                prepared.permit,
                prepared.draft.copy(
                    consistencyMappingSnapshotJson = tampered,
                    consistencyMappingSnapshotContentHash = sha256(tampered),
                ),
                prepared.policyInput,
            )
        }

        assertTrue(failure is IllegalArgumentException)
        assertEquals(null, database.generationDao().findStage(FINAL_STAGE))
        assertEquals(GenerationStageStatus.COMMITTING, database.generationDao().findStage(CHECK_STAGE)?.status)
        assertEquals(null, database.libraryDao().findChapter(CHAPTER_ID)?.currentVersionId)
    }

    @Test
    fun finalCandidateRecoveryLoadsRevisedBodyAndCompleteCandidateHistory() = runBlocking {
        val firstRoute = prepareCandidateRevisionStage()
        val revised = requireNotNull(sealRevisionCandidate(firstRoute, ".recovery").result)
        val revisedHash = sha256(REVISED_BODY)
        val memory = seal(
            role = ChapterCandidateArtifactRoleV1.MEMORY,
            stageId = REVISED_MEMORY_STAGE,
            content = "{}",
            next = stageSetup(
                REVISED_TRACKING_STAGE,
                GenerationPhase.EXTRACT_MEMORY,
                ChapterCandidateArtifactRoleV1.TRACKING,
                REVISED_MEMORY_STAGE,
                candidateChapterVersionId = REVISED_VERSION_ID,
                candidateContentHash = revisedHash,
                revisionIndex = 1,
                routeBindingHash = revised.routeBindingHash,
            ),
            candidateChapterVersionId = REVISED_VERSION_ID,
            candidateContentHash = revisedHash,
            revisionIndex = 1,
            routeBindingHash = revised.routeBindingHash,
            attemptSuffix = ".recovery",
        )
        val tracking = seal(
            role = ChapterCandidateArtifactRoleV1.TRACKING,
            stageId = REVISED_TRACKING_STAGE,
            content = "{}",
            next = stageSetup(
                REVISED_CHECK_STAGE,
                GenerationPhase.CHECK_CONSISTENCY,
                ChapterCandidateArtifactRoleV1.CONSISTENCY,
                REVISED_TRACKING_STAGE,
                candidateChapterVersionId = REVISED_VERSION_ID,
                candidateContentHash = revisedHash,
                revisionIndex = 1,
                routeBindingHash = revised.routeBindingHash,
            ),
            candidateChapterVersionId = REVISED_VERSION_ID,
            candidateContentHash = revisedHash,
            revisionIndex = 1,
            routeBindingHash = revised.routeBindingHash,
            attemptSuffix = ".recovery",
        )
        val (consistency, route) = routeConsistency(
            stageId = REVISED_CHECK_STAGE,
            nextStageId = FINAL_STAGE,
            candidateChapterVersionId = REVISED_VERSION_ID,
            candidateContent = REVISED_BODY,
            revisionIndex = 1,
            policyInput = revisionPolicyInput(
                candidateContent = REVISED_BODY,
                history = revised.candidateContentHashHistory,
                completedRevisions = 1,
                attemptsUsed = 1,
                issues = listOf(revisionIssue(ConsistencyIssueSeverity.MINOR)),
            ),
            attemptSuffix = ".recovery",
        )
        assertTrue(route is ChapterConsistencyOutcomeResultV1.CommitReady)
        val generation = database.generationDao()
        val finalLease = generation.acquireStageLease(FINAL_STAGE, "final.worker.recovery", ++now).let {
            GenerationLeaseToken(requireNotNull(it.leaseOwnerId), requireNotNull(it.leaseAcquiredAt))
        }
        generation.transitionStage(
            FINAL_STAGE,
            GenerationStageStatus.PREPARING,
            StageEvent.LOCAL_OUTPUT_READY,
            updatedAt = ++now,
            leaseToken = finalLease,
        )

        val recovered = ChapterFinalCandidateRecoveryRepository(database).load(FINAL_STAGE)

        assertEquals(REVISED_VERSION_ID, recovered.source.candidateChapterVersionId)
        assertEquals(1, recovered.source.revisionIndex)
        assertEquals(revised.routeBindingHash, recovered.candidateRouteBindingHash)
        assertEquals(listOf(sha256(BODY), revisedHash), recovered.source.candidateContentHashHistory)
        assertEquals(
            listOf(revised.seal.evidence, memory, tracking, consistency),
            recovered.artifacts,
        )
        assertEquals(GenerationStageStatus.COMMITTING, recovered.finalStageStatus)
        assertEquals(null, database.libraryDao().findChapter(CHAPTER_ID)?.currentVersionId)
    }

    @Test
    fun finalCandidateRecoveryRejectsMalformedPersistedModelSnapshotWithoutPublishing() = runBlocking {
        prepareAcceptedCandidatePipeline()
        database.openHelper.writableDatabase.execSQL(
            "UPDATE request_attempt SET model_snapshot_json = ? WHERE attempt_id = ?",
            arrayOf("[]", "attempt.memory"),
        )

        val failure = expectFailure {
            ChapterFinalCandidateRecoveryRepository(database).load(FINAL_STAGE)
        }

        assertTrue(failure is IllegalArgumentException)
        assertEquals(null, database.libraryDao().findChapter(CHAPTER_ID)?.currentVersionId)
        assertEquals(null, database.memoryDao().findSummaryForVersion(VERSION_ID))
        assertEquals(null, database.memoryDao().findConsistencyReport(REPORT_ID))
        assertEquals(GenerationStageStatus.COMMITTING, database.generationDao().findStage(FINAL_STAGE)?.status)
        assertEquals(GenerationJobStatus.RUNNING, database.generationDao().findJob(JOB_ID)?.status)
    }

    @Test
    fun finalCandidateRecoveryRejectsBrokenSealedNextStageChainWithoutPublishing() = runBlocking {
        prepareAcceptedCandidatePipeline()
        val generation = database.generationDao()
        val bodyStage = requireNotNull(generation.findStage(BODY_STAGE))
        val output = Json.parseToJsonElement(requireNotNull(bodyStage.outputReferenceJson)) as JsonObject
        val tampered = JsonObject(output + ("nextStageId" to JsonPrimitive("stage.wrong.next"))).toString()
        database.openHelper.writableDatabase.execSQL(
            "UPDATE generation_stage SET output_reference_json = ? WHERE stage_id = ?",
            arrayOf(tampered, BODY_STAGE),
        )

        val failure = expectFailure {
            ChapterFinalCandidateRecoveryRepository(database).load(FINAL_STAGE)
        }

        assertTrue(failure is IllegalArgumentException)
        assertEquals(null, database.libraryDao().findChapter(CHAPTER_ID)?.currentVersionId)
        assertEquals(null, database.memoryDao().findSummaryForVersion(VERSION_ID))
        assertEquals(null, database.memoryDao().findConsistencyReport(REPORT_ID))
        assertEquals(GenerationStageStatus.COMMITTING, generation.findStage(FINAL_STAGE)?.status)
        assertEquals(GenerationJobStatus.RUNNING, generation.findJob(JOB_ID)?.status)
    }

    @Test
    fun finalCommitRejectsRevisedMaximumAutomaticRevisionsWithoutPublishing() = runBlocking {
        val prepared = prepareAcceptedCandidatePipeline()
        val tampered = prepared.draft.copy(maximumAutomaticRevisions = 2)

        val failure = expectFailure {
            ChapterFinalCandidateCommitRepositoryV1(database, artifacts).commit(
                FINAL_STAGE,
                prepared.finalLease,
                tampered,
            )
        }

        assertTrue(failure is IllegalArgumentException)
        assertEquals(null, database.libraryDao().findChapter(CHAPTER_ID)?.currentVersionId)
        assertEquals(0L, database.libraryDao().versionCount(CHAPTER_ID))
        assertEquals(null, database.memoryDao().findSummaryForVersion(VERSION_ID))
        assertEquals(null, database.memoryDao().findConsistencyReport(REPORT_ID))
        assertEquals(GenerationStageStatus.COMMITTING, database.generationDao().findStage(FINAL_STAGE)?.status)
        assertEquals(GenerationJobStatus.RUNNING, database.generationDao().findJob(JOB_ID)?.status)
    }

    @Test
    fun finalCommitRejectsReplacedExpectedCurrentVersionWithoutPublishing() = runBlocking {
        val prepared = prepareAcceptedCandidatePipeline()
        val tampered = prepared.draft.copy(expectedCurrentVersionId = "chapter.version.other.1")

        val failure = expectFailure {
            ChapterFinalCandidateCommitRepositoryV1(database, artifacts).commit(
                FINAL_STAGE,
                prepared.finalLease,
                tampered,
            )
        }

        assertTrue(failure is IllegalArgumentException)
        assertEquals(null, database.libraryDao().findChapter(CHAPTER_ID)?.currentVersionId)
        assertEquals(0L, database.libraryDao().versionCount(CHAPTER_ID))
        assertEquals(null, database.memoryDao().findSummaryForVersion(VERSION_ID))
        assertEquals(null, database.memoryDao().findConsistencyReport(REPORT_ID))
        assertEquals(GenerationStageStatus.COMMITTING, database.generationDao().findStage(FINAL_STAGE)?.status)
        assertEquals(GenerationJobStatus.RUNNING, database.generationDao().findJob(JOB_ID)?.status)
    }

    @Test
    fun foreignKeyFailureRollsBackEveryFormalRowAndLeavesFinalStageRecoverable() = runBlocking {
        val prepared = prepareAcceptedCandidatePipeline()
        val broken = prepared.draft.copy(
            entityEvents = prepared.draft.entityEvents.map { it.copy(entityId = "entity.missing") },
        )

        expectFailure {
            ChapterFinalCandidateCommitRepositoryV1(database, artifacts).commit(
                FINAL_STAGE,
                prepared.finalLease,
                broken,
            )
        }

        assertEquals(null, database.libraryDao().findChapter(CHAPTER_ID)?.currentVersionId)
        assertEquals(0L, database.libraryDao().versionCount(CHAPTER_ID))
        assertEquals(null, database.memoryDao().findSummaryForVersion(VERSION_ID))
        assertEquals(null, database.memoryDao().findTrackingProjectionForVersion(VERSION_ID))
        assertEquals(null, database.memoryDao().findConsistencyReport(REPORT_ID))
        assertEquals(null, database.memoryDao().findForeshadow(FORESHADOW_ID))
        assertEquals(0L, database.memorySearchDao().count())
        assertEquals(GenerationStageStatus.COMMITTING, database.generationDao().findStage(FINAL_STAGE)?.status)
        assertEquals(GenerationJobStatus.RUNNING, database.generationDao().findJob(JOB_ID)?.status)
    }

    @Test
    fun lateChapterPublishFailureRollsBackAuthoritativeAndSearchRowsTogether() = runBlocking {
        val prepared = prepareAcceptedCandidatePipeline()
        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER block_final_chapter_publish
            BEFORE UPDATE OF current_version_id ON chapter
            WHEN NEW.current_version_id = '$VERSION_ID'
            BEGIN
                SELECT RAISE(ABORT, 'forced late publication failure');
            END
            """.trimIndent(),
        )

        expectFailure {
            ChapterFinalCandidateCommitRepositoryV1(database, artifacts).commit(
                FINAL_STAGE,
                prepared.finalLease,
                prepared.draft,
            )
        }

        assertEquals(null, database.libraryDao().findChapter(CHAPTER_ID)?.currentVersionId)
        assertEquals(0L, database.libraryDao().versionCount(CHAPTER_ID))
        assertEquals(null, database.memoryDao().findSummaryForVersion(VERSION_ID))
        assertEquals(null, database.memoryDao().findForeshadow(FORESHADOW_ID))
        assertEquals(0L, database.memorySearchDao().count())
        assertEquals(GenerationStageStatus.COMMITTING, database.generationDao().findStage(FINAL_STAGE)?.status)
        assertEquals(GenerationJobStatus.RUNNING, database.generationDao().findJob(JOB_ID)?.status)
    }

    @Test
    fun exactReplayAfterArtifactCleanupDoesNotDuplicatePublication() = runBlocking {
        val prepared = prepareAcceptedCandidatePipeline()
        val repository = ChapterFinalCandidateCommitRepositoryV1(database, artifacts)
        repository.commit(FINAL_STAGE, prepared.finalLease, prepared.draft)
        assertEquals(5L, database.memorySearchDao().count())
        database.memorySearchDao().deleteBySource(
            BOOK_ID,
            MemorySearchSourceTypeV1.CHAPTER_SUMMARY.name,
            prepared.draft.summary.chapterSummaryId,
        )
        database.memorySearchDao().deleteBySource(
            BOOK_ID,
            MemorySearchSourceTypeV1.FORESHADOW.name,
            FORESHADOW_ID,
        )
        assertEquals(3L, database.memorySearchDao().count())
        prepared.draft.artifacts.forEach { artifacts.delete(it.artifactRefId) }

        val replay = repository.commit(FINAL_STAGE, prepared.finalLease, prepared.draft)

        assertTrue(replay.replayed)
        assertEquals(1L, database.libraryDao().versionCount(CHAPTER_ID))
        assertEquals(1, database.memoryDao().entityEventsForVersion(VERSION_ID).size)
        assertEquals(1, database.memoryDao().foreshadowTransitionsForStage(TRACKING_STAGE).size)
        assertEquals(1, database.libraryDao().findBook(BOOK_ID)?.completedChapterCount)
        assertEquals(5L, database.memorySearchDao().count())
    }

    @Test
    fun replayUsesSealedRevisionWithoutOverwritingAChangedCurrentForeshadow() = runBlocking {
        val prepared = prepareAcceptedCandidatePipeline()
        val repository = ChapterFinalCandidateCommitRepositoryV1(database, artifacts)
        repository.commit(FINAL_STAGE, prepared.finalLease, prepared.draft)
        val sql = database.openHelper.writableDatabase
        sql.execSQL(
            "UPDATE foreshadow_item SET importance = 77, updated_at = 999 WHERE foreshadow_item_id = ?",
            arrayOf(FORESHADOW_ID),
        )
        sql.execSQL(
            "UPDATE memory_search_document SET importance = 77, chapter_index = 2, updated_at = 999 " +
                "WHERE book_id = ? AND source_type = ? AND source_id = ?",
            arrayOf(BOOK_ID, MemorySearchSourceTypeV1.FORESHADOW.name, FORESHADOW_ID),
        )

        val replay = repository.commit(FINAL_STAGE, prepared.finalLease, prepared.draft)

        assertTrue(replay.replayed)
        assertEquals(77, database.memoryDao().findForeshadow(FORESHADOW_ID)?.importance)
        sql.query(
            "SELECT importance, chapter_index FROM memory_search_document " +
                "WHERE book_id = ? AND source_type = ? AND source_id = ?",
            arrayOf(BOOK_ID, MemorySearchSourceTypeV1.FORESHADOW.name, FORESHADOW_ID),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(77, cursor.getInt(0))
            assertEquals(2, cursor.getInt(1))
        }
    }

    @Test
    fun concurrentExactFinalCommitsCreateOneVersionAndOneReplay() = runBlocking {
        val prepared = prepareAcceptedCandidatePipeline()
        val repository = ChapterFinalCandidateCommitRepositoryV1(database, artifacts)
        val results = coroutineScope {
            listOf(
                async(Dispatchers.IO) { runCatching { repository.commit(FINAL_STAGE, prepared.finalLease, prepared.draft) } },
                async(Dispatchers.IO) { runCatching { repository.commit(FINAL_STAGE, prepared.finalLease, prepared.draft) } },
            ).awaitAll()
        }

        assertEquals(2, results.count { it.isSuccess })
        assertEquals(1, results.mapNotNull { it.getOrNull() }.count { it.replayed })
        assertEquals(1L, database.libraryDao().versionCount(CHAPTER_ID))
        assertEquals(1, database.memoryDao().foreshadowTransitionsForStage(TRACKING_STAGE).size)
    }

    @Test
    fun candidateProviderOpenRejectsTamperedSealedPredecessorBeforeSendClaim() = runBlocking {
        prepareCandidateJobAndBody()
        val generation = database.generationDao()
        val bodyStage = requireNotNull(generation.findStage(BODY_STAGE))
        val output = Json.parseToJsonElement(requireNotNull(bodyStage.outputReferenceJson)) as JsonObject
        val tampered = JsonObject(output + ("nextStageId" to JsonPrimitive("stage.wrong.next"))).toString()
        database.openHelper.writableDatabase.execSQL(
            "UPDATE generation_stage SET output_reference_json = ? WHERE stage_id = ?",
            arrayOf(tampered, BODY_STAGE),
        )

        val failure = expectFailure {
            seal(
                role = ChapterCandidateArtifactRoleV1.MEMORY,
                stageId = MEMORY_STAGE,
                content = "{}",
                next = stageSetup(
                    TRACKING_STAGE,
                    GenerationPhase.EXTRACT_MEMORY,
                    ChapterCandidateArtifactRoleV1.TRACKING,
                    MEMORY_STAGE,
                ),
            )
        }

        assertTrue(failure is StaleGenerationStateException)
        assertEquals(RequestAttemptStatus.INTENT_RECORDED, generation.findAttempt("attempt.memory")?.status)
        assertEquals(UsageLedgerStatus.PROVISIONAL, generation.findUsageForAttempt("attempt.memory")?.status)
        assertEquals(
            GenerationStageStatus.REQUEST_INTENT_RECORDED,
            generation.findStage(MEMORY_STAGE)?.status,
        )
    }

    @Test
    fun candidateProviderOpenRejectsMalformedSealedPredecessorBeforeSendClaim() = runBlocking {
        prepareCandidateJobAndBody()
        val generation = database.generationDao()
        database.openHelper.writableDatabase.execSQL(
            "UPDATE generation_stage SET output_reference_json = ? WHERE stage_id = ?",
            arrayOf("{", BODY_STAGE),
        )

        val failure = expectFailure {
            seal(
                role = ChapterCandidateArtifactRoleV1.MEMORY,
                stageId = MEMORY_STAGE,
                content = "{}",
                next = stageSetup(
                    TRACKING_STAGE,
                    GenerationPhase.EXTRACT_MEMORY,
                    ChapterCandidateArtifactRoleV1.TRACKING,
                    MEMORY_STAGE,
                ),
            )
        }

        assertTrue(failure is StaleGenerationStateException)
        assertProviderWasNotClaimed(generation, "attempt.memory", MEMORY_STAGE)
    }

    @Test
    fun candidateProviderOpenRejectsStaleCandidateHashBeforeSendClaim() = runBlocking {
        prepareCandidateJobAndBody()
        val generation = database.generationDao()
        overwriteCandidateBinding(
            stageId = MEMORY_STAGE,
            role = ChapterCandidateArtifactRoleV1.MEMORY,
            predecessorStageId = BODY_STAGE,
            candidateContentHash = "f".repeat(64),
        )

        val failure = expectFailure {
            seal(
                role = ChapterCandidateArtifactRoleV1.MEMORY,
                stageId = MEMORY_STAGE,
                content = "{}",
                next = stageSetup(
                    TRACKING_STAGE,
                    GenerationPhase.EXTRACT_MEMORY,
                    ChapterCandidateArtifactRoleV1.TRACKING,
                    MEMORY_STAGE,
                ),
            )
        }

        assertTrue(failure is StaleGenerationStateException)
        assertProviderWasNotClaimed(generation, "attempt.memory", MEMORY_STAGE)
    }

    @Test
    fun candidateProviderOpenRejectsStaleRevisionBeforeSendClaim() = runBlocking {
        prepareCandidateJobAndBody()
        val generation = database.generationDao()
        overwriteCandidateBinding(
            stageId = MEMORY_STAGE,
            role = ChapterCandidateArtifactRoleV1.MEMORY,
            predecessorStageId = BODY_STAGE,
            revisionIndex = 1,
        )

        val failure = expectFailure {
            seal(
                role = ChapterCandidateArtifactRoleV1.MEMORY,
                stageId = MEMORY_STAGE,
                content = "{}",
                next = stageSetup(
                    TRACKING_STAGE,
                    GenerationPhase.EXTRACT_MEMORY,
                    ChapterCandidateArtifactRoleV1.TRACKING,
                    MEMORY_STAGE,
                ),
            )
        }

        assertTrue(failure is StaleGenerationStateException)
        assertProviderWasNotClaimed(generation, "attempt.memory", MEMORY_STAGE)
    }

    @Test
    fun candidateProviderOpenRejectsCrossChapterPredecessorBeforeSendClaim() = runBlocking {
        prepareCandidateJobAndBody()
        database.libraryDao().createChapter(
            ChapterEntity(
                chapterId = CROSS_CHAPTER_ID,
                bookId = BOOK_ID,
                chapterIndex = 2,
                plannedTitle = "chapter two",
                displayTitle = "chapter two",
                status = ChapterStatus.PLANNED,
                consistencyStatus = ConsistencyStatus.UNKNOWN,
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )
        val generation = database.generationDao()
        overwriteCandidateBinding(
            stageId = MEMORY_STAGE,
            role = ChapterCandidateArtifactRoleV1.MEMORY,
            predecessorStageId = BODY_STAGE,
            chapterId = CROSS_CHAPTER_ID,
            chapterIndex = 2,
        )

        val failure = expectFailure {
            seal(
                role = ChapterCandidateArtifactRoleV1.MEMORY,
                stageId = MEMORY_STAGE,
                content = "{}",
                next = stageSetup(
                    TRACKING_STAGE,
                    GenerationPhase.EXTRACT_MEMORY,
                    ChapterCandidateArtifactRoleV1.TRACKING,
                    MEMORY_STAGE,
                ),
            )
        }

        assertTrue(failure is StaleGenerationStateException)
        assertProviderWasNotClaimed(generation, "attempt.memory", MEMORY_STAGE)
    }

    @Test
    fun candidateRevisionProviderOpenAcceptsSealedConsistencyPredecessor() = runBlocking {
        val route = prepareCandidateRevisionStage()
        val revisedBody = sealRevisionCandidate(route, ".revision")

        assertEquals(ChapterCandidateArtifactRoleV1.BODY, revisedBody.evidence.role)
        assertEquals(revisedBody.evidence, requireNotNull(revisedBody.result).seal.evidence)
        assertEquals(GenerationStageStatus.SUCCEEDED, database.generationDao().findStage(REVISE_STAGE)?.status)
        assertEquals(GenerationStageStatus.READY, database.generationDao().findStage(REVISED_MEMORY_STAGE)?.status)
    }

    @Test
    fun revisedDerivedStageRejectsDroppedRevisionResultBinding() = runBlocking {
        val route = prepareCandidateRevisionStage()
        sealRevisionCandidate(route, ".dropped-route")
        val revisedHash = sha256(REVISED_BODY)
        val generation = database.generationDao()

        val failure = expectFailure {
            seal(
                role = ChapterCandidateArtifactRoleV1.MEMORY,
                stageId = REVISED_MEMORY_STAGE,
                content = "{}",
                next = stageSetup(
                    REVISED_TRACKING_STAGE,
                    GenerationPhase.EXTRACT_MEMORY,
                    ChapterCandidateArtifactRoleV1.TRACKING,
                    REVISED_MEMORY_STAGE,
                    candidateChapterVersionId = REVISED_VERSION_ID,
                    candidateContentHash = revisedHash,
                    revisionIndex = 1,
                    routeBindingHash = null,
                ),
                candidateChapterVersionId = REVISED_VERSION_ID,
                candidateContentHash = revisedHash,
                revisionIndex = 1,
                routeBindingHash = null,
                attemptSuffix = ".dropped-route",
            )
        }

        assertTrue(failure is IllegalArgumentException)
        assertEquals(GenerationStageStatus.COMMITTING, generation.findStage(REVISED_MEMORY_STAGE)?.status)
        assertEquals(null, generation.findStage(REVISED_TRACKING_STAGE))
    }

    @Test
    fun revisedCandidateSealReplaysExactlyAndRejectsChangedRoutePolicy() = runBlocking {
        val route = prepareCandidateRevisionStage()
        val prepared = sealRevisionCandidate(route, ".candidate-replay")
        val repository = ChapterRevisionCandidateRepositoryV1(database, artifacts)
        artifacts.delete(prepared.permit.artifactRefId)

        val replay = repository.seal(
            prepared.permit,
            prepared.draft.copy(sealedAt = ++now),
            prepared.policyInput,
        )
        val conflictingReplay = expectFailure {
            repository.seal(
                prepared.permit,
                prepared.draft.copy(sealedAt = ++now),
                prepared.policyInput.copy(
                    issues = listOf(
                        revisionIssue(ConsistencyIssueSeverity.MAJOR).copy(issueId = "issue.candidate.changed"),
                    ),
                ),
            )
        }
        val changedLengthReplay = expectFailure {
            repository.seal(
                prepared.permit,
                prepared.draft.copy(
                    revisedBodyCodePointCount = prepared.draft.revisedBodyCodePointCount + 1,
                    sealedAt = ++now,
                ),
                prepared.policyInput,
            )
        }

        assertTrue(replay.seal.replayed)
        assertTrue(conflictingReplay is IllegalArgumentException)
        assertTrue(changedLengthReplay is IllegalArgumentException)
        assertEquals(1, replay.completedAutomaticRevisions)
        assertEquals(listOf(sha256(BODY), sha256(REVISED_BODY)), replay.candidateContentHashHistory)
        assertEquals(GenerationStageStatus.SUCCEEDED, database.generationDao().findStage(REVISE_STAGE)?.status)
        assertEquals(GenerationStageStatus.READY, database.generationDao().findStage(REVISED_MEMORY_STAGE)?.status)
    }

    @Test
    fun consistencyRevisionRouteRejectsChangedPolicyOnReplay() = runBlocking {
        val prepared = prepareCandidateRevisionStage()
        val generation = database.generationDao()
        val originalOutput = generation.findStage(CHECK_STAGE)?.outputReferenceJson

        val failure = expectFailure {
            ChapterConsistencyOutcomeRepositoryV1(database, artifacts).route(
                prepared.permit,
                prepared.draft.copy(routedAt = ++now),
                prepared.policyInput.copy(
                    issues = listOf(
                        revisionIssue(ConsistencyIssueSeverity.MAJOR).copy(issueId = "issue.route.changed"),
                    ),
                ),
            )
        }

        assertTrue(failure is IllegalArgumentException)
        assertEquals(originalOutput, generation.findStage(CHECK_STAGE)?.outputReferenceJson)
        assertEquals(GenerationStageStatus.SUCCEEDED, generation.findStage(CHECK_STAGE)?.status)
        assertEquals(GenerationStageStatus.READY, generation.findStage(REVISE_STAGE)?.status)
    }

    @Test
    fun revisionProviderOpenRejectsRequestInputThatDiffersFromFrozenRoute() = runBlocking {
        prepareCandidateRevisionStage()
        val generation = database.generationDao()

        val failure = expectFailure {
            streamCandidateResponse(
                role = ChapterCandidateArtifactRoleV1.BODY,
                stageId = REVISE_STAGE,
                content = REVISED_BODY,
                attemptSuffix = ".wrong-route",
                sourceBindingHashOverride = sha256("different-revision-request"),
            )
        }

        assertTrue(failure is StaleGenerationStateException)
        assertProviderWasNotClaimed(generation, "attempt.body.wrong-route", REVISE_STAGE)
    }

    @Test
    fun revisedCandidateSealRejectsBodyLengthThatDiffersFromEncryptedArtifact() = runBlocking {
        val route = prepareCandidateRevisionStage()
        val prepared = prepareRevisionCandidate(route, ".wrong-length")
        val generation = database.generationDao()

        val failure = expectFailure {
            ChapterRevisionCandidateRepositoryV1(database, artifacts).seal(
                prepared.permit,
                prepared.draft.copy(revisedBodyCodePointCount = prepared.draft.revisedBodyCodePointCount + 1),
                prepared.policyInput,
            )
        }

        assertTrue(failure is IllegalArgumentException)
        assertEquals(GenerationStageStatus.COMMITTING, generation.findStage(REVISE_STAGE)?.status)
        assertEquals(UsageLedgerStatus.PROVISIONAL, generation.findUsageForAttempt(prepared.evidence.attemptId)?.status)
        assertEquals(null, generation.findStage(REVISED_MEMORY_STAGE))
    }

    @Test
    fun revisionQualityNeedsActionSettlementIsAtomicAndExactlyReplayable() = runBlocking {
        prepareCandidateRevisionStage()
        val streamed = streamCandidateResponse(
            role = ChapterCandidateArtifactRoleV1.BODY,
            stageId = REVISE_STAGE,
            content = REVISED_BODY,
            attemptSuffix = ".quality",
        )
        val generation = database.generationDao()
        val outcomes = ChapterRevisionOutcomeRepository(database)

        val invalidReason = expectFailure {
            outcomes.settleNeedsAction(
                response = streamed.response,
                reason = ChapterRevisionNeedsActionReasonV1.AUTOMATIC_REVISION_LIMIT_REACHED,
                usage = FinalUsageCommit.UNKNOWN,
                settledAt = ++now,
            )
        }

        assertTrue(invalidReason is IllegalArgumentException)
        assertEquals(GenerationStageStatus.VALIDATING, generation.findStage(REVISE_STAGE)?.status)
        assertEquals(GenerationJobStatus.RUNNING, generation.findJob(JOB_ID)?.status)
        assertEquals(UsageLedgerStatus.PROVISIONAL, generation.findUsageForAttempt("attempt.body.quality")?.status)

        val first = outcomes.settleNeedsAction(
            response = streamed.response,
            reason = ChapterRevisionNeedsActionReasonV1.REVISED_CANDIDATE_UNCHANGED,
            usage = FinalUsageCommit.UNKNOWN,
            settledAt = ++now,
        )
        val replay = outcomes.settleNeedsAction(
            response = streamed.response,
            reason = ChapterRevisionNeedsActionReasonV1.REVISED_CANDIDATE_UNCHANGED,
            usage = FinalUsageCommit.UNKNOWN,
            settledAt = ++now,
        )
        val conflictingReplay = expectFailure {
            outcomes.settleNeedsAction(
                response = streamed.response,
                reason = ChapterRevisionNeedsActionReasonV1.REVISED_CANDIDATE_CYCLE,
                usage = FinalUsageCommit.UNKNOWN,
                settledAt = ++now,
            )
        }

        assertTrue(!first.replayed)
        assertTrue(replay.replayed)
        assertTrue(conflictingReplay is IllegalArgumentException)
        assertEquals(GenerationStageStatus.NEEDS_ACTION, generation.findStage(REVISE_STAGE)?.status)
        assertEquals(GenerationJobStatus.NEEDS_ACTION, generation.findJob(JOB_ID)?.status)
        assertEquals(
            "CHAPTER_REVISION:REVISED_CANDIDATE_UNCHANGED",
            generation.findJob(JOB_ID)?.pauseOrStopReason,
        )
        assertEquals(UsageLedgerStatus.FINAL, generation.findUsageForAttempt("attempt.body.quality")?.status)
        assertEquals(null, database.libraryDao().findChapter(CHAPTER_ID)?.currentVersionId)
    }

    @Test
    fun consistencyRoutingExhaustionIsAtomicExactlyReplayableAndCreatesNoSuccessor() = runBlocking {
        val route = prepareCandidateRevisionStage()
        val revisedHash = sha256(REVISED_BODY)
        val revised = sealRevisionCandidate(route, ".exhausted")
        val revisionResultBinding = requireNotNull(revised.result).routeBindingHash
        seal(
            role = ChapterCandidateArtifactRoleV1.MEMORY,
            stageId = REVISED_MEMORY_STAGE,
            content = "{}",
            next = stageSetup(
                REVISED_TRACKING_STAGE,
                GenerationPhase.EXTRACT_MEMORY,
                ChapterCandidateArtifactRoleV1.TRACKING,
                REVISED_MEMORY_STAGE,
                candidateChapterVersionId = REVISED_VERSION_ID,
                candidateContentHash = revisedHash,
                revisionIndex = 1,
                routeBindingHash = revisionResultBinding,
            ),
            candidateChapterVersionId = REVISED_VERSION_ID,
            candidateContentHash = revisedHash,
            revisionIndex = 1,
            routeBindingHash = revisionResultBinding,
            attemptSuffix = ".exhausted",
        )
        seal(
            role = ChapterCandidateArtifactRoleV1.TRACKING,
            stageId = REVISED_TRACKING_STAGE,
            content = "{}",
            next = stageSetup(
                REVISED_CHECK_STAGE,
                GenerationPhase.CHECK_CONSISTENCY,
                ChapterCandidateArtifactRoleV1.CONSISTENCY,
                REVISED_TRACKING_STAGE,
                candidateChapterVersionId = REVISED_VERSION_ID,
                candidateContentHash = revisedHash,
                revisionIndex = 1,
                routeBindingHash = revisionResultBinding,
            ),
            candidateChapterVersionId = REVISED_VERSION_ID,
            candidateContentHash = revisedHash,
            revisionIndex = 1,
            routeBindingHash = revisionResultBinding,
            attemptSuffix = ".exhausted",
        )
        val policyInput = revisionPolicyInput(
            candidateContent = REVISED_BODY,
            history = listOf(sha256(BODY), revisedHash),
            completedRevisions = 1,
            attemptsUsed = 1,
            issues = listOf(revisionIssue(ConsistencyIssueSeverity.MAJOR)),
        )
        val prepared = prepareConsistencyRoute(
            stageId = REVISED_CHECK_STAGE,
            nextStageId = UNUSED_NEXT_STAGE,
            candidateChapterVersionId = REVISED_VERSION_ID,
            candidateContent = REVISED_BODY,
            revisionIndex = 1,
            policyInput = policyInput,
            attemptSuffix = ".exhausted",
        )
        val repository = ChapterConsistencyOutcomeRepositoryV1(database, artifacts)

        val droppedCandidateRoute = expectFailure {
            repository.route(
                prepared.permit,
                prepared.draft.copy(candidateRouteBindingHash = null, routedAt = ++now),
                prepared.policyInput,
            )
        }

        val first = repository.route(prepared.permit, prepared.draft, prepared.policyInput)
        val replay = repository.route(
            prepared.permit,
            prepared.draft.copy(routedAt = ++now),
            prepared.policyInput,
        )
        val conflictingReplay = expectFailure {
            repository.route(
                prepared.permit,
                prepared.draft.copy(routedAt = ++now),
                prepared.policyInput.copy(
                    issues = listOf(
                        revisionIssue(ConsistencyIssueSeverity.MAJOR).copy(issueId = "issue.route.changed"),
                    ),
                ),
            )
        }

        val firstSettlement = (first as ChapterConsistencyOutcomeResultV1.NeedsAction).settlement
        val replaySettlement = (replay as ChapterConsistencyOutcomeResultV1.NeedsAction).settlement
        val generation = database.generationDao()
        assertTrue(droppedCandidateRoute is IllegalArgumentException)
        assertTrue(!firstSettlement.replayed)
        assertTrue(replaySettlement.replayed)
        assertTrue(conflictingReplay is IllegalArgumentException)
        assertEquals(ChapterRevisionNeedsActionReasonV1.AUTOMATIC_REVISION_LIMIT_REACHED, firstSettlement.reason)
        assertEquals(GenerationStageStatus.NEEDS_ACTION, generation.findStage(REVISED_CHECK_STAGE)?.status)
        assertEquals(GenerationJobStatus.NEEDS_ACTION, generation.findJob(JOB_ID)?.status)
        assertEquals(
            "CHAPTER_REVISION:AUTOMATIC_REVISION_LIMIT_REACHED",
            generation.findJob(JOB_ID)?.pauseOrStopReason,
        )
        assertEquals(UsageLedgerStatus.FINAL, generation.findUsageForAttempt(firstSettlement.attemptId)?.status)
        assertEquals(null, generation.findStage(UNUSED_NEXT_STAGE))
        assertEquals(null, database.libraryDao().findChapter(CHAPTER_ID)?.currentVersionId)
    }

    private data class PreparedPipeline(
        val draft: ChapterFinalCandidateCommitDraftV1,
        val finalLease: GenerationLeaseToken,
    )

    private suspend fun prepareAcceptedCandidatePipeline(): PreparedPipeline {
        val body = prepareCandidateJobAndBody()
        val generation = database.generationDao()
        val memory = seal(
            role = ChapterCandidateArtifactRoleV1.MEMORY,
            stageId = MEMORY_STAGE,
            content = "{}",
            next = stageSetup(TRACKING_STAGE, GenerationPhase.EXTRACT_MEMORY, ChapterCandidateArtifactRoleV1.TRACKING, MEMORY_STAGE),
        )
        val tracking = seal(
            role = ChapterCandidateArtifactRoleV1.TRACKING,
            stageId = TRACKING_STAGE,
            content = "{}",
            next = stageSetup(CHECK_STAGE, GenerationPhase.CHECK_CONSISTENCY, ChapterCandidateArtifactRoleV1.CONSISTENCY, TRACKING_STAGE),
        )
        val (consistency, route) = routeConsistency(
            stageId = CHECK_STAGE,
            nextStageId = FINAL_STAGE,
            candidateChapterVersionId = VERSION_ID,
            candidateContent = BODY,
            revisionIndex = 0,
            policyInput = revisionPolicyInput(
                candidateContent = BODY,
                issues = listOf(revisionIssue(ConsistencyIssueSeverity.MINOR)),
            ),
        )
        assertTrue(route is ChapterConsistencyOutcomeResultV1.CommitReady)
        val finalStageSource = ChapterFinalCommitStageBindingV1.parseAndVerify(
            requireNotNull(generation.findStage(FINAL_STAGE)),
        )
        assertEquals(null, finalStageSource.expectedCurrentVersionId)
        assertEquals(1, finalStageSource.maximumAutomaticRevisions)
        assertEquals(listOf(sha256(BODY)), finalStageSource.candidateContentHashHistory)
        assertEquals(CHECK_STAGE, finalStageSource.predecessorStageId)
        assertEquals(consistency.sourceBindingHash, finalStageSource.consistencyRequestSourceBindingHash)
        assertEquals(sha256(finalStageSource.consistencyMappingSnapshotJson), finalStageSource.consistencyMappingSnapshotContentHash)
        val finalStageJson = Json.parseToJsonElement(
            requireNotNull(generation.findStage(FINAL_STAGE)).inputSourcesJson,
        ) as JsonObject
        assertTrue(finalStageJson["consistencyMappingSnapshot"] is JsonObject)
        val finalLease = generation.acquireStageLease(FINAL_STAGE, "final.worker", ++now).let {
            GenerationLeaseToken(requireNotNull(it.leaseOwnerId), requireNotNull(it.leaseAcquiredAt))
        }
        generation.transitionStage(
            FINAL_STAGE,
            GenerationStageStatus.PREPARING,
            StageEvent.LOCAL_OUTPUT_READY,
            updatedAt = ++now,
            leaseToken = finalLease,
        )
        val committedAt = ++now
        val allArtifacts = listOf(body, memory, tracking, consistency)
        return PreparedPipeline(
            draft = finalDraft(allArtifacts, committedAt),
            finalLease = finalLease,
        )
    }

    private suspend fun prepareCandidateJobAndBody(): ChapterFinalCandidateArtifactEvidenceV1 {
        val generation = database.generationDao()
        generation.createJob(
            GenerationJobEntity(
                jobId = JOB_ID,
                bookId = BOOK_ID,
                jobType = GenerationJobType.CREATE_BOOK,
                status = GenerationJobStatus.CREATED,
                userIntentJson = "{}",
                budgetSnapshotJson = "{}",
                promptBundleVersion = "fixture.prompt.v1",
                createdAt = 1L,
                updatedAt = 1L,
            ),
            listOf(stageEntity(BODY_STAGE, GenerationPhase.DRAFT_CHAPTER, 1L)),
        )
        generation.transitionJob(JOB_ID, GenerationJobStatus.CREATED, JobEvent.VALIDATION_PASSED, 2L)
        generation.acquireJobLease(JOB_ID, "job.worker", 3L)
        generation.transitionStage(
            BODY_STAGE,
            GenerationStageStatus.PENDING,
            StageEvent.DEPENDENCIES_SATISFIED,
            updatedAt = 4L,
        )
        now = 5L
        return seal(
            role = ChapterCandidateArtifactRoleV1.BODY,
            stageId = BODY_STAGE,
            content = BODY,
            next = stageSetup(MEMORY_STAGE, GenerationPhase.EXTRACT_MEMORY, ChapterCandidateArtifactRoleV1.MEMORY, BODY_STAGE),
        )
    }

    private suspend fun prepareCandidateRevisionStage(): PreparedConsistencyRoute {
        prepareCandidateJobAndBody()
        seal(
            role = ChapterCandidateArtifactRoleV1.MEMORY,
            stageId = MEMORY_STAGE,
            content = "{}",
            next = stageSetup(
                TRACKING_STAGE,
                GenerationPhase.EXTRACT_MEMORY,
                ChapterCandidateArtifactRoleV1.TRACKING,
                MEMORY_STAGE,
            ),
        )
        seal(
            role = ChapterCandidateArtifactRoleV1.TRACKING,
            stageId = TRACKING_STAGE,
            content = "{}",
            next = stageSetup(
                CHECK_STAGE,
                GenerationPhase.CHECK_CONSISTENCY,
                ChapterCandidateArtifactRoleV1.CONSISTENCY,
                TRACKING_STAGE,
            ),
        )
        val prepared = prepareConsistencyRoute(
            stageId = CHECK_STAGE,
            nextStageId = REVISE_STAGE,
            candidateChapterVersionId = VERSION_ID,
            candidateContent = BODY,
            revisionIndex = 0,
            policyInput = revisionPolicyInput(
                candidateContent = BODY,
                issues = listOf(revisionIssue(ConsistencyIssueSeverity.MAJOR)),
            ),
        )
        val route = ChapterConsistencyOutcomeRepositoryV1(database, artifacts).route(
            prepared.permit,
            prepared.draft,
            prepared.policyInput,
        )
        assertTrue(route is ChapterConsistencyOutcomeResultV1.RevisionReady)
        return prepared
    }

    private data class StreamedCandidateResponse(
        val response: CompletedStreamingResponse,
        val sourceBindingHash: String,
    )

    private data class PreparedConsistencyRoute(
        val permit: app.zhijuan.core.database.generation.ValidatedOutputCommitPermit,
        val draft: ChapterConsistencyOutcomeDraftV1,
        val policyInput: ChapterRevisionPolicyInputV1,
        val evidence: ChapterFinalCandidateArtifactEvidenceV1,
    )

    private data class PreparedRevisionCandidateSeal(
        val permit: app.zhijuan.core.database.generation.ValidatedOutputCommitPermit,
        val draft: ChapterRevisionCandidateDraftV1,
        val policyInput: ChapterRevisionPolicyInputV1,
        val evidence: ChapterFinalCandidateArtifactEvidenceV1,
        val result: app.zhijuan.core.database.generation.ChapterRevisionCandidateResultV1? = null,
    )

    private suspend fun streamCandidateResponse(
        role: ChapterCandidateArtifactRoleV1,
        stageId: String,
        content: String,
        attemptSuffix: String,
        sourceBindingHashOverride: String? = null,
    ): StreamedCandidateResponse {
        val generation = database.generationDao()
        val stage = requireNotNull(generation.findStage(stageId))
        if (stage.status == GenerationStageStatus.READY) {
            generation.acquireStageLease(stageId, "worker.${role.name.lowercase()}", ++now)
        }
        val lease = stageLease(stageId)
        val sourceBindingHash = sourceBindingHashOverride ?: sha256("source:$stageId")
        val intent = RequestIntentDraft(
            attemptId = "attempt.${role.name.lowercase()}$attemptSuffix",
            usageLedgerId = "usage.${role.name.lowercase()}$attemptSuffix",
            stageId = stageId,
            retryParentAttemptId = null,
            connectionSnapshotJson = "{}",
            modelSnapshotJson = MODEL_SNAPSHOT,
            protocolSnapshotJson = "{}",
            inputHash = sourceBindingHash,
            streamDraftRef = null,
            createdAt = ++now,
        )
        val drafts = GenerationStreamingDraftRepository(database, artifacts)
        val prepared = drafts.prepareBeforeSend(
            intent,
            BudgetedRequestTestSupport.budgetedDraft(intent.attemptId),
            lease,
        )
        val claimed = drafts.claimForProviderOpen(prepared, ++now)
        val checkpoint = drafts.openDraftBuffer(claimed).use { buffer ->
            drafts.markRequestSent(claimed, null, ++now)
            drafts.markStreamStarted(claimed, ++now)
            buffer.appendUtf8(content, ++now)
            buffer.flush(now)
        }
        val response = GenerationOutputValidationRepository(database, artifacts)
            .recordSuccessfulResponse(claimed, checkpoint, ++now)
        return StreamedCandidateResponse(response, sourceBindingHash)
    }

    private suspend fun prepareConsistencyRoute(
        stageId: String,
        nextStageId: String,
        candidateChapterVersionId: String,
        candidateContent: String,
        revisionIndex: Int,
        policyInput: ChapterRevisionPolicyInputV1,
        attemptSuffix: String = "",
    ): PreparedConsistencyRoute {
        val streamed = streamCandidateResponse(
            role = ChapterCandidateArtifactRoleV1.CONSISTENCY,
            stageId = stageId,
            content = "{}",
            attemptSuffix = attemptSuffix,
        )
        val permit = GenerationOutputValidationRepository(database, artifacts)
            .recordStructuredOutputValid(streamed.response, ++now)
        val canonicalHash = sha256("{}")
        val decision = ChapterRevisionPolicyV1.evaluate(policyInput)
        val mappingSnapshot = if (decision is ChapterRevisionPolicyDecisionV1.AcceptCandidate) {
            consistencyMappingSnapshot(streamed.sourceBindingHash)
        } else {
            null
        }
        val draft = ChapterConsistencyOutcomeDraftV1(
            candidateChapterVersionId = candidateChapterVersionId,
            chapterId = CHAPTER_ID,
            chapterIndex = 1,
            candidateContentHash = sha256(candidateContent),
            canonicalOutputHash = canonicalHash,
            sourceBindingHash = streamed.sourceBindingHash,
            revisionIndex = revisionIndex,
            nextStageId = nextStageId,
            candidateRouteBindingHash = ChapterCandidateStageBindingV1.parseAndVerify(
                requireNotNull(database.generationDao().findStage(stageId)),
            ).routeBindingHash,
            revisionRequestSourceBindingHash = when (decision) {
                is ChapterRevisionPolicyDecisionV1.ReviseAutomatically -> sha256("source:$nextStageId")
                else -> null
            },
            usage = FinalUsageCommit.UNKNOWN,
            routedAt = ++now,
            consistencyMappingSnapshotJson = mappingSnapshot,
            consistencyMappingSnapshotContentHash = mappingSnapshot?.let(::sha256),
        )
        return PreparedConsistencyRoute(
            permit = permit,
            draft = draft,
            policyInput = policyInput,
            evidence = ChapterFinalCandidateArtifactEvidenceV1(
                role = ChapterCandidateArtifactRoleV1.CONSISTENCY,
                stageId = stageId,
                attemptId = streamed.response.attemptId,
                artifactRefId = streamed.response.artifactRefId,
                artifactRevision = streamed.response.artifactRevision,
                rawOutputHash = streamed.response.persistedOutputHash,
                canonicalOutputHash = canonicalHash,
                sourceBindingHash = streamed.sourceBindingHash,
            ),
        )
    }

    private suspend fun routeConsistency(
        stageId: String,
        nextStageId: String,
        candidateChapterVersionId: String,
        candidateContent: String,
        revisionIndex: Int,
        policyInput: ChapterRevisionPolicyInputV1,
        attemptSuffix: String = "",
    ): Pair<ChapterFinalCandidateArtifactEvidenceV1, ChapterConsistencyOutcomeResultV1> {
        val prepared = prepareConsistencyRoute(
            stageId = stageId,
            nextStageId = nextStageId,
            candidateChapterVersionId = candidateChapterVersionId,
            candidateContent = candidateContent,
            revisionIndex = revisionIndex,
            policyInput = policyInput,
            attemptSuffix = attemptSuffix,
        )
        val result = ChapterConsistencyOutcomeRepositoryV1(database, artifacts).route(
            prepared.permit,
            prepared.draft,
            prepared.policyInput,
        )
        return prepared.evidence to result
    }

    private suspend fun sealRevisionCandidate(
        route: PreparedConsistencyRoute,
        attemptSuffix: String,
    ): PreparedRevisionCandidateSeal {
        val prepared = prepareRevisionCandidate(route, attemptSuffix)
        val result = ChapterRevisionCandidateRepositoryV1(database, artifacts).seal(
            permit = prepared.permit,
            draft = prepared.draft,
            policyInput = prepared.policyInput,
        )
        return prepared.copy(result = result)
    }

    private suspend fun prepareRevisionCandidate(
        route: PreparedConsistencyRoute,
        attemptSuffix: String,
    ): PreparedRevisionCandidateSeal {
        val streamed = streamCandidateResponse(
            role = ChapterCandidateArtifactRoleV1.BODY,
            stageId = REVISE_STAGE,
            content = REVISED_BODY,
            attemptSuffix = attemptSuffix,
        )
        val permit = GenerationOutputValidationRepository(database, artifacts)
            .recordStructuredOutputValid(streamed.response, ++now)
        val revisedHash = sha256(REVISED_BODY)
        val draft = ChapterRevisionCandidateDraftV1(
            revisedCandidateChapterVersionId = REVISED_VERSION_ID,
            chapterId = CHAPTER_ID,
            chapterIndex = 1,
            revisedCandidateContentHash = revisedHash,
            revisedBodyCodePointCount = REVISED_BODY.codePointCount(0, REVISED_BODY.length),
            candidateContentHashHistory = route.policyInput.candidateContentHashHistory + revisedHash,
            sourceBindingHash = streamed.sourceBindingHash,
            nextMemoryStageId = REVISED_MEMORY_STAGE,
            nextMemoryMaximumAttempts = 3,
            usage = FinalUsageCommit.UNKNOWN,
            sealedAt = ++now,
        )
        return PreparedRevisionCandidateSeal(
            permit = permit,
            draft = draft,
            policyInput = route.policyInput,
            evidence = ChapterFinalCandidateArtifactEvidenceV1(
                role = ChapterCandidateArtifactRoleV1.BODY,
                stageId = REVISE_STAGE,
                attemptId = streamed.response.attemptId,
                artifactRefId = streamed.response.artifactRefId,
                artifactRevision = streamed.response.artifactRevision,
                rawOutputHash = streamed.response.persistedOutputHash,
                canonicalOutputHash = revisedHash,
                sourceBindingHash = streamed.sourceBindingHash,
            ),
        )
    }

    private suspend fun seal(
        role: ChapterCandidateArtifactRoleV1,
        stageId: String,
        content: String,
        next: GenerationStageSetup,
        candidateChapterVersionId: String = VERSION_ID,
        candidateContentHash: String? = null,
        revisionIndex: Int = 0,
        routeBindingHash: String? = null,
        attemptSuffix: String = "",
    ): ChapterFinalCandidateArtifactEvidenceV1 {
        val frozenCandidateContentHash = candidateContentHash ?: sha256(BODY)
        val streamed = streamCandidateResponse(role, stageId, content, attemptSuffix)
        val outputs = GenerationOutputValidationRepository(database, artifacts)
        val response = streamed.response
        val permit = outputs.recordStructuredOutputValid(response, ++now)
        val canonicalHash = if (role == ChapterCandidateArtifactRoleV1.BODY) {
            sha256(content)
        } else {
            sha256((Json.parseToJsonElement(content) as JsonObject).toString())
        }
        ChapterCandidateArtifactSealRepositoryV1(database, artifacts).seal(
            permit,
            ChapterCandidateArtifactSealDraftV1(
                role = role,
                candidateChapterVersionId = candidateChapterVersionId,
                chapterId = CHAPTER_ID,
                chapterIndex = 1,
                candidateContentHash = frozenCandidateContentHash,
                canonicalOutputHash = canonicalHash,
                sourceBindingHash = streamed.sourceBindingHash,
                revisionIndex = revisionIndex,
                usage = FinalUsageCommit.UNKNOWN,
                nextStage = next,
                sealedAt = ++now,
                routeBindingHash = routeBindingHash,
            ),
        )
        return ChapterFinalCandidateArtifactEvidenceV1(
            role = role,
            stageId = stageId,
            attemptId = response.attemptId,
            artifactRefId = response.artifactRefId,
            artifactRevision = response.artifactRevision,
            rawOutputHash = response.persistedOutputHash,
            canonicalOutputHash = canonicalHash,
            sourceBindingHash = streamed.sourceBindingHash,
        )
    }

    private fun finalDraft(
        artifactEvidence: List<ChapterFinalCandidateArtifactEvidenceV1>,
        committedAt: Long,
    ): ChapterFinalCandidateCommitDraftV1 {
        val bodyHash = sha256(BODY)
        val structuredHash = sha256("{}")
        val summary = ChapterSummaryEntity(
            chapterSummaryId = "summary.final.1",
            bookId = BOOK_ID,
            chapterVersionId = VERSION_ID,
            chapterIndex = 1,
            schemaVersion = 1,
            summaryJson = "{\"sourceChapterContentHash\":\"$bodyHash\",\"endingState\":\"fixture\"}",
            importance = 80,
            status = DerivedDataStatus.VALID,
            modelSnapshotJson = MODEL_SNAPSHOT,
            createdAt = committedAt,
            updatedAt = committedAt,
        )
        val event = EntityEventEntity(
            entityEventId = "event.final.1",
            bookId = BOOK_ID,
            entityId = CHARACTER_ID,
            sourceChapterVersionId = VERSION_ID,
            storyOrder = 1_000_001L,
            attributeKey = "physical_state",
            oldValueJson = null,
            newValueJson = "{\"value\":\"changed\"}",
            storyTimeExpression = "当晚",
            confidenceMicros = 990_000,
            canonLevel = CanonLevel.STORY_CANON,
            evidenceJson = "{\"sourceChapterContentHash\":\"$bodyHash\"}",
            status = DerivedDataStatus.VALID,
            createdAt = committedAt,
        )
        val fact = CanonFactEntity(
            canonFactId = "fact.final.1",
            bookId = BOOK_ID,
            entityId = CHARACTER_ID,
            factText = "fixture fact",
            factPayloadJson = "{\"sourceChapterContentHash\":\"$bodyHash\"}",
            canonLevel = CanonLevel.STORY_CANON,
            scopeJson = "{}",
            sourceChapterVersionId = VERSION_ID,
            sourceBibleRevisionId = null,
            validFromStoryOrder = 1_000_002L,
            validToStoryOrder = null,
            conflictGroupId = null,
            status = DerivedDataStatus.VALID,
            createdAt = committedAt,
        )
        val timeline = TimelineEventEntity(
            timelineEventId = "timeline.final.1",
            bookId = BOOK_ID,
            name = "fixture event",
            participantsJson = "[\"$CHARACTER_ID\"]",
            locationEntityId = LOCATION_ID,
            storyTimeExpression = "当晚",
            storyOrder = 1_000_001L,
            constraintsJson = "{\"sourceChapterContentHash\":\"$bodyHash\"}",
            sourceChapterVersionId = VERSION_ID,
            status = DerivedDataStatus.VALID,
            createdAt = committedAt,
        )
        val foreshadow = ForeshadowItemEntity(
            foreshadowItemId = FORESHADOW_ID,
            bookId = BOOK_ID,
            description = "fixture clue",
            foreshadowStatus = ForeshadowStatus.PLANTED,
            memoryStatus = DerivedDataStatus.VALID,
            targetStartChapterIndex = 2,
            targetEndChapterIndex = 10,
            sourceChapterVersionId = VERSION_ID,
            plantedChapterVersionId = VERSION_ID,
            resolvedChapterVersionId = null,
            visibleEntityIdsJson = "[\"$CHARACTER_ID\"]",
            importance = 70,
            source = MemorySource.CHAPTER_EXTRACTION,
            createdAt = committedAt,
            updatedAt = committedAt,
        )
        val transition = ForeshadowTransitionEntity(
            transitionId = "transition.final.1",
            foreshadowItemId = FORESHADOW_ID,
            bookId = BOOK_ID,
            sourceChapterVersionId = VERSION_ID,
            generationStageId = TRACKING_STAGE,
            storyOrder = 1_100_000L,
            operation = "PLANT",
            fromStatus = null,
            toStatus = ForeshadowStatus.PLANTED,
            evidenceJson = "{\"sourceChapterContentHash\":\"$bodyHash\"}",
            status = DerivedDataStatus.VALID,
            createdAt = committedAt,
        )
        val trackingPayloadHash = ChapterTrackingPayloadHasher.hash(
            listOf(timeline),
            listOf(foreshadow),
            emptyList(),
            listOf(transition),
        )
        val projection = ChapterTrackingProjectionEntity(
            projectionId = "projection.final.1",
            bookId = BOOK_ID,
            chapterVersionId = VERSION_ID,
            chapterIndex = 1,
            generationStageId = TRACKING_STAGE,
            sourceChapterContentHash = bodyHash,
            sourceMemorySnapshotHash = "a".repeat(64),
            priorForeshadowSnapshotHash = "b".repeat(64),
            outputContentHash = structuredHash,
            payloadHash = trackingPayloadHash,
            status = DerivedDataStatus.VALID,
            modelSnapshotJson = MODEL_SNAPSHOT,
            timelineEventCount = 1,
            foreshadowTransitionCount = 1,
            createdAt = committedAt,
            updatedAt = committedAt,
        )
        val reportJson = """
            {"schemaVersion":1,"sourceChapterVersionId":"$VERSION_ID","sourceChapterContentHash":"$bodyHash","chapterId":"$CHAPTER_ID","chapterIndex":1,"decision":"ACCEPT_CANDIDATE","chapterConsistencyStatus":"VALID","blockerCount":0,"majorCount":0,"minorCount":0,"modelSnapshot":$MODEL_SNAPSHOT,"criterionResults":[],"requiredProcessResults":[],"issues":[]}
        """.trimIndent()
        val report = ConsistencyReportEntity(
            consistencyReportId = REPORT_ID,
            bookId = BOOK_ID,
            targetChapterVersionId = VERSION_ID,
            targetChapterIndex = 1,
            generationStageId = CHECK_STAGE,
            checkerVersion = "zhijuan.consistency-combined.v1",
            issuesJson = reportJson,
            status = DerivedDataStatus.VALID,
            createdAt = committedAt,
            updatedAt = committedAt,
        )
        return ChapterFinalCandidateCommitDraftV1(
            chapterVersionId = VERSION_ID,
            chapterId = CHAPTER_ID,
            expectedCurrentVersionId = null,
            content = BODY,
            revisionIndex = 0,
            maximumAutomaticRevisions = 1,
            candidateContentHashHistory = listOf(bodyHash),
            artifacts = artifactEvidence,
            summary = summary,
            entityEvents = listOf(event),
            canonFacts = listOf(fact),
            memoryOutputContentHash = structuredHash,
            trackingProjection = projection,
            timelineEvents = listOf(timeline),
            newForeshadows = listOf(foreshadow),
            existingForeshadowUpdates = emptyList(),
            foreshadowTransitions = listOf(transition),
            trackingOutputContentHash = structuredHash,
            consistencyReport = report,
            consistencyReportContentHash = sha256(reportJson),
            consistencyOutputContentHash = structuredHash,
            committedAt = committedAt,
        )
    }

    private suspend fun seedBookChapterAndEntities() {
        val library = database.libraryDao()
        val snapshot = BookCreationSnapshotEntity(
            snapshotId = "snapshot.final.1",
            rawInputJson = "{}",
            normalizedInputJson = "{}",
            inferenceProvenanceJson = "{}",
            genrePayloadJson = "{}",
            presentationProfileJson = "{}",
            modelPreferenceJson = "{}",
            schemaVersion = 1,
            promptBundleVersion = "fixture.prompt.v1",
            contentControlSchemaVersion = 1,
            contentHash = "snapshot.hash",
            createdAt = 1L,
        )
        library.createBook(
            snapshot,
            BookEntity(
                bookId = BOOK_ID,
                creationSnapshotId = snapshot.snapshotId,
                title = "fixture book",
                titleSource = TitleSource.USER,
                status = BookStatus.DRAFT,
                lengthMode = BookLengthMode.SHORT,
                targetCharacters = 100_000,
                targetChapters = 80,
                minimumChapters = 80,
                lengthPolicySchemaVersion = 1,
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )
        library.createChapter(
            ChapterEntity(
                chapterId = CHAPTER_ID,
                bookId = BOOK_ID,
                chapterIndex = 1,
                plannedTitle = "chapter one",
                displayTitle = "chapter one",
                status = ChapterStatus.PLANNED,
                consistencyStatus = ConsistencyStatus.UNKNOWN,
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )
        database.memoryDao().insertStoryEntity(
            StoryEntity(
                entityId = CHARACTER_ID,
                bookId = BOOK_ID,
                entityType = StoryEntityType.CHARACTER,
                canonicalName = "character fixture",
                aliasesJson = "[]",
                stableDefinitionJson = "{}",
                adultStatus = AdultStatus.CONFIRMED_ADULT,
                ageYears = 24,
                sourceBibleRevisionId = null,
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )
        database.memoryDao().insertStoryEntity(
            StoryEntity(
                entityId = LOCATION_ID,
                bookId = BOOK_ID,
                entityType = StoryEntityType.LOCATION,
                canonicalName = "location fixture",
                aliasesJson = "[]",
                stableDefinitionJson = "{}",
                adultStatus = AdultStatus.NOT_APPLICABLE,
                ageYears = null,
                sourceBibleRevisionId = null,
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )
    }

    private fun stageEntity(id: String, phase: GenerationPhase, at: Long) = GenerationStageEntity(
        stageId = id,
        jobId = JOB_ID,
        phase = phase,
        targetType = GenerationTargetType.CHAPTER,
        targetId = CHAPTER_ID,
        status = GenerationStageStatus.PENDING,
        inputVersionHash = sha256("input:$id"),
        idempotencyKey = "idem.$id",
        maxAttempts = 3,
        inputSourcesJson = "{}",
        createdAt = at,
        updatedAt = at,
    )

    private fun stageSetup(
        id: String,
        phase: GenerationPhase,
        role: ChapterCandidateArtifactRoleV1?,
        predecessorStageId: String,
        candidateChapterVersionId: String = VERSION_ID,
        candidateContentHash: String? = null,
        revisionIndex: Int = 0,
        routeBindingHash: String? = null,
    ): GenerationStageSetup = if (role == null) {
        GenerationStageSetup(
            stageId = id,
            phase = phase,
            targetType = GenerationTargetType.CHAPTER,
            targetId = CHAPTER_ID,
            inputVersionHash = sha256("input:$id"),
            idempotencyKey = "idem.$id",
            maxAttempts = 1,
            inputSourcesJson = "{}",
        )
    } else {
        ChapterCandidateStageBindingV1.stageSetup(
            jobId = JOB_ID,
            stageId = id,
            phase = phase,
            source = ChapterCandidateStageSourceV1(
                role = role,
                candidateChapterVersionId = candidateChapterVersionId,
                candidateContentHash = candidateContentHash ?: sha256(BODY),
                chapterId = CHAPTER_ID,
                chapterIndex = 1,
                revisionIndex = revisionIndex,
                predecessorStageId = predecessorStageId,
                routeBindingHash = routeBindingHash,
            ),
            maxAttempts = 3,
        )
    }

    private suspend fun overwriteCandidateBinding(
        stageId: String,
        role: ChapterCandidateArtifactRoleV1,
        predecessorStageId: String,
        candidateContentHash: String = sha256(BODY),
        chapterId: String = CHAPTER_ID,
        chapterIndex: Int = 1,
        revisionIndex: Int = 0,
    ) {
        val stage = requireNotNull(database.generationDao().findStage(stageId))
        val replacement = ChapterCandidateStageBindingV1.stageSetup(
            jobId = JOB_ID,
            stageId = stageId,
            phase = stage.phase,
            source = ChapterCandidateStageSourceV1(
                role = role,
                candidateChapterVersionId = VERSION_ID,
                candidateContentHash = candidateContentHash,
                chapterId = chapterId,
                chapterIndex = chapterIndex,
                revisionIndex = revisionIndex,
                predecessorStageId = predecessorStageId,
            ),
            maxAttempts = stage.maxAttempts,
        )
        database.openHelper.writableDatabase.execSQL(
            "UPDATE generation_stage SET target_id = ?, input_sources_json = ?, input_version_hash = ?, idempotency_key = ? WHERE stage_id = ?",
            arrayOf(
                replacement.targetId,
                replacement.inputSourcesJson,
                replacement.inputVersionHash,
                replacement.idempotencyKey,
                stageId,
            ),
        )
    }

    private suspend fun assertProviderWasNotClaimed(
        generation: app.zhijuan.core.database.generation.GenerationDao,
        attemptId: String,
        stageId: String,
    ) {
        assertEquals(RequestAttemptStatus.INTENT_RECORDED, generation.findAttempt(attemptId)?.status)
        assertEquals(UsageLedgerStatus.PROVISIONAL, generation.findUsageForAttempt(attemptId)?.status)
        assertEquals(GenerationStageStatus.REQUEST_INTENT_RECORDED, generation.findStage(stageId)?.status)
    }

    private suspend fun stageLease(stageId: String): GenerationLeaseToken {
        val stage = requireNotNull(database.generationDao().findStage(stageId))
        return GenerationLeaseToken(requireNotNull(stage.leaseOwnerId), requireNotNull(stage.leaseAcquiredAt))
    }

    private suspend fun expectFailure(block: suspend () -> Unit): Throwable = try {
        block()
        throw AssertionError("Expected failure")
    } catch (error: Throwable) {
        if (error is AssertionError && error.message == "Expected failure") throw error
        error
    }

    private fun revisionPolicyInput(
        candidateContent: String,
        history: List<String> = listOf(sha256(candidateContent)),
        completedRevisions: Int = history.size - 1,
        attemptsUsed: Int = completedRevisions,
        issues: List<ChapterRevisionIssueRefV1>,
    ) = ChapterRevisionPolicyInputV1(
        currentCandidateContentHash = sha256(candidateContent),
        candidateContentHashHistory = history,
        bodyCodePointCount = candidateContent.codePointCount(0, candidateContent.length),
        minimumBodyCodePoints = 1,
        completedAutomaticRevisions = completedRevisions,
        totalRevisionAttemptsUsed = attemptsUsed,
        stageMaximumAttempts = 3,
        sceneContract = ChapterSceneConsistencyContractV1(
            mode = ChapterSceneConsistencyModeV1.PROPORTIONAL,
            intimacyDetailLevel = 3,
            fadePolicy = FadePolicy.ALLOW,
            requiredKeyProcessCoveragePercent = null,
            fadeSubstitutionAllowed = true,
            requiresStateContinuity = true,
            requiresRelevantAftermath = true,
            requiredProcessNodeIds = emptyList(),
            expectedCriteria = listOf(ConsistencyCriterionV1.ACTION_REACTION),
            contractHash = "c".repeat(64),
        ),
        issues = issues,
    )

    private fun revisionIssue(severity: ConsistencyIssueSeverity) = ChapterRevisionIssueRefV1(
        issueId = "issue.route.${severity.name.lowercase()}",
        code = ConsistencyIssueCode.ACTION_REACTION_GAP,
        severity = severity,
        startCodePointInclusive = 0,
        endCodePointExclusive = 2,
        repairAction = ConsistencyRepairActionV1.RESTORE_CONTINUITY,
    )

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private suspend fun GenerationStreamingDraftRepository.claimForProviderOpen(
        request: PersistedStreamingRequest,
        validatedAt: Long,
    ) = claimForProviderOpen(
        request,
        validatedAt,
        BudgetedRequestTestSupport.budgetedDestinationEvidence(),
    )

    private fun consistencyMappingSnapshot(sourceBindingHash: String): String = JsonObject(
        linkedMapOf(
            "schemaVersion" to JsonPrimitive(1),
            "schemaId" to JsonPrimitive("zhijuan.chapter-final-consistency-mapping.v1"),
            "consistencyRequestSourceBindingHash" to JsonPrimitive(sourceBindingHash),
            "minimumBodyCodePoints" to JsonPrimitive(100),
            "totalRevisionAttemptsUsed" to JsonPrimitive(0),
            "revisionStageMaximumAttempts" to JsonPrimitive(2),
            "localReport" to JsonObject(emptyMap()),
            "expectation" to JsonObject(emptyMap()),
            "sceneContract" to JsonObject(emptyMap()),
        ),
    ).toString()

    private companion object {
        const val BOOK_ID = "book.final.1"
        const val CHAPTER_ID = "chapter.final.1"
        const val CROSS_CHAPTER_ID = "chapter.final.cross.2"
        const val VERSION_ID = "chapter.version.final.1"
        const val JOB_ID = "job.final.1"
        const val BODY_STAGE = "stage.final.body"
        const val MEMORY_STAGE = "stage.final.memory"
        const val TRACKING_STAGE = "stage.final.tracking"
        const val CHECK_STAGE = "stage.final.check"
        const val FINAL_STAGE = "stage.final.commit"
        const val REVISE_STAGE = "stage.final.revise"
        const val REVISED_MEMORY_STAGE = "stage.final.memory.revision"
        const val REVISED_TRACKING_STAGE = "stage.final.tracking.revision"
        const val REVISED_CHECK_STAGE = "stage.final.check.revision"
        const val UNUSED_NEXT_STAGE = "stage.final.unused"
        const val REVISED_VERSION_ID = "chapter.version.final.revision.1"
        const val CHARACTER_ID = "entity.character.1"
        const val LOCATION_ID = "entity.location.1"
        const val FORESHADOW_ID = "foreshadow.final.1"
        const val REPORT_ID = "report.final.1"
        const val MODEL_SNAPSHOT = "{\"model\":\"fixture\"}"
        val BODY = "完整候选章节正文。".repeat(80)
        val REVISED_BODY = "修订后的完整候选章节正文。".repeat(80)
    }
}

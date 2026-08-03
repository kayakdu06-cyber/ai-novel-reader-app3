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
import app.zhijuan.core.database.generation.ChapterFinalCandidateArtifactEvidenceV1
import app.zhijuan.core.database.generation.ChapterFinalCandidateCommitDraftV1
import app.zhijuan.core.database.generation.ChapterFinalCandidateCommitRepositoryV1
import app.zhijuan.core.database.generation.ChapterTrackingPayloadHasher
import app.zhijuan.core.database.generation.FinalUsageCommit
import app.zhijuan.core.database.generation.GenerationJobEntity
import app.zhijuan.core.database.generation.GenerationLeaseToken
import app.zhijuan.core.database.generation.GenerationOutputValidationRepository
import app.zhijuan.core.database.generation.GenerationStageEntity
import app.zhijuan.core.database.generation.GenerationStageSetup
import app.zhijuan.core.database.generation.GenerationStreamingDraftRepository
import app.zhijuan.core.database.generation.RequestIntentDraft
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
import app.zhijuan.core.security.AndroidProtectedArtifactStore
import app.zhijuan.core.model.AdultStatus
import app.zhijuan.core.model.BookLengthMode
import app.zhijuan.core.model.BookStatus
import app.zhijuan.core.model.CanonLevel
import app.zhijuan.core.model.ChapterStatus
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
import app.zhijuan.core.task.JobEvent
import app.zhijuan.core.task.StageEvent
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
        assertEquals(GenerationStageStatus.SUCCEEDED, database.generationDao().findStage(FINAL_STAGE)?.status)
        assertEquals(GenerationJobStatus.COMPLETED, database.generationDao().findJob(JOB_ID)?.status)
        assertEquals(1, database.libraryDao().findBook(BOOK_ID)?.completedChapterCount)
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
        assertEquals(GenerationStageStatus.COMMITTING, database.generationDao().findStage(FINAL_STAGE)?.status)
        assertEquals(GenerationJobStatus.RUNNING, database.generationDao().findJob(JOB_ID)?.status)
    }

    @Test
    fun exactReplayAfterArtifactCleanupDoesNotDuplicatePublication() = runBlocking {
        val prepared = prepareAcceptedCandidatePipeline()
        val repository = ChapterFinalCandidateCommitRepositoryV1(database, artifacts)
        repository.commit(FINAL_STAGE, prepared.finalLease, prepared.draft)
        prepared.draft.artifacts.forEach { artifacts.delete(it.artifactRefId) }

        val replay = repository.commit(FINAL_STAGE, prepared.finalLease, prepared.draft)

        assertTrue(replay.replayed)
        assertEquals(1L, database.libraryDao().versionCount(CHAPTER_ID))
        assertEquals(1, database.memoryDao().entityEventsForVersion(VERSION_ID).size)
        assertEquals(1, database.memoryDao().foreshadowTransitionsForStage(TRACKING_STAGE).size)
        assertEquals(1, database.libraryDao().findBook(BOOK_ID)?.completedChapterCount)
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

    private data class PreparedPipeline(
        val draft: ChapterFinalCandidateCommitDraftV1,
        val finalLease: GenerationLeaseToken,
    )

    private suspend fun prepareAcceptedCandidatePipeline(): PreparedPipeline {
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
        val body = seal(
            role = ChapterCandidateArtifactRoleV1.BODY,
            stageId = BODY_STAGE,
            content = BODY,
            next = stageSetup(MEMORY_STAGE, GenerationPhase.EXTRACT_MEMORY, ChapterCandidateArtifactRoleV1.MEMORY, BODY_STAGE),
        )
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
        val consistency = seal(
            role = ChapterCandidateArtifactRoleV1.CONSISTENCY,
            stageId = CHECK_STAGE,
            content = "{}",
            next = stageSetup(FINAL_STAGE, GenerationPhase.COMMIT_CHAPTER, null, CHECK_STAGE),
        )
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

    private suspend fun seal(
        role: ChapterCandidateArtifactRoleV1,
        stageId: String,
        content: String,
        next: GenerationStageSetup,
    ): ChapterFinalCandidateArtifactEvidenceV1 {
        val generation = database.generationDao()
        val stage = requireNotNull(generation.findStage(stageId))
        if (stage.status == GenerationStageStatus.READY) {
            generation.acquireStageLease(stageId, "worker.${role.name.lowercase()}", ++now)
        }
        val lease = stageLease(stageId)
        val sourceBindingHash = sha256("source:$stageId")
        val intent = RequestIntentDraft(
            attemptId = "attempt.${role.name.lowercase()}",
            usageLedgerId = "usage.${role.name.lowercase()}",
            stageId = stageId,
            retryParentAttemptId = null,
            connectionSnapshotJson = "{}",
            modelSnapshotJson = MODEL_SNAPSHOT,
            protocolSnapshotJson = "{}",
            inputHash = sourceBindingHash,
            streamDraftRef = null,
            dailyPeriodKey = "2026-08-03|Asia/Shanghai",
            createdAt = ++now,
        )
        val drafts = GenerationStreamingDraftRepository(database, artifacts)
        val prepared = drafts.prepareBeforeSend(intent, lease)
        val claimed = drafts.claimForProviderOpen(prepared, ++now)
        val checkpoint = drafts.openDraftBuffer(claimed).use { buffer ->
            drafts.markRequestSent(claimed, null, ++now)
            drafts.markStreamStarted(claimed, ++now)
            buffer.appendUtf8(content, ++now)
            buffer.flush(now)
        }
        val outputs = GenerationOutputValidationRepository(database, artifacts)
        val response = outputs.recordSuccessfulResponse(claimed, checkpoint, ++now)
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
                candidateChapterVersionId = VERSION_ID,
                chapterId = CHAPTER_ID,
                chapterIndex = 1,
                candidateContentHash = sha256(BODY),
                canonicalOutputHash = canonicalHash,
                sourceBindingHash = sourceBindingHash,
                revisionIndex = 0,
                usage = FinalUsageCommit.UNKNOWN,
                nextStage = next,
                sealedAt = ++now,
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
            sourceBindingHash = sourceBindingHash,
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
                candidateChapterVersionId = VERSION_ID,
                candidateContentHash = sha256(BODY),
                chapterId = CHAPTER_ID,
                chapterIndex = 1,
                revisionIndex = 0,
                predecessorStageId = predecessorStageId,
            ),
            maxAttempts = 3,
        )
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

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private companion object {
        const val BOOK_ID = "book.final.1"
        const val CHAPTER_ID = "chapter.final.1"
        const val VERSION_ID = "chapter.version.final.1"
        const val JOB_ID = "job.final.1"
        const val BODY_STAGE = "stage.final.body"
        const val MEMORY_STAGE = "stage.final.memory"
        const val TRACKING_STAGE = "stage.final.tracking"
        const val CHECK_STAGE = "stage.final.check"
        const val FINAL_STAGE = "stage.final.commit"
        const val CHARACTER_ID = "entity.character.1"
        const val LOCATION_ID = "entity.location.1"
        const val FORESHADOW_ID = "foreshadow.final.1"
        const val REPORT_ID = "report.final.1"
        const val MODEL_SNAPSHOT = "{\"model\":\"fixture\"}"
        val BODY = "完整候选章节正文。".repeat(80)
    }
}

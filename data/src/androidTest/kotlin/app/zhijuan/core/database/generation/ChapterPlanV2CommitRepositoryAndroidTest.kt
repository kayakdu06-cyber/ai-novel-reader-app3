package app.zhijuan.core.database.generation

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.zhijuan.core.database.LibraryDatabaseGuards
import app.zhijuan.core.database.ZhijuanDatabase
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
import app.zhijuan.core.model.TitleSource
import app.zhijuan.core.security.AndroidProtectedArtifactStore
import app.zhijuan.core.security.ProtectedArtifactType
import app.zhijuan.core.task.AttemptEvent
import app.zhijuan.core.task.JobEvent
import app.zhijuan.core.task.PromptBundleCatalogV1
import app.zhijuan.core.task.StageEvent
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChapterPlanV2CommitRepositoryAndroidTest {
    private lateinit var database: ZhijuanDatabase
    private lateinit var artifactStore: AndroidProtectedArtifactStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        database = Room.inMemoryDatabaseBuilder(context, ZhijuanDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(LibraryDatabaseGuards.callback)
            .build()
            .also { it.openHelper.writableDatabase }
        artifactStore = AndroidProtectedArtifactStore(context)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun successfulCommitAndReplayCreateExactlyOneInitialDraft() = runBlocking {
        val fixture = seedReadyPlan()
        val state = GenerationStateRepository(database)
        state.transitionJob(JOB_ID, GenerationJobStatus.CREATED, JobEvent.VALIDATION_PASSED, 11L)
        state.transitionStage(PLAN_STAGE_ID, GenerationStageStatus.PENDING, StageEvent.DEPENDENCIES_SATISFIED, 12L)
        state.acquireJobLease(JOB_ID, LEASE_OWNER, 13L)
        val stageLease = requireNotNull(state.acquireStageLease(PLAN_STAGE_ID, LEASE_OWNER, 14L).leaseToken)

        val plan = canonical(JsonObject(linkedMapOf(
            "activationHash" to JsonPrimitive(HASH_A),
            "chapterId" to JsonPrimitive(CHAPTER_ID),
            "chapterIndex" to JsonPrimitive(2),
            "contextContentHash" to JsonPrimitive(HASH_D),
            "contextEvidenceHash" to JsonPrimitive(HASH_C),
            "contextSourceManifestHash" to JsonPrimitive(HASH_E),
            "policyCompilationHash" to JsonPrimitive(HASH_B),
        ))).toString()
        val rawHash = sha256(plan)
        val artifact = artifactStore.createAndClear(
            ProtectedArtifactType.STREAM_DRAFT,
            plan.encodeToByteArray(),
            15L,
        ).descriptor
        val dao = database.generationDao()
        dao.recordRequestIntent(
            NewRequestIntent(
                attemptId = ATTEMPT_ID,
                usageLedgerId = USAGE_ID,
                stageId = PLAN_STAGE_ID,
                retryParentAttemptId = null,
                connectionSnapshotJson = "{}",
                modelSnapshotJson = "{}",
                protocolSnapshotJson = "{}",
                inputHash = REQUEST_BINDING_HASH,
                streamDraftRef = artifact.artifactRefId,
                dailyPeriodKey = "1970-01-01",
                createdAt = 15L,
            ),
            stageLease,
        )
        dao.recordRequestSent(ATTEMPT_ID, "fake-request", 16L, stageLease)
        dao.recordStreamStarted(ATTEMPT_ID, 17L, stageLease)
        dao.recordAttemptOutcome(
            ATTEMPT_ID,
            AttemptEvent.RESPONSE_COMPLETED,
            errorCode = null,
            httpStatus = 200,
            outputHash = rawHash,
            nextRetryAt = null,
            updatedAt = 18L,
            leaseToken = stageLease,
        )
        val permit = GenerationOutputValidationRepository(database, artifactStore).recordStructuredOutputValid(
            CompletedStreamingResponse(
                attemptId = ATTEMPT_ID,
                stageId = PLAN_STAGE_ID,
                artifactRefId = artifact.artifactRefId,
                artifactRevision = artifact.revision,
                outputHash = rawHash,
                leaseToken = stageLease,
                plaintextBytes = plan.encodeToByteArray().size,
            ),
            validatedAt = 19L,
        )
        val draft = ChapterPlanV2CommitDraft(
            canonicalPlanJson = plan,
            canonicalPlanHash = rawHash,
            requestBindingHash = REQUEST_BINDING_HASH,
            expectationHash = fixture.expectationHash,
            activationManifestHash = fixture.activationManifestHash,
            activationHash = HASH_A,
            policyManifestHash = fixture.policyManifestHash,
            policyCompilationHash = HASH_B,
            contextEvidenceHash = HASH_C,
            initialDraftStageId = DRAFT_STAGE_ID,
            initialDraftMaxAttempts = 2,
            usage = FinalUsageCommit.UNKNOWN,
            committedAt = 20L,
        )
        val repository = ChapterPlanV2CommitRepository(database, artifactStore)

        val first = repository.commit(permit, draft)
        val replay = repository.commit(permit, draft)

        assertFalse(first.replayed)
        assertTrue(replay.replayed)
        assertEquals(GenerationStageStatus.SUCCEEDED, dao.findStage(PLAN_STAGE_ID)?.status)
        assertEquals(GenerationStageStatus.READY, dao.findStage(DRAFT_STAGE_ID)?.status)
        assertEquals(DRAFT_STAGE_ID, dao.findJob(JOB_ID)?.currentStageId)
        assertEquals(1, dao.stagesForJob(JOB_ID).count { it.phase == GenerationPhase.DRAFT_CHAPTER })
    }

    private suspend fun seedReadyPlan(): ChapterPlanV2FrozenSources {
        BookCreationRepository(database).create(
            BookCreationSnapshotEntity(
                snapshotId = SNAPSHOT_ID,
                rawInputJson = "{}",
                normalizedInputJson = "{}",
                inferenceProvenanceJson = "{}",
                genrePayloadJson = "{}",
                presentationProfileJson = "{}",
                modelPreferenceJson = "{}",
                schemaVersion = 1,
                promptBundleVersion = PromptBundleCatalogV1.BUNDLE_VERSION,
                contentControlSchemaVersion = 1,
                contentHash = "9".repeat(64),
                createdAt = 1L,
            ),
            BookEntity(
                bookId = BOOK_ID,
                creationSnapshotId = SNAPSHOT_ID,
                title = "事务测试",
                titleSource = TitleSource.USER,
                status = BookStatus.GENERATING,
                lengthMode = BookLengthMode.SHORT,
                targetCharacters = null,
                targetChapters = 80,
                minimumChapters = 80,
                lengthPolicySchemaVersion = 1,
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )
        val frozen = ChapterPlanV2FrozenSources.freeze(
            expectationJson = JsonObject(linkedMapOf(
                "activationHash" to JsonPrimitive(HASH_A),
                "chapterId" to JsonPrimitive(CHAPTER_ID),
                "chapterIndex" to JsonPrimitive(2),
                "contextContentHash" to JsonPrimitive(HASH_D),
                "contextEvidenceHash" to JsonPrimitive(HASH_C),
                "contextSourceManifestHash" to JsonPrimitive(HASH_E),
                "policyCompilationHash" to JsonPrimitive(HASH_B),
            )).toString(),
            activationManifestJson = "{\"activationHash\":\"$HASH_A\"}",
            activationHash = HASH_A,
            policyManifestJson = "{\"policyCompilationHash\":\"$HASH_B\"}",
            policyCompilationHash = HASH_B,
            contextEvidenceHash = HASH_C,
        )
        val progression = linkedMapOf<String, JsonElement>(
            "mode" to JsonPrimitive("FULL_PLANNING"),
            "chapterId" to JsonPrimitive(CHAPTER_ID),
            "chapterIndex" to JsonPrimitive(2),
        )
        val source = JsonObject(linkedMapOf(
            "schemaVersion" to JsonPrimitive(1),
            "sourcePolicyVersion" to JsonPrimitive(ChapterContextAssemblyJobFactory.CHAPTER_PLAN_SOURCE_POLICY_VERSION),
            "promptBundleVersion" to JsonPrimitive(PromptBundleCatalogV1.BUNDLE_VERSION),
            "outputSchemaId" to JsonPrimitive(ChapterContextAssemblyJobFactory.CHAPTER_PLAN_SCHEMA_ID),
            "dependencyStageIds" to JsonArray(listOf(JsonPrimitive(CONTEXT_STAGE_ID))),
            "contextAssemblyStageId" to JsonPrimitive(CONTEXT_STAGE_ID),
            "contextInputVersionHash" to JsonPrimitive("8".repeat(64)),
            "contextPolicyVersion" to JsonPrimitive(app.zhijuan.core.task.ChapterContextBudgetPolicyV1.POLICY_VERSION),
            "contextManifestSchemaId" to JsonPrimitive(app.zhijuan.core.task.ChapterContextBudgetPolicyV1.MANIFEST_SCHEMA_ID),
            "chapterProgressionGate" to JsonObject(progression + ("evidenceHash" to JsonPrimitive(sha256(JsonObject(progression).toString())))),
        )).toString()
        val setup = GenerationJobSetup(
            jobId = JOB_ID,
            bookId = BOOK_ID,
            jobType = GenerationJobType.CONTINUE_BOOK,
            userIntentJson = "{}",
            budgetSnapshotJson = "{}",
            promptBundleVersion = PromptBundleCatalogV1.BUNDLE_VERSION,
            stages = listOf(GenerationStageSetup(
                stageId = PLAN_STAGE_ID,
                phase = GenerationPhase.BUILD_CHAPTER_PLAN,
                targetType = GenerationTargetType.CHAPTER,
                targetId = CHAPTER_ID,
                inputVersionHash = sha256(source),
                idempotencyKey = "plan-v1-key",
                maxAttempts = 2,
                inputSourcesJson = source,
            )),
            createdAt = 10L,
        )
        GenerationJobSetupRepository(database).create(ChapterPlanV2StageBinding.bind(setup, frozen))
        return frozen
    }

    private fun canonical(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.entries.sortedBy { it.key }.associate { it.key to canonical(it.value) })
        is JsonArray -> JsonArray(value.map(::canonical))
        else -> value
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.encodeToByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        const val SNAPSHOT_ID = "snapshot-plan-v2"
        const val BOOK_ID = "book-plan-v2"
        const val JOB_ID = "job-plan-v2"
        const val CONTEXT_STAGE_ID = "context-stage-v2"
        const val PLAN_STAGE_ID = "plan-stage-v2"
        const val DRAFT_STAGE_ID = "draft-stage-v2"
        const val CHAPTER_ID = "chapter-2"
        const val ATTEMPT_ID = "attempt-plan-v2"
        const val USAGE_ID = "usage-plan-v2"
        const val LEASE_OWNER = "runner-plan-v2"
        val HASH_A = "a".repeat(64)
        val HASH_B = "b".repeat(64)
        val HASH_C = "c".repeat(64)
        val HASH_D = "d".repeat(64)
        val HASH_E = "e".repeat(64)
        val REQUEST_BINDING_HASH = "f".repeat(64)
    }
}

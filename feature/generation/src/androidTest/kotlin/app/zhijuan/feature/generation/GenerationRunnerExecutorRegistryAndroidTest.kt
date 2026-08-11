package app.zhijuan.feature.generation

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.zhijuan.core.database.LibraryDatabaseGuards
import app.zhijuan.core.database.ZhijuanDatabase
import app.zhijuan.core.database.generation.ChapterContextAssemblyBoundExecutorV1
import app.zhijuan.core.database.generation.ChapterFinalCommitStageBindingV1
import app.zhijuan.core.database.generation.ChapterFinalCommitStageSourceV1
import app.zhijuan.core.database.generation.ChapterMemoryExtractionJobFactory
import app.zhijuan.core.database.generation.ChapterMemoryExtractionJobSpec
import app.zhijuan.core.database.generation.ChapterMemoryExtractionSourceV1
import app.zhijuan.core.database.generation.GenerationJobSetup
import app.zhijuan.core.database.generation.GenerationJobSetupRepository
import app.zhijuan.core.database.generation.GenerationRunnerCurrentStageRouteSnapshot
import app.zhijuan.core.database.generation.GenerationRunnerExecutionLeaseRepository
import app.zhijuan.core.database.generation.GenerationRunnerQueueRepository
import app.zhijuan.core.database.generation.GenerationRunnerStageRoute
import app.zhijuan.core.database.generation.GenerationStageSetup
import app.zhijuan.core.database.generation.GenerationStateRepository
import app.zhijuan.core.database.generation.PersistedChapterContextAssemblyResult
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
import app.zhijuan.core.model.StandardErrorCode
import app.zhijuan.core.model.TitleSource
import app.zhijuan.core.task.ChapterContextBlockReason
import app.zhijuan.core.task.ChapterContextBudgetPolicyV1
import app.zhijuan.core.task.ChapterContextLimitSource
import app.zhijuan.core.task.FirstChapterGenerationMode
import app.zhijuan.core.task.FirstChapterProgressionPolicyV1
import app.zhijuan.core.task.JobEvent
import app.zhijuan.core.task.PromptBundleCatalogV1
import app.zhijuan.core.task.StageIdempotencyKey
import app.zhijuan.core.task.StageEvent
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
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
class GenerationRunnerExecutorRegistryAndroidTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: ZhijuanDatabase

    @Before
    fun setUp() {
        runBlocking {
            database = Room.inMemoryDatabaseBuilder(context, ZhijuanDatabase::class.java)
                .allowMainThreadQueries()
                .addCallback(LibraryDatabaseGuards.callback)
                .build()
                .also { it.openHelper.writableDatabase }
            BookCreationRepository(database).create(
                BookCreationSnapshotEntity(
                    snapshotId = "snapshot.registry",
                    rawInputJson = "{}",
                    normalizedInputJson = "{}",
                    inferenceProvenanceJson = "{}",
                    genrePayloadJson = "{}",
                    presentationProfileJson = "{}",
                    modelPreferenceJson = "{}",
                    schemaVersion = 1,
                    promptBundleVersion = "prompt.registry",
                    contentControlSchemaVersion = 1,
                    contentHash = "a".repeat(64),
                    createdAt = 1L,
                ),
                BookEntity(
                    bookId = BOOK_ID,
                    creationSnapshotId = "snapshot.registry",
                    title = "Registry fixture",
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
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun finalRoutePassesTheBoundExactTokenToTheOnlyRegisteredExecutor() = runBlocking {
        val snapshot = leaseRoute(finalSetup())
        val trace = mutableListOf<String>()
        val state = GenerationStateRepository(database)
        val executor = ChapterFinalCandidateCommitStageExecutorV1(
            ChapterFinalCandidateCommitStageExecutorDependenciesV1(
                findStage = state::findStage,
                acquireStageLease = { _, _, _ ->
                    throw AssertionError("Registry must use the bound final executor entry.")
                },
                commitFinalCandidate = { stageId, token, at ->
                    trace += "commit"
                    assertEquals(snapshot.executionLease.stageId, stageId)
                    assertEquals(snapshot.executionLease.stageLeaseToken, token)
                    assertEquals(5L, at)
                    FINAL_RESULT
                },
            ),
        )
        var contextCalls = 0
        val registry = GenerationRunnerExecutorRegistryV1(
            executor,
            ChapterContextAssemblyBoundExecutorV1 { _, _ ->
                contextCalls += 1
                error("not called")
            },
        )

        val result = registry.execute(snapshot, requestedAt = 5L)

        assertTrue(result is GenerationRunnerRegisteredExecutionResultV1.FinalChapterCommit)
        assertEquals(listOf("commit"), trace)
        assertEquals(0, contextCalls)
        assertEquals(
            setOf(
                GenerationRunnerStageRoute.FINAL_CHAPTER_COMMIT_V3,
                GenerationRunnerStageRoute.CHAPTER_CONTEXT_ASSEMBLY_V1,
            ),
            registry.registeredRoutes,
        )
        assertFalse(result.toString().contains(FINAL_STAGE_ID))
        assertFalse(result.toString().contains(OWNER_ID))
    }

    @Test
    fun contextRoutePassesTheOriginalBoundSnapshotToTheContextExecutorOnly() = runBlocking {
        val snapshot = leaseRoute(contextSetup())
        val state = GenerationStateRepository(database)
        val beforeJob = state.findJob(CONTEXT_JOB_ID)
        val beforeStage = state.findStage(CONTEXT_STAGE_ID)
        var finalCalls = 0
        var contextCalls = 0
        val finalExecutor = ChapterFinalCandidateCommitStageExecutorV1(
            ChapterFinalCandidateCommitStageExecutorDependenciesV1(
                findStage = {
                    finalCalls += 1
                    null
                },
                acquireStageLease = { _, _, _ ->
                    finalCalls += 1
                    error("not called")
                },
                commitFinalCandidate = { _, _, _ ->
                    finalCalls += 1
                    FINAL_RESULT
                },
            ),
        )
        val contextResult = PersistedChapterContextAssemblyResult.Blocked(
            reason = ChapterContextBlockReason.UNKNOWN_CONTEXT_LIMIT_REQUIRES_CONFIRMATION,
            standardErrorCode = StandardErrorCode.CONTEXT_TOO_LARGE,
            effectiveContextLimitTokens = null,
            inputBudgetTokens = null,
            requiredEstimatedTokens = null,
            missingKinds = emptySet(),
        )
        val registry = GenerationRunnerExecutorRegistryV1(
            finalExecutor,
            ChapterContextAssemblyBoundExecutorV1 { received, at ->
                contextCalls += 1
                assertTrue(received === snapshot)
                assertEquals(5L, at)
                contextResult
            },
        )

        val result = registry.execute(snapshot, requestedAt = 5L)

        assertTrue(result is GenerationRunnerRegisteredExecutionResultV1.ChapterContextAssembly)
        assertEquals(
            contextResult,
            (result as GenerationRunnerRegisteredExecutionResultV1.ChapterContextAssembly).result,
        )
        assertEquals(1, contextCalls)
        assertEquals(0, finalCalls)
        assertEquals(beforeJob, state.findJob(CONTEXT_JOB_ID))
        assertEquals(beforeStage, state.findStage(CONTEXT_STAGE_ID))
        assertFalse(result.toString().contains(CONTEXT_STAGE_ID))
        assertFalse(result.toString().contains(OWNER_ID))
    }

    @Test
    fun remoteRouteFailsUnregisteredBeforeAnyExecutorOrStateWrite() = runBlocking {
        val snapshot = leaseRoute(memorySetup())
        val state = GenerationStateRepository(database)
        val beforeJob = state.findJob(MEMORY_JOB_ID)
        val beforeStage = state.findStage(MEMORY_STAGE_ID)
        var finalCalls = 0
        val executor = ChapterFinalCandidateCommitStageExecutorV1(
            ChapterFinalCandidateCommitStageExecutorDependenciesV1(
                findStage = {
                    finalCalls += 1
                    null
                },
                acquireStageLease = { _, _, _ ->
                    finalCalls += 1
                    error("not called")
                },
                commitFinalCandidate = { _, _, _ ->
                    finalCalls += 1
                    FINAL_RESULT
                },
            ),
        )
        var contextCalls = 0
        val registry = GenerationRunnerExecutorRegistryV1(
            executor,
            ChapterContextAssemblyBoundExecutorV1 { _, _ ->
                contextCalls += 1
                error("not called")
            },
        )

        val failure = expectFailure { registry.execute(snapshot, requestedAt = 5L) }

        assertTrue(failure is GenerationRunnerRouteNotRegisteredException)
        assertEquals(
            GenerationRunnerStageRoute.FORMAL_CHAPTER_MEMORY_V1,
            (failure as GenerationRunnerRouteNotRegisteredException).route,
        )
        assertEquals(0, finalCalls)
        assertEquals(0, contextCalls)
        assertEquals(beforeJob, state.findJob(MEMORY_JOB_ID))
        assertEquals(beforeStage, state.findStage(MEMORY_STAGE_ID))
    }

    private suspend fun leaseRoute(setup: GenerationJobSetup): GenerationRunnerCurrentStageRouteSnapshot {
        GenerationJobSetupRepository(database).create(setup)
        val state = GenerationStateRepository(database)
        state.transitionJob(
            setup.jobId,
            GenerationJobStatus.CREATED,
            JobEvent.VALIDATION_PASSED,
            updatedAt = 2L,
        )
        val stageId = setup.stages.single().stageId
        state.transitionStage(
            stageId,
            GenerationStageStatus.PENDING,
            StageEvent.DEPENDENCIES_SATISFIED,
            updatedAt = 2L,
        )
        val queue = GenerationRunnerQueueRepository(database)
        val jobToken = queue.claimReadyJob(
            queue.scanReadyJobs(observedAt = 3L).candidates.single(),
            OWNER_ID,
            claimedAt = 3L,
        ).jobLeaseToken
        val leases = GenerationRunnerExecutionLeaseRepository(database)
        val acquired = leases.acquireCurrentStageLease(
            setup.jobId,
            jobToken,
            stageId,
            OWNER_ID,
            acquiredAt = 4L,
        )
        return leases.resolveCurrentStageRoute(
            setup.jobId,
            jobToken,
            stageId,
            acquired.stageLeaseToken,
            observedAt = 5L,
        )
    }

    private fun finalSetup(): GenerationJobSetup {
        val sourceBindingHash = "1".repeat(64)
        val mappingSnapshot = consistencyMappingSnapshot(sourceBindingHash)
        val stage = ChapterFinalCommitStageBindingV1.stageSetup(
            jobId = FINAL_JOB_ID,
            stageId = FINAL_STAGE_ID,
            source = ChapterFinalCommitStageSourceV1(
                candidateChapterVersionId = "candidate.version.registry",
                candidateContentHash = "c".repeat(64),
                chapterId = "chapter.registry",
                chapterIndex = 1,
                revisionIndex = 0,
                predecessorStageId = "stage.predecessor.registry",
                routeBindingHash = "d".repeat(64),
                expectedCurrentVersionId = "chapter.version.registry",
                maximumAutomaticRevisions = 1,
                candidateContentHashHistory = listOf("c".repeat(64)),
                consistencyRequestSourceBindingHash = sourceBindingHash,
                consistencyMappingSnapshotJson = mappingSnapshot,
                consistencyMappingSnapshotContentHash = sha256(mappingSnapshot),
            ),
        )
        return GenerationJobSetup(
            jobId = FINAL_JOB_ID,
            bookId = BOOK_ID,
            jobType = GenerationJobType.REVISE_CHAPTER,
            userIntentJson = "{}",
            budgetSnapshotJson = "{}",
            promptBundleVersion = "prompt.registry",
            stages = listOf(stage),
            createdAt = 1L,
        )
    }

    private fun memorySetup() = ChapterMemoryExtractionJobFactory.create(
        ChapterMemoryExtractionJobSpec(
            jobId = MEMORY_JOB_ID,
            stageId = MEMORY_STAGE_ID,
            bookId = BOOK_ID,
            userIntentJson = "{}",
            budgetSnapshotJson = "{}",
            source = ChapterMemoryExtractionSourceV1(
                chapterVersionId = "chapter.version.memory.registry",
                chapterContentHash = "b".repeat(64),
                chapterId = "chapter.memory.registry",
                chapterIndex = 1,
            ),
            createdAt = 1L,
        ),
    )

    private fun contextSetup(): GenerationJobSetup {
        val progressionBase = JsonObject(
            linkedMapOf(
                "schemaVersion" to JsonPrimitive(1),
                "policyVersion" to JsonPrimitive(FirstChapterProgressionPolicyV1.POLICY_VERSION),
                "mode" to JsonPrimitive(FirstChapterGenerationMode.FULL_PLANNING.name),
                "bookId" to JsonPrimitive(BOOK_ID),
                "chapterId" to JsonPrimitive(CONTEXT_TARGET_ID),
                "chapterIndex" to JsonPrimitive(2),
            ),
        )
        val progression = JsonObject(
            progressionBase + ("evidenceHash" to JsonPrimitive(sha256(progressionBase.toString()))),
        )
        val input = JsonObject(
            linkedMapOf(
                "schemaVersion" to JsonPrimitive(1),
                "sourcePolicyVersion" to JsonPrimitive("zhijuan.chapter-context-assembly-source.v1"),
                "promptBundleVersion" to JsonPrimitive(PromptBundleCatalogV1.BUNDLE_VERSION),
                "outputSchemaId" to JsonPrimitive(ChapterContextBudgetPolicyV1.MANIFEST_SCHEMA_ID),
                "dependencyStageIds" to JsonArray(emptyList()),
                "contextAssembly" to JsonObject(
                    linkedMapOf(
                        "policyVersion" to JsonPrimitive(ChapterContextBudgetPolicyV1.POLICY_VERSION),
                        "targetChapterIndex" to JsonPrimitive(2),
                        "promptBindingHash" to JsonPrimitive("e".repeat(64)),
                        "targetPhase" to JsonPrimitive(GenerationPhase.BUILD_CHAPTER_PLAN.name),
                        "contextLimitTokens" to JsonPrimitive(32_768),
                        "maximumOutputTokens" to JsonPrimitive(4_096),
                        "requestedOutputTokens" to JsonPrimitive(4_096),
                        "limitSource" to JsonPrimitive(ChapterContextLimitSource.OFFICIAL_METADATA.name),
                        "unknownLimitConfirmed" to JsonPrimitive(false),
                        "tokenizerFamily" to JsonPrimitive("TEST"),
                        "userAddition" to JsonNull,
                    ),
                ),
                "chapterProgressionGate" to progression,
            ),
        ).toString()
        val inputHash = sha256(input)
        return GenerationJobSetup(
            jobId = CONTEXT_JOB_ID,
            bookId = BOOK_ID,
            jobType = GenerationJobType.CONTINUE_BOOK,
            userIntentJson = "{}",
            budgetSnapshotJson = "{}",
            promptBundleVersion = PromptBundleCatalogV1.BUNDLE_VERSION,
            stages = listOf(
                GenerationStageSetup(
                    stageId = CONTEXT_STAGE_ID,
                    phase = GenerationPhase.ASSEMBLE_CONTEXT,
                    targetType = GenerationTargetType.CHAPTER,
                    targetId = CONTEXT_TARGET_ID,
                    inputVersionHash = inputHash,
                    idempotencyKey = StageIdempotencyKey.create(
                        jobId = CONTEXT_JOB_ID,
                        phase = GenerationPhase.ASSEMBLE_CONTEXT,
                        targetId = CONTEXT_TARGET_ID,
                        inputVersionHash = inputHash,
                    ).value,
                    maxAttempts = 1,
                    inputSourcesJson = input,
                ),
            ),
            createdAt = 1L,
        )
    }

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

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private suspend fun expectFailure(block: suspend () -> Unit): Throwable = try {
        block()
        throw AssertionError("Expected failure")
    } catch (error: Throwable) {
        if (error is AssertionError && error.message == "Expected failure") throw error
        error
    }

    private companion object {
        const val BOOK_ID = "book.registry"
        const val OWNER_ID = "runner.registry"
        const val FINAL_JOB_ID = "job.final.registry"
        const val FINAL_STAGE_ID = "stage.final.registry"
        const val MEMORY_JOB_ID = "job.memory.registry"
        const val MEMORY_STAGE_ID = "stage.memory.registry"
        const val CONTEXT_JOB_ID = "job.context.registry"
        const val CONTEXT_STAGE_ID = "stage.context.registry"
        const val CONTEXT_TARGET_ID = "chapter.context.registry"
        val FINAL_RESULT = app.zhijuan.core.database.generation.ChapterFinalCandidateCommitResultV1(
            chapterVersionId = "version.registry",
            chapterId = "chapter.registry",
            stageId = FINAL_STAGE_ID,
            revisionIndex = 0,
            replayed = false,
            isCurrentVersion = true,
            staleCascade = null,
        )
    }
}

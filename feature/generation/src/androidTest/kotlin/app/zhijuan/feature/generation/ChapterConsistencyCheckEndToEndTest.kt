package app.zhijuan.feature.generation

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.zhijuan.core.database.ZhijuanDatabase
import app.zhijuan.core.database.generation.GenerationJobSetup
import app.zhijuan.core.database.generation.GenerationJobSetupRepository
import app.zhijuan.core.database.generation.GenerationOutputValidationRepository
import app.zhijuan.core.database.generation.GenerationStageSetup
import app.zhijuan.core.database.generation.GenerationStateRepository
import app.zhijuan.core.database.generation.GenerationStreamingDraftRepository
import app.zhijuan.core.database.generation.RequestIntentDraft
import app.zhijuan.core.model.AdultStatus
import app.zhijuan.core.model.FadePolicy
import app.zhijuan.core.model.GenerationJobStatus
import app.zhijuan.core.model.GenerationJobType
import app.zhijuan.core.model.GenerationPhase
import app.zhijuan.core.model.GenerationStageStatus
import app.zhijuan.core.model.GenerationTargetType
import app.zhijuan.core.model.StoryEntityType
import app.zhijuan.core.security.AndroidProtectedArtifactStore
import app.zhijuan.core.task.ChapterDeterministicConsistencyFactsV1
import app.zhijuan.core.task.ConsistencyEvidenceRange
import app.zhijuan.core.task.DeterministicEntityFactV1
import app.zhijuan.core.task.DeterministicEntityReferenceV1
import app.zhijuan.core.task.JobEvent
import app.zhijuan.core.task.PromptInstruction
import app.zhijuan.core.task.SceneExecutionContract
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChapterConsistencyCheckEndToEndTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: ZhijuanDatabase
    private lateinit var artifacts: AndroidProtectedArtifactStore
    private lateinit var states: GenerationStateRepository
    private lateinit var drafts: GenerationStreamingDraftRepository
    private lateinit var outputs: GenerationOutputValidationRepository

    @Before
    fun setUp() {
        artifacts = AndroidProtectedArtifactStore(context)
        cleanArtifacts()
        database = Room.inMemoryDatabaseBuilder(context, ZhijuanDatabase::class.java)
            .allowMainThreadQueries()
            .build()
            .also { it.openHelper.writableDatabase }
        states = GenerationStateRepository(database)
        drafts = GenerationStreamingDraftRepository(database, artifacts)
        outputs = GenerationOutputValidationRepository(database, artifacts)
        seedBook()
        runBlocking {
            BudgetedGenerationTestSupport.seedBudgetedRequestEnvironment(
                database = database,
                bookId = BOOK_ID,
                connectionId = "connection.consistency",
            )
        }
    }

    @After
    fun tearDown() {
        runCatching { cleanArtifacts() }
        database.close()
    }

    @Test
    fun exactAllPassMatrixStreamsThroughAuditedValidation() = runBlocking {
        val bound = bound("job.check.valid", "stage.check.valid", "attempt.check.valid")
        createRunningJob(bound, 100L)
        val persisted = prepare(bound, "ledger.check.valid", 103L)
        val result = coordinator(110L).execute(
            persistedRequest = persisted,
            adapter = ConsistencyFakeAdapter(events(allPassJson(bound.expectation))),
            profile = profile(),
            boundRequest = bound,
        )

        assertTrue(result is ChapterConsistencyCheckResultV1.Accepted)
        result as ChapterConsistencyCheckResultV1.Accepted
        val gate = ChapterConsistencyAcceptanceGateV1.evaluate(
            bound.localReport,
            result.report,
            bound.expectation,
            bound.sceneContract,
        )
        assertEquals(ChapterConsistencyGateDecisionV1.ACCEPT_CANDIDATE, gate.decision)
        assertEquals("SUCCEEDED", scalarString("SELECT status FROM request_attempt WHERE attempt_id = 'attempt.check.valid'"))
        assertEquals("COMMITTING", scalarString("SELECT status FROM generation_stage WHERE stage_id = 'stage.check.valid'"))
    }

    @Test
    fun omittedCriterionTriggersOneBoundedRepairAndNoReportRow() = runBlocking {
        val bound = bound("job.check.invalid", "stage.check.invalid", "attempt.check.invalid")
        createRunningJob(bound, 200L)
        val persisted = prepare(bound, "ledger.check.invalid", 203L)
        val valid = Json.parseToJsonElement(allPassJson(bound.expectation)) as JsonObject
        val criteria = valid.getValue("criterionResults") as JsonArray
        val invalid = JsonObject(valid + ("criterionResults" to JsonArray(criteria.dropLast(1)))).toString()
        val result = coordinator(210L).execute(
            persistedRequest = persisted,
            adapter = ConsistencyFakeAdapter(events(invalid)),
            profile = profile(),
            boundRequest = bound,
        )

        assertTrue(result is ChapterConsistencyCheckResultV1.RepairRequired)
        assertEquals("RETRY_WAIT", scalarString("SELECT status FROM generation_stage WHERE stage_id = 'stage.check.invalid'"))
        assertEquals("FINAL", scalarString("SELECT status FROM usage_ledger WHERE attempt_id = 'attempt.check.invalid'"))
        assertEquals(0L, scalarLong("SELECT COUNT(*) FROM consistency_report"))
    }

    @Test
    fun differentFrozenCandidateCannotReusePersistedRequestIntent() = runBlocking {
        val original = bound("job.check.stale", "stage.check.stale", "attempt.check.stale")
        createRunningJob(original, 300L)
        val persisted = prepare(original, "ledger.check.stale", 303L)
        val changed = bound(
            "job.check.stale",
            "stage.check.stale",
            "attempt.check.stale",
            body = "这是另一份不能借用旧请求凭据的候选正文。".repeat(40),
        )
        val calls = AtomicInteger(0)

        val failure = runCatching {
            coordinator(310L).execute(
                persistedRequest = persisted,
                adapter = ConsistencyFakeAdapter(events(allPassJson(changed.expectation))) {
                    calls.incrementAndGet()
                },
                profile = profile(),
                boundRequest = changed,
            )
        }.exceptionOrNull()

        assertTrue(requireNotNull(failure).message.orEmpty().contains("frozen check source"))
        assertEquals(0, calls.get())
        assertEquals("INTENT_RECORDED", scalarString("SELECT status FROM request_attempt WHERE attempt_id = 'attempt.check.stale'"))
    }

    private suspend fun createRunningJob(bound: BoundChapterConsistencyCheckRequest, createdAt: Long) {
        val setup = GenerationJobSetup(
            jobId = bound.request.generationId,
            bookId = BOOK_ID,
            jobType = GenerationJobType.CONTINUE_BOOK,
            userIntentJson = "{\"mode\":\"fixture\"}",
            budgetSnapshotJson = "{\"mode\":\"fixture\"}",
            promptBundleVersion = "fixture.consistency.v1",
            stages = listOf(
                GenerationStageSetup(
                    stageId = bound.request.stageId,
                    phase = GenerationPhase.CHECK_CONSISTENCY,
                    targetType = GenerationTargetType.CHAPTER,
                    targetId = CHAPTER_ID,
                    inputVersionHash = bound.sourceBindingHash,
                    idempotencyKey = "idempotency.${bound.request.stageId}",
                    maxAttempts = 2,
                    inputSourcesJson = "{\"fixture\":true}",
                ),
            ),
            createdAt = createdAt,
        )
        GenerationJobSetupRepository(database).create(setup)
        states.transitionJob(setup.jobId, GenerationJobStatus.CREATED, JobEvent.VALIDATION_PASSED, createdAt + 1L)
        states.transitionStage(bound.request.stageId, GenerationStageStatus.PENDING, StageEvent.DEPENDENCIES_SATISFIED, createdAt + 1L)
        states.acquireJobLease(setup.jobId, "worker.${setup.jobId}", createdAt + 2L)
        states.acquireStageLease(bound.request.stageId, "worker.${bound.request.stageId}", createdAt + 2L)
    }

    private suspend fun prepare(
        bound: BoundChapterConsistencyCheckRequest,
        ledgerId: String,
        createdAt: Long,
    ) = drafts.prepareBeforeSend(
        RequestIntentDraft(
            attemptId = bound.request.attemptId,
            usageLedgerId = ledgerId,
            stageId = bound.request.stageId,
            retryParentAttemptId = null,
            connectionSnapshotJson = "{\"referenceId\":\"fixture-connection\"}",
            modelSnapshotJson = MODEL_SNAPSHOT,
            protocolSnapshotJson = "{\"protocol\":\"fixture\"}",
            inputHash = bound.sourceBindingHash,
            streamDraftRef = null,
            createdAt = createdAt,
        ),
        BudgetedGenerationTestSupport.budgetedDraft(
            attemptId = bound.request.attemptId,
            connectionId = "connection.consistency",
        ),
        requireNotNull(states.findStage(bound.request.stageId)?.leaseToken),
    )

    private fun bound(
        jobId: String,
        stageId: String,
        attemptId: String,
        body: String = BODY,
    ): BoundChapterConsistencyCheckRequest {
        val prepared = ChapterConsistencyCheckRequestFactoryV1.prepare(
            ChapterConsistencyCheckRequestSpec(
                requestId = "request.$attemptId",
                generationId = jobId,
                stageId = stageId,
                attemptId = attemptId,
                modelId = ProviderModelId.from("local-fake"),
                sourceChapterVersionId = VERSION_ID,
                sourceChapterContentHash = sha256(body),
                chapterId = CHAPTER_ID,
                chapterIndex = 1,
                chapterContent = body,
                minimumBodyCodePoints = 100,
                deterministicFacts = ChapterDeterministicConsistencyFactsV1(
                    currentChapterIndex = 1,
                    expectedChapterIndex = 1,
                    entities = listOf(
                        DeterministicEntityFactV1(
                            "char.hero",
                            StoryEntityType.CHARACTER,
                            AdultStatus.CONFIRMED_ADULT,
                            24,
                        ),
                    ),
                    references = listOf(
                        DeterministicEntityReferenceV1("char.hero", true, ConsistencyEvidenceRange(0, 4)),
                    ),
                    characterReturns = emptyList(),
                    locationConstraints = emptyList(),
                    itemOwnershipConstraints = emptyList(),
                    timelineConstraints = emptyList(),
                    requiredEvents = emptyList(),
                ),
                sceneExecutionContract = SceneExecutionContract.Allowed(
                    automatic = true,
                    intimacyDetailLevel = 4,
                    fadePolicy = FadePolicy.AVOID,
                    strictBodyAndSensoryContinuity = true,
                    requiredKeyProcessCoveragePercent = 100,
                    fadeSubstitutionAllowed = false,
                    requiresStateContinuity = true,
                    requiresRelevantAftermath = true,
                    instructions = listOf(PromptInstruction("scene.fixture", "fixture")),
                ),
                sceneParticipantEntityIds = setOf("char.hero"),
                requiredProcessNodeIds = setOf("process.1"),
                knownEntities = listOf(
                    ChapterConsistencyKnownEntityV1(
                        entityId = "char.hero",
                        canonicalName = "主角",
                        entityType = StoryEntityType.CHARACTER,
                        adultStatus = AdultStatus.CONFIRMED_ADULT,
                        ageYears = 24,
                        realIdentifiablePerson = false,
                    ),
                ),
                evidenceItems = listOf(
                    ChapterConsistencyEvidenceItemV1(
                        "fact.1",
                        ChapterConsistencyEvidenceKindV1.HARD_FACT,
                        "{\"value\":\"fixture\"}",
                    ),
                ),
                maximumOutputTokens = 2_048,
                timeouts = ProviderTimeoutPolicy(1_000, 1_000, 1_000, 2_000),
            ),
        )
        return (prepared as ChapterConsistencyRequestPreparationV1.Ready).boundRequest
    }

    private fun allPassJson(expectation: ChapterConsistencyExpectation): String = JsonObject(
        linkedMapOf(
            "schemaVersion" to JsonPrimitive(1),
            "sourceChapterVersionId" to JsonPrimitive(expectation.sourceChapterVersionId),
            "sourceChapterContentHash" to JsonPrimitive(expectation.sourceChapterContentHash),
            "chapterId" to JsonPrimitive(expectation.chapterId),
            "chapterIndex" to JsonPrimitive(expectation.chapterIndex),
            "checkSourceSnapshotHash" to JsonPrimitive(expectation.checkSourceSnapshotHash),
            "sceneContractHash" to JsonPrimitive(expectation.sceneContractHash),
            "criterionResults" to JsonArray(expectation.expectedCriteria.map { criterion ->
                JsonObject(
                    linkedMapOf(
                        "criterion" to JsonPrimitive(criterion.name),
                        "status" to JsonPrimitive("PASS"),
                        "issueIds" to JsonArray(emptyList()),
                    ),
                )
            }),
            "requiredProcessResults" to JsonArray(expectation.requiredProcessNodeIds.sorted().map { nodeId ->
                JsonObject(
                    linkedMapOf(
                        "requiredProcessNodeId" to JsonPrimitive(nodeId),
                        "status" to JsonPrimitive("COVERED"),
                        "issueId" to JsonNull,
                    ),
                )
            }),
            "issues" to JsonArray(emptyList()),
        ),
    ).toString()

    private fun events(json: String) = listOf(
        ProviderStreamEvent.Started(),
        ProviderStreamEvent.StructuredDelta(SensitiveProviderText.from(json)),
        ProviderStreamEvent.UsageUpdate(
            ProviderUsage(
                inputTokens = 120L,
                outputTokens = 80L,
                cachedInputTokens = null,
                cachedWriteTokens = null,
                reasoningTokens = null,
                totalTokens = 200L,
                quality = ProviderUsageQuality.PROVIDER_REPORTED,
            ),
        ),
        ProviderStreamEvent.Completed(ProviderFinishReason.STOP),
    )

    private fun coordinator(startAt: Long) = ChapterConsistencyCheckCoordinatorV1(
        executor = AuditedStreamingProviderExecutor(drafts, outputs, ConsistencyClock(startAt)),
        validation = StructuredOutputValidationCoordinator(outputs),
        clock = ConsistencyClock(startAt + 20L),
    )

    private fun profile() = ProviderConnectionProfile.create(
        connectionId = "connection.consistency",
        protocol = ProviderProtocol.OPENAI_CHAT_COMPAT,
        baseUrl = "https://example.invalid",
    )

    private fun seedBook() {
        val sql = database.openHelper.writableDatabase
        sql.execSQL("INSERT INTO book_creation_snapshot VALUES ('snapshot.check','{}','{}','{}','{}','{}','{}',1,'fixture.consistency.v1',1,'snapshot-check-hash',1)")
        sql.execSQL(
            """
            INSERT INTO book (
              book_id,creation_snapshot_id,title,title_source,status,length_mode,target_characters,target_chapters,
              minimum_chapters,length_policy_schema_version,branched_from_book_id,branched_from_chapter_version_id,
              completed_chapter_count,generation_status_summary,archived_at,deleted_at,created_at,updated_at
            ) VALUES ('$BOOK_ID','snapshot.check','检查测试','USER','DRAFT','SHORT',80000,80,80,1,NULL,NULL,0,'checking',NULL,NULL,1,1)
            """.trimIndent(),
        )
    }

    private fun scalarString(query: String): String? =
        database.openHelper.readableDatabase.query(query).use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
        }

    private fun scalarLong(query: String): Long? =
        database.openHelper.readableDatabase.query(query).use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
        }

    private fun cleanArtifacts() {
        artifacts.unlockAfterAuthentication()
        artifacts.listArtifactReferenceIds().forEach(artifacts::delete)
    }

    private companion object {
        const val BOOK_ID = "book.check"
        const val CHAPTER_ID = "chapter.check.1"
        const val VERSION_ID = "version.candidate.1"
        const val MODEL_SNAPSHOT = "{\"model\":\"local-fake\"}"
        val BODY = "这是用于一致性检查端到端验证的普通候选正文。".repeat(40)

        fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}

private class ConsistencyClock(startAt: Long) : GenerationExecutionClock {
    private val next = AtomicLong(startAt)
    override fun nowMillis(): Long = next.getAndIncrement()
}

private class ConsistencyFakeAdapter(
    private val events: List<ProviderStreamEvent>,
    private val onGenerate: () -> Unit = {},
) : ProviderAdapter {
    override val protocol = ProviderProtocol.OPENAI_CHAT_COMPAT
    override val adapterVersion = "consistency-test-1"

    override suspend fun testConnection(profile: ProviderConnectionProfile): ConnectionTestResult =
        error("Not used by consistency tests.")

    override suspend fun listModels(profile: ProviderConnectionProfile): ModelListResult =
        error("Not used by consistency tests.")

    override suspend fun getCapabilities(
        profile: ProviderConnectionProfile,
        modelId: ProviderModelId,
    ): CapabilityResult = error("Not used by consistency tests.")

    override fun generate(
        profile: ProviderConnectionProfile,
        request: GenerationRequest,
    ): Flow<ProviderStreamEvent> = flow {
        onGenerate()
        events.forEach { emit(it) }
    }

    override suspend fun cancel(
        profile: ProviderConnectionProfile,
        requestId: String,
    ): ProviderCancellationResult = ProviderCancellationResult.ALREADY_TERMINAL
}

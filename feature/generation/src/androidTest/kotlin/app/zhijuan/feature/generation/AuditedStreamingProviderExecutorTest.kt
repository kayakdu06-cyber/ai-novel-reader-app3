package app.zhijuan.feature.generation

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.zhijuan.core.database.ZhijuanDatabase
import app.zhijuan.core.database.generation.GenerationControlDisposition
import app.zhijuan.core.database.generation.GenerationControlRepository
import app.zhijuan.core.database.generation.ChapterDraftContinuationRepository
import app.zhijuan.core.database.generation.ChapterDraftTruncationAction
import app.zhijuan.core.database.generation.GenerationExecutionControl
import app.zhijuan.core.database.generation.GenerationLeasePolicy
import app.zhijuan.core.database.generation.GenerationOutputValidationRepository
import app.zhijuan.core.database.generation.GenerationRecoveryDisposition
import app.zhijuan.core.database.generation.GenerationStateRepository
import app.zhijuan.core.database.generation.GenerationStreamingDraftRepository
import app.zhijuan.core.database.generation.RequestIntentDraft
import app.zhijuan.core.database.generation.RecoveredChapterDraftSettlement
import app.zhijuan.core.database.generation.PreparedChapterDraftContinuation
import app.zhijuan.core.database.generation.StreamingDraftRecoveryDisposition
import app.zhijuan.core.model.GenerationJobStatus
import app.zhijuan.core.model.GenerationStageStatus
import app.zhijuan.core.model.RequestAttemptStatus
import app.zhijuan.core.model.StandardErrorCode
import app.zhijuan.core.model.UsageLedgerStatus
import app.zhijuan.core.security.AndroidProtectedArtifactStore
import app.zhijuan.core.security.ProtectedArtifactType
import app.zhijuan.core.task.StageEvent
import app.zhijuan.core.task.JobEvent
import app.zhijuan.core.task.ChapterDraftContinuationPolicyV1
import app.zhijuan.core.task.ChapterDraftContinuationBlockReason
import app.zhijuan.provider.common.CapabilityResult
import app.zhijuan.provider.common.ConnectionTestResult
import app.zhijuan.provider.common.GenerationParameters
import app.zhijuan.provider.common.GenerationRequest
import app.zhijuan.provider.common.ModelListResult
import app.zhijuan.provider.common.PromptLayer
import app.zhijuan.provider.common.PromptPart
import app.zhijuan.provider.common.ProviderAdapter
import app.zhijuan.provider.common.ProviderCancellationResult
import app.zhijuan.provider.common.ProviderConnectionProfile
import app.zhijuan.provider.common.ProviderFinishReason
import app.zhijuan.provider.common.ProviderJsonSchema
import app.zhijuan.provider.common.ProviderModelId
import app.zhijuan.provider.common.ProviderPrompt
import app.zhijuan.provider.common.ProviderProtocol
import app.zhijuan.provider.common.ProviderRemoteRequestId
import app.zhijuan.provider.common.ProviderRequestRecoveryCapability
import app.zhijuan.provider.common.ProviderRequestRecoveryResult
import app.zhijuan.provider.common.ProviderStreamEvent
import app.zhijuan.provider.common.ProviderTimeoutPolicy
import app.zhijuan.provider.common.ProviderUsage
import app.zhijuan.provider.common.ProviderUsageQuality
import app.zhijuan.provider.common.SensitiveProviderText
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AuditedStreamingProviderExecutorTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: ZhijuanDatabase
    private lateinit var artifactStore: AndroidProtectedArtifactStore
    private lateinit var drafts: GenerationStreamingDraftRepository
    private lateinit var outputs: GenerationOutputValidationRepository

    @Before
    fun setUp() = runBlocking {
        artifactStore = AndroidProtectedArtifactStore(context)
        cleanArtifacts()
        database = Room.inMemoryDatabaseBuilder(context, ZhijuanDatabase::class.java)
            .allowMainThreadQueries()
            .build()
            .also { it.openHelper.writableDatabase }
        seedGenerationRows()
        val states = GenerationStateRepository(database)
        states.transitionJob(
            jobId = "job-runtime",
            expectedStatus = GenerationJobStatus.CREATED,
            event = JobEvent.VALIDATION_PASSED,
            updatedAt = 2L,
        )
        states.acquireJobLease("job-runtime", "job-executor-a", now = 3L)
        states.transitionStage(
            stageId = STAGE_ID,
            expectedStatus = GenerationStageStatus.PENDING,
            event = StageEvent.DEPENDENCIES_SATISFIED,
            updatedAt = 2L,
        )
        states.acquireStageLease(STAGE_ID, "executor-a", now = 3L)
        drafts = GenerationStreamingDraftRepository(database, artifactStore)
        outputs = GenerationOutputValidationRepository(database, artifactStore)
    }

    @After
    fun tearDown() {
        runCatching { cleanArtifacts() }
        database.close()
    }

    @Test
    fun providerFlowStartsOnlyAfterPersistedAuditAndFinishesInEncryptedDraft() = runBlocking {
        val prepared = prepareRequest("attempt-runtime-1", "ledger-runtime-1")
        var openedAfterAudit = false
        val adapter = FakeAdapter(
            onGenerate = {
                val status = scalarString(
                    "SELECT status FROM request_attempt WHERE attempt_id = 'attempt-runtime-1'",
                )
                val draftRef = scalarString(
                    "SELECT stream_draft_ref FROM request_attempt WHERE attempt_id = 'attempt-runtime-1'",
                )
                openedAfterAudit = status == "INTENT_RECORDED" &&
                    draftRef != null &&
                    artifactStore.descriptor(draftRef).type == ProtectedArtifactType.STREAM_DRAFT
            },
            events = listOf(
                ProviderStreamEvent.Started(ProviderRemoteRequestId.from("remote-runtime-1")),
                ProviderStreamEvent.TextDelta(SensitiveProviderText.from("第一段，")),
                ProviderStreamEvent.TextDelta(SensitiveProviderText.from("第二段。")),
                ProviderStreamEvent.UsageUpdate(
                    ProviderUsage(
                        inputTokens = 10,
                        outputTokens = 5,
                        cachedInputTokens = null,
                        cachedWriteTokens = null,
                        reasoningTokens = null,
                        totalTokens = 15,
                        quality = ProviderUsageQuality.PROVIDER_REPORTED,
                    ),
                ),
                ProviderStreamEvent.Completed(ProviderFinishReason.STOP),
            ),
        )
        val result = AuditedStreamingProviderExecutor(
            drafts = drafts,
            outputs = outputs,
            clock = IncrementingClock(4L),
        ).execute(prepared, adapter, profile(), generationRequest("attempt-runtime-1"))

        assertTrue(openedAfterAudit)
        assertTrue(result is AuditedStreamingExecutionResult.Completed)
        assertEquals(15L, result.latestUsage?.totalTokens)
        assertEquals(RequestAttemptStatus.SUCCEEDED, drafts.inspectAttempt("attempt-runtime-1", 20L).attemptStatus)
        assertEquals(GenerationStageStatus.VALIDATING, GenerationStateRepository(database).findStage(STAGE_ID)?.status)
        assertEquals(
            StreamingDraftRecoveryDisposition.RECOVERY_REQUIRED,
            drafts.inspectAttempt("attempt-runtime-1", 20L).disposition,
        )
        val ref = artifactStore.listArtifactReferenceIds().single()
        artifactStore.readBytes(ref, ProtectedArtifactType.STREAM_DRAFT).use { lease ->
            lease.withBytes { bytes ->
                assertEquals("第一段，第二段。", bytes.toString(Charsets.UTF_8))
            }
        }
    }

    @Test
    fun malformedProviderFlowFailsBeforeAnyUnstartedDeltaCanReachDraft() = runBlocking {
        val prepared = prepareRequest("attempt-runtime-2", "ledger-runtime-2")
        val adapter = FakeAdapter(
            onGenerate = {
                assertEquals(
                    "INTENT_RECORDED",
                    scalarString("SELECT status FROM request_attempt WHERE attempt_id = 'attempt-runtime-2'"),
                )
            },
            events = listOf(
                ProviderStreamEvent.TextDelta(SensitiveProviderText.from("must-not-persist")),
            ),
        )

        val failure = expectFailure {
            AuditedStreamingProviderExecutor(
                drafts = drafts,
                outputs = outputs,
                clock = IncrementingClock(4L),
            ).execute(prepared, adapter, profile(), generationRequest("attempt-runtime-2"))
        }
        assertTrue(failure is ProviderStreamContractException)
        val recovery = drafts.inspectAttempt("attempt-runtime-2", 20L)
        assertEquals(RequestAttemptStatus.UNKNOWN_RESULT, recovery.attemptStatus)
        assertEquals(0L, recovery.plaintextBytes)
        assertEquals(GenerationJobStatus.NEEDS_ACTION, GenerationStateRepository(database).findJob("job-runtime")?.status)
        assertEquals(UsageLedgerStatus.FINAL.name, scalarString(
            "SELECT status FROM usage_ledger WHERE attempt_id = 'attempt-runtime-2'",
        ))
    }

    @Test
    fun unknownProviderResultIsPersistedBeforeExecutorReturns() = runBlocking {
        val prepared = prepareRequest("attempt-runtime-unknown", "ledger-runtime-unknown")
        val result = AuditedStreamingProviderExecutor(
            drafts = drafts,
            outputs = outputs,
            clock = IncrementingClock(4L),
        ).execute(
            prepared,
            FakeAdapter(
                onGenerate = {},
                events = listOf(
                    ProviderStreamEvent.Started(ProviderRemoteRequestId.from("remote-runtime-unknown")),
                    ProviderStreamEvent.TextDelta(SensitiveProviderText.from("encrypted partial")),
                    ProviderStreamEvent.Failed(
                        code = app.zhijuan.core.model.StandardErrorCode.UNKNOWN_RESULT,
                        requestState = app.zhijuan.core.model.FailureRequestState.RESPONSE_STARTED,
                    ),
                ),
            ),
            profile(),
            generationRequest("attempt-runtime-unknown"),
        )

        assertTrue(result is AuditedStreamingExecutionResult.Failed)
        assertEquals(RequestAttemptStatus.UNKNOWN_RESULT.name, scalarString(
            "SELECT status FROM request_attempt WHERE attempt_id = 'attempt-runtime-unknown'",
        ))
        assertEquals(GenerationStageStatus.UNKNOWN_RESULT.name, scalarString(
            "SELECT status FROM generation_stage WHERE stage_id = '$STAGE_ID'",
        ))
        assertEquals(GenerationJobStatus.NEEDS_ACTION.name, scalarString(
            "SELECT status FROM generation_job WHERE job_id = 'job-runtime'",
        ))
        assertEquals(UsageLedgerStatus.FINAL.name, scalarString(
            "SELECT status FROM usage_ledger WHERE attempt_id = 'attempt-runtime-unknown'",
        ))
    }

    @Test
    fun chapterLengthClassificationIsDurableBeforeContinuationSettlement() = runBlocking {
        val prepared = prepareRequest("attempt-length-durable", "ledger-length-durable")
        val execution = AuditedStreamingProviderExecutor(
            drafts = drafts,
            outputs = outputs,
            clock = IncrementingClock(4L),
        ).execute(
            persistedRequest = prepared,
            adapter = FakeAdapter(
                onGenerate = {},
                events = listOf(
                    ProviderStreamEvent.Started(),
                    ProviderStreamEvent.StructuredDelta(
                        SensitiveProviderText.from(truncatedBodyEnvelope("可恢复的候选正文。".repeat(20))),
                    ),
                    ProviderStreamEvent.Completed(ProviderFinishReason.LENGTH),
                ),
            ),
            profile = profile(),
            request = generationRequest("attempt-length-durable"),
            payloadDecoder = ChapterDraftV1StreamPayloadDecoder(),
        ) as AuditedStreamingExecutionResult.Completed

        assertEquals(ProviderPayloadCompletion.TRUNCATED_SAFE_PREFIX, execution.payloadCompletion)
        assertEquals(StandardErrorCode.OUTPUT_TRUNCATED.name, scalarString(
            "SELECT standard_error_code FROM request_attempt WHERE attempt_id = 'attempt-length-durable'",
        ))
        assertEquals(StandardErrorCode.OUTPUT_TRUNCATED.name, scalarString(
            "SELECT standard_error_code FROM generation_stage WHERE stage_id = '$STAGE_ID'",
        ))
        assertEquals(GenerationStageStatus.VALIDATING, GenerationStateRepository(database).findStage(STAGE_ID)?.status)
        assertEquals("executor-a", scalarString(
            "SELECT lease_owner_id FROM generation_stage WHERE stage_id = '$STAGE_ID'",
        ))
        assertEquals(UsageLedgerStatus.PROVISIONAL.name, scalarString(
            "SELECT status FROM usage_ledger WHERE attempt_id = 'attempt-length-durable'",
        ))

        val recovered = ChapterDraftContinuationRepository(database, artifactStore)
            .recoverPendingSettlement("attempt-length-durable", recoveredAt = 70_000L)
            as RecoveredChapterDraftSettlement.Truncated
        assertEquals(ChapterDraftTruncationAction.CONTINUE_AUTOMATICALLY, recovered.settlement.action)
        assertEquals(GenerationStageStatus.RETRY_WAIT, GenerationStateRepository(database).findStage(STAGE_ID)?.status)
        assertEquals(UsageLedgerStatus.FINAL.name, scalarString(
            "SELECT status FROM usage_ledger WHERE attempt_id = 'attempt-length-durable'",
        ))

        val replay = ChapterDraftContinuationRepository(database, artifactStore)
            .recoverPendingSettlement("attempt-length-durable", recoveredAt = 70_001L)
            as RecoveredChapterDraftSettlement.Truncated
        assertTrue(replay.settlement.replayed)
    }

    @Test
    fun chapterLengthContinuationSeedsPreviousDraftStripsExactAnchorAndKeepsAttemptUsage() = runBlocking {
        val partial = "第一段建立人物位置与动作。".repeat(16) + "这一段还没有结束"
        val firstPrepared = prepareRequest("attempt-chapter-length-1", "ledger-chapter-length-1")
        val firstCoordinator = ChapterDraftStreamingCoordinator(
            executor = AuditedStreamingProviderExecutor(drafts, outputs, IncrementingClock(4L)),
            continuations = ChapterDraftContinuationRepository(database, artifactStore),
            clock = IncrementingClock(20L),
        )
        val firstResult = firstCoordinator.executeInitial(
            persistedRequest = firstPrepared,
            adapter = FakeAdapter(
                onGenerate = {},
                events = listOf(
                    ProviderStreamEvent.Started(),
                    ProviderStreamEvent.StructuredDelta(
                        SensitiveProviderText.from(truncatedBodyEnvelope(partial)),
                    ),
                    ProviderStreamEvent.UsageUpdate(providerUsage(120, 64)),
                    ProviderStreamEvent.Completed(ProviderFinishReason.LENGTH),
                ),
            ),
            profile = profile(),
            request = generationRequest("attempt-chapter-length-1"),
        ) as ChapterDraftStreamingResult.ContinuationSettled

        assertEquals(ChapterDraftTruncationAction.CONTINUE_AUTOMATICALLY, firstResult.settlement.action)
        assertEquals(1, firstResult.settlement.continuationIndex)
        assertEquals(GenerationStageStatus.RETRY_WAIT, GenerationStateRepository(database).findStage(STAGE_ID)?.status)
        assertEquals(GenerationJobStatus.RUNNING, GenerationStateRepository(database).findJob("job-runtime")?.status)
        assertEquals("OUTPUT_TRUNCATED", scalarString(
            "SELECT standard_error_code FROM request_attempt WHERE attempt_id = 'attempt-chapter-length-1'",
        ))
        assertEquals(UsageLedgerStatus.FINAL.name, scalarString(
            "SELECT status FROM usage_ledger WHERE attempt_id = 'attempt-chapter-length-1'",
        ))

        val states = GenerationStateRepository(database)
        states.transitionStage(
            stageId = STAGE_ID,
            expectedStatus = GenerationStageStatus.RETRY_WAIT,
            event = StageEvent.RETRY_DELAY_ELAPSED,
            updatedAt = 21L,
        )
        states.acquireStageLease(STAGE_ID, "executor-continuation", now = 22L)
        val parentHash = requireNotNull(scalarString(
            "SELECT output_hash FROM request_attempt WHERE attempt_id = 'attempt-chapter-length-1'",
        ))
        val continuationHash = ChapterDraftContinuationPolicyV1.continuationInputHash(
            stageInputVersionHash = "a".repeat(64),
            parentOutputHash = parentHash,
            anchorHash = requireNotNull(firstResult.settlement.anchorHash),
            continuationIndex = 1,
        )
        val continuationRepository = ChapterDraftContinuationRepository(database, artifactStore)
        val preparedContinuation = continuationRepository.prepareContinuationBeforeSend(
            draft = RequestIntentDraft(
                attemptId = "attempt-chapter-length-2",
                usageLedgerId = "ledger-chapter-length-2",
                stageId = STAGE_ID,
                retryParentAttemptId = "attempt-chapter-length-1",
                connectionSnapshotJson = "{\"secretRefId\":\"fixture-ref\"}",
                modelSnapshotJson = "{\"model\":\"fixture\"}",
                protocolSnapshotJson = "{\"protocol\":\"fixture\"}",
                inputHash = continuationHash,
                streamDraftRef = null,
                dailyPeriodKey = "2026-08-03|Asia/Shanghai",
                createdAt = 22L,
            ),
            leaseToken = requireNotNull(states.findStage(STAGE_ID)?.leaseToken),
        )
        val unboundProviderCalls = AtomicInteger(0)
        val unboundError = expectFailure {
            ChapterDraftStreamingCoordinator(
                executor = AuditedStreamingProviderExecutor(drafts, outputs, IncrementingClock(23L)),
                continuations = continuationRepository,
                clock = IncrementingClock(40L),
            ).executeContinuation(
                prepared = preparedContinuation,
                adapter = FakeAdapter(
                    onGenerate = { unboundProviderCalls.incrementAndGet() },
                    events = emptyList(),
                ),
                profile = profile(),
                request = generationRequest("attempt-chapter-length-2"),
            )
        }
        assertTrue(unboundError.message.orEmpty().contains("exact prefix"))
        assertEquals(0, unboundProviderCalls.get())
        val continuationBody = preparedContinuation.withAnchor { anchor ->
            anchor + "，随后动作自然完成并留下新的章节钩子。"
        }
        val secondResult = ChapterDraftStreamingCoordinator(
            executor = AuditedStreamingProviderExecutor(drafts, outputs, IncrementingClock(23L)),
            continuations = continuationRepository,
            clock = IncrementingClock(40L),
        ).executeContinuation(
            prepared = preparedContinuation,
            adapter = FakeAdapter(
                onGenerate = {},
                events = listOf(
                    ProviderStreamEvent.Started(),
                    ProviderStreamEvent.StructuredDelta(
                        SensitiveProviderText.from(completeBodyEnvelope(continuationBody)),
                    ),
                    ProviderStreamEvent.UsageUpdate(providerUsage(80, 32)),
                    ProviderStreamEvent.Completed(ProviderFinishReason.STOP),
                ),
            ),
            profile = profile(),
            request = generationRequest(
                "attempt-chapter-length-2",
                ChapterDraftOutputContractV1.continuationParts(preparedContinuation),
            ),
        ) as ChapterDraftStreamingResult.ReadyForValidation

        assertEquals(GenerationStageStatus.VALIDATING, states.findStage(STAGE_ID)?.status)
        assertEquals(
            "attempt-chapter-length-1",
            scalarString(
                "SELECT retry_parent_attempt_id FROM request_attempt WHERE attempt_id = 'attempt-chapter-length-2'",
            ),
        )
        val secondRef = requireNotNull(scalarString(
            "SELECT stream_draft_ref FROM request_attempt WHERE attempt_id = 'attempt-chapter-length-2'",
        ))
        artifactStore.readBytes(secondRef, ProtectedArtifactType.STREAM_DRAFT).use { lease ->
            lease.withBytes { bytes ->
                val accumulated = bytes.toString(Charsets.UTF_8)
                assertEquals(partial + "，随后动作自然完成并留下新的章节钩子。", accumulated)
                assertEquals(1, Regex("这一段还没有结束").findAll(accumulated).count())
            }
        }
    }

    @Test
    fun wrongContinuationAnchorFailsClosedAndDoesNotAppendProviderText() = runBlocking {
        val partial = "已经安全保存的候选正文。".repeat(20)
        val firstPrepared = prepareRequest("attempt-anchor-1", "ledger-anchor-1")
        val continuationRepository = ChapterDraftContinuationRepository(database, artifactStore)
        val first = ChapterDraftStreamingCoordinator(
            executor = AuditedStreamingProviderExecutor(drafts, outputs, IncrementingClock(4L)),
            continuations = continuationRepository,
            clock = IncrementingClock(20L),
        ).executeInitial(
            firstPrepared,
            FakeAdapter(
                onGenerate = {},
                events = listOf(
                    ProviderStreamEvent.Started(),
                    ProviderStreamEvent.StructuredDelta(SensitiveProviderText.from(truncatedBodyEnvelope(partial))),
                    ProviderStreamEvent.Completed(ProviderFinishReason.LENGTH),
                ),
            ),
            profile(),
            generationRequest("attempt-anchor-1"),
        ) as ChapterDraftStreamingResult.ContinuationSettled
        val states = GenerationStateRepository(database)
        states.transitionStage(STAGE_ID, GenerationStageStatus.RETRY_WAIT, StageEvent.RETRY_DELAY_ELAPSED, 21L)
        states.acquireStageLease(STAGE_ID, "executor-anchor", 22L)
        val parentHash = requireNotNull(scalarString(
            "SELECT output_hash FROM request_attempt WHERE attempt_id = 'attempt-anchor-1'",
        ))
        val prepared = continuationRepository.prepareContinuationBeforeSend(
            RequestIntentDraft(
                attemptId = "attempt-anchor-2",
                usageLedgerId = "ledger-anchor-2",
                stageId = STAGE_ID,
                retryParentAttemptId = "attempt-anchor-1",
                connectionSnapshotJson = "{\"secretRefId\":\"fixture-ref\"}",
                modelSnapshotJson = "{\"model\":\"fixture\"}",
                protocolSnapshotJson = "{\"protocol\":\"fixture\"}",
                inputHash = ChapterDraftContinuationPolicyV1.continuationInputHash(
                    "a".repeat(64),
                    parentHash,
                    requireNotNull(first.settlement.anchorHash),
                    1,
                ),
                streamDraftRef = null,
                dailyPeriodKey = "2026-08-03|Asia/Shanghai",
                createdAt = 22L,
            ),
            requireNotNull(states.findStage(STAGE_ID)?.leaseToken),
        )
        val result = ChapterDraftStreamingCoordinator(
            executor = AuditedStreamingProviderExecutor(drafts, outputs, IncrementingClock(23L)),
            continuations = continuationRepository,
            clock = IncrementingClock(40L),
        ).executeContinuation(
            prepared,
            FakeAdapter(
                onGenerate = {},
                events = listOf(
                    ProviderStreamEvent.Started(),
                    ProviderStreamEvent.StructuredDelta(
                        SensitiveProviderText.from(completeBodyEnvelope("错误锚点后出现的内容")),
                    ),
                    ProviderStreamEvent.Completed(ProviderFinishReason.STOP),
                ),
            ),
            profile(),
            generationRequest(
                "attempt-anchor-2",
                ChapterDraftOutputContractV1.continuationParts(prepared),
            ),
        ) as ChapterDraftStreamingResult.InvalidPayloadSettled

        assertEquals(StandardErrorCode.FORMAT_INVALID, result.settlement.standardErrorCode)
        assertEquals(GenerationStageStatus.NEEDS_ACTION, states.findStage(STAGE_ID)?.status)
        assertEquals(GenerationJobStatus.NEEDS_ACTION, states.findJob("job-runtime")?.status)
        assertEquals(UsageLedgerStatus.FINAL.name, scalarString(
            "SELECT status FROM usage_ledger WHERE attempt_id = 'attempt-anchor-2'",
        ))
        val secondRef = requireNotNull(scalarString(
            "SELECT stream_draft_ref FROM request_attempt WHERE attempt_id = 'attempt-anchor-2'",
        ))
        artifactStore.readBytes(secondRef, ProtectedArtifactType.STREAM_DRAFT).use { lease ->
            lease.withBytes { bytes -> assertEquals(partial, bytes.toString(Charsets.UTF_8)) }
        }
    }

    @Test
    fun automaticContinuationStopsAfterThreeFollowupsAndFinalizesEveryAttempt() = runBlocking {
        val repository = ChapterDraftContinuationRepository(database, artifactStore)
        val states = GenerationStateRepository(database)
        val firstPrepared = prepareRequest("attempt-cap-1", "ledger-cap-1")
        var continuation: PreparedChapterDraftContinuation? = null
        var providerBody = "用于验证自动续写次数上限的正文。".repeat(16)

        for (attemptNumber in 1..4) {
            val attemptId = "attempt-cap-$attemptNumber"
            val executeAt = attemptNumber * 100L
            val coordinator = ChapterDraftStreamingCoordinator(
                executor = AuditedStreamingProviderExecutor(drafts, outputs, IncrementingClock(executeAt)),
                continuations = repository,
                clock = IncrementingClock(executeAt + 50L),
            )
            val adapter = FakeAdapter(
                onGenerate = {},
                events = listOf(
                    ProviderStreamEvent.Started(),
                    ProviderStreamEvent.StructuredDelta(
                        SensitiveProviderText.from(truncatedBodyEnvelope(providerBody)),
                    ),
                    ProviderStreamEvent.Completed(ProviderFinishReason.LENGTH),
                ),
            )
            val result = if (attemptNumber == 1) {
                coordinator.executeInitial(
                    firstPrepared,
                    adapter,
                    profile(),
                    generationRequest(attemptId),
                )
            } else {
                val prepared = requireNotNull(continuation)
                coordinator.executeContinuation(
                    prepared,
                    adapter,
                    profile(),
                    generationRequest(attemptId, ChapterDraftOutputContractV1.continuationParts(prepared)),
                )
            } as ChapterDraftStreamingResult.ContinuationSettled

            assertEquals(UsageLedgerStatus.FINAL.name, scalarString(
                "SELECT status FROM usage_ledger WHERE attempt_id = '$attemptId'",
            ))
            if (attemptNumber == 4) {
                assertEquals(ChapterDraftTruncationAction.NEEDS_ACTION, result.settlement.action)
                assertEquals(
                    ChapterDraftContinuationBlockReason.AUTOMATIC_CONTINUATION_LIMIT_REACHED,
                    result.settlement.reason,
                )
                assertEquals(GenerationStageStatus.NEEDS_ACTION, states.findStage(STAGE_ID)?.status)
                break
            }

            assertEquals(ChapterDraftTruncationAction.CONTINUE_AUTOMATICALLY, result.settlement.action)
            assertEquals(attemptNumber, result.settlement.continuationIndex)
            val readyAt = executeAt + 51L
            states.transitionStage(
                STAGE_ID,
                GenerationStageStatus.RETRY_WAIT,
                StageEvent.RETRY_DELAY_ELAPSED,
                readyAt,
            )
            states.acquireStageLease(STAGE_ID, "executor-cap-$attemptNumber", readyAt + 1L)
            val parentHash = requireNotNull(scalarString(
                "SELECT output_hash FROM request_attempt WHERE attempt_id = '$attemptId'",
            ))
            val nextAttemptNumber = attemptNumber + 1
            continuation = repository.prepareContinuationBeforeSend(
                RequestIntentDraft(
                    attemptId = "attempt-cap-$nextAttemptNumber",
                    usageLedgerId = "ledger-cap-$nextAttemptNumber",
                    stageId = STAGE_ID,
                    retryParentAttemptId = attemptId,
                    connectionSnapshotJson = "{\"secretRefId\":\"fixture-ref\"}",
                    modelSnapshotJson = "{\"model\":\"fixture\"}",
                    protocolSnapshotJson = "{\"protocol\":\"fixture\"}",
                    inputHash = ChapterDraftContinuationPolicyV1.continuationInputHash(
                        stageInputVersionHash = "a".repeat(64),
                        parentOutputHash = parentHash,
                        anchorHash = requireNotNull(result.settlement.anchorHash),
                        continuationIndex = attemptNumber,
                    ),
                    streamDraftRef = null,
                    dailyPeriodKey = "2026-08-03|Asia/Shanghai",
                    createdAt = readyAt + 1L,
                ),
                requireNotNull(states.findStage(STAGE_ID)?.leaseToken),
            )
            providerBody = requireNotNull(continuation).withAnchor { anchor ->
                anchor + "第${nextAttemptNumber}次新增正文保持动作连续。".repeat(8)
            }
        }

        assertEquals(4L, scalarLong(
            "SELECT COUNT(*) FROM request_attempt WHERE stage_id = '$STAGE_ID' AND standard_error_code = 'OUTPUT_TRUNCATED'",
        ))
        assertEquals(4L, scalarLong(
            "SELECT COUNT(*) FROM usage_ledger WHERE status = 'FINAL'",
        ))
    }

    @Test
    fun recoveryCoordinatorQueriesExistingRequestAndNeverCallsGenerate() = runBlocking {
        val prepared = prepareRequest("attempt-runtime-query", "ledger-runtime-query")
        val claimed = drafts.claimForProviderOpen(prepared, 4L)
        drafts.markRequestSent(claimed, "remote-runtime-query", 5L)
        drafts.markStreamStarted(claimed, 6L)
        val generated = AtomicInteger(0)
        val queried = AtomicInteger(0)
        val adapter = FakeAdapter(
            onGenerate = { generated.incrementAndGet() },
            events = emptyList(),
            recoveryCapability = ProviderRequestRecoveryCapability.STATUS_QUERY,
            onRecoveryQuery = {
                queried.incrementAndGet()
                ProviderRequestRecoveryResult.ConfirmedNotExecuted
            },
        )

        val result = UnknownResultRecoveryCoordinator(
            drafts = drafts,
            clock = IncrementingClock(60_004L),
        ).auditExpiredAttempt(
            attemptId = "attempt-runtime-query",
            observedLease = requireNotNull(GenerationStateRepository(database).findStage(STAGE_ID)?.leaseToken),
            adapter = adapter,
            profile = profile(),
        )

        assertEquals(GenerationRecoveryDisposition.REQUEUED_AFTER_PROVIDER_PROOF, result.disposition)
        assertEquals(0, generated.get())
        assertEquals(1, queried.get())
        assertEquals(GenerationStageStatus.READY, GenerationStateRepository(database).findStage(STAGE_ID)?.status)
        assertEquals(GenerationJobStatus.READY, GenerationStateRepository(database).findJob("job-runtime")?.status)
    }

    @Test
    fun stalledProviderFlowKeepsLeaseAliveWithIndependentHeartbeats() = runBlocking {
        drafts = GenerationStreamingDraftRepository(
            database = database,
            artifactStore = artifactStore,
            leasePolicy = GenerationLeasePolicy(
                heartbeatIntervalMillis = 100L,
                timeoutMillis = 400L,
            ),
        )
        val prepared = prepareRequest("attempt-runtime-heartbeat", "ledger-runtime-heartbeat")
        val adapter = FakeAdapter(
            onGenerate = {},
            events = listOf(
                ProviderStreamEvent.Started(),
                ProviderStreamEvent.Completed(ProviderFinishReason.STOP),
            ),
            delayBeforeEachEventMillis = 250L,
        )

        val result = AuditedStreamingProviderExecutor(
            drafts = drafts,
            outputs = outputs,
            clock = IncrementingClock(4L),
        ).execute(
            prepared,
            adapter,
            profile(),
            generationRequest("attempt-runtime-heartbeat"),
        )

        assertTrue(result is AuditedStreamingExecutionResult.Completed)
        assertTrue(
            requireNotNull(
                scalarLong("SELECT lease_heartbeat_at FROM generation_stage WHERE stage_id = '$STAGE_ID'"),
            ) > 4L,
        )
    }

    @Test
    fun persistedStopCancelsSlowProviderAndSettlesDatabaseBeforeReturning() = runBlocking {
        drafts = GenerationStreamingDraftRepository(
            database = database,
            artifactStore = artifactStore,
            leasePolicy = GenerationLeasePolicy(
                heartbeatIntervalMillis = 100L,
                timeoutMillis = 1_000L,
            ),
            controlPollIntervalMillis = 50L,
        )
        val prepared = prepareRequest("attempt-runtime-stop", "ledger-runtime-stop")
        val cancellationCount = AtomicInteger(0)
        val clock = ControllableClock(4L)
        val adapter = FakeAdapter(
            onGenerate = {},
            events = listOf(
                ProviderStreamEvent.Started(ProviderRemoteRequestId.from("remote-runtime-stop")),
                ProviderStreamEvent.TextDelta(SensitiveProviderText.from("retained encrypted partial draft")),
                ProviderStreamEvent.Completed(ProviderFinishReason.STOP),
            ),
            delayBeforeEachEventMillis = 200L,
            onCancel = { cancellationCount.incrementAndGet() },
            cancellationResult = ProviderCancellationResult.REMOTE_CANCELLATION_REQUESTED,
        )
        val running = async {
            AuditedStreamingProviderExecutor(
                drafts = drafts,
                outputs = outputs,
                clock = clock,
            ).execute(prepared, adapter, profile(), generationRequest("attempt-runtime-stop"))
        }
        withTimeout(5_000L) {
            while (scalarString(
                    "SELECT status FROM request_attempt WHERE attempt_id = 'attempt-runtime-stop'",
                ) != RequestAttemptStatus.STREAMING.name
            ) {
                delay(20L)
            }
        }

        val requested = GenerationControlRepository(
            database,
            GenerationLeasePolicy(heartbeatIntervalMillis = 100L, timeoutMillis = 1_000L),
        ).requestStop("job-runtime", requestedAt = clock.advanceTo(20L))
        val result = withTimeout(5_000L) { running.await() }

        assertEquals(GenerationControlDisposition.SAFE_POINT_REQUIRED, requested.disposition)
        assertTrue(result is AuditedStreamingExecutionResult.Controlled)
        result as AuditedStreamingExecutionResult.Controlled
        assertEquals(GenerationExecutionControl.STOP, result.action)
        assertEquals(ProviderCancellationResult.REMOTE_CANCELLATION_REQUESTED, result.cancellation)
        assertEquals(1, cancellationCount.get())
        assertEquals(GenerationJobStatus.STOPPED.name, scalarString(
            "SELECT status FROM generation_job WHERE job_id = 'job-runtime'",
        ))
        assertEquals(GenerationStageStatus.CANCELLED.name, scalarString(
            "SELECT status FROM generation_stage WHERE stage_id = '$STAGE_ID'",
        ))
        assertEquals(RequestAttemptStatus.CANCELLED.name, scalarString(
            "SELECT status FROM request_attempt WHERE attempt_id = 'attempt-runtime-stop'",
        ))
        assertEquals(UsageLedgerStatus.FINAL.name, scalarString(
            "SELECT status FROM usage_ledger WHERE attempt_id = 'attempt-runtime-stop'",
        ))
        assertTrue(artifactStore.listArtifactReferenceIds().isNotEmpty())
    }

    @Test
    fun validStructuredOutputMovesToCommittingWithoutCreatingFormalChapter() = runBlocking {
        val prepared = prepareRequest("attempt-structured-valid", "ledger-structured-valid")
        val adapter = FakeAdapter(
            onGenerate = {},
            events = listOf(
                ProviderStreamEvent.Started(),
                ProviderStreamEvent.StructuredDelta(
                    SensitiveProviderText.from(
                        "{\"schemaVersion\":1,\"title\":\"第一章计划\",\"beats\":[\"开场\"]}",
                    ),
                ),
                ProviderStreamEvent.Completed(ProviderFinishReason.STOP),
            ),
        )
        val completed = AuditedStreamingProviderExecutor(
            drafts = drafts,
            outputs = outputs,
            clock = IncrementingClock(4L),
        ).execute(
            prepared,
            adapter,
            profile(),
            generationRequest("attempt-structured-valid"),
        ) as AuditedStreamingExecutionResult.Completed

        val decision = StructuredOutputValidationCoordinator(outputs).validate(
            completed = completed,
            contract = AndroidFixtureContract,
            validatedAt = 10L,
        )

        assertTrue(decision is StructuredOutputValidationDecision.Accepted)
        assertEquals(GenerationStageStatus.COMMITTING, GenerationStateRepository(database).findStage(STAGE_ID)?.status)
        assertEquals(0L, scalarLong("SELECT COUNT(*) FROM chapter_version"))
    }

    @Test
    fun outputBeyondContractLimitBecomesNeedsActionInsteadOfLeavingValidationStuck() = runBlocking {
        val prepared = prepareRequest("attempt-structured-oversized", "ledger-structured-oversized")
        val oversizedJson = "{\"schemaVersion\":1,\"title\":\"${"x".repeat(1_100)}\",\"beats\":[]}"
        val completed = AuditedStreamingProviderExecutor(
            drafts = drafts,
            outputs = outputs,
            clock = IncrementingClock(4L),
        ).execute(
            prepared,
            FakeAdapter(
                onGenerate = {},
                events = listOf(
                    ProviderStreamEvent.Started(),
                    ProviderStreamEvent.StructuredDelta(SensitiveProviderText.from(oversizedJson)),
                    ProviderStreamEvent.Completed(ProviderFinishReason.STOP),
                ),
            ),
            profile(),
            generationRequest("attempt-structured-oversized"),
        ) as AuditedStreamingExecutionResult.Completed

        val decision = StructuredOutputValidationCoordinator(outputs).validate(
            completed = completed,
            contract = AndroidSmallLimitContract,
            validatedAt = 10L,
        )

        assertTrue(decision is StructuredOutputValidationDecision.NeedsAction)
        val report = (decision as StructuredOutputValidationDecision.NeedsAction).report
        assertEquals(listOf(StructuredOutputIssueCode.BYTE_LIMIT_EXCEEDED), report.issues.map { it.code })
        assertTrue(!report.repairEligible)
        assertEquals(GenerationStageStatus.NEEDS_ACTION, GenerationStateRepository(database).findStage(STAGE_ID)?.status)
    }

    @Test
    fun twoInvalidStructuredOutputsPersistOneRepairThenNeedAction() = runBlocking {
        val firstPrepared = prepareRequest("attempt-structured-invalid-1", "ledger-structured-invalid-1")
        val firstCompleted = AuditedStreamingProviderExecutor(
            drafts = drafts,
            outputs = outputs,
            clock = IncrementingClock(4L),
        ).execute(
            firstPrepared,
            FakeAdapter(
                onGenerate = {},
                events = listOf(
                    ProviderStreamEvent.Started(),
                    ProviderStreamEvent.StructuredDelta(SensitiveProviderText.from("{invalid-one")),
                    ProviderStreamEvent.Completed(ProviderFinishReason.STOP),
                ),
            ),
            profile(),
            generationRequest("attempt-structured-invalid-1"),
        ) as AuditedStreamingExecutionResult.Completed
        val firstDecision = StructuredOutputValidationCoordinator(outputs).validate(
            firstCompleted,
            AndroidFixtureContract,
            validatedAt = 10L,
        )
        val repair = firstDecision as StructuredOutputValidationDecision.RepairRequired
        assertEquals(GenerationStageStatus.RETRY_WAIT, GenerationStateRepository(database).findStage(STAGE_ID)?.status)

        val repairRequest = StructuredOutputRepairRequestFactory(outputs).create(
            plan = repair.plan,
            contract = AndroidFixtureContract,
            spec = StructuredOutputRepairRequestSpec(
                requestId = "request-repair-1",
                generationId = "generation-1",
                stageId = STAGE_ID,
                attemptId = "attempt-structured-invalid-2",
                modelId = ProviderModelId.from("fixture-model"),
                maximumOutputTokens = 256,
                timeouts = ProviderTimeoutPolicy(1_000, 1_000, 1_000, 2_000),
                idempotencyKey = "idem-repair-0001",
            ),
        )
        assertEquals(2, repairRequest.prompt.partCount)
        assertEquals(0.0, repairRequest.parameters.temperature)
        assertEquals(256, repairRequest.parameters.maxOutputTokens)
        assertTrue(repairRequest.toString().contains("structuredOutput=true"))
        assertTrue(repairRequest.toString().contains("promptParts=2"))
        assertTrue(!repairRequest.toString().contains("invalid-one"))

        GenerationStateRepository(database).transitionStage(
            stageId = STAGE_ID,
            expectedStatus = GenerationStageStatus.RETRY_WAIT,
            event = StageEvent.RETRY_DELAY_ELAPSED,
            updatedAt = 10L,
        )
        GenerationStateRepository(database).acquireStageLease(STAGE_ID, "executor-repair", now = 11L)
        val secondPrepared = prepareRequest(
            attemptId = "attempt-structured-invalid-2",
            ledgerId = "ledger-structured-invalid-2",
            createdAt = 11L,
            retryParentAttemptId = "attempt-structured-invalid-1",
        )
        val secondCompleted = AuditedStreamingProviderExecutor(
            drafts = drafts,
            outputs = outputs,
            clock = IncrementingClock(12L),
        ).execute(
            secondPrepared,
            FakeAdapter(
                onGenerate = {},
                events = listOf(
                    ProviderStreamEvent.Started(),
                    ProviderStreamEvent.StructuredDelta(SensitiveProviderText.from("{invalid-two")),
                    ProviderStreamEvent.Completed(ProviderFinishReason.STOP),
                ),
            ),
            profile(),
            repairRequest,
        ) as AuditedStreamingExecutionResult.Completed
        val secondDecision = StructuredOutputValidationCoordinator(outputs).validate(
            secondCompleted,
            AndroidFixtureContract,
            validatedAt = 18L,
        )

        assertTrue(secondDecision is StructuredOutputValidationDecision.NeedsAction)
        assertEquals(GenerationStageStatus.NEEDS_ACTION, GenerationStateRepository(database).findStage(STAGE_ID)?.status)
        assertEquals("FORMAT_INVALID", scalarString(
            "SELECT standard_error_code FROM request_attempt WHERE attempt_id = 'attempt-structured-invalid-2'",
        ))
    }

    private suspend fun prepareRequest(
        attemptId: String,
        ledgerId: String,
        createdAt: Long = 3L,
        retryParentAttemptId: String? = null,
    ) =
        drafts.prepareBeforeSend(
            RequestIntentDraft(
                attemptId = attemptId,
                usageLedgerId = ledgerId,
                stageId = STAGE_ID,
                retryParentAttemptId = retryParentAttemptId,
                connectionSnapshotJson = "{\"secretRefId\":\"fixture-ref\"}",
                modelSnapshotJson = "{\"model\":\"fixture\"}",
                protocolSnapshotJson = "{\"protocol\":\"fixture\"}",
                inputHash = "a".repeat(64),
                streamDraftRef = null,
                dailyPeriodKey = "2026-08-02|Asia/Shanghai",
                createdAt = createdAt,
            ),
            requireNotNull(GenerationStateRepository(database).findStage(STAGE_ID)?.leaseToken),
        )

    private fun generationRequest(
        attemptId: String,
        promptParts: List<PromptPart> = listOf(ChapterDraftOutputContractV1.initialStageContractPart()),
    ) = GenerationRequest(
        requestId = "request-$attemptId",
        generationId = "generation-1",
        stageId = STAGE_ID,
        attemptId = attemptId,
        modelId = ProviderModelId.from("fixture-model"),
        prompt = ProviderPrompt(promptParts),
        parameters = GenerationParameters(maxOutputTokens = 64),
        structuredOutputSchema = ChapterDraftOutputContractV1.providerSchema,
        stream = true,
        timeouts = ProviderTimeoutPolicy(1_000, 1_000, 1_000, 2_000),
        idempotencyKey = "idem-runtime-0001",
    )

    private fun truncatedBodyEnvelope(body: String): String =
        "{\"body\":" + JsonPrimitive(body).toString().dropLast(1)

    private fun completeBodyEnvelope(body: String): String =
        "{\"body\":" + JsonPrimitive(body).toString() + "}"

    private fun providerUsage(input: Long, output: Long) = ProviderUsage(
        inputTokens = input,
        outputTokens = output,
        cachedInputTokens = null,
        cachedWriteTokens = null,
        reasoningTokens = null,
        totalTokens = input + output,
        quality = ProviderUsageQuality.PROVIDER_REPORTED,
    )

    private fun profile() = ProviderConnectionProfile.create(
        connectionId = "connection-1",
        protocol = ProviderProtocol.OPENAI_CHAT_COMPAT,
        baseUrl = "https://example.invalid",
    )

    private fun scalarString(sql: String): String? =
        database.openHelper.readableDatabase.query(sql).use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
        }

    private fun scalarLong(sql: String): Long? =
        database.openHelper.readableDatabase.query(sql).use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
        }

    private fun seedGenerationRows() {
        val sql = database.openHelper.writableDatabase
        sql.execSQL(
            """
            INSERT INTO book_creation_snapshot VALUES (
                'snapshot-runtime', '{}', '{}', '{}', '{}', '{}', '{}',
                1, 'prompt-1', 1, 'snapshot-hash', 1
            )
            """.trimIndent(),
        )
        sql.execSQL(
            """
            INSERT INTO book (
                book_id, creation_snapshot_id, title, title_source, status, length_mode,
                target_characters, target_chapters, minimum_chapters, length_policy_schema_version,
                branched_from_book_id, branched_from_chapter_version_id, completed_chapter_count,
                generation_status_summary, archived_at, deleted_at, created_at, updated_at
            ) VALUES (
                'book-runtime', 'snapshot-runtime', 'fixture', 'USER', 'DRAFT', 'LONG',
                500000, 500, 301, 1, NULL, NULL, 0, 'ready', NULL, NULL, 1, 1
            )
            """.trimIndent(),
        )
        sql.execSQL(
            """
            INSERT INTO generation_job VALUES (
                'job-runtime', 'book-runtime', 'CREATE_BOOK', 'CREATED', '{}', '{}',
                'prompt-1', NULL, NULL, NULL, NULL, NULL, NULL, NULL, 1, 1
            )
            """.trimIndent(),
        )
        sql.execSQL(
            """
            INSERT INTO generation_stage VALUES (
                '$STAGE_ID', 'job-runtime', 'DRAFT_CHAPTER', 'CHAPTER', 'chapter-1',
                'PENDING', '${"a".repeat(64)}', 'idem-runtime-stage', 0, 4, '[]', NULL,
                NULL, NULL, NULL, NULL, NULL, 1, 1
            )
            """.trimIndent(),
        )
        sql.execSQL(
            "UPDATE generation_job SET current_stage_id = '$STAGE_ID' WHERE job_id = 'job-runtime'",
        )
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

    private companion object {
        const val STAGE_ID = "stage-runtime"
    }
}

private object AndroidFixtureContract : StructuredOutputContract {
    override val schemaId: String = "fixture.chapter-plan"
    override val currentSchemaVersion: Int = 1
    override val providerSchema: ProviderJsonSchema = ProviderJsonSchema.from(
        """{"type":"object","required":["schemaVersion","title","beats"]}""",
    )

    override fun validate(document: JsonObject): List<StructuredOutputIssue> = buildList {
        val title = document["title"]
        if (title !is JsonPrimitive || !title.isString) {
            add(StructuredOutputIssue(StructuredOutputIssueCode.TYPE_MISMATCH, "$.title"))
        }
        if (document["beats"] !is JsonArray) {
            add(StructuredOutputIssue(StructuredOutputIssueCode.TYPE_MISMATCH, "$.beats"))
        }
    }
}

private object AndroidSmallLimitContract : StructuredOutputContract by AndroidFixtureContract {
    override val limits = StructuredOutputLimits(
        maximumBytes = 1_024,
        maximumRepairSourceBytes = 1_024,
    )
}

private class IncrementingClock(startAt: Long) : GenerationExecutionClock {
    private val next = AtomicLong(startAt)
    override fun nowMillis(): Long = next.getAndIncrement()
}

private class ControllableClock(startAt: Long) : GenerationExecutionClock {
    private val next = AtomicLong(startAt)

    override fun nowMillis(): Long = next.getAndIncrement()

    fun advanceTo(at: Long): Long {
        while (true) {
            val observed = next.get()
            val advanced = maxOf(observed, at)
            if (next.compareAndSet(observed, advanced)) return advanced
        }
    }
}

private class FakeAdapter(
    private val onGenerate: () -> Unit,
    private val events: List<ProviderStreamEvent>,
    private val delayBeforeEachEventMillis: Long = 0L,
    private val onCancel: () -> Unit = {},
    private val cancellationResult: ProviderCancellationResult = ProviderCancellationResult.ALREADY_TERMINAL,
    private val recoveryCapability: ProviderRequestRecoveryCapability =
        ProviderRequestRecoveryCapability.NOT_SUPPORTED,
    private val onRecoveryQuery: () -> ProviderRequestRecoveryResult = {
        ProviderRequestRecoveryResult.NotSupported
    },
) : ProviderAdapter {
    override val protocol = ProviderProtocol.OPENAI_CHAT_COMPAT
    override val adapterVersion = "test-1"
    override val requestRecoveryCapability = recoveryCapability

    override suspend fun testConnection(profile: ProviderConnectionProfile): ConnectionTestResult =
        error("Not used by the streaming executor test.")

    override suspend fun listModels(profile: ProviderConnectionProfile): ModelListResult =
        error("Not used by the streaming executor test.")

    override suspend fun getCapabilities(
        profile: ProviderConnectionProfile,
        modelId: ProviderModelId,
    ): CapabilityResult = error("Not used by the streaming executor test.")

    override fun generate(
        profile: ProviderConnectionProfile,
        request: GenerationRequest,
    ): Flow<ProviderStreamEvent> = flow {
        onGenerate()
        events.forEach {
            if (delayBeforeEachEventMillis > 0L) delay(delayBeforeEachEventMillis)
            emit(it)
        }
    }

    override suspend fun cancel(
        profile: ProviderConnectionProfile,
        requestId: String,
    ): ProviderCancellationResult {
        onCancel()
        return cancellationResult
    }

    override suspend fun queryRequestRecovery(
        profile: ProviderConnectionProfile,
        remoteRequestId: ProviderRemoteRequestId,
    ): ProviderRequestRecoveryResult = onRecoveryQuery()
}

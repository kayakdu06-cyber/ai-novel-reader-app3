package app.zhijuan.core.database.generation

import androidx.room.withTransaction
import app.zhijuan.core.database.ZhijuanDatabase
import app.zhijuan.core.model.GenerationJobStatus
import app.zhijuan.core.model.GenerationPhase
import app.zhijuan.core.model.GenerationStageStatus
import app.zhijuan.core.model.RequestAttemptStatus
import app.zhijuan.core.model.StandardErrorCode
import app.zhijuan.core.model.UsageLedgerStatus
import app.zhijuan.core.security.AndroidProtectedArtifactStore
import app.zhijuan.core.security.ProtectedArtifactType
import app.zhijuan.core.task.ChapterDraftContinuationDecision
import app.zhijuan.core.task.ChapterDraftContinuationInput
import app.zhijuan.core.task.ChapterDraftContinuationPolicyV1
import app.zhijuan.core.task.GenerationJobStateMachine
import app.zhijuan.core.task.GenerationStageStateMachine
import app.zhijuan.core.task.JobEvent
import app.zhijuan.core.task.StageEvent
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

enum class ChapterDraftTruncationAction {
    CONTINUE_AUTOMATICALLY,
    NEEDS_ACTION,
}

data class PersistedChapterDraftTruncation(
    val action: ChapterDraftTruncationAction,
    val attemptId: String,
    val continuationIndex: Int?,
    val reason: app.zhijuan.core.task.ChapterDraftContinuationBlockReason?,
    val accumulatedUtf8Bytes: Int,
    val anchorHash: String?,
    val replayed: Boolean,
)

data class PersistedInvalidChapterDraft(
    val attemptId: String,
    val standardErrorCode: StandardErrorCode,
    val replayed: Boolean,
)

sealed interface RecoveredChapterDraftSettlement {
    data class Truncated(val settlement: PersistedChapterDraftTruncation) : RecoveredChapterDraftSettlement
    data class Invalid(val settlement: PersistedInvalidChapterDraft) : RecoveredChapterDraftSettlement
}

class PreparedChapterDraftContinuation internal constructor(
    val request: PersistedStreamingRequest,
    val continuationIndex: Int,
    val parentAttemptId: String,
    val parentOutputHash: String,
    val anchorHash: String,
    val accumulatedUtf8Bytes: Int,
    private val anchor: String,
    private val tail: String,
) {
    fun <T> withAnchor(block: (String) -> T): T = block(anchor)

    fun <T> withTail(block: (String) -> T): T = block(tail)

    override fun toString(): String =
        "PreparedChapterDraftContinuation(index=$continuationIndex, accumulatedUtf8Bytes=$accumulatedUtf8Bytes, content=redacted)"
}

class ChapterDraftContinuationRepository(
    private val database: ZhijuanDatabase,
    private val artifactStore: AndroidProtectedArtifactStore,
    private val leasePolicy: GenerationLeasePolicy = GenerationLeasePolicy(),
) {
    suspend fun settleInvalidPayload(
        response: CompletedStreamingResponse,
        usage: FinalUsageCommit,
        settledAt: Long,
    ): PersistedInvalidChapterDraft = settleInvalidPayloadInternal(
        response = response,
        usage = usage,
        settledAt = settledAt,
        requireLiveLease = true,
    )

    private suspend fun settleInvalidPayloadInternal(
        response: CompletedStreamingResponse,
        usage: FinalUsageCommit,
        settledAt: Long,
        requireLiveLease: Boolean,
    ): PersistedInvalidChapterDraft = database.withTransaction {
        val dao = database.generationDao()
        val attempt = requireNotNull(dao.findAttempt(response.attemptId)) {
            "Invalid chapter attempt is missing."
        }
        val stage = requireNotNull(dao.findStage(response.stageId)) {
            "Invalid chapter stage is missing."
        }
        val job = requireNotNull(dao.findJob(stage.jobId)) { "Invalid chapter job is missing." }
        require(stage.phase in CHAPTER_TEXT_PHASES)
        require(
            attempt.stageId == stage.stageId && attempt.status == RequestAttemptStatus.SUCCEEDED &&
                attempt.outputHash == response.outputHash && attempt.streamDraftRef == response.artifactRefId,
        ) { "Invalid chapter payload no longer matches its completed response." }
        require(dao.attemptsForStage(stage.stageId).lastOrNull()?.attemptId == attempt.attemptId)
        if (
            attempt.standardErrorCode == StandardErrorCode.FORMAT_INVALID &&
            stage.status == GenerationStageStatus.NEEDS_ACTION
        ) {
            return@withTransaction PersistedInvalidChapterDraft(
                attemptId = attempt.attemptId,
                standardErrorCode = StandardErrorCode.FORMAT_INVALID,
                replayed = true,
            )
        }
        require(
            attempt.standardErrorCode in setOf(null, StandardErrorCode.FORMAT_INVALID) &&
                stage.status == GenerationStageStatus.VALIDATING,
        )
        require(
            job.status in setOf(GenerationJobStatus.RUNNING, GenerationJobStatus.PAUSING) &&
                job.currentStageId == stage.stageId,
        )
        if (requireLiveLease) requireActiveLease(stage, response.leaseToken, settledAt)
        require(settledAt >= attempt.updatedAt && settledAt >= stage.updatedAt && settledAt >= job.updatedAt)
        if (attempt.standardErrorCode == null) {
            check(
                dao.markCompletedAttemptValidationError(
                    attemptId = attempt.attemptId,
                    expectedOutputHash = response.outputHash,
                    errorCode = StandardErrorCode.FORMAT_INVALID,
                    updatedAt = settledAt,
                ) == 1,
            )
        }
        dao.recordUsage(attempt.attemptId, usage.toFinalUpdate(settledAt))
        check(
            dao.compareAndSetStageStatus(
                stageId = stage.stageId,
                expectedStatus = stage.status,
                nextStatus = GenerationStageStateMachine.transition(stage.status, StageEvent.USER_ACTION_REQUIRED),
                errorCode = StandardErrorCode.FORMAT_INVALID,
                nextRetryAt = null,
                updatedAt = settledAt,
            ) == 1,
        )
        val nextJob = if (job.status == GenerationJobStatus.PAUSING) {
            GenerationJobStateMachine.transition(job.status, JobEvent.SAFE_POINT_REACHED)
        } else {
            GenerationJobStateMachine.transition(job.status, JobEvent.USER_ACTION_REQUIRED)
        }
        check(
            dao.compareAndSetJobControlStatus(
                jobId = job.jobId,
                expectedStatus = job.status,
                nextStatus = nextJob,
                reason = job.pauseOrStopReason ?: StandardErrorCode.FORMAT_INVALID.name,
                updatedAt = settledAt,
            ) == 1,
        )
        PersistedInvalidChapterDraft(
            attemptId = attempt.attemptId,
            standardErrorCode = StandardErrorCode.FORMAT_INVALID,
            replayed = false,
        )
    }

    suspend fun settleTruncated(
        response: CompletedStreamingResponse,
        usage: FinalUsageCommit,
        settledAt: Long,
    ): PersistedChapterDraftTruncation = settleTruncatedInternal(
        response = response,
        usage = usage,
        settledAt = settledAt,
        requireLiveLease = true,
    )

    private suspend fun settleTruncatedInternal(
        response: CompletedStreamingResponse,
        usage: FinalUsageCommit,
        settledAt: Long,
        requireLiveLease: Boolean,
    ): PersistedChapterDraftTruncation {
        require(settledAt >= 0L)
        val inspected = readDraft(response)
        return database.withTransaction {
            val dao = database.generationDao()
            val attempt = requireNotNull(dao.findAttempt(response.attemptId)) {
                "Truncated chapter attempt is missing."
            }
            val stage = requireNotNull(dao.findStage(response.stageId)) {
                "Truncated chapter stage is missing."
            }
            val job = requireNotNull(dao.findJob(stage.jobId)) {
                "Truncated chapter job is missing."
            }
            require(stage.phase in CHAPTER_TEXT_PHASES) {
                "Only chapter text stages can use output continuation."
            }
            require(
                attempt.stageId == stage.stageId && attempt.status == RequestAttemptStatus.SUCCEEDED &&
                    attempt.outputHash == response.outputHash && attempt.streamDraftRef == response.artifactRefId,
            ) { "Truncated chapter response no longer matches its attempt." }
            val attempts = dao.attemptsForStage(stage.stageId)
            require(attempts.lastOrNull()?.attemptId == attempt.attemptId) {
                "Only the latest chapter attempt can settle a truncation."
            }
            val currentIndex = attempts.indexOfLast { it.attemptId == attempt.attemptId }
            val priorTruncations = attempts.take(currentIndex).count {
                it.standardErrorCode == StandardErrorCode.OUTPUT_TRUNCATED
            }
            val decision = ChapterDraftContinuationPolicyV1.evaluate(
                ChapterDraftContinuationInput(
                    accumulatedText = inspected.text,
                    completedTruncations = priorTruncations,
                    totalAttemptsUsed = stage.attemptCount,
                    stageMaximumAttempts = stage.maxAttempts,
                ),
            )
            if (
                attempt.standardErrorCode == StandardErrorCode.OUTPUT_TRUNCATED &&
                stage.status in setOf(GenerationStageStatus.RETRY_WAIT, GenerationStageStatus.NEEDS_ACTION)
            ) {
                return@withTransaction decision.toStored(attempt.attemptId, inspected.bytes, replayed = true)
            }
            require(
                attempt.standardErrorCode in setOf(null, StandardErrorCode.OUTPUT_TRUNCATED) &&
                    stage.status == GenerationStageStatus.VALIDATING,
            ) {
                "Chapter truncation requires a newly completed response awaiting validation."
            }
            require(
                job.status in setOf(GenerationJobStatus.RUNNING, GenerationJobStatus.PAUSING) &&
                    job.currentStageId == stage.stageId,
            ) { "Paused, stopped, or superseded jobs cannot settle a chapter truncation." }
            if (requireLiveLease) requireActiveLease(stage, response.leaseToken, settledAt)
            require(settledAt >= attempt.updatedAt && settledAt >= stage.updatedAt && settledAt >= job.updatedAt)

            if (attempt.standardErrorCode == null) {
                check(
                    dao.markCompletedAttemptValidationError(
                        attemptId = attempt.attemptId,
                        expectedOutputHash = response.outputHash,
                        errorCode = StandardErrorCode.OUTPUT_TRUNCATED,
                        updatedAt = settledAt,
                    ) == 1,
                ) { "Chapter truncation lost the completed attempt." }
            }
            dao.recordUsage(attempt.attemptId, usage.toFinalUpdate(settledAt))

            val automatic = decision is ChapterDraftContinuationDecision.ContinueAutomatically
            val nextStage = GenerationStageStateMachine.transition(
                stage.status,
                if (automatic) StageEvent.RETRYABLE_FAILURE else StageEvent.USER_ACTION_REQUIRED,
            )
            check(
                dao.compareAndSetStageStatus(
                    stageId = stage.stageId,
                    expectedStatus = stage.status,
                    nextStatus = nextStage,
                    errorCode = StandardErrorCode.OUTPUT_TRUNCATED,
                    nextRetryAt = settledAt.takeIf { automatic },
                    updatedAt = settledAt,
                ) == 1,
            ) { "Chapter truncation lost the current stage." }

            if (job.status == GenerationJobStatus.PAUSING) {
                check(
                    dao.compareAndSetJobControlStatus(
                        jobId = job.jobId,
                        expectedStatus = job.status,
                        nextStatus = GenerationJobStateMachine.transition(job.status, JobEvent.SAFE_POINT_REACHED),
                        reason = job.pauseOrStopReason,
                        updatedAt = settledAt,
                    ) == 1,
                ) { "Chapter truncation pause settlement lost the job." }
            } else if (!automatic) {
                check(
                    dao.compareAndSetJobControlStatus(
                        jobId = job.jobId,
                        expectedStatus = job.status,
                        nextStatus = GenerationJobStateMachine.transition(job.status, JobEvent.USER_ACTION_REQUIRED),
                        reason = StandardErrorCode.OUTPUT_TRUNCATED.name,
                        updatedAt = settledAt,
                    ) == 1,
                ) { "Chapter truncation user-action settlement lost the job." }
            }
            decision.toStored(attempt.attemptId, inspected.bytes, replayed = false)
        }
    }

    /**
     * Completes the narrow crash window after response evidence and its classification were
     * committed, but before the in-memory coordinator advanced the stage. No provider is called.
     * Usage is finalized as unknown because an in-memory usage event may have been lost.
     */
    suspend fun recoverPendingSettlement(
        attemptId: String,
        recoveredAt: Long,
    ): RecoveredChapterDraftSettlement {
        require(RECOVERY_ID.matches(attemptId)) { "Recovery attempt id is invalid." }
        require(recoveredAt >= 0L) { "Recovery time is invalid." }
        val dao = database.generationDao()
        val attempt = requireNotNull(dao.findAttempt(attemptId)) { "Recovery attempt is missing." }
        val stage = requireNotNull(dao.findStage(attempt.stageId)) { "Recovery stage is missing." }
        val job = requireNotNull(dao.findJob(stage.jobId)) { "Recovery job is missing." }
        require(stage.phase in CHAPTER_TEXT_PHASES)
        require(attempt.status == RequestAttemptStatus.SUCCEEDED)
        require(attempt.standardErrorCode in RECOVERABLE_CLASSIFICATIONS)
        require(attempt.streamDraftRef != null && attempt.outputHash != null)
        require(dao.attemptsForStage(stage.stageId).lastOrNull()?.attemptId == attempt.attemptId)
        require(job.currentStageId == stage.stageId)
        require(
            stage.status in setOf(
                GenerationStageStatus.VALIDATING,
                GenerationStageStatus.RETRY_WAIT,
                GenerationStageStatus.NEEDS_ACTION,
            ),
        )
        val transfer = artifactStore.verify(
            artifactRefId = requireNotNull(attempt.streamDraftRef),
            expectedType = ProtectedArtifactType.STREAM_DRAFT,
        )
        require(transfer.plaintextBytes <= ChapterDraftContinuationPolicyV1.MAXIMUM_CHAPTER_UTF8_BYTES)
        readDraft(
            artifactRefId = requireNotNull(attempt.streamDraftRef),
            expectedRevision = transfer.descriptor.revision,
            expectedHash = requireNotNull(attempt.outputHash),
        )
        val response = CompletedStreamingResponse(
            attemptId = attempt.attemptId,
            stageId = stage.stageId,
            artifactRefId = requireNotNull(attempt.streamDraftRef),
            artifactRevision = transfer.descriptor.revision,
            outputHash = requireNotNull(attempt.outputHash),
            leaseToken = stage.leaseTokenOrNull() ?: run {
                // Settled RETRY_WAIT/NEEDS_ACTION stages intentionally release the lease.
                // This token is never authorized for provider I/O and is ignored by the
                // idempotent replay branch below.
                require(stage.status in setOf(GenerationStageStatus.RETRY_WAIT, GenerationStageStatus.NEEDS_ACTION)) {
                    "Pending classified response lost its lease identity."
                }
                GenerationLeaseToken(RECOVERY_REPLAY_LEASE_OWNER, 0L)
            },
            plaintextBytes = transfer.plaintextBytes.toInt(),
        )
        return when (attempt.standardErrorCode) {
            StandardErrorCode.OUTPUT_TRUNCATED -> RecoveredChapterDraftSettlement.Truncated(
                settleTruncatedInternal(response, FinalUsageCommit.UNKNOWN, recoveredAt, requireLiveLease = false),
            )
            StandardErrorCode.FORMAT_INVALID -> RecoveredChapterDraftSettlement.Invalid(
                settleInvalidPayloadInternal(response, FinalUsageCommit.UNKNOWN, recoveredAt, requireLiveLease = false),
            )
            else -> error("Recovery classification changed after validation.")
        }
    }

    suspend fun prepareContinuationBeforeSend(
        draft: RequestIntentDraft,
        leaseToken: GenerationLeaseToken,
    ): PreparedChapterDraftContinuation {
        val dao = database.generationDao()
        val stage = requireNotNull(dao.findStage(draft.stageId)) { "Continuation stage is missing." }
        val job = requireNotNull(dao.findJob(stage.jobId)) { "Continuation job is missing." }
        require(stage.phase in CHAPTER_TEXT_PHASES && stage.status == GenerationStageStatus.PREPARING)
        require(job.status == GenerationJobStatus.RUNNING && job.currentStageId == stage.stageId)
        requireActiveLease(stage, leaseToken, draft.createdAt)
        val attempts = dao.attemptsForStage(stage.stageId)
        val parent = requireNotNull(attempts.lastOrNull()) { "Continuation requires a parent attempt." }
        require(
            parent.attemptId == draft.retryParentAttemptId && parent.status == RequestAttemptStatus.SUCCEEDED &&
                parent.standardErrorCode == StandardErrorCode.OUTPUT_TRUNCATED &&
                !parent.outputHash.isNullOrBlank() && !parent.streamDraftRef.isNullOrBlank(),
        ) { "Continuation parent is not the latest safely truncated attempt." }
        require(dao.findUsageForAttempt(parent.attemptId)?.status == UsageLedgerStatus.FINAL) {
            "Continuation parent usage must be finalized before another request."
        }
        val inspected = readDraft(
            artifactRefId = requireNotNull(parent.streamDraftRef),
            expectedRevision = null,
            expectedHash = requireNotNull(parent.outputHash),
        )
        val completedTruncations = attempts.count {
            it.standardErrorCode == StandardErrorCode.OUTPUT_TRUNCATED
        }
        require(completedTruncations > 0) { "Continuation requires a recorded truncation." }
        val decision = ChapterDraftContinuationPolicyV1.evaluate(
            ChapterDraftContinuationInput(
                accumulatedText = inspected.text,
                // Settlement evaluates the current truncation against the number that
                // happened before it. Recreate that same decision here so the persisted
                // input fingerprint cannot drift by one continuation index.
                completedTruncations = completedTruncations - 1,
                totalAttemptsUsed = stage.attemptCount,
                stageMaximumAttempts = stage.maxAttempts,
            ),
        ) as? ChapterDraftContinuationDecision.ContinueAutomatically
            ?: throw IllegalStateException("Chapter continuation is no longer automatically eligible.")
        val expectedInputHash = ChapterDraftContinuationPolicyV1.continuationInputHash(
            stageInputVersionHash = stage.inputVersionHash,
            parentOutputHash = requireNotNull(parent.outputHash),
            anchorHash = decision.anchorHash,
            continuationIndex = decision.continuationIndex,
        )
        require(draft.inputHash == expectedInputHash) {
            "Continuation request input hash does not bind its parent draft and exact anchor."
        }
        val seed = inspected.text.toByteArray(Charsets.UTF_8)
        val request = GenerationStreamingDraftRepository(database, artifactStore, leasePolicy)
            .prepareContinuationBeforeSend(draft, leaseToken, seed)
        return PreparedChapterDraftContinuation(
            request = request,
            continuationIndex = decision.continuationIndex,
            parentAttemptId = parent.attemptId,
            parentOutputHash = requireNotNull(parent.outputHash),
            anchorHash = decision.anchorHash,
            accumulatedUtf8Bytes = inspected.bytes,
            anchor = decision.withAnchor { it },
            tail = decision.withTail { it },
        )
    }

    private fun requireActiveLease(
        stage: GenerationStageEntity,
        expected: GenerationLeaseToken,
        now: Long,
    ) {
        require(stage.leaseTokenOrNull() == expected) { "Chapter continuation lost its stage lease." }
        val heartbeat = requireNotNull(stage.leaseHeartbeatAt)
        require(!leasePolicy.isExpired(heartbeat, now)) { "Chapter continuation stage lease expired." }
    }

    private fun readDraft(response: CompletedStreamingResponse): InspectedText = readDraft(
        artifactRefId = response.artifactRefId,
        expectedRevision = response.artifactRevision,
        expectedHash = response.outputHash,
    )

    private fun readDraft(
        artifactRefId: String,
        expectedRevision: Int?,
        expectedHash: String,
    ): InspectedText = artifactStore.readBytes(
        artifactRefId = artifactRefId,
        expectedType = ProtectedArtifactType.STREAM_DRAFT,
        maximumBytes = ChapterDraftContinuationPolicyV1.MAXIMUM_CHAPTER_UTF8_BYTES,
    ).use { lease ->
        require(expectedRevision == null || lease.descriptor.revision == expectedRevision) {
            "Chapter draft revision changed before continuation."
        }
        lease.withBytes { bytes ->
            require(sha256(bytes) == expectedHash) { "Chapter draft hash changed before continuation." }
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val text = decoder.decode(ByteBuffer.wrap(bytes)).toString()
            require(text.none { it == '\u0000' }) { "Chapter draft contains an invalid null character." }
            InspectedText(text = text, bytes = bytes.size)
        }
    }

    private fun ChapterDraftContinuationDecision.toStored(
        attemptId: String,
        accumulatedBytes: Int,
        replayed: Boolean,
    ): PersistedChapterDraftTruncation = when (this) {
        is ChapterDraftContinuationDecision.ContinueAutomatically -> PersistedChapterDraftTruncation(
            action = ChapterDraftTruncationAction.CONTINUE_AUTOMATICALLY,
            attemptId = attemptId,
            continuationIndex = continuationIndex,
            reason = null,
            accumulatedUtf8Bytes = accumulatedBytes,
            anchorHash = anchorHash,
            replayed = replayed,
        )
        is ChapterDraftContinuationDecision.NeedsAction -> PersistedChapterDraftTruncation(
            action = ChapterDraftTruncationAction.NEEDS_ACTION,
            attemptId = attemptId,
            continuationIndex = null,
            reason = reason,
            accumulatedUtf8Bytes = accumulatedBytes,
            anchorHash = null,
            replayed = replayed,
        )
    }

    private fun FinalUsageCommit.toFinalUpdate(updatedAt: Long) = UsageUpdate(
        source = source,
        status = UsageLedgerStatus.FINAL,
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

    private fun sha256(bytes: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private data class InspectedText(val text: String, val bytes: Int)

    private companion object {
        val CHAPTER_TEXT_PHASES = setOf(GenerationPhase.DRAFT_CHAPTER, GenerationPhase.REVISE_CHAPTER)
        val RECOVERABLE_CLASSIFICATIONS = setOf(
            StandardErrorCode.OUTPUT_TRUNCATED,
            StandardErrorCode.FORMAT_INVALID,
        )
        val RECOVERY_ID = Regex("[A-Za-z0-9._:-]{1,128}")
        const val RECOVERY_REPLAY_LEASE_OWNER = "chapter-settlement-replay"
    }
}

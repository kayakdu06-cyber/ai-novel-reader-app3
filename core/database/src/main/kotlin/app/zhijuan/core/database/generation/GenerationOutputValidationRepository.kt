package app.zhijuan.core.database.generation

import app.zhijuan.core.database.ZhijuanDatabase
import app.zhijuan.core.model.GenerationStageStatus
import app.zhijuan.core.model.RequestAttemptStatus
import app.zhijuan.core.model.StandardErrorCode
import app.zhijuan.core.model.UsageLedgerStatus
import app.zhijuan.core.security.AndroidProtectedArtifactStore
import app.zhijuan.core.security.ProtectedArtifactLease
import app.zhijuan.core.security.ProtectedArtifactType
import app.zhijuan.core.security.StreamingDraftWriteResult
import app.zhijuan.core.task.AttemptEvent
import app.zhijuan.core.task.StageEvent
import java.security.MessageDigest

class CompletedStreamingResponse internal constructor(
    internal val attemptId: String,
    internal val stageId: String,
    internal val artifactRefId: String,
    internal val artifactRevision: Int,
    internal val outputHash: String,
    internal val leaseToken: GenerationLeaseToken,
    val plaintextBytes: Int,
) {
    /**
     * SHA-256 of the decoded plaintext persisted in the protected draft artifact.
     *
     * This is intentionally the only public output evidence. The artifact reference,
     * lease token, and attempt identity remain module-internal so callers cannot forge
     * a commit permit from this value.
     */
    val persistedOutputHash: String
        get() = outputHash

    override fun toString(): String =
        "CompletedStreamingResponse(revision=$artifactRevision, plaintextBytes=$plaintextBytes, evidence=redacted)"
}

class ValidatedOutputCommitPermit internal constructor(
    internal val attemptId: String,
    internal val stageId: String,
    internal val artifactRefId: String,
    internal val artifactRevision: Int,
    internal val rawOutputHash: String,
    internal val leaseToken: GenerationLeaseToken,
    internal val validatedAt: Long,
) {
    override fun toString(): String =
        "ValidatedOutputCommitPermit(revision=$artifactRevision, evidence=redacted)"
}

enum class StructuredOutputInvalidAction {
    REPAIR_REQUIRED,
    NEEDS_ACTION,
}

class GenerationOutputValidationRepository(
    private val database: ZhijuanDatabase,
    private val artifactStore: AndroidProtectedArtifactStore,
) {
    suspend fun recordSuccessfulResponse(
        request: ClaimedStreamingRequest,
        checkpoint: StreamingDraftWriteResult,
        completedAt: Long,
        pendingValidationError: StandardErrorCode? = null,
    ): CompletedStreamingResponse {
        require(completedAt >= request.leaseValidatedAt) {
            "Response completion cannot precede send authorization."
        }
        require(checkpoint.persistedBytes == checkpoint.plaintextBytes) {
            "A completed response must be fully checkpointed before validation."
        }
        require(
            pendingValidationError == null ||
                pendingValidationError in setOf(
                    StandardErrorCode.OUTPUT_TRUNCATED,
                    StandardErrorCode.FORMAT_INVALID,
                ),
        ) { "Successful response classification is invalid." }
        val attempt = requireCurrentAttempt(
            attemptId = request.attemptId,
            artifactRefId = request.artifactRefId,
            expectedAttemptStatus = RequestAttemptStatus.STREAMING,
            expectedStageStatus = GenerationStageStatus.STREAMING,
        )
        val transfer = artifactStore.verify(request.artifactRefId, ProtectedArtifactType.STREAM_DRAFT)
        require(transfer.descriptor.revision == checkpoint.revision) {
            "Completed draft revision changed before response persistence."
        }
        require(transfer.plaintextBytes == checkpoint.plaintextBytes.toLong()) {
            "Completed draft length does not match its terminal checkpoint."
        }
        val outputHash = artifactStore.readBytes(
            artifactRefId = request.artifactRefId,
            expectedType = ProtectedArtifactType.STREAM_DRAFT,
        ).use { lease ->
            require(lease.descriptor.revision == checkpoint.revision) {
                "Completed draft changed while its output hash was calculated."
            }
            lease.withBytes(::sha256)
        }
        database.generationDao().recordAttemptOutcome(
            attemptId = attempt.attemptId,
            event = AttemptEvent.RESPONSE_COMPLETED,
            // Persist the terminal stream classification in the same transaction as the
            // successful response. If the process dies before the coordinator settles the
            // next state, recovery can still distinguish truncation from malformed output.
            errorCode = pendingValidationError,
            httpStatus = null,
            outputHash = outputHash,
            nextRetryAt = null,
            updatedAt = completedAt,
            leaseToken = request.claimedSend.leaseToken,
        )
        return CompletedStreamingResponse(
            attemptId = attempt.attemptId,
            stageId = attempt.stageId,
            artifactRefId = request.artifactRefId,
            artifactRevision = checkpoint.revision,
            outputHash = outputHash,
            leaseToken = request.claimedSend.leaseToken,
            plaintextBytes = checkpoint.plaintextBytes,
        )
    }

    suspend fun openForValidation(
        response: CompletedStreamingResponse,
        maximumBytes: Int,
    ): ProtectedArtifactLease {
        requireCurrentAttempt(
            response = response,
            expectedAttemptStatus = RequestAttemptStatus.SUCCEEDED,
            expectedStageStatus = GenerationStageStatus.VALIDATING,
            expectedError = null,
        )
        return openAndVerify(response, maximumBytes)
    }

    suspend fun openForRepair(
        response: CompletedStreamingResponse,
        maximumBytes: Int,
    ): ProtectedArtifactLease {
        requireCurrentAttempt(
            response = response,
            expectedAttemptStatus = RequestAttemptStatus.SUCCEEDED,
            expectedStageStatus = GenerationStageStatus.RETRY_WAIT,
            expectedError = StandardErrorCode.FORMAT_INVALID,
        )
        return openAndVerify(response, maximumBytes)
    }

    suspend fun recordStructuredOutputValid(
        response: CompletedStreamingResponse,
        validatedAt: Long,
    ): ValidatedOutputCommitPermit {
        requireCurrentAttempt(
            response = response,
            expectedAttemptStatus = RequestAttemptStatus.SUCCEEDED,
            expectedStageStatus = GenerationStageStatus.VALIDATING,
            expectedError = null,
        )
        database.generationDao().transitionStage(
            stageId = response.stageId,
            expectedStatus = GenerationStageStatus.VALIDATING,
            event = StageEvent.OUTPUT_VALID,
            errorCode = null,
            nextRetryAt = null,
            updatedAt = validatedAt,
            leaseToken = response.leaseToken,
        )
        return ValidatedOutputCommitPermit(
            attemptId = response.attemptId,
            stageId = response.stageId,
            artifactRefId = response.artifactRefId,
            artifactRevision = response.artifactRevision,
            rawOutputHash = response.outputHash,
            leaseToken = response.leaseToken,
            validatedAt = validatedAt,
        )
    }

    suspend fun recordStructuredOutputInvalid(
        response: CompletedStreamingResponse,
        repairEligible: Boolean,
        validatedAt: Long,
        usage: FinalUsageCommit = FinalUsageCommit.UNKNOWN,
    ): StructuredOutputInvalidAction {
        requireCurrentAttempt(
            response = response,
            expectedAttemptStatus = RequestAttemptStatus.SUCCEEDED,
            expectedStageStatus = GenerationStageStatus.VALIDATING,
            expectedError = null,
        )
        val repairRequired = database.generationDao().recordStructuredOutputInvalid(
            attemptId = response.attemptId,
            expectedOutputHash = response.outputHash,
            repairEligible = repairEligible,
            updatedAt = validatedAt,
            leaseToken = response.leaseToken,
            usage = usage.toFinalUpdate(validatedAt),
        )
        return if (repairRequired) {
            StructuredOutputInvalidAction.REPAIR_REQUIRED
        } else {
            StructuredOutputInvalidAction.NEEDS_ACTION
        }
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

    private suspend fun requireCurrentAttempt(
        response: CompletedStreamingResponse,
        expectedAttemptStatus: RequestAttemptStatus,
        expectedStageStatus: GenerationStageStatus,
        expectedError: StandardErrorCode?,
    ): RequestAttemptEntity {
        val attempt = requireCurrentAttempt(
            attemptId = response.attemptId,
            artifactRefId = response.artifactRefId,
            expectedAttemptStatus = expectedAttemptStatus,
            expectedStageStatus = expectedStageStatus,
        )
        require(attempt.stageId == response.stageId && attempt.outputHash == response.outputHash) {
            "Completed response no longer matches persisted output evidence."
        }
        require(attempt.standardErrorCode == expectedError) {
            "Completed response validation status changed."
        }
        return attempt
    }

    private suspend fun requireCurrentAttempt(
        attemptId: String,
        artifactRefId: String,
        expectedAttemptStatus: RequestAttemptStatus,
        expectedStageStatus: GenerationStageStatus,
    ): RequestAttemptEntity {
        val dao = database.generationDao()
        val attempt = requireNotNull(dao.findAttempt(attemptId)) {
            "Completed response attempt no longer exists."
        }
        val stage = requireNotNull(dao.findStage(attempt.stageId)) {
            "Completed response stage no longer exists."
        }
        require(attempt.streamDraftRef == artifactRefId) {
            "Completed response draft reference changed."
        }
        require(dao.attemptsForStreamDraft(artifactRefId).singleOrNull()?.attemptId == attemptId) {
            "Completed response draft reference is missing or reused."
        }
        require(attempt.status == expectedAttemptStatus && stage.status == expectedStageStatus) {
            "Completed response state changed before validation."
        }
        require(dao.attemptsForStage(stage.stageId).lastOrNull()?.attemptId == attemptId) {
            "Completed response is not the latest attempt in its stage."
        }
        return attempt
    }

    private fun openAndVerify(
        response: CompletedStreamingResponse,
        maximumBytes: Int,
    ): ProtectedArtifactLease {
        val lease = artifactStore.readBytes(
            artifactRefId = response.artifactRefId,
            expectedType = ProtectedArtifactType.STREAM_DRAFT,
            maximumBytes = maximumBytes,
        )
        try {
            require(lease.descriptor.revision == response.artifactRevision) {
                "Completed response draft revision changed before validation."
            }
            require(lease.withBytes(::sha256) == response.outputHash) {
                "Completed response draft hash changed before validation."
            }
            return lease
        } catch (error: Exception) {
            lease.close()
            throw error
        }
    }

    private companion object {
        fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(value)
            .joinToString(separator = "") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            }
    }
}

package app.zhijuan.core.database.generation

import app.zhijuan.core.database.ZhijuanDatabase
import app.zhijuan.core.model.GenerationStageStatus
import app.zhijuan.core.model.RequestAttemptStatus
import app.zhijuan.core.task.ProviderRecoveryEvidence
import app.zhijuan.core.task.RecoveryDraftEvidence
import app.zhijuan.core.security.AndroidProtectedArtifactStore
import app.zhijuan.core.security.ProtectedArtifactDescriptor
import app.zhijuan.core.security.ProtectedArtifactType
import app.zhijuan.core.security.StreamingDraftBuffer
import app.zhijuan.core.security.StreamingDraftPolicy
import app.zhijuan.core.security.createStreamingDraftBuffer
import app.zhijuan.core.security.resumeStreamingDraftBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class StreamingDraftRetentionPolicy(
    val committedSuccessMillis: Long = 24L * 60L * 60L * 1_000L,
    val unsuccessfulMillis: Long = 7L * 24L * 60L * 60L * 1_000L,
    val orphanMillis: Long = 24L * 60L * 60L * 1_000L,
) {
    init {
        require(committedSuccessMillis in MINIMUM_RETENTION_MILLIS..MAXIMUM_RETENTION_MILLIS)
        require(unsuccessfulMillis in committedSuccessMillis..MAXIMUM_RETENTION_MILLIS)
        require(orphanMillis in MINIMUM_RETENTION_MILLIS..MAXIMUM_RETENTION_MILLIS)
    }

    private companion object {
        const val MINIMUM_RETENTION_MILLIS = 60L * 60L * 1_000L
        const val MAXIMUM_RETENTION_MILLIS = 30L * 24L * 60L * 60L * 1_000L
    }
}

enum class StreamingDraftRecoveryDisposition {
    RECOVERY_REQUIRED,
    RETAINED_AFTER_COMMITTED_SUCCESS,
    RETAINED_AFTER_UNSUCCESSFUL_RESULT,
    ELIGIBLE_FOR_SUCCESS_CLEANUP,
    ELIGIBLE_FOR_UNSUCCESSFUL_CLEANUP,
    ORPHAN_RETAINED,
    ORPHAN_ELIGIBLE_FOR_CLEANUP,
    NOT_REFERENCED,
    MISSING_OR_UNREADABLE,
    REFERENCE_CONFLICT,
    EXPIRED_AND_REMOVED,
}

data class StreamingDraftRecoveryRecord(
    val attemptId: String?,
    val attemptStatus: RequestAttemptStatus?,
    val stageStatus: GenerationStageStatus?,
    val disposition: StreamingDraftRecoveryDisposition,
    val revision: Int?,
    val plaintextBytes: Long?,
) {
    override fun toString(): String =
        "StreamingDraftRecoveryRecord(hasAttempt=${attemptId != null}, " +
            "attemptStatus=$attemptStatus, stageStatus=$stageStatus, disposition=$disposition, " +
            "revision=$revision, plaintextBytes=$plaintextBytes, reference=redacted)"
}

data class StreamingDraftCleanupResult(
    val deletedArtifacts: Int,
    val skippedAfterRecheck: Int,
) {
    init {
        require(deletedArtifacts >= 0 && skippedAfterRecheck >= 0)
    }
}

class PersistedStreamingRequest internal constructor(
    internal val requestAudit: PersistedRequestAudit,
    internal val artifactRefId: String,
    val attempt: StoredRequestAttemptAudit,
    val usage: StoredUsageLedgerAudit,
    val initialDraftRevision: Int,
) {
    val inputHash: String
        get() = requestAudit.permit.inputHash

    override fun toString(): String =
        "PersistedStreamingRequest(attemptNo=${attempt.attemptNo}, " +
            "initialDraftRevision=$initialDraftRevision, audit=redacted)"
}

class ClaimedStreamingRequest internal constructor(
    internal val claimedSend: ClaimedRequestSend,
    internal val artifactRefId: String,
) {
    private val bufferOpened = AtomicBoolean(false)

    internal fun claimBufferOpen() {
        check(bufferOpened.compareAndSet(false, true)) {
            "A claimed streaming request can open its draft buffer only once."
        }
    }

    val attemptId: String
        get() = claimedSend.attemptId

    val leaseValidatedAt: Long
        get() = claimedSend.leaseValidatedAt

    override fun toString(): String =
        "ClaimedStreamingRequest(bufferOpened=${bufferOpened.get()}, audit=redacted)"
}

class GenerationStreamingDraftRepository(
    private val database: ZhijuanDatabase,
    private val artifactStore: AndroidProtectedArtifactStore,
    private val leasePolicy: GenerationLeasePolicy = GenerationLeasePolicy(),
    private val draftPolicy: StreamingDraftPolicy = StreamingDraftPolicy(),
    private val retentionPolicy: StreamingDraftRetentionPolicy = StreamingDraftRetentionPolicy(),
    val controlPollIntervalMillis: Long = 250L,
) {
    private val auditRepository = GenerationRequestAuditRepository(database, leasePolicy)
    private val controlRepository = GenerationControlRepository(database, leasePolicy)
    private val recoveryRepository = GenerationUnknownResultRecoveryRepository(database, leasePolicy)

    init {
        require(controlPollIntervalMillis in 50L..5_000L) {
            "Execution control polling must be between 50 ms and 5 seconds."
        }
    }

    val heartbeatIntervalMillis: Long
        get() = leasePolicy.heartbeatIntervalMillis

    suspend fun prepareBeforeSend(
        draft: RequestIntentDraft,
        leaseToken: GenerationLeaseToken,
    ): PersistedStreamingRequest = prepareBeforeSendInternal(draft, leaseToken, initialDraft = null)

    internal suspend fun prepareContinuationBeforeSend(
        draft: RequestIntentDraft,
        leaseToken: GenerationLeaseToken,
        initialDraft: ByteArray,
    ): PersistedStreamingRequest = prepareBeforeSendInternal(draft, leaseToken, initialDraft)

    private suspend fun prepareBeforeSendInternal(
        draft: RequestIntentDraft,
        leaseToken: GenerationLeaseToken,
        initialDraft: ByteArray?,
    ): PersistedStreamingRequest = LIFECYCLE_LOCK.withLock {
        require(draft.streamDraftRef == null) {
            "The protected stream draft reference is allocated by the repository."
        }
        require(initialDraft == null || initialDraft.size <= draftPolicy.maximumPlaintextBytes) {
            "Continuation seed exceeds the protected draft limit."
        }
        val descriptor = if (initialDraft == null) {
            val buffer = artifactStore.createStreamingDraftBuffer(draft.createdAt, draftPolicy)
            val created = buffer.descriptor
            buffer.close()
            created
        } else {
            artifactStore.createAndClear(
                type = ProtectedArtifactType.STREAM_DRAFT,
                plaintext = initialDraft,
                now = draft.createdAt,
            ).descriptor
        }
        try {
            val audit = auditRepository.persistBeforeSend(
                draft = draft.withStreamDraftRef(descriptor.artifactRefId),
                leaseToken = leaseToken,
            )
            val persistedAttempt = requireNotNull(database.generationDao().findAttempt(audit.attempt.attemptId))
            check(persistedAttempt.streamDraftRef == descriptor.artifactRefId) {
                "Persisted request intent does not reference its protected stream draft."
            }
            PersistedStreamingRequest(
                requestAudit = audit,
                artifactRefId = descriptor.artifactRefId,
                attempt = audit.attempt,
                usage = audit.usage,
                initialDraftRevision = descriptor.revision,
            )
        } catch (error: Exception) {
            try {
                artifactStore.delete(descriptor.artifactRefId)
            } catch (cleanupError: Exception) {
                error.addSuppressed(cleanupError)
            }
            throw error
        }
    }

    suspend fun claimForProviderOpen(
        request: PersistedStreamingRequest,
        validatedAt: Long,
    ): ClaimedStreamingRequest {
        requireUniquePersistedReference(request.attempt.attemptId, request.artifactRefId)
        requireDraftDescriptor(request.artifactRefId)
        val claimed = auditRepository.claimForProviderOpen(
            request.requestAudit.permit,
            validatedAt,
        )
        return ClaimedStreamingRequest(claimed, request.artifactRefId)
    }

    suspend fun openDraftBuffer(request: ClaimedStreamingRequest): StreamingDraftBuffer {
        requireUniquePersistedReference(request.attemptId, request.artifactRefId)
        request.claimBufferOpen()
        return artifactStore.resumeStreamingDraftBuffer(request.artifactRefId, draftPolicy)
    }

    suspend fun markRequestSent(
        request: ClaimedStreamingRequest,
        providerRequestId: String?,
        sentAt: Long,
    ): StoredRequestAttemptAudit = auditRepository.markRequestSent(
        claimedSend = request.claimedSend,
        providerRequestId = providerRequestId,
        sentAt = sentAt,
    )

    suspend fun markStreamStarted(
        request: ClaimedStreamingRequest,
        startedAt: Long,
    ): StoredRequestAttemptAudit = auditRepository.markStreamStarted(
        claimedSend = request.claimedSend,
        startedAt = startedAt,
    )

    suspend fun heartbeat(
        request: ClaimedStreamingRequest,
        now: Long,
    ) {
        database.generationDao().heartbeatStageLease(
            stageId = request.claimedSend.stageId,
            leaseToken = request.claimedSend.leaseToken,
            now = now,
            policy = leasePolicy,
        )
    }

    suspend fun executionControl(request: ClaimedStreamingRequest): GenerationExecutionControl? =
        controlRepository.controlForAttempt(request.attemptId)

    suspend fun settleExecutionControl(
        request: ClaimedStreamingRequest,
        action: GenerationExecutionControl,
        usage: FinalUsageCommit,
        settledAt: Long,
    ): GenerationControlResult = controlRepository.settleActiveAttempt(
        attemptId = request.attemptId,
        leaseToken = request.claimedSend.leaseToken,
        action = action,
        usage = usage,
        settledAt = settledAt,
    )

    suspend fun inspectAttempt(
        attemptId: String,
        now: Long,
    ): StreamingDraftRecoveryRecord {
        require(attemptId.matches(IDENTIFIER_PATTERN)) { "Attempt id is invalid." }
        require(now >= 0L) { "Recovery inspection time is invalid." }
        val attempt = database.generationDao().findAttempt(attemptId)
            ?: return StreamingDraftRecoveryRecord(
                attemptId = null,
                attemptStatus = null,
                stageStatus = null,
                disposition = StreamingDraftRecoveryDisposition.NOT_REFERENCED,
                revision = null,
                plaintextBytes = null,
            )
        return inspectAttached(attempt, now, verifyContent = true).record
    }

    suspend fun inspectRecovery(
        attemptId: String,
        now: Long,
    ): GenerationRecoveryProbe {
        val draft = inspectAttempt(attemptId, now)
        return recoveryRepository.inspect(attemptId, draft.toRecoveryEvidence())
    }

    suspend fun auditExpiredAttempt(
        attemptId: String,
        observedLease: GenerationLeaseToken,
        providerEvidence: ProviderRecoveryEvidence,
        providerUsage: FinalUsageCommit? = null,
        auditedAt: Long,
    ): GenerationRecoveryResult {
        val draft = inspectAttempt(attemptId, auditedAt)
        return recoveryRepository.auditExpiredAttempt(
            attemptId = attemptId,
            observedLease = observedLease,
            draftEvidence = draft.toRecoveryEvidence(),
            providerEvidence = providerEvidence,
            providerUsage = providerUsage,
            auditedAt = auditedAt,
        )
    }

    suspend fun reconcilePendingAttempt(
        attemptId: String,
        providerEvidence: ProviderRecoveryEvidence,
        providerUsage: FinalUsageCommit? = null,
        auditedAt: Long,
    ): GenerationRecoveryResult {
        val draft = inspectAttempt(attemptId, auditedAt)
        return recoveryRepository.reconcilePendingAttempt(
            attemptId = attemptId,
            draftEvidence = draft.toRecoveryEvidence(),
            providerEvidence = providerEvidence,
            providerUsage = providerUsage,
            auditedAt = auditedAt,
        )
    }

    suspend fun markLiveAttemptUnknown(
        request: ClaimedStreamingRequest,
        usage: FinalUsageCommit,
        updatedAt: Long,
    ): GenerationRecoveryResult = recoveryRepository.markLiveAttemptUnknown(
        attemptId = request.attemptId,
        leaseToken = request.claimedSend.leaseToken,
        usage = usage,
        updatedAt = updatedAt,
    )

    suspend fun confirmUnknownResultRetry(
        attemptId: String,
        confirmedAt: Long,
    ): GenerationRecoveryResult = recoveryRepository.confirmRetry(attemptId, confirmedAt)

    suspend fun scanRecovery(now: Long): List<StreamingDraftRecoveryRecord> {
        require(now >= 0L) { "Recovery inspection time is invalid." }
        return scanInternal(now, verifyContent = false).map(InspectedDraft::record)
    }

    suspend fun cleanupExpired(now: Long): StreamingDraftCleanupResult = LIFECYCLE_LOCK.withLock {
        require(now >= 0L) { "Draft cleanup time is invalid." }
        val candidates = scanInternal(now, verifyContent = false).filter { inspected ->
            inspected.record.disposition in CLEANUP_DISPOSITIONS
        }
        var deleted = 0
        var skipped = 0
        candidates.forEach { candidate ->
            if (stillEligibleForCleanup(candidate, now)) {
                artifactStore.delete(candidate.artifactRefId)
                deleted += 1
            } else {
                skipped += 1
            }
        }
        StreamingDraftCleanupResult(deleted, skipped)
    }

    private suspend fun scanInternal(
        now: Long,
        verifyContent: Boolean,
    ): List<InspectedDraft> {
        val dao = database.generationDao()
        val attempts = dao.attemptsWithStreamDraft()
        val attachedRefs = attempts.mapNotNull(RequestAttemptEntity::streamDraftRef).toSet()
        val attached = attempts.map { attempt -> inspectAttached(attempt, now, verifyContent) }
        val orphans = artifactStore.listArtifactReferenceIds()
            .asSequence()
            .filterNot(attachedRefs::contains)
            .mapNotNull { artifactRefId -> inspectOrphan(artifactRefId, now) }
            .toList()
        return attached + orphans
    }

    private suspend fun inspectAttached(
        attempt: RequestAttemptEntity,
        now: Long,
        verifyContent: Boolean,
    ): InspectedDraft {
        val artifactRefId = attempt.streamDraftRef ?: return InspectedDraft(
            artifactRefId = "",
            record = StreamingDraftRecoveryRecord(
                attemptId = attempt.attemptId,
                attemptStatus = attempt.status,
                stageStatus = null,
                disposition = StreamingDraftRecoveryDisposition.NOT_REFERENCED,
                revision = null,
                plaintextBytes = null,
            ),
        )
        val dao = database.generationDao()
        val stage = requireNotNull(dao.findStage(attempt.stageId)) { "Owning stage does not exist." }
        if (dao.attemptsForStreamDraft(artifactRefId).size != 1) {
            return InspectedDraft(
                artifactRefId,
                recoveryRecord(
                    attempt,
                    stage.status,
                    StreamingDraftRecoveryDisposition.REFERENCE_CONFLICT,
                ),
            )
        }
        val transfer = runCatching {
            if (verifyContent) {
                artifactStore.verify(artifactRefId, ProtectedArtifactType.STREAM_DRAFT)
            } else {
                val descriptor = requireDraftDescriptor(artifactRefId)
                app.zhijuan.core.security.ProtectedArtifactTransfer(descriptor, -1L)
            }
        }.getOrNull()
        if (transfer == null) {
            val expired = classifyAttached(attempt, stage.status, stage.updatedAt, now) in CLEANUP_DISPOSITIONS
            return InspectedDraft(
                artifactRefId,
                recoveryRecord(
                    attempt,
                    stage.status,
                    if (expired) {
                        StreamingDraftRecoveryDisposition.EXPIRED_AND_REMOVED
                    } else {
                        StreamingDraftRecoveryDisposition.MISSING_OR_UNREADABLE
                    },
                ),
            )
        }
        return InspectedDraft(
            artifactRefId,
            recoveryRecord(
                attempt = attempt,
                stageStatus = stage.status,
                disposition = classifyAttached(attempt, stage.status, stage.updatedAt, now),
                descriptor = transfer.descriptor,
                plaintextBytes = transfer.plaintextBytes.takeIf { it >= 0L },
            ),
        )
    }

    private fun inspectOrphan(
        artifactRefId: String,
        now: Long,
    ): InspectedDraft? {
        val descriptor = runCatching { requireDraftDescriptor(artifactRefId) }.getOrNull() ?: return null
        val disposition = if (retentionElapsed(descriptor.updatedAt, retentionPolicy.orphanMillis, now)) {
            StreamingDraftRecoveryDisposition.ORPHAN_ELIGIBLE_FOR_CLEANUP
        } else {
            StreamingDraftRecoveryDisposition.ORPHAN_RETAINED
        }
        return InspectedDraft(
            artifactRefId,
            StreamingDraftRecoveryRecord(
                attemptId = null,
                attemptStatus = null,
                stageStatus = null,
                disposition = disposition,
                revision = descriptor.revision,
                plaintextBytes = null,
            ),
        )
    }

    private suspend fun stillEligibleForCleanup(candidate: InspectedDraft, now: Long): Boolean {
        val attempts = database.generationDao().attemptsForStreamDraft(candidate.artifactRefId)
        if (attempts.isEmpty()) {
            val descriptor = runCatching { requireDraftDescriptor(candidate.artifactRefId) }.getOrNull()
                ?: return false
            return retentionElapsed(descriptor.updatedAt, retentionPolicy.orphanMillis, now)
        }
        if (attempts.size != 1) return false
        val attempt = attempts.single()
        val stage = database.generationDao().findStage(attempt.stageId) ?: return false
        return classifyAttached(attempt, stage.status, stage.updatedAt, now) in CLEANUP_DISPOSITIONS
    }

    private suspend fun requireUniquePersistedReference(attemptId: String, artifactRefId: String) {
        val attempts = database.generationDao().attemptsForStreamDraft(artifactRefId)
        check(attempts.size == 1 && attempts.single().attemptId == attemptId) {
            "Protected stream draft reference is missing, reused, or belongs to another attempt."
        }
    }

    private fun requireDraftDescriptor(artifactRefId: String): ProtectedArtifactDescriptor =
        artifactStore.descriptor(artifactRefId).also { descriptor ->
            check(descriptor.type == ProtectedArtifactType.STREAM_DRAFT) {
                "Protected artifact is not a stream draft."
            }
        }

    private fun classifyAttached(
        attempt: RequestAttemptEntity,
        stageStatus: GenerationStageStatus,
        stageUpdatedAt: Long,
        now: Long,
    ): StreamingDraftRecoveryDisposition {
        if (attempt.status == RequestAttemptStatus.SUCCEEDED) {
            if (stageStatus == GenerationStageStatus.SUCCEEDED) {
                return if (retentionElapsed(stageUpdatedAt, retentionPolicy.committedSuccessMillis, now)) {
                    StreamingDraftRecoveryDisposition.ELIGIBLE_FOR_SUCCESS_CLEANUP
                } else {
                    StreamingDraftRecoveryDisposition.RETAINED_AFTER_COMMITTED_SUCCESS
                }
            }
            if (
                attempt.standardErrorCode == null &&
                stageStatus in setOf(
                    GenerationStageStatus.VALIDATING,
                    GenerationStageStatus.COMMITTING,
                )
            ) {
                return StreamingDraftRecoveryDisposition.RECOVERY_REQUIRED
            }
            return if (retentionElapsed(attempt.finishedAt ?: attempt.updatedAt, retentionPolicy.unsuccessfulMillis, now)) {
                StreamingDraftRecoveryDisposition.ELIGIBLE_FOR_UNSUCCESSFUL_CLEANUP
            } else {
                StreamingDraftRecoveryDisposition.RETAINED_AFTER_UNSUCCESSFUL_RESULT
            }
        }
        if (attempt.status in TERMINAL_UNSUCCESSFUL_ATTEMPTS) {
            return if (retentionElapsed(attempt.finishedAt ?: attempt.updatedAt, retentionPolicy.unsuccessfulMillis, now)) {
                StreamingDraftRecoveryDisposition.ELIGIBLE_FOR_UNSUCCESSFUL_CLEANUP
            } else {
                StreamingDraftRecoveryDisposition.RETAINED_AFTER_UNSUCCESSFUL_RESULT
            }
        }
        return StreamingDraftRecoveryDisposition.RECOVERY_REQUIRED
    }

    private fun recoveryRecord(
        attempt: RequestAttemptEntity,
        stageStatus: GenerationStageStatus,
        disposition: StreamingDraftRecoveryDisposition,
        descriptor: ProtectedArtifactDescriptor? = null,
        plaintextBytes: Long? = null,
    ) = StreamingDraftRecoveryRecord(
        attemptId = attempt.attemptId,
        attemptStatus = attempt.status,
        stageStatus = stageStatus,
        disposition = disposition,
        revision = descriptor?.revision,
        plaintextBytes = plaintextBytes,
    )

    private fun retentionElapsed(baseAt: Long, retentionMillis: Long, now: Long): Boolean =
        now >= baseAt && now - baseAt >= retentionMillis

    private data class InspectedDraft(
        val artifactRefId: String,
        val record: StreamingDraftRecoveryRecord,
    )

    private companion object {
        val LIFECYCLE_LOCK = Mutex()
        val IDENTIFIER_PATTERN = Regex("[A-Za-z0-9._:-]{1,128}")
        val TERMINAL_UNSUCCESSFUL_ATTEMPTS = setOf(
            RequestAttemptStatus.FAILED_RETRYABLE,
            RequestAttemptStatus.FAILED_FINAL,
            RequestAttemptStatus.REFUSED,
            RequestAttemptStatus.CANCELLED,
            RequestAttemptStatus.UNKNOWN_RESULT,
        )
        val CLEANUP_DISPOSITIONS = setOf(
            StreamingDraftRecoveryDisposition.ELIGIBLE_FOR_SUCCESS_CLEANUP,
            StreamingDraftRecoveryDisposition.ELIGIBLE_FOR_UNSUCCESSFUL_CLEANUP,
            StreamingDraftRecoveryDisposition.ORPHAN_ELIGIBLE_FOR_CLEANUP,
        )
    }
}

private fun StreamingDraftRecoveryRecord.toRecoveryEvidence(): RecoveryDraftEvidence = when {
    disposition in setOf(
        StreamingDraftRecoveryDisposition.MISSING_OR_UNREADABLE,
        StreamingDraftRecoveryDisposition.REFERENCE_CONFLICT,
        StreamingDraftRecoveryDisposition.EXPIRED_AND_REMOVED,
        StreamingDraftRecoveryDisposition.NOT_REFERENCED,
    ) -> RecoveryDraftEvidence.MISSING_UNREADABLE_OR_CONFLICTING
    plaintextBytes == 0L -> RecoveryDraftEvidence.READABLE_EMPTY
    plaintextBytes != null && plaintextBytes > 0L -> RecoveryDraftEvidence.CONTENT_PRESENT
    else -> RecoveryDraftEvidence.MISSING_UNREADABLE_OR_CONFLICTING
}

private fun RequestIntentDraft.withStreamDraftRef(artifactRefId: String) = RequestIntentDraft(
    attemptId = attemptId,
    usageLedgerId = usageLedgerId,
    stageId = stageId,
    retryParentAttemptId = retryParentAttemptId,
    connectionSnapshotJson = connectionSnapshotJson,
    modelSnapshotJson = modelSnapshotJson,
    protocolSnapshotJson = protocolSnapshotJson,
    inputHash = inputHash,
    streamDraftRef = artifactRefId,
    dailyPeriodKey = dailyPeriodKey,
    createdAt = createdAt,
)

package app.zhijuan.core.database.generation

import androidx.room.withTransaction
import app.zhijuan.core.database.ZhijuanDatabase
import app.zhijuan.core.model.GenerationJobStatus
import app.zhijuan.core.model.GenerationStageStatus
import app.zhijuan.core.model.RequestAttemptStatus
import app.zhijuan.core.model.StandardErrorCode
import app.zhijuan.core.model.UsageLedgerStatus
import app.zhijuan.core.model.UsageSource
import app.zhijuan.core.task.AttemptEvent
import app.zhijuan.core.task.GenerationJobStateMachine
import app.zhijuan.core.task.GenerationStageStateMachine
import app.zhijuan.core.task.JobEvent
import app.zhijuan.core.task.ProviderRecoveryEvidence
import app.zhijuan.core.task.RecoveryDraftEvidence
import app.zhijuan.core.task.RequestAttemptStateMachine
import app.zhijuan.core.task.StageEvent
import app.zhijuan.core.task.UnknownResultRecoveryContext
import app.zhijuan.core.task.UnknownResultRecoveryDecision
import app.zhijuan.core.task.UnknownResultRecoveryPolicy

enum class GenerationRecoveryReason {
    UNKNOWN_RESULT_CONFIRMATION_REQUIRED,
    REMOTE_RESULT_PENDING,
    LOCAL_RESULT_RECOVERY_REQUIRED,
}

enum class GenerationRecoveryDisposition {
    REQUEUED_AFTER_PROVIDER_PROOF,
    WAITING_FOR_REMOTE_RESULT,
    USER_CONFIRMATION_REQUIRED,
    LOCAL_RECOVERY_REQUIRED,
    USER_CONFIRMED_RETRY,
    CONTROL_SETTLEMENT_REQUIRED,
    ALREADY_SETTLED,
    NO_WORK,
}

class PersistedProviderRequestReference internal constructor(
    private val value: String,
) {
    fun <T> withValue(block: (String) -> T): T = block(value)

    override fun toString(): String = "<persisted-provider-request-reference>"
}

data class GenerationRecoveryProbe(
    val attemptId: String,
    val attemptStatus: RequestAttemptStatus,
    val stageStatus: GenerationStageStatus,
    val jobStatus: GenerationJobStatus,
    val sentAtRecorded: Boolean,
    val providerRequestReference: PersistedProviderRequestReference?,
    val draftEvidence: RecoveryDraftEvidence,
    val knownUsageObserved: Boolean,
    val usageFinal: Boolean,
    val leaseToken: GenerationLeaseToken?,
    val leaseHeartbeatAt: Long?,
) {
    override fun toString(): String =
        "GenerationRecoveryProbe(attemptStatus=$attemptStatus, stageStatus=$stageStatus, " +
            "jobStatus=$jobStatus, sentAtRecorded=$sentAtRecorded, " +
            "hasProviderRequestId=${providerRequestReference != null}, draftEvidence=$draftEvidence, " +
            "knownUsageObserved=$knownUsageObserved, usageFinal=$usageFinal, identifiers=redacted)"
}

data class GenerationRecoveryResult(
    val disposition: GenerationRecoveryDisposition,
    val attemptStatus: RequestAttemptStatus,
    val stageStatus: GenerationStageStatus,
    val jobStatus: GenerationJobStatus,
) {
    override fun toString(): String =
        "GenerationRecoveryResult(disposition=$disposition, attemptStatus=$attemptStatus, " +
            "stageStatus=$stageStatus, jobStatus=$jobStatus, identifiers=redacted)"
}

class GenerationUnknownResultRecoveryRepository(
    private val database: ZhijuanDatabase,
    private val leasePolicy: GenerationLeasePolicy = GenerationLeasePolicy(),
) {
    suspend fun inspect(
        attemptId: String,
        draftEvidence: RecoveryDraftEvidence,
    ): GenerationRecoveryProbe = database.withTransaction {
        val dao = database.generationDao()
        val attempt = requireNotNull(dao.findAttempt(attemptId)) { "Recovery attempt does not exist." }
        val stage = requireNotNull(dao.findStage(attempt.stageId)) { "Recovery stage does not exist." }
        val job = requireNotNull(dao.findJob(attempt.jobId)) { "Recovery job does not exist." }
        val usage = requireNotNull(dao.findUsageForAttempt(attempt.attemptId)) {
            "Recovery usage ledger does not exist."
        }
        require(dao.attemptsForStage(stage.stageId).lastOrNull()?.attemptId == attempt.attemptId) {
            "Only the latest attempt can be recovered."
        }
        GenerationRecoveryProbe(
            attemptId = attempt.attemptId,
            attemptStatus = attempt.status,
            stageStatus = stage.status,
            jobStatus = job.status,
            sentAtRecorded = attempt.sentAt != null,
            providerRequestReference = attempt.providerRequestId?.let(::PersistedProviderRequestReference),
            draftEvidence = draftEvidence,
            knownUsageObserved = usage.hasKnownUsage(),
            usageFinal = usage.status == UsageLedgerStatus.FINAL,
            leaseToken = stage.leaseTokenOrNull(),
            leaseHeartbeatAt = stage.leaseHeartbeatAt,
        )
    }

    suspend fun auditExpiredAttempt(
        attemptId: String,
        observedLease: GenerationLeaseToken,
        draftEvidence: RecoveryDraftEvidence,
        providerEvidence: ProviderRecoveryEvidence,
        providerUsage: FinalUsageCommit? = null,
        auditedAt: Long,
    ): GenerationRecoveryResult = database.withTransaction {
        val evidence = requireRecoveryEvidence(
            attemptId = attemptId,
            draftEvidence = draftEvidence,
            auditedAt = auditedAt,
        )
        if (evidence.job.status in setOf(GenerationJobStatus.PAUSING, GenerationJobStatus.STOPPING)) {
            return@withTransaction evidence.result(GenerationRecoveryDisposition.CONTROL_SETTLEMENT_REQUIRED)
        }
        require(evidence.job.status == GenerationJobStatus.RUNNING) {
            "Expired network recovery requires a running job."
        }
        require(evidence.stage.leaseTokenOrNull() == observedLease) { "Recovery stage lease changed." }
        val heartbeatAt = requireNotNull(evidence.stage.leaseHeartbeatAt) { "Recovery stage has no lease heartbeat." }
        require(leasePolicy.isExpired(heartbeatAt, auditedAt)) { "Active stage lease cannot be recovered." }
        settle(evidence, providerEvidence, providerUsage, auditedAt)
    }

    suspend fun reconcilePendingAttempt(
        attemptId: String,
        draftEvidence: RecoveryDraftEvidence,
        providerEvidence: ProviderRecoveryEvidence,
        providerUsage: FinalUsageCommit? = null,
        auditedAt: Long,
    ): GenerationRecoveryResult = database.withTransaction {
        val evidence = requireRecoveryEvidence(attemptId, draftEvidence, auditedAt)
        require(evidence.stage.status == GenerationStageStatus.RECOVERY_REQUIRED) {
            "Only a pending recovery audit can be reconciled without a lease."
        }
        require(evidence.stage.leaseTokenOrNull() == null) { "Pending recovery must not retain a stage lease." }
        require(
            evidence.job.status == GenerationJobStatus.NEEDS_ACTION &&
                evidence.job.pauseOrStopReason in RECOVERY_REASONS,
        ) { "Pending recovery job evidence is missing or inconsistent." }
        settle(evidence, providerEvidence, providerUsage, auditedAt)
    }

    suspend fun markLiveAttemptUnknown(
        attemptId: String,
        leaseToken: GenerationLeaseToken,
        usage: FinalUsageCommit,
        updatedAt: Long,
    ): GenerationRecoveryResult = database.withTransaction {
        val evidence = requireRecoveryEvidence(
            attemptId = attemptId,
            draftEvidence = RecoveryDraftEvidence.MISSING_UNREADABLE_OR_CONFLICTING,
            auditedAt = updatedAt,
        )
        require(evidence.job.status == GenerationJobStatus.RUNNING) {
            "A controlled or inactive job cannot be settled as an unknown live request."
        }
        require(evidence.stage.leaseTokenOrNull() == leaseToken) { "Live recovery stage lease changed." }
        val heartbeatAt = requireNotNull(evidence.stage.leaseHeartbeatAt)
        require(!leasePolicy.isExpired(heartbeatAt, updatedAt)) { "Expired requests require recovery audit." }
        requireUserConfirmation(evidence, usage, updatedAt)
    }

    suspend fun confirmRetry(
        attemptId: String,
        confirmedAt: Long,
    ): GenerationRecoveryResult = database.withTransaction {
        val evidence = requireRecoveryEvidence(
            attemptId = attemptId,
            draftEvidence = RecoveryDraftEvidence.MISSING_UNREADABLE_OR_CONFLICTING,
            auditedAt = confirmedAt,
        )
        require(evidence.attempt.status == RequestAttemptStatus.UNKNOWN_RESULT) {
            "Only an unknown attempt can be explicitly retried."
        }
        require(evidence.stage.status == GenerationStageStatus.UNKNOWN_RESULT) {
            "Unknown-result retry requires an UNKNOWN_RESULT stage."
        }
        require(
            evidence.job.status == GenerationJobStatus.NEEDS_ACTION &&
                evidence.job.pauseOrStopReason ==
                GenerationRecoveryReason.UNKNOWN_RESULT_CONFIRMATION_REQUIRED.name,
        ) { "Unknown-result retry confirmation gate is missing." }
        require(evidence.usage.status == UsageLedgerStatus.FINAL) {
            "Unknown-result usage must be finalized before retry confirmation."
        }
        val nextStage = GenerationStageStateMachine.transition(
            evidence.stage.status,
            StageEvent.USER_CONFIRMED_RETRY,
        )
        val nextJob = GenerationJobStateMachine.transition(
            evidence.job.status,
            JobEvent.ISSUE_RESOLVED,
        )
        check(
            evidence.dao.compareAndSetStageStatus(
                stageId = evidence.stage.stageId,
                expectedStatus = evidence.stage.status,
                nextStatus = nextStage,
                errorCode = null,
                nextRetryAt = null,
                updatedAt = confirmedAt,
            ) == 1,
        ) { "Unknown-result stage confirmation lost a concurrent update." }
        check(
            evidence.dao.compareAndSetJobControlStatus(
                jobId = evidence.job.jobId,
                expectedStatus = evidence.job.status,
                nextStatus = nextJob,
                reason = null,
                updatedAt = confirmedAt,
            ) == 1,
        ) { "Unknown-result job confirmation lost a concurrent update." }
        currentResult(evidence.dao, evidence.attempt.attemptId, GenerationRecoveryDisposition.USER_CONFIRMED_RETRY)
    }

    private suspend fun settle(
        evidence: RecoveryEvidence,
        providerEvidence: ProviderRecoveryEvidence,
        providerUsage: FinalUsageCommit?,
        auditedAt: Long,
    ): GenerationRecoveryResult {
        val decision = UnknownResultRecoveryPolicy.evaluate(
            UnknownResultRecoveryContext(
                attemptStatus = evidence.attempt.status,
                stageStatus = evidence.stage.status,
                sentAtRecorded = evidence.attempt.sentAt != null,
                providerRequestIdRecorded = evidence.attempt.providerRequestId != null,
                draftEvidence = evidence.draftEvidence,
                knownUsageObserved = evidence.usage.hasKnownUsage(),
                providerEvidence = providerEvidence,
            ),
        )
        return when (decision) {
            UnknownResultRecoveryDecision.REQUEUE_PROVEN_NOT_EXECUTED ->
                requeueAfterProviderProof(evidence, auditedAt)
            UnknownResultRecoveryDecision.RECONCILE_WITHOUT_NEW_REQUEST ->
                waitForRemoteResult(evidence, auditedAt)
            UnknownResultRecoveryDecision.REQUIRE_USER_RETRY_CONFIRMATION ->
                requireUserConfirmation(evidence, providerUsage, auditedAt)
            UnknownResultRecoveryDecision.RECOVER_LOCAL_RESULT_WITHOUT_NEW_REQUEST ->
                requireLocalRecovery(evidence, providerUsage, auditedAt)
            UnknownResultRecoveryDecision.NO_WORK -> evidence.result(GenerationRecoveryDisposition.NO_WORK)
        }
    }

    private suspend fun requeueAfterProviderProof(
        evidence: RecoveryEvidence,
        updatedAt: Long,
    ): GenerationRecoveryResult {
        val nextAttempt = RequestAttemptStateMachine.transition(
            evidence.attempt.status,
            AttemptEvent.RETRYABLE_FAILURE,
        )
        val nextStage = GenerationStageStateMachine.transition(
            evidence.stage.status,
            StageEvent.PROVIDER_CONFIRMED_NOT_EXECUTED,
        )
        val nextJob = when (evidence.job.status) {
            GenerationJobStatus.RUNNING -> GenerationJobStateMachine.transition(
                evidence.job.status,
                JobEvent.RECOVERY_REQUEUED,
            )
            GenerationJobStatus.NEEDS_ACTION -> GenerationJobStateMachine.transition(
                evidence.job.status,
                JobEvent.ISSUE_RESOLVED,
            )
            else -> error("Recovery requeue job is not eligible.")
        }
        check(
            evidence.dao.compareAndSetAttemptStatus(
                attemptId = evidence.attempt.attemptId,
                expectedStatus = evidence.attempt.status,
                nextStatus = nextAttempt,
                providerRequestId = null,
                errorCode = StandardErrorCode.UNKNOWN_RESULT,
                httpStatus = null,
                outputHash = null,
                updatedAt = updatedAt,
            ) == 1,
        ) { "Provider-proof attempt requeue lost a concurrent update." }
        finalizeUsage(evidence.dao, evidence.usage, providerUsage = null, updatedAt)
        check(
            evidence.dao.compareAndSetStageStatus(
                evidence.stage.stageId,
                evidence.stage.status,
                nextStage,
                null,
                null,
                updatedAt,
            ) == 1,
        ) { "Provider-proof stage requeue lost a concurrent update." }
        check(
            evidence.dao.compareAndSetJobControlStatus(
                evidence.job.jobId,
                evidence.job.status,
                nextJob,
                null,
                updatedAt,
            ) == 1,
        ) { "Provider-proof job requeue lost a concurrent update." }
        return currentResult(
            evidence.dao,
            evidence.attempt.attemptId,
            GenerationRecoveryDisposition.REQUEUED_AFTER_PROVIDER_PROOF,
        )
    }

    private suspend fun waitForRemoteResult(
        evidence: RecoveryEvidence,
        updatedAt: Long,
    ): GenerationRecoveryResult {
        if (
            evidence.stage.status == GenerationStageStatus.RECOVERY_REQUIRED &&
            evidence.job.status == GenerationJobStatus.NEEDS_ACTION &&
            evidence.job.pauseOrStopReason == GenerationRecoveryReason.REMOTE_RESULT_PENDING.name
        ) {
            return evidence.result(GenerationRecoveryDisposition.WAITING_FOR_REMOTE_RESULT)
        }
        val nextStage = GenerationStageStateMachine.transition(
            evidence.stage.status,
            StageEvent.RECOVERY_AUDIT_REQUIRED,
        )
        val nextJob = GenerationJobStateMachine.transition(
            evidence.job.status,
            JobEvent.USER_ACTION_REQUIRED,
        )
        check(
            evidence.dao.compareAndSetStageStatus(
                evidence.stage.stageId,
                evidence.stage.status,
                nextStage,
                StandardErrorCode.UNKNOWN_RESULT,
                null,
                updatedAt,
            ) == 1,
        ) { "Pending provider recovery lost the stage." }
        check(
            evidence.dao.compareAndSetJobControlStatus(
                evidence.job.jobId,
                evidence.job.status,
                nextJob,
                GenerationRecoveryReason.REMOTE_RESULT_PENDING.name,
                updatedAt,
            ) == 1,
        ) { "Pending provider recovery lost the job." }
        return currentResult(
            evidence.dao,
            evidence.attempt.attemptId,
            GenerationRecoveryDisposition.WAITING_FOR_REMOTE_RESULT,
        )
    }

    private suspend fun requireUserConfirmation(
        evidence: RecoveryEvidence,
        providerUsage: FinalUsageCommit?,
        updatedAt: Long,
    ): GenerationRecoveryResult {
        if (
            evidence.attempt.status == RequestAttemptStatus.UNKNOWN_RESULT &&
            evidence.stage.status == GenerationStageStatus.UNKNOWN_RESULT &&
            evidence.job.status == GenerationJobStatus.NEEDS_ACTION &&
            evidence.job.pauseOrStopReason ==
            GenerationRecoveryReason.UNKNOWN_RESULT_CONFIRMATION_REQUIRED.name
        ) {
            return evidence.result(GenerationRecoveryDisposition.USER_CONFIRMATION_REQUIRED)
        }
        val nextAttempt = RequestAttemptStateMachine.transition(
            evidence.attempt.status,
            AttemptEvent.RESULT_UNCERTAIN,
        )
        val nextStage = GenerationStageStateMachine.transition(
            evidence.stage.status,
            StageEvent.RESULT_UNCERTAIN,
        )
        check(
            evidence.dao.compareAndSetAttemptStatus(
                evidence.attempt.attemptId,
                evidence.attempt.status,
                nextAttempt,
                null,
                StandardErrorCode.UNKNOWN_RESULT,
                null,
                null,
                updatedAt,
            ) == 1,
        ) { "Unknown-result attempt settlement lost a concurrent update." }
        finalizeUsage(evidence.dao, evidence.usage, providerUsage, updatedAt)
        check(
            evidence.dao.compareAndSetStageStatus(
                evidence.stage.stageId,
                evidence.stage.status,
                nextStage,
                StandardErrorCode.UNKNOWN_RESULT,
                null,
                updatedAt,
            ) == 1,
        ) { "Unknown-result stage settlement lost a concurrent update." }
        val nextJob = when (evidence.job.status) {
            GenerationJobStatus.RUNNING -> GenerationJobStateMachine.transition(
                evidence.job.status,
                JobEvent.USER_ACTION_REQUIRED,
            )
            GenerationJobStatus.NEEDS_ACTION -> GenerationJobStatus.NEEDS_ACTION
            else -> error("Unknown-result job is not eligible for user confirmation.")
        }
        check(
            evidence.dao.compareAndSetJobControlStatus(
                evidence.job.jobId,
                evidence.job.status,
                nextJob,
                GenerationRecoveryReason.UNKNOWN_RESULT_CONFIRMATION_REQUIRED.name,
                updatedAt,
            ) == 1,
        ) { "Unknown-result job settlement lost a concurrent update." }
        return currentResult(
            evidence.dao,
            evidence.attempt.attemptId,
            GenerationRecoveryDisposition.USER_CONFIRMATION_REQUIRED,
        )
    }

    private suspend fun requireLocalRecovery(
        evidence: RecoveryEvidence,
        providerUsage: FinalUsageCommit?,
        updatedAt: Long,
    ): GenerationRecoveryResult {
        if (
            evidence.stage.status == GenerationStageStatus.RECOVERY_REQUIRED &&
            evidence.job.status == GenerationJobStatus.NEEDS_ACTION &&
            evidence.job.pauseOrStopReason == GenerationRecoveryReason.LOCAL_RESULT_RECOVERY_REQUIRED.name
        ) {
            return evidence.result(GenerationRecoveryDisposition.LOCAL_RECOVERY_REQUIRED)
        }
        val nextStage = GenerationStageStateMachine.transition(
            evidence.stage.status,
            StageEvent.RECOVERY_AUDIT_REQUIRED,
        )
        val nextJob = GenerationJobStateMachine.transition(
            evidence.job.status,
            JobEvent.USER_ACTION_REQUIRED,
        )
        finalizeUsage(evidence.dao, evidence.usage, providerUsage, updatedAt)
        check(
            evidence.dao.compareAndSetStageStatus(
                evidence.stage.stageId,
                evidence.stage.status,
                nextStage,
                null,
                null,
                updatedAt,
            ) == 1,
        ) { "Local recovery lost the stage." }
        check(
            evidence.dao.compareAndSetJobControlStatus(
                evidence.job.jobId,
                evidence.job.status,
                nextJob,
                GenerationRecoveryReason.LOCAL_RESULT_RECOVERY_REQUIRED.name,
                updatedAt,
            ) == 1,
        ) { "Local recovery lost the job." }
        return currentResult(
            evidence.dao,
            evidence.attempt.attemptId,
            GenerationRecoveryDisposition.LOCAL_RECOVERY_REQUIRED,
        )
    }

    private suspend fun requireRecoveryEvidence(
        attemptId: String,
        draftEvidence: RecoveryDraftEvidence,
        auditedAt: Long,
    ): RecoveryEvidence {
        require(auditedAt >= 0L) { "Recovery audit time is invalid." }
        val dao = database.generationDao()
        val attempt = requireNotNull(dao.findAttempt(attemptId)) { "Recovery attempt does not exist." }
        val stage = requireNotNull(dao.findStage(attempt.stageId)) { "Recovery stage does not exist." }
        val job = requireNotNull(dao.findJob(attempt.jobId)) { "Recovery job does not exist." }
        val usage = requireNotNull(dao.findUsageForAttempt(attempt.attemptId)) {
            "Recovery usage ledger does not exist."
        }
        require(job.currentStageId == stage.stageId) { "Recovery attempt is no longer the current stage." }
        require(dao.attemptsForStage(stage.stageId).lastOrNull()?.attemptId == attempt.attemptId) {
            "Only the latest attempt can be recovered."
        }
        require(
            auditedAt >= attempt.updatedAt && auditedAt >= stage.updatedAt && auditedAt >= job.updatedAt &&
                auditedAt >= usage.updatedAt,
        ) { "Recovery audit time cannot move backwards." }
        return RecoveryEvidence(dao, attempt, stage, job, usage, draftEvidence)
    }

    private suspend fun finalizeUsage(
        dao: GenerationDao,
        current: UsageLedgerEntity,
        providerUsage: FinalUsageCommit?,
        updatedAt: Long,
    ) {
        val update = providerUsage
            ?.takeIf { sourceRank(it.source) >= sourceRank(current.source) }
            ?.toFinalUpdate(updatedAt)
            ?: current.toFinalUpdate(updatedAt)
        if (current.status == UsageLedgerStatus.FINAL && providerUsage == null) return
        dao.recordUsage(current.attemptId, update)
    }

    private suspend fun currentResult(
        dao: GenerationDao,
        attemptId: String,
        disposition: GenerationRecoveryDisposition,
    ): GenerationRecoveryResult {
        val attempt = requireNotNull(dao.findAttempt(attemptId))
        val stage = requireNotNull(dao.findStage(attempt.stageId))
        val job = requireNotNull(dao.findJob(attempt.jobId))
        return GenerationRecoveryResult(disposition, attempt.status, stage.status, job.status)
    }

    private fun RecoveryEvidence.result(disposition: GenerationRecoveryDisposition) =
        GenerationRecoveryResult(disposition, attempt.status, stage.status, job.status)

    private fun UsageLedgerEntity.hasKnownUsage(): Boolean =
        source != UsageSource.UNKNOWN || totalTokens != null || estimatedCostMicros != null

    private fun UsageLedgerEntity.toFinalUpdate(updatedAt: Long) = UsageUpdate(
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

    private fun sourceRank(source: UsageSource): Int = when (source) {
        UsageSource.UNKNOWN -> 0
        UsageSource.ESTIMATED -> 1
        UsageSource.PROVIDER_REPORTED -> 2
    }

    private data class RecoveryEvidence(
        val dao: GenerationDao,
        val attempt: RequestAttemptEntity,
        val stage: GenerationStageEntity,
        val job: GenerationJobEntity,
        val usage: UsageLedgerEntity,
        val draftEvidence: RecoveryDraftEvidence,
    )

    private companion object {
        val RECOVERY_REASONS = GenerationRecoveryReason.entries.map(Enum<*>::name).toSet()
    }
}

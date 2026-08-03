package app.zhijuan.core.database.generation

import androidx.room.withTransaction
import app.zhijuan.core.database.ZhijuanDatabase
import app.zhijuan.core.model.GenerationJobStatus
import app.zhijuan.core.model.GenerationPhase
import app.zhijuan.core.model.GenerationStageStatus
import app.zhijuan.core.model.RequestAttemptStatus
import app.zhijuan.core.model.UsageLedgerStatus
import app.zhijuan.core.task.ChapterRevisionNeedsActionReasonV1
import app.zhijuan.core.task.GenerationJobStateMachine
import app.zhijuan.core.task.GenerationStageStateMachine
import app.zhijuan.core.task.JobEvent
import app.zhijuan.core.task.StageEvent

data class ChapterRevisionNeedsActionSettlement(
    val stageId: String,
    val attemptId: String,
    val reason: ChapterRevisionNeedsActionReasonV1,
    val replayed: Boolean,
)

/**
 * Persists a validly received revision that cannot safely continue (unchanged body,
 * cycle, or minimum-length failure). This is a quality-gate outcome, not a transport
 * or JSON-format failure, so the successful Attempt keeps its output hash and the
 * Stage/Job move to NEEDS_ACTION without inventing a provider error.
 */
class ChapterRevisionOutcomeRepository(
    private val database: ZhijuanDatabase,
    private val leasePolicy: GenerationLeasePolicy = GenerationLeasePolicy(),
) {
    suspend fun settleNeedsAction(
        response: CompletedStreamingResponse,
        reason: ChapterRevisionNeedsActionReasonV1,
        usage: FinalUsageCommit,
        settledAt: Long,
    ): ChapterRevisionNeedsActionSettlement = database.withTransaction {
        require(settledAt >= 0L)
        val dao = database.generationDao()
        val attempt = requireNotNull(dao.findAttempt(response.attemptId)) {
            "Revision attempt no longer exists."
        }
        val stage = requireNotNull(dao.findStage(response.stageId)) {
            "Revision stage no longer exists."
        }
        val job = requireNotNull(dao.findJob(stage.jobId)) {
            "Revision job no longer exists."
        }
        require(
            stage.phase == GenerationPhase.REVISE_CHAPTER &&
                attempt.stageId == stage.stageId &&
                attempt.status == RequestAttemptStatus.SUCCEEDED &&
                attempt.standardErrorCode == null &&
                attempt.outputHash == response.outputHash &&
                attempt.streamDraftRef == response.artifactRefId &&
                dao.attemptsForStage(stage.stageId).lastOrNull()?.attemptId == attempt.attemptId,
        ) { "Revision quality outcome no longer matches the latest successful response." }

        if (stage.status == GenerationStageStatus.NEEDS_ACTION) {
            require(job.status in setOf(GenerationJobStatus.NEEDS_ACTION, GenerationJobStatus.PAUSED)) {
                "Revision Stage and Job disagree about the persisted needs-action outcome."
            }
            val ledger = requireNotNull(dao.findUsageForAttempt(attempt.attemptId))
            require(ledger.status == UsageLedgerStatus.FINAL) {
                "A replayed revision outcome must already have final usage."
            }
            dao.recordUsage(attempt.attemptId, usage.toFinalUpdate(settledAt))
            return@withTransaction ChapterRevisionNeedsActionSettlement(
                stage.stageId,
                attempt.attemptId,
                reason,
                replayed = true,
            )
        }

        require(stage.status == GenerationStageStatus.VALIDATING) {
            "A revision quality outcome can only settle from VALIDATING."
        }
        require(
            job.status in setOf(GenerationJobStatus.RUNNING, GenerationJobStatus.PAUSING) &&
                job.currentStageId == stage.stageId,
        ) { "Revision job is no longer running the candidate being settled." }
        requireActiveLease(stage, response.leaseToken, settledAt)
        require(settledAt >= attempt.updatedAt && settledAt >= job.updatedAt)
        check(
            GenerationStageStateMachine.transition(stage.status, StageEvent.USER_ACTION_REQUIRED) ==
                GenerationStageStatus.NEEDS_ACTION,
        )
        if (
            dao.compareAndSetStageStatus(
                stageId = stage.stageId,
                expectedStatus = GenerationStageStatus.VALIDATING,
                nextStatus = GenerationStageStatus.NEEDS_ACTION,
                errorCode = null,
                nextRetryAt = null,
                updatedAt = settledAt,
            ) != 1
        ) throw StaleGenerationStateException("Revision outcome lost the current Stage state.")

        val nextJobStatus = if (job.status == GenerationJobStatus.PAUSING) {
            check(
                GenerationJobStateMachine.transition(job.status, JobEvent.SAFE_POINT_REACHED) ==
                    GenerationJobStatus.PAUSED,
            )
            GenerationJobStatus.PAUSED
        } else {
            check(
                GenerationJobStateMachine.transition(job.status, JobEvent.USER_ACTION_REQUIRED) ==
                    GenerationJobStatus.NEEDS_ACTION,
            )
            GenerationJobStatus.NEEDS_ACTION
        }
        if (
            dao.compareAndSetJobControlStatus(
                jobId = job.jobId,
                expectedStatus = job.status,
                nextStatus = nextJobStatus,
                reason = "CHAPTER_REVISION:${reason.name}",
                updatedAt = settledAt,
            ) != 1
        ) throw StaleGenerationStateException("Revision outcome lost the current Job state.")

        dao.recordUsage(attempt.attemptId, usage.toFinalUpdate(settledAt))
        ChapterRevisionNeedsActionSettlement(
            stage.stageId,
            attempt.attemptId,
            reason,
            replayed = false,
        )
    }

    private fun requireActiveLease(
        stage: GenerationStageEntity,
        token: GenerationLeaseToken,
        operationAt: Long,
    ) {
        require(stage.leaseOwnerId == token.ownerId && stage.leaseAcquiredAt == token.acquiredAt) {
            "Revision Stage lease changed before quality settlement."
        }
        val heartbeatAt = requireNotNull(stage.leaseHeartbeatAt)
        require(operationAt >= stage.updatedAt && operationAt >= heartbeatAt)
        if (leasePolicy.isExpired(heartbeatAt, operationAt)) {
            throw StaleGenerationStateException("Revision Stage lease expired before quality settlement.")
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
}

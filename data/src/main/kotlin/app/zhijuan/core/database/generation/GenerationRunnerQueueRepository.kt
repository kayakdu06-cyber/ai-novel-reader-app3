package app.zhijuan.core.database.generation

import androidx.room.withTransaction
import app.zhijuan.core.database.ZhijuanDatabase
import app.zhijuan.core.model.GenerationJobStatus
import app.zhijuan.core.model.GenerationPhase
import app.zhijuan.core.model.GenerationStageStatus
import app.zhijuan.core.model.GenerationTargetType

data class GenerationRunnerQueueCandidate(
    val jobId: String,
    val jobStatus: GenerationJobStatus,
    val currentStageId: String,
    val currentStageStatus: GenerationStageStatus,
    val jobUpdatedAt: Long,
    val stageUpdatedAt: Long,
) {
    override fun toString(): String =
        "GenerationRunnerQueueCandidate(jobStatus=$jobStatus, " +
            "currentStageStatus=$currentStageStatus, identifiers=redacted)"
}

data class GenerationRunnerQueueScan(
    val candidates: List<GenerationRunnerQueueCandidate>,
    val hasMore: Boolean,
)

data class GenerationRunnerQueueStageSnapshot(
    val stageId: String,
    val jobId: String,
    val phase: GenerationPhase,
    val targetType: GenerationTargetType,
    val status: GenerationStageStatus,
    val attemptCount: Int,
    val maxAttempts: Int,
) {
    override fun toString(): String =
        "GenerationRunnerQueueStageSnapshot(phase=$phase, targetType=$targetType, status=$status, " +
            "attemptCount=$attemptCount, maxAttempts=$maxAttempts, identifiers=redacted)"
}

data class GenerationRunnerQueueClaimResult(
    val jobId: String,
    val jobStatus: GenerationJobStatus,
    val jobLeaseToken: GenerationLeaseToken,
    val currentStage: GenerationRunnerQueueStageSnapshot,
) {
    override fun toString(): String =
        "GenerationRunnerQueueClaimResult(jobStatus=$jobStatus, " +
            "jobLeaseToken=GenerationLeaseToken(ownerId=redacted, acquiredAt=${jobLeaseToken.acquiredAt}), " +
            "currentStage=$currentStage, identifiers=redacted)"
}

data class GenerationRunnerQueueHeartbeatResult(
    val jobStatus: GenerationJobStatus,
    val jobUpdatedAt: Long,
    val currentStage: GenerationRunnerQueueStageSnapshot,
) {
    override fun toString(): String =
        "GenerationRunnerQueueHeartbeatResult(jobStatus=$jobStatus, jobUpdatedAt=$jobUpdatedAt, " +
            "currentStage=$currentStage, identifiers=redacted)"
}

/** Persistent queue over the existing Job/current-Stage cursor; never owns business writes. */
class GenerationRunnerQueueRepository(
    private val database: ZhijuanDatabase,
    private val leasePolicy: GenerationLeasePolicy = GenerationLeasePolicy(),
) {
    suspend fun findReadyJob(
        jobId: String,
        observedAt: Long,
    ): GenerationRunnerQueueCandidate? {
        require(RUNNER_OWNER_ID_REGEX.matches(jobId)) { "Runner queue Job id is invalid." }
        require(observedAt >= 0L) { "Runner queue time is invalid." }
        return database.withTransaction {
            val dao = database.generationDao()
            val job = dao.findJob(jobId) ?: return@withTransaction null
            val stageId = job.currentStageId ?: return@withTransaction null
            val stage = dao.findStage(stageId) ?: return@withTransaction null
            if (
                job.status != GenerationJobStatus.READY ||
                job.updatedAt > observedAt ||
                job.leaseTokenOrNull() != null ||
                stage.jobId != job.jobId ||
                stage.status != GenerationStageStatus.READY ||
                stage.updatedAt > observedAt ||
                stage.leaseTokenOrNull() != null
            ) return@withTransaction null
            GenerationRunnerQueueCandidate(
                jobId = job.jobId,
                jobStatus = job.status,
                currentStageId = stage.stageId,
                currentStageStatus = stage.status,
                jobUpdatedAt = job.updatedAt,
                stageUpdatedAt = stage.updatedAt,
            )
        }
    }

    suspend fun scanReadyJobs(
        observedAt: Long,
        limit: Int = DEFAULT_BATCH_LIMIT,
    ): GenerationRunnerQueueScan {
        require(observedAt >= 0L) { "Runner queue time is invalid." }
        require(limit in 1..MAX_BATCH_LIMIT) { "Runner queue batch limit is invalid." }
        val rows = database.generationDao().readyJobsForRunnerQueue(observedAt, limit + 1)
        return GenerationRunnerQueueScan(
            candidates = rows.take(limit).map(GenerationRunnerQueueRow::toCandidate),
            hasMore = rows.size > limit,
        )
    }

    suspend fun claimReadyJob(
        candidate: GenerationRunnerQueueCandidate,
        runnerOwnerId: String,
        claimedAt: Long,
    ): GenerationRunnerQueueClaimResult {
        requireValidRunnerOwnerId(runnerOwnerId)
        require(claimedAt >= 0L) { "Runner queue claim time is invalid." }
        return database.withTransaction {
            val dao = database.generationDao()
            val job = requireNotNull(dao.findJob(candidate.jobId)) { "Runner queue job is missing." }
            val stage = requireNotNull(dao.findStage(candidate.currentStageId)) { "Runner queue stage is missing." }
            verifyClaimEvidence(candidate, job, stage)
            require(claimedAt > job.updatedAt) { "Runner claim must advance persisted time." }
            val claimed = dao.acquireJobLease(candidate.jobId, runnerOwnerId, claimedAt)
            val currentStage = requireNotNull(dao.findStage(requireNotNull(claimed.currentStageId))) {
                "Runner queue current stage is missing."
            }
            require(currentStage.stageId == candidate.currentStageId && currentStage.jobId == candidate.jobId) {
                "Runner queue current stage changed during claim."
            }
            GenerationRunnerQueueClaimResult(
                jobId = claimed.jobId,
                jobStatus = claimed.status,
                jobLeaseToken = requireNotNull(claimed.leaseTokenOrNull()),
                currentStage = currentStage.toRunnerQueueStageSnapshot(),
            )
        }
    }

    suspend fun heartbeatAndLoadCurrentStage(
        jobId: String,
        jobLeaseToken: GenerationLeaseToken,
        heartbeatAt: Long,
    ): GenerationRunnerQueueHeartbeatResult {
        require(heartbeatAt >= 0L) { "Runner heartbeat time is invalid." }
        return database.withTransaction {
            val dao = database.generationDao()
            val job = dao.heartbeatJobLease(jobId, jobLeaseToken, heartbeatAt, leasePolicy)
            val stage = requireNotNull(dao.findStage(requireNotNull(job.currentStageId))) {
                "Runner queue current stage is missing."
            }
            require(stage.jobId == job.jobId) { "Runner queue current stage does not belong to the job." }
            GenerationRunnerQueueHeartbeatResult(
                jobStatus = job.status,
                jobUpdatedAt = job.updatedAt,
                currentStage = stage.toRunnerQueueStageSnapshot(),
            )
        }
    }

    private fun verifyClaimEvidence(
        candidate: GenerationRunnerQueueCandidate,
        job: GenerationJobEntity,
        stage: GenerationStageEntity,
    ) {
        if (
            candidate.jobStatus != GenerationJobStatus.READY ||
            candidate.currentStageStatus != GenerationStageStatus.READY ||
            job.jobId != candidate.jobId ||
            job.status != GenerationJobStatus.READY ||
            job.currentStageId != candidate.currentStageId ||
            job.updatedAt != candidate.jobUpdatedAt ||
            job.leaseTokenOrNull() != null ||
            stage.stageId != candidate.currentStageId ||
            stage.jobId != candidate.jobId ||
            stage.status != GenerationStageStatus.READY ||
            stage.updatedAt != candidate.stageUpdatedAt ||
            stage.leaseTokenOrNull() != null
        ) {
            throw StaleGenerationStateException("Runner queue candidate evidence changed.")
        }
    }

    private fun requireValidRunnerOwnerId(ownerId: String) {
        require(RUNNER_OWNER_ID_REGEX.matches(ownerId)) {
            "Runner owner id must be 1-128 characters of [A-Za-z0-9._:-]."
        }
    }

    companion object {
        const val DEFAULT_BATCH_LIMIT = 50
        const val MAX_BATCH_LIMIT = 100
        private val RUNNER_OWNER_ID_REGEX = Regex("^[A-Za-z0-9._:-]{1,128}$")
    }
}

private fun GenerationRunnerQueueRow.toCandidate(): GenerationRunnerQueueCandidate {
    if (
        jobStatus != GenerationJobStatus.READY ||
        stageStatus != GenerationStageStatus.READY ||
        jobId.isBlank() ||
        currentStageId.isBlank()
    ) {
        throw StaleGenerationStateException("Runner queue row is invalid.")
    }
    return GenerationRunnerQueueCandidate(
        jobId = jobId,
        jobStatus = jobStatus,
        currentStageId = currentStageId,
        currentStageStatus = stageStatus,
        jobUpdatedAt = jobUpdatedAt,
        stageUpdatedAt = stageUpdatedAt,
    )
}

private fun GenerationStageEntity.toRunnerQueueStageSnapshot() = GenerationRunnerQueueStageSnapshot(
    stageId = stageId,
    jobId = jobId,
    phase = phase,
    targetType = targetType,
    status = status,
    attemptCount = attemptCount,
    maxAttempts = maxAttempts,
)

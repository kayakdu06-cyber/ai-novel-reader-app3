package app.zhijuan.core.database.generation

import androidx.room.withTransaction
import app.zhijuan.core.database.ZhijuanDatabase
import app.zhijuan.core.model.GenerationJobStatus
import app.zhijuan.core.model.GenerationPhase
import app.zhijuan.core.model.GenerationStageStatus
import app.zhijuan.core.model.GenerationTargetType

/**
 * A scanned READY job candidate carrying only the identity and evidence needed to re-verify the
 * exact persisted row at claim time: job/stage identity, statuses and updatedAt. No book, target,
 * input, intent, source or lease-owner payload is included, and [toString] redacts identifiers.
 */
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

/**
 * Limited routing snapshot of the current stage: only phase, target type, status and attempt bounds
 * are exposed. `targetId` and all payload columns are never carried, and [toString] redacts
 * stage/job identifiers.
 */
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

/**
 * Result of a successful READY job claim: the exact job lease token held by the caller plus a
 * limited snapshot of the still READY, lease-free current stage. [toString] never exposes the
 * lease owner or any identifier.
 */
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

/**
 * Result of resuming an owned job lease: the job is still lease-owned and the current stage is the
 * latest one, which business transactions may have advanced since the last heartbeat.
 */
data class GenerationRunnerQueueHeartbeatResult(
    val jobStatus: GenerationJobStatus,
    val jobUpdatedAt: Long,
    val currentStage: GenerationRunnerQueueStageSnapshot,
) {
    override fun toString(): String =
        "GenerationRunnerQueueHeartbeatResult(jobStatus=$jobStatus, jobUpdatedAt=$jobUpdatedAt, " +
            "currentStage=$currentStage, identifiers=redacted)"
}

/**
 * Minimal persistent runner queue over the existing generation state: scans READY jobs whose
 * current stage is READY and lease-free, claims one exact candidate by reusing the existing job
 * lease CAS, and resumes the same job lease across stage handoffs. It never writes the stage,
 * never creates attempts, never parses input sources, and exposes no payload beyond limited
 * routing fields.
 */
class GenerationRunnerQueueRepository(
    private val database: ZhijuanDatabase,
    private val leasePolicy: GenerationLeasePolicy = GenerationLeasePolicy(),
) {
    /**
     * Bounded, stably ordered snapshot of READY jobs whose current stage is READY and lease-free.
     * Rows updated after [observedAt] never enter the snapshot, and `limit + 1` determines
     * [GenerationRunnerQueueScan.hasMore].
     */
    suspend fun scanReadyJobs(
        observedAt: Long,
        limit: Int = DEFAULT_BATCH_LIMIT,
    ): GenerationRunnerQueueScan {
        require(observedAt >= 0L) { "Runner queue time is invalid." }
        require(limit in 1..MAX_BATCH_LIMIT) { "Runner queue batch limit is invalid." }
        val dao = database.generationDao()
        val rows = dao.readyJobsForRunnerQueue(observedAt = observedAt, limit = limit + 1)
        return GenerationRunnerQueueScan(
            candidates = rows.take(limit).map { it.toCandidate() },
            hasMore = rows.size > limit,
        )
    }

    /**
     * Claims one exact scanned candidate inside a single Room transaction: re-reads the job and its
     * current stage and verifies every candidate evidence (job id/status/currentStageId/updatedAt,
     * stage id/status/updatedAt and a lease-free stage) before reusing the existing job lease CAS.
     * Any evidence change fails closed with zero writes.
     */
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
            val stage = requireNotNull(dao.findStage(candidate.currentStageId)) {
                "Runner queue stage is missing."
            }
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

    /**
     * Resumes an already owned job inside a single Room transaction: the caller-held exact
     * [GenerationLeaseToken] continues the existing job heartbeat (never adopting a persisted
     * lease by owner string), then the latest current stage is read. Business transactions may have
     * advanced the cursor from one stage to the next; the job itself is never re-claimed here.
     */
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

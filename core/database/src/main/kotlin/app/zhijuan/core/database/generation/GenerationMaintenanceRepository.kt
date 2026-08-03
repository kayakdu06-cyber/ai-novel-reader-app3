package app.zhijuan.core.database.generation

import androidx.room.withTransaction
import app.zhijuan.core.database.ZhijuanDatabase
import app.zhijuan.core.model.GenerationJobStatus
import app.zhijuan.core.model.GenerationStageStatus
import app.zhijuan.core.task.GenerationJobStateMachine
import app.zhijuan.core.task.GenerationStageStateMachine
import app.zhijuan.core.task.JobEvent
import app.zhijuan.core.task.StageEvent

data class GenerationMaintenanceCandidate(
    val jobId: String,
    val jobStatus: GenerationJobStatus,
    val stageId: String,
    val stageStatus: GenerationStageStatus,
    val latestAttemptId: String?,
    val observedJobLease: GenerationLeaseToken,
    val jobLeaseHeartbeatAt: Long,
    val observedLease: GenerationLeaseToken,
    val leaseHeartbeatAt: Long,
) {
    override fun toString(): String =
        "GenerationMaintenanceCandidate(jobStatus=$jobStatus, stageStatus=$stageStatus, " +
            "hasAttempt=${latestAttemptId != null}, identifiers=redacted)"
}

data class GenerationMaintenanceScan(
    val candidates: List<GenerationMaintenanceCandidate>,
    val hasMore: Boolean,
)

/**
 * Finds only persisted, expired execution leases. It does not acquire a lease, create an attempt,
 * inspect connection data, or provide any network capability.
 */
class GenerationMaintenanceRepository(
    private val database: ZhijuanDatabase,
    private val leasePolicy: GenerationLeasePolicy = GenerationLeasePolicy(),
) {
    suspend fun scanExpiredExecutionLeases(
        observedAt: Long,
        limit: Int = DEFAULT_BATCH_LIMIT,
    ): GenerationMaintenanceScan {
        require(observedAt >= 0L) { "Maintenance time is invalid." }
        require(limit in 1..MAX_BATCH_LIMIT) { "Maintenance batch limit is invalid." }
        if (observedAt < leasePolicy.timeoutMillis) {
            return GenerationMaintenanceScan(emptyList(), hasMore = false)
        }
        return database.withTransaction {
            val dao = database.generationDao()
            val rows = dao.leasedStagesForMaintenance(
                expiredAtOrBefore = observedAt - leasePolicy.timeoutMillis,
                observedAt = observedAt,
                limit = limit + 1,
            )
            val candidates = mutableListOf<GenerationMaintenanceCandidate>()
            for (stage in rows.take(limit)) {
                stage.toMaintenanceCandidateOrNull(dao, observedAt)?.let(candidates::add)
            }
            GenerationMaintenanceScan(
                candidates = candidates,
                hasMore = rows.size > limit,
            )
        }
    }

    suspend fun requeueExpiredPreRequestExecution(
        candidate: GenerationMaintenanceCandidate,
        observedAt: Long,
    ) = database.withTransaction {
        require(observedAt >= 0L) { "Maintenance time is invalid." }
        val dao = database.generationDao()
        val stage = requireNotNull(dao.findStage(candidate.stageId)) { "Maintenance stage is missing." }
        val job = requireNotNull(dao.findJob(candidate.jobId)) { "Maintenance job is missing." }
        if (
            stage.jobId != job.jobId || job.currentStageId != stage.stageId ||
            stage.status != GenerationStageStatus.PREPARING ||
            job.status != GenerationJobStatus.RUNNING ||
            stage.leaseTokenOrNull() != candidate.observedLease ||
            job.leaseTokenOrNull() != candidate.observedJobLease
        ) {
            throw StaleGenerationStateException("Pre-request maintenance evidence changed.")
        }
        val stageHeartbeat = requireNotNull(stage.leaseHeartbeatAt)
        val jobHeartbeat = requireNotNull(job.leaseHeartbeatAt)
        require(
            leasePolicy.isExpired(stageHeartbeat, observedAt) &&
                leasePolicy.isExpired(jobHeartbeat, observedAt),
        ) { "Active generation execution cannot be reclaimed." }
        require(observedAt >= stage.updatedAt && observedAt >= job.updatedAt) {
            "Maintenance time cannot move backwards."
        }
        val nextStage = GenerationStageStateMachine.transition(
            stage.status,
            StageEvent.LEASE_EXPIRED_BEFORE_REQUEST,
        )
        val nextJob = GenerationJobStateMachine.transition(
            job.status,
            JobEvent.RECOVERY_REQUEUED,
        )
        if (
            dao.compareAndRequeueExpiredPreparingStage(
                stageId = stage.stageId,
                leaseOwnerId = candidate.observedLease.ownerId,
                leaseAcquiredAt = candidate.observedLease.acquiredAt,
                expectedHeartbeatAt = stageHeartbeat,
                nextStatus = nextStage,
                now = observedAt,
            ) != 1 ||
            dao.compareAndSetJobStatus(
                jobId = job.jobId,
                expectedStatus = job.status,
                nextStatus = nextJob,
                updatedAt = observedAt,
            ) != 1
        ) {
            throw StaleGenerationStateException("Pre-request maintenance lost a concurrent update.")
        }
    }

    private suspend fun GenerationStageEntity.toMaintenanceCandidateOrNull(
        dao: GenerationDao,
        observedAt: Long,
    ): GenerationMaintenanceCandidate? {
        val lease = leaseTokenOrNull() ?: return null
        val heartbeatAt = leaseHeartbeatAt ?: return null
        if (!leasePolicy.isExpired(heartbeatAt, observedAt)) return null
        val job = dao.findJob(jobId) ?: return null
        if (job.currentStageId != stageId || job.status !in MAINTAINABLE_JOB_STATUSES) return null
        if (status !in MAINTAINABLE_STAGE_STATUSES) return null
        val jobLease = job.leaseTokenOrNull() ?: return null
        val jobHeartbeatAt = job.leaseHeartbeatAt ?: return null
        if (!leasePolicy.isExpired(jobHeartbeatAt, observedAt)) return null
        return GenerationMaintenanceCandidate(
            jobId = job.jobId,
            jobStatus = job.status,
            stageId = stageId,
            stageStatus = status,
            latestAttemptId = dao.attemptsForStage(stageId).lastOrNull()?.attemptId,
            observedJobLease = jobLease,
            jobLeaseHeartbeatAt = jobHeartbeatAt,
            observedLease = lease,
            leaseHeartbeatAt = heartbeatAt,
        )
    }

    companion object {
        const val DEFAULT_BATCH_LIMIT = 50
        const val MAX_BATCH_LIMIT = 100

        private val MAINTAINABLE_JOB_STATUSES = setOf(
            GenerationJobStatus.RUNNING,
            GenerationJobStatus.PAUSING,
            GenerationJobStatus.STOPPING,
        )
        private val MAINTAINABLE_STAGE_STATUSES = setOf(
            GenerationStageStatus.PREPARING,
            GenerationStageStatus.REQUEST_INTENT_RECORDED,
            GenerationStageStatus.STREAMING,
            GenerationStageStatus.VALIDATING,
            GenerationStageStatus.COMMITTING,
        )
    }
}

package app.zhijuan.core.database.generation

import androidx.room.withTransaction
import app.zhijuan.core.database.ZhijuanDatabase
import app.zhijuan.core.model.GenerationJobStatus
import app.zhijuan.core.model.GenerationStageStatus

/**
 * Limited execution snapshot of a lease-owned current stage: only the finite job/stage statuses,
 * the two exact lease tokens and the two lease heartbeat times are exposed. It never carries
 * payload, target, input, intent or connection information, and [toString] redacts job id, stage id
 * and the lease owner id.
 */
data class GenerationRunnerExecutionLeaseSnapshot(
    val jobId: String,
    val jobStatus: GenerationJobStatus,
    val jobLeaseToken: GenerationLeaseToken,
    val jobHeartbeatAt: Long,
    val stageId: String,
    val stageStatus: GenerationStageStatus,
    val stageLeaseToken: GenerationLeaseToken,
    val stageHeartbeatAt: Long,
) {
    override fun toString(): String =
        "GenerationRunnerExecutionLeaseSnapshot(jobStatus=$jobStatus, " +
            "jobLeaseToken=${jobLeaseToken.redacted()}, jobHeartbeatAt=$jobHeartbeatAt, " +
            "stageStatus=$stageStatus, stageLeaseToken=${stageLeaseToken.redacted()}, " +
            "stageHeartbeatAt=$stageHeartbeatAt, identifiers=redacted)"
}

private fun GenerationLeaseToken.redacted(): String =
    "GenerationLeaseToken(ownerId=redacted, acquiredAt=$acquiredAt)"

/**
 * A finite route bound to the exact persisted Job/Stage lease snapshot that authorized resolving
 * it. The route cannot be detached from the two tokens by this API, while [toString] keeps all
 * identifiers and owner data out of logs.
 */
class GenerationRunnerCurrentStageRouteSnapshot internal constructor(
    val route: GenerationRunnerStageRoute,
    val executionLease: GenerationRunnerExecutionLeaseSnapshot,
    val attemptCount: Int,
    val maxAttempts: Int,
) {
    init {
        require(attemptCount >= 0 && maxAttempts > 0 && attemptCount < maxAttempts) {
            "Current Stage route attempt bounds are invalid."
        }
    }

    override fun toString(): String =
        "GenerationRunnerCurrentStageRouteSnapshot(route=" + route +
            ", jobStatus=" + executionLease.jobStatus +
            ", stageStatus=" + executionLease.stageStatus +
            ", attempts=" + attemptCount + "/" + maxAttempts +
            ", executionLease=redacted)"
}

/**
 * Atomic current-stage execution leases for the runner that already holds the exact job lease:
 * only the job token owner can open the current READY stage, and while executing it keeps the job
 * and stage leases alive together inside a single Room transaction. Any token, status,
 * currentStage, expiry or time evidence failure fails closed with zero writes. It never creates
 * attempts, never calls providers, never parses contracts, never advances the stage cursor and
 * never commits business output.
 */
class GenerationRunnerExecutionLeaseRepository(
    private val database: ZhijuanDatabase,
    private val leasePolicy: GenerationLeasePolicy = GenerationLeasePolicy(),
) {
    /**
     * Atomically opens the current READY stage of an exactly token-owned RUNNING job: the job
     * heartbeat is continued first inside the same Room transaction, then the existing stage lease
     * CAS is reused. If the stage cannot be acquired (wrong stage, already leased, not READY, not
     * current, or belonging to another job) the whole transaction rolls back including the job
     * heartbeat. PAUSING/STOPPING jobs never open a new stage.
     */
    suspend fun acquireCurrentStageLease(
        jobId: String,
        jobLeaseToken: GenerationLeaseToken,
        stageId: String,
        runnerOwnerId: String,
        acquiredAt: Long,
    ): GenerationRunnerExecutionLeaseSnapshot {
        requireValidIdentifier(jobId, "Job")
        requireValidIdentifier(stageId, "Stage")
        requireValidRunnerOwnerId(runnerOwnerId)
        require(jobLeaseToken.ownerId == runnerOwnerId) {
            "Runner owner id must equal the job lease token owner."
        }
        require(acquiredAt >= 0L) { "Runner stage acquisition time is invalid." }
        return database.withTransaction {
            val dao = database.generationDao()
            val job = requireNotNull(dao.findJob(jobId)) { "Execution lease job is missing." }
            val stage = requireNotNull(dao.findStage(stageId)) { "Execution lease stage is missing." }
            verifyAcquireEvidence(job, stage, stageId)
            dao.heartbeatJobLease(jobId, jobLeaseToken, acquiredAt, leasePolicy)
            dao.acquireStageLease(stageId, runnerOwnerId, acquiredAt)
            executionLeaseSnapshot(
                requireNotNull(dao.findJob(jobId)) { "Execution lease job is missing." },
                requireNotNull(dao.findStage(stageId)) { "Execution lease stage is missing." },
            )
        }
    }

    /**
     * Continues the job and stage heartbeats together inside a single Room transaction. The job may
     * be RUNNING/PAUSING/STOPPING, but the exact job token, the current stage id, the exact stage
     * token and the stage's lease-owned status must all match. Any failure of the second heartbeat
     * rolls back the first, so both leases stay exactly as they were.
     */
    suspend fun heartbeatCurrentExecutionLeases(
        jobId: String,
        jobLeaseToken: GenerationLeaseToken,
        stageId: String,
        stageLeaseToken: GenerationLeaseToken,
        heartbeatAt: Long,
    ): GenerationRunnerExecutionLeaseSnapshot {
        requireValidIdentifier(jobId, "Job")
        requireValidIdentifier(stageId, "Stage")
        requireValidRunnerOwnerId(jobLeaseToken.ownerId)
        require(stageLeaseToken.ownerId == jobLeaseToken.ownerId) {
            "Job and stage lease tokens must belong to the same runner owner."
        }
        require(heartbeatAt >= 0L) { "Runner heartbeat time is invalid." }
        return database.withTransaction {
            val dao = database.generationDao()
            val job = requireNotNull(dao.findJob(jobId)) { "Execution lease job is missing." }
            val stage = requireNotNull(dao.findStage(stageId)) { "Execution lease stage is missing." }
            verifyHeartbeatEvidence(job, stage, stageId)
            dao.heartbeatJobLease(jobId, jobLeaseToken, heartbeatAt, leasePolicy)
            dao.heartbeatStageLease(stageId, stageLeaseToken, heartbeatAt, leasePolicy)
            executionLeaseSnapshot(
                requireNotNull(dao.findJob(jobId)) { "Execution lease job is missing." },
                requireNotNull(dao.findStage(stageId)) { "Execution lease stage is missing." },
            )
        }
    }

    /**
     * Resolves the current Stage route only while the caller still owns the exact, same-owner,
     * unexpired Job and Stage leases. The authoritative Job/Stage rows and the frozen source
     * contract are checked inside one read-only Room transaction. Only RUNNING + PREPARING is
     * dispatchable: PAUSING/STOPPING and already request-owned statuses cannot start another
     * executor action.
     *
     * This method never writes state, creates an Attempt, opens a Provider or advances the cursor.
     */
    suspend fun resolveCurrentStageRoute(
        jobId: String,
        jobLeaseToken: GenerationLeaseToken,
        stageId: String,
        stageLeaseToken: GenerationLeaseToken,
        observedAt: Long,
    ): GenerationRunnerCurrentStageRouteSnapshot {
        requireValidIdentifier(jobId, "Job")
        requireValidIdentifier(stageId, "Stage")
        requireValidRunnerOwnerId(jobLeaseToken.ownerId)
        require(stageLeaseToken.ownerId == jobLeaseToken.ownerId) {
            "Job and stage lease tokens must belong to the same runner owner."
        }
        require(observedAt >= 0L) { "Runner route observation time is invalid." }
        return database.withTransaction {
            val dao = database.generationDao()
            val job = requireNotNull(dao.findJob(jobId)) { "Current route job is missing." }
            val stage = requireNotNull(dao.findStage(stageId)) { "Current route stage is missing." }
            verifyRouteEvidence(
                job = job,
                jobLeaseToken = jobLeaseToken,
                stage = stage,
                stageLeaseToken = stageLeaseToken,
                observedAt = observedAt,
            )
            GenerationRunnerCurrentStageRouteSnapshot(
                route = GenerationRunnerStageRouteResolver.resolve(stage),
                executionLease = executionLeaseSnapshot(job, stage),
                attemptCount = stage.attemptCount,
                maxAttempts = stage.maxAttempts,
            )
        }
    }

    private fun verifyAcquireEvidence(
        job: GenerationJobEntity,
        stage: GenerationStageEntity,
        stageId: String,
    ) {
        if (
            job.status != GenerationJobStatus.RUNNING ||
            job.currentStageId != stageId ||
            stage.jobId != job.jobId ||
            stage.status != GenerationStageStatus.READY ||
            stage.leaseTokenOrNull() != null
        ) {
            throw StaleGenerationStateException("Execution lease acquire evidence changed.")
        }
    }

    private fun verifyHeartbeatEvidence(
        job: GenerationJobEntity,
        stage: GenerationStageEntity,
        stageId: String,
    ) {
        if (job.currentStageId != stageId || stage.jobId != job.jobId) {
            throw StaleGenerationStateException("Execution lease heartbeat evidence changed.")
        }
    }

    private fun verifyRouteEvidence(
        job: GenerationJobEntity,
        jobLeaseToken: GenerationLeaseToken,
        stage: GenerationStageEntity,
        stageLeaseToken: GenerationLeaseToken,
        observedAt: Long,
    ) {
        if (
            job.status != GenerationJobStatus.RUNNING ||
            stage.status != GenerationStageStatus.PREPARING ||
            job.currentStageId != stage.stageId ||
            stage.jobId != job.jobId ||
            job.leaseTokenOrNull() != jobLeaseToken ||
            stage.leaseTokenOrNull() != stageLeaseToken ||
            stage.attemptCount !in 0 until stage.maxAttempts
        ) {
            throw StaleGenerationStateException("Current Stage route lease evidence changed.")
        }
        val jobHeartbeatAt = requireNotNull(job.leaseHeartbeatAt) {
            "Current route Job lease heartbeat is missing."
        }
        val stageHeartbeatAt = requireNotNull(stage.leaseHeartbeatAt) {
            "Current route Stage lease heartbeat is missing."
        }
        if (
            jobHeartbeatAt < jobLeaseToken.acquiredAt ||
            stageHeartbeatAt < stageLeaseToken.acquiredAt
        ) {
            throw StaleGenerationStateException("Current Stage route lease timing is inconsistent.")
        }
        require(
            observedAt >= job.updatedAt &&
                observedAt >= stage.updatedAt &&
                observedAt >= jobHeartbeatAt &&
                observedAt >= stageHeartbeatAt,
        ) { "Runner route observation time cannot move backwards." }
        if (
            leasePolicy.isExpired(jobHeartbeatAt, observedAt) ||
            leasePolicy.isExpired(stageHeartbeatAt, observedAt)
        ) {
            throw StaleGenerationStateException("Current Stage route lease expired.")
        }
    }

    private fun executionLeaseSnapshot(
        job: GenerationJobEntity,
        stage: GenerationStageEntity,
    ): GenerationRunnerExecutionLeaseSnapshot {
        if (
            job.currentStageId != stage.stageId ||
            stage.jobId != job.jobId ||
            job.leaseTokenOrNull() == null ||
            stage.leaseTokenOrNull() == null ||
            job.leaseHeartbeatAt == null ||
            stage.leaseHeartbeatAt == null
        ) {
            throw StaleGenerationStateException("Execution lease snapshot is invalid.")
        }
        return GenerationRunnerExecutionLeaseSnapshot(
            jobId = job.jobId,
            jobStatus = job.status,
            jobLeaseToken = requireNotNull(job.leaseTokenOrNull()),
            jobHeartbeatAt = requireNotNull(job.leaseHeartbeatAt),
            stageId = stage.stageId,
            stageStatus = stage.status,
            stageLeaseToken = requireNotNull(stage.leaseTokenOrNull()),
            stageHeartbeatAt = requireNotNull(stage.leaseHeartbeatAt),
        )
    }

    private fun requireValidRunnerOwnerId(ownerId: String) {
        require(RUNNER_OWNER_ID_REGEX.matches(ownerId)) {
            "Runner owner id must be 1-128 characters of [A-Za-z0-9._:-]."
        }
    }

    private fun requireValidIdentifier(identifier: String, label: String) {
        require(RUNNER_OWNER_ID_REGEX.matches(identifier)) {
            "$label id must be 1-128 characters of [A-Za-z0-9._:-]."
        }
    }

    private companion object {
        val RUNNER_OWNER_ID_REGEX = Regex("^[A-Za-z0-9._:-]{1,128}$")
    }
}

package app.zhijuan.core.database.generation

import app.zhijuan.core.database.ZhijuanDatabase
import app.zhijuan.core.model.GenerationJobStatus
import app.zhijuan.core.model.GenerationStageStatus
import app.zhijuan.core.model.StandardErrorCode
import app.zhijuan.core.task.JobEvent
import app.zhijuan.core.task.StageEvent

data class StoredGenerationJobState(
    val jobId: String,
    val bookId: String,
    val status: GenerationJobStatus,
    val currentStageId: String?,
    val pauseOrStopReason: String?,
    val startedAt: Long?,
    val finishedAt: Long?,
    val leaseToken: GenerationLeaseToken?,
    val leaseHeartbeatAt: Long?,
    val updatedAt: Long,
)

data class StoredGenerationStageState(
    val stageId: String,
    val jobId: String,
    val status: GenerationStageStatus,
    val attemptCount: Int,
    val maxAttempts: Int,
    val standardErrorCode: StandardErrorCode?,
    val nextRetryAt: Long?,
    val leaseToken: GenerationLeaseToken?,
    val leaseHeartbeatAt: Long?,
    val updatedAt: Long,
)

class GenerationStateRepository(
    private val database: ZhijuanDatabase,
    private val leasePolicy: GenerationLeasePolicy = GenerationLeasePolicy(),
) {
    suspend fun findJob(jobId: String): StoredGenerationJobState? =
        database.generationDao().findJob(jobId)?.toStoredState()

    suspend fun findStage(stageId: String): StoredGenerationStageState? =
        database.generationDao().findStage(stageId)?.toStoredState()

    suspend fun transitionJob(
        jobId: String,
        expectedStatus: GenerationJobStatus,
        event: JobEvent,
        updatedAt: Long,
        leaseToken: GenerationLeaseToken? = null,
    ): StoredGenerationJobState = database.generationDao()
        .transitionJob(jobId, expectedStatus, event, updatedAt, leaseToken, leasePolicy)
        .toStoredState()

    suspend fun acquireJobLease(
        jobId: String,
        leaseOwnerId: String,
        now: Long,
    ): StoredGenerationJobState = database.generationDao()
        .acquireJobLease(jobId, leaseOwnerId, now)
        .toStoredState()

    suspend fun heartbeatJobLease(
        jobId: String,
        leaseToken: GenerationLeaseToken,
        now: Long,
    ): StoredGenerationJobState = database.generationDao()
        .heartbeatJobLease(jobId, leaseToken, now, leasePolicy)
        .toStoredState()

    suspend fun transitionStage(
        stageId: String,
        expectedStatus: GenerationStageStatus,
        event: StageEvent,
        updatedAt: Long,
        errorCode: StandardErrorCode? = null,
        nextRetryAt: Long? = null,
        leaseToken: GenerationLeaseToken? = null,
    ): StoredGenerationStageState = database.generationDao()
        .transitionStage(
            stageId = stageId,
            expectedStatus = expectedStatus,
            event = event,
            errorCode = errorCode,
            nextRetryAt = nextRetryAt,
            updatedAt = updatedAt,
            leaseToken = leaseToken,
            leasePolicy = leasePolicy,
        )
        .toStoredState()

    suspend fun acquireStageLease(
        stageId: String,
        leaseOwnerId: String,
        now: Long,
    ): StoredGenerationStageState = database.generationDao()
        .acquireStageLease(stageId, leaseOwnerId, now)
        .toStoredState()

    suspend fun heartbeatStageLease(
        stageId: String,
        leaseToken: GenerationLeaseToken,
        now: Long,
    ): StoredGenerationStageState = database.generationDao()
        .heartbeatStageLease(stageId, leaseToken, now, leasePolicy)
        .toStoredState()

    suspend fun reclaimExpiredStageLease(
        stageId: String,
        observedLease: GenerationLeaseToken,
        now: Long,
    ): ExpiredStageLeaseResult = database.generationDao()
        .reclaimExpiredStageLease(stageId, observedLease, now, leasePolicy)
}

internal fun GenerationJobEntity.toStoredState() = StoredGenerationJobState(
    jobId = jobId,
    bookId = bookId,
    status = status,
    currentStageId = currentStageId,
    pauseOrStopReason = pauseOrStopReason,
    startedAt = startedAt,
    finishedAt = finishedAt,
    leaseToken = leaseTokenOrNull(),
    leaseHeartbeatAt = leaseHeartbeatAt,
    updatedAt = updatedAt,
)

internal fun GenerationStageEntity.toStoredState() = StoredGenerationStageState(
    stageId = stageId,
    jobId = jobId,
    status = status,
    attemptCount = attemptCount,
    maxAttempts = maxAttempts,
    standardErrorCode = standardErrorCode,
    nextRetryAt = nextRetryAt,
    leaseToken = leaseTokenOrNull(),
    leaseHeartbeatAt = leaseHeartbeatAt,
    updatedAt = updatedAt,
)

internal fun GenerationJobEntity.leaseTokenOrNull(): GenerationLeaseToken? =
    leaseTokenOrNull(leaseOwnerId, leaseAcquiredAt, leaseHeartbeatAt)

internal fun GenerationStageEntity.leaseTokenOrNull(): GenerationLeaseToken? =
    leaseTokenOrNull(leaseOwnerId, leaseAcquiredAt, leaseHeartbeatAt)

private fun leaseTokenOrNull(
    ownerId: String?,
    acquiredAt: Long?,
    heartbeatAt: Long?,
): GenerationLeaseToken? {
    val allNull = ownerId == null && acquiredAt == null && heartbeatAt == null
    require(allNull || (ownerId != null && acquiredAt != null && heartbeatAt != null)) {
        "Persisted lease fields must be either all present or all absent."
    }
    return if (allNull) null else GenerationLeaseToken(requireNotNull(ownerId), requireNotNull(acquiredAt))
}

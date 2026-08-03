package app.zhijuan.core.database.generation

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
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
import app.zhijuan.core.task.RequestAttemptStateMachine
import app.zhijuan.core.task.StageEvent

internal data class NewRequestIntent(
    val attemptId: String,
    val usageLedgerId: String,
    val stageId: String,
    val retryParentAttemptId: String?,
    val connectionSnapshotJson: String,
    val modelSnapshotJson: String,
    val protocolSnapshotJson: String,
    val inputHash: String,
    val streamDraftRef: String?,
    val dailyPeriodKey: String,
    val createdAt: Long,
)

internal data class UsageUpdate(
    val source: UsageSource,
    val status: UsageLedgerStatus,
    val inputTokens: Long?,
    val outputTokens: Long?,
    val cachedTokens: Long?,
    val reasoningTokens: Long?,
    val totalTokens: Long?,
    val currency: String?,
    val estimatedCostMicros: Long?,
    val priceCatalogVersion: String?,
    val updatedAt: Long,
)

class StaleGenerationStateException(message: String) : IllegalStateException(message)

@Dao
internal interface GenerationDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertJob(job: GenerationJobEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertStages(stages: List<GenerationStageEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAttempt(attempt: RequestAttemptEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertUsageLedger(ledger: UsageLedgerEntity)

    @Query("SELECT * FROM generation_job WHERE job_id = :jobId")
    suspend fun findJob(jobId: String): GenerationJobEntity?

    @Query("SELECT * FROM generation_stage WHERE stage_id = :stageId")
    suspend fun findStage(stageId: String): GenerationStageEntity?

    @Query("SELECT * FROM request_attempt WHERE attempt_id = :attemptId")
    suspend fun findAttempt(attemptId: String): RequestAttemptEntity?

    @Query("SELECT * FROM request_attempt WHERE stream_draft_ref IS NOT NULL")
    suspend fun attemptsWithStreamDraft(): List<RequestAttemptEntity>

    @Query("SELECT * FROM request_attempt WHERE stream_draft_ref = :streamDraftRef")
    suspend fun attemptsForStreamDraft(streamDraftRef: String): List<RequestAttemptEntity>

    @Query("SELECT * FROM usage_ledger WHERE attempt_id = :attemptId")
    suspend fun findUsageForAttempt(attemptId: String): UsageLedgerEntity?

    @Query("SELECT * FROM usage_ledger WHERE usage_ledger_id = :usageLedgerId")
    suspend fun findUsageLedger(usageLedgerId: String): UsageLedgerEntity?

    @Query(
        """
        SELECT * FROM generation_stage
        WHERE job_id = :jobId
        ORDER BY created_at ASC, stage_id ASC
        """,
    )
    suspend fun stagesForJob(jobId: String): List<GenerationStageEntity>

    @Query(
        """
        SELECT * FROM generation_stage
        WHERE lease_heartbeat_at IS NOT NULL
          AND lease_heartbeat_at <= :expiredAtOrBefore
          AND updated_at <= :observedAt
        ORDER BY lease_heartbeat_at ASC, stage_id ASC
        LIMIT :limit
        """,
    )
    suspend fun leasedStagesForMaintenance(
        expiredAtOrBefore: Long,
        observedAt: Long,
        limit: Int,
    ): List<GenerationStageEntity>

    @Query(
        """
        SELECT * FROM request_attempt
        WHERE stage_id = :stageId
        ORDER BY attempt_no ASC
        """,
    )
    suspend fun attemptsForStage(stageId: String): List<RequestAttemptEntity>

    @Query(
        """
        UPDATE generation_job
        SET current_stage_id = :stageId,
            updated_at = :updatedAt
        WHERE job_id = :jobId
        """,
    )
    suspend fun setCurrentStage(jobId: String, stageId: String, updatedAt: Long): Int

    @Query(
        """
        UPDATE generation_job
        SET status = :nextStatus,
            updated_at = :updatedAt,
            lease_owner_id = CASE
                WHEN :nextStatus IN ('READY', 'PAUSED', 'NEEDS_ACTION', 'BLOCKED', 'STOPPED', 'COMPLETED') THEN NULL
                ELSE lease_owner_id
            END,
            lease_acquired_at = CASE
                WHEN :nextStatus IN ('READY', 'PAUSED', 'NEEDS_ACTION', 'BLOCKED', 'STOPPED', 'COMPLETED') THEN NULL
                ELSE lease_acquired_at
            END,
            lease_heartbeat_at = CASE
                WHEN :nextStatus IN ('READY', 'PAUSED', 'NEEDS_ACTION', 'BLOCKED', 'STOPPED', 'COMPLETED') THEN NULL
                ELSE lease_heartbeat_at
            END,
            started_at = CASE
                WHEN :nextStatus = 'RUNNING' AND started_at IS NULL THEN :updatedAt
                ELSE started_at
            END,
            finished_at = CASE
                WHEN :nextStatus IN ('COMPLETED', 'STOPPED') THEN :updatedAt
                ELSE finished_at
            END
        WHERE job_id = :jobId AND status = :expectedStatus
        """,
    )
    suspend fun compareAndSetJobStatus(
        jobId: String,
        expectedStatus: GenerationJobStatus,
        nextStatus: GenerationJobStatus,
        updatedAt: Long,
    ): Int

    @Query(
        """
        UPDATE generation_job
        SET status = :nextStatus,
            pause_or_stop_reason = :reason,
            updated_at = :updatedAt,
            lease_owner_id = CASE
                WHEN :nextStatus IN ('READY', 'PAUSED', 'NEEDS_ACTION', 'BLOCKED', 'STOPPED', 'COMPLETED') THEN NULL
                ELSE lease_owner_id
            END,
            lease_acquired_at = CASE
                WHEN :nextStatus IN ('READY', 'PAUSED', 'NEEDS_ACTION', 'BLOCKED', 'STOPPED', 'COMPLETED') THEN NULL
                ELSE lease_acquired_at
            END,
            lease_heartbeat_at = CASE
                WHEN :nextStatus IN ('READY', 'PAUSED', 'NEEDS_ACTION', 'BLOCKED', 'STOPPED', 'COMPLETED') THEN NULL
                ELSE lease_heartbeat_at
            END,
            finished_at = CASE
                WHEN :nextStatus IN ('STOPPED', 'COMPLETED') THEN :updatedAt
                ELSE finished_at
            END
        WHERE job_id = :jobId
          AND status = :expectedStatus
          AND updated_at <= :updatedAt
        """,
    )
    suspend fun compareAndSetJobControlStatus(
        jobId: String,
        expectedStatus: GenerationJobStatus,
        nextStatus: GenerationJobStatus,
        reason: String?,
        updatedAt: Long,
    ): Int

    @Query(
        """
        UPDATE generation_job
        SET status = :nextStatus,
            lease_owner_id = :leaseOwnerId,
            lease_acquired_at = :now,
            lease_heartbeat_at = :now,
            started_at = COALESCE(started_at, :now),
            updated_at = :now
        WHERE job_id = :jobId AND status = :expectedStatus
        """,
    )
    suspend fun compareAndAcquireJobLease(
        jobId: String,
        expectedStatus: GenerationJobStatus,
        nextStatus: GenerationJobStatus,
        leaseOwnerId: String,
        now: Long,
    ): Int

    @Query(
        """
        UPDATE generation_job
        SET lease_heartbeat_at = :now,
            updated_at = :now
        WHERE job_id = :jobId
          AND status = :expectedStatus
          AND lease_owner_id = :leaseOwnerId
          AND lease_acquired_at = :leaseAcquiredAt
          AND lease_heartbeat_at = :expectedHeartbeatAt
        """,
    )
    suspend fun compareAndHeartbeatJobLease(
        jobId: String,
        expectedStatus: GenerationJobStatus,
        leaseOwnerId: String,
        leaseAcquiredAt: Long,
        expectedHeartbeatAt: Long,
        now: Long,
    ): Int

    @Query(
        """
        UPDATE generation_stage
        SET status = :nextStatus,
            standard_error_code = :errorCode,
            next_retry_at = :nextRetryAt,
            lease_owner_id = CASE
                WHEN :nextStatus IN (
                    'READY', 'BLOCKED', 'RETRY_WAIT', 'UNKNOWN_RESULT',
                    'NEEDS_ACTION', 'RECOVERY_REQUIRED', 'SUCCEEDED', 'CANCELLED'
                ) THEN NULL
                ELSE lease_owner_id
            END,
            lease_acquired_at = CASE
                WHEN :nextStatus IN (
                    'READY', 'BLOCKED', 'RETRY_WAIT', 'UNKNOWN_RESULT',
                    'NEEDS_ACTION', 'RECOVERY_REQUIRED', 'SUCCEEDED', 'CANCELLED'
                ) THEN NULL
                ELSE lease_acquired_at
            END,
            lease_heartbeat_at = CASE
                WHEN :nextStatus IN (
                    'READY', 'BLOCKED', 'RETRY_WAIT', 'UNKNOWN_RESULT',
                    'NEEDS_ACTION', 'RECOVERY_REQUIRED', 'SUCCEEDED', 'CANCELLED'
                ) THEN NULL
                ELSE lease_heartbeat_at
            END,
            updated_at = :updatedAt
        WHERE stage_id = :stageId AND status = :expectedStatus
        """,
    )
    suspend fun compareAndSetStageStatus(
        stageId: String,
        expectedStatus: GenerationStageStatus,
        nextStatus: GenerationStageStatus,
        errorCode: StandardErrorCode?,
        nextRetryAt: Long?,
        updatedAt: Long,
    ): Int

    @Query(
        """
        UPDATE generation_stage
        SET status = 'CANCELLED',
            standard_error_code = NULL,
            next_retry_at = NULL,
            lease_owner_id = NULL,
            lease_acquired_at = NULL,
            lease_heartbeat_at = NULL,
            updated_at = :updatedAt
        WHERE job_id = :jobId
          AND status IN (
              'PENDING', 'READY', 'PREPARING', 'BLOCKED',
              'REQUEST_INTENT_RECORDED', 'STREAMING', 'VALIDATING', 'COMMITTING',
              'RETRY_WAIT', 'UNKNOWN_RESULT', 'NEEDS_ACTION', 'RECOVERY_REQUIRED'
          )
          AND updated_at <= :updatedAt
        """,
    )
    suspend fun cancelUnfinishedStagesForStoppedJob(jobId: String, updatedAt: Long): Int

    @Query(
        """
        UPDATE generation_stage
        SET status = 'SUCCEEDED',
            output_reference_json = :outputReferenceJson,
            standard_error_code = NULL,
            next_retry_at = NULL,
            lease_owner_id = NULL,
            lease_acquired_at = NULL,
            lease_heartbeat_at = NULL,
            updated_at = :updatedAt
        WHERE stage_id = :stageId
          AND status = 'COMMITTING'
          AND lease_owner_id = :leaseOwnerId
          AND lease_acquired_at = :leaseAcquiredAt
          AND lease_heartbeat_at IS NOT NULL
          AND lease_heartbeat_at <= :updatedAt
          AND updated_at <= :updatedAt
        """,
    )
    suspend fun compareAndCommitStageOutput(
        stageId: String,
        leaseOwnerId: String,
        leaseAcquiredAt: Long,
        outputReferenceJson: String,
        updatedAt: Long,
    ): Int

    @Query(
        """
        UPDATE generation_job
        SET current_stage_id = :nextStageId,
            updated_at = :updatedAt
        WHERE job_id = :jobId
          AND status = 'RUNNING'
          AND current_stage_id = :expectedCurrentStageId
          AND updated_at <= :updatedAt
        """,
    )
    suspend fun compareAndAdvanceJobStage(
        jobId: String,
        expectedCurrentStageId: String,
        nextStageId: String,
        updatedAt: Long,
    ): Int

    @Query(
        """
        UPDATE generation_job
        SET current_stage_id = :nextStageId,
            status = 'PAUSED',
            lease_owner_id = NULL,
            lease_acquired_at = NULL,
            lease_heartbeat_at = NULL,
            updated_at = :updatedAt
        WHERE job_id = :jobId
          AND status = 'PAUSING'
          AND current_stage_id = :expectedCurrentStageId
          AND updated_at <= :updatedAt
        """,
    )
    suspend fun compareAndPauseJobAfterStage(
        jobId: String,
        expectedCurrentStageId: String,
        nextStageId: String,
        updatedAt: Long,
    ): Int

    @Query(
        """
        UPDATE generation_job
        SET status = 'COMPLETED',
            pause_or_stop_reason = NULL,
            lease_owner_id = NULL,
            lease_acquired_at = NULL,
            lease_heartbeat_at = NULL,
            finished_at = :updatedAt,
            updated_at = :updatedAt
        WHERE job_id = :jobId
          AND status IN ('RUNNING', 'PAUSING')
          AND current_stage_id = :expectedCurrentStageId
          AND updated_at <= :updatedAt
        """,
    )
    suspend fun compareAndCompleteJobAfterStage(
        jobId: String,
        expectedCurrentStageId: String,
        updatedAt: Long,
    ): Int

    @Query(
        """
        UPDATE generation_stage
        SET status = :nextStatus,
            lease_owner_id = :leaseOwnerId,
            lease_acquired_at = :now,
            lease_heartbeat_at = :now,
            updated_at = :now
        WHERE stage_id = :stageId AND status = :expectedStatus
        """,
    )
    suspend fun compareAndAcquireStageLease(
        stageId: String,
        expectedStatus: GenerationStageStatus,
        nextStatus: GenerationStageStatus,
        leaseOwnerId: String,
        now: Long,
    ): Int

    @Query(
        """
        UPDATE generation_stage
        SET lease_heartbeat_at = :now,
            updated_at = :now
        WHERE stage_id = :stageId
          AND status = :expectedStatus
          AND lease_owner_id = :leaseOwnerId
          AND lease_acquired_at = :leaseAcquiredAt
          AND lease_heartbeat_at = :expectedHeartbeatAt
        """,
    )
    suspend fun compareAndHeartbeatStageLease(
        stageId: String,
        expectedStatus: GenerationStageStatus,
        leaseOwnerId: String,
        leaseAcquiredAt: Long,
        expectedHeartbeatAt: Long,
        now: Long,
    ): Int

    @Query(
        """
        UPDATE generation_stage
        SET status = :nextStatus,
            standard_error_code = NULL,
            next_retry_at = NULL,
            lease_owner_id = NULL,
            lease_acquired_at = NULL,
            lease_heartbeat_at = NULL,
            updated_at = :now
        WHERE stage_id = :stageId
          AND status = 'PREPARING'
          AND lease_owner_id = :leaseOwnerId
          AND lease_acquired_at = :leaseAcquiredAt
          AND lease_heartbeat_at = :expectedHeartbeatAt
        """,
    )
    suspend fun compareAndRequeueExpiredPreparingStage(
        stageId: String,
        leaseOwnerId: String,
        leaseAcquiredAt: Long,
        expectedHeartbeatAt: Long,
        nextStatus: GenerationStageStatus,
        now: Long,
    ): Int

    @Query(
        """
        UPDATE generation_stage
        SET status = :nextStatus,
            attempt_count = attempt_count + 1,
            updated_at = :updatedAt
        WHERE stage_id = :stageId
          AND status = :expectedStatus
          AND attempt_count < max_attempts
        """,
    )
    suspend fun compareAndRecordIntent(
        stageId: String,
        expectedStatus: GenerationStageStatus,
        nextStatus: GenerationStageStatus,
        updatedAt: Long,
    ): Int

    @Query(
        """
        UPDATE request_attempt
        SET status = :nextStatus,
            sent_at = CASE WHEN :nextStatus = 'SENT' THEN :updatedAt ELSE sent_at END,
            finished_at = CASE
                WHEN :nextStatus IN (
                    'SUCCEEDED', 'FAILED_RETRYABLE', 'FAILED_FINAL',
                    'REFUSED', 'CANCELLED', 'UNKNOWN_RESULT'
                ) THEN :updatedAt
                ELSE finished_at
            END,
            provider_request_id = COALESCE(:providerRequestId, provider_request_id),
            standard_error_code = :errorCode,
            http_status = COALESCE(:httpStatus, http_status),
            output_hash = COALESCE(:outputHash, output_hash),
            updated_at = :updatedAt
        WHERE attempt_id = :attemptId AND status = :expectedStatus
        """,
    )
    suspend fun compareAndSetAttemptStatus(
        attemptId: String,
        expectedStatus: RequestAttemptStatus,
        nextStatus: RequestAttemptStatus,
        providerRequestId: String?,
        errorCode: StandardErrorCode?,
        httpStatus: Int?,
        outputHash: String?,
        updatedAt: Long,
    ): Int

    @Query(
        """
        UPDATE request_attempt
        SET standard_error_code = :errorCode,
            updated_at = :updatedAt
        WHERE attempt_id = :attemptId
          AND status = 'SUCCEEDED'
          AND output_hash = :expectedOutputHash
          AND standard_error_code IS NULL
          AND updated_at <= :updatedAt
        """,
    )
    suspend fun markCompletedAttemptValidationError(
        attemptId: String,
        expectedOutputHash: String,
        errorCode: StandardErrorCode,
        updatedAt: Long,
    ): Int

    @Query(
        """
        UPDATE usage_ledger
        SET source = :source,
            status = :status,
            input_tokens = :inputTokens,
            output_tokens = :outputTokens,
            cached_tokens = :cachedTokens,
            reasoning_tokens = :reasoningTokens,
            total_tokens = :totalTokens,
            currency = :currency,
            estimated_cost_micros = :estimatedCostMicros,
            price_catalog_version = :priceCatalogVersion,
            finalized_at = CASE WHEN :status = 'FINAL' THEN :updatedAt ELSE NULL END,
            updated_at = :updatedAt
        WHERE attempt_id = :attemptId AND status = 'PROVISIONAL'
        """,
    )
    suspend fun updateProvisionalUsage(
        attemptId: String,
        source: UsageSource,
        status: UsageLedgerStatus,
        inputTokens: Long?,
        outputTokens: Long?,
        cachedTokens: Long?,
        reasoningTokens: Long?,
        totalTokens: Long?,
        currency: String?,
        estimatedCostMicros: Long?,
        priceCatalogVersion: String?,
        updatedAt: Long,
    ): Int

    @Query(
        """
        UPDATE usage_ledger
        SET source = 'PROVIDER_REPORTED',
            input_tokens = :inputTokens,
            output_tokens = :outputTokens,
            cached_tokens = :cachedTokens,
            reasoning_tokens = :reasoningTokens,
            total_tokens = :totalTokens,
            currency = :currency,
            estimated_cost_micros = :estimatedCostMicros,
            price_catalog_version = :priceCatalogVersion,
            finalized_at = :updatedAt,
            updated_at = :updatedAt
        WHERE attempt_id = :attemptId
          AND status = 'FINAL'
          AND source IN ('UNKNOWN', 'ESTIMATED')
        """,
    )
    suspend fun upgradeFinalUsageToProviderReport(
        attemptId: String,
        inputTokens: Long?,
        outputTokens: Long?,
        cachedTokens: Long?,
        reasoningTokens: Long?,
        totalTokens: Long,
        currency: String?,
        estimatedCostMicros: Long?,
        priceCatalogVersion: String?,
        updatedAt: Long,
    ): Int

    @Query(
        """
        SELECT book.book_id
        FROM generation_stage
        INNER JOIN generation_job ON generation_job.job_id = generation_stage.job_id
        INNER JOIN book ON book.book_id = generation_job.book_id
        WHERE generation_stage.stage_id = :stageId
        """,
    )
    suspend fun findBookIdForStage(stageId: String): String?

    @Query(
        """
        SELECT COUNT(*) FROM generation_stage
        WHERE job_id = :jobId AND status != 'SUCCEEDED'
        """,
    )
    suspend fun countNonSucceededStages(jobId: String): Int

    @Transaction
    suspend fun createJob(job: GenerationJobEntity, stages: List<GenerationStageEntity>) {
        require(job.jobId.isNotBlank()) { "Job id must not be blank." }
        require(job.bookId.isNotBlank()) { "Book id must not be blank." }
        require(job.status == GenerationJobStatus.CREATED) { "A new job must start in CREATED." }
        require(job.currentStageId == null) { "A new job cannot point at stages before they are inserted." }
        require(job.promptBundleVersion.isNotBlank()) { "Prompt bundle version must not be blank." }
        require(job.budgetSnapshotJson.isNotBlank()) { "Budget snapshot must not be blank." }
        require(stages.isNotEmpty()) { "A generation job requires at least one stage." }
        require(stages.all { it.jobId == job.jobId }) { "All stages must belong to the new job." }
        require(stages.all { it.status == GenerationStageStatus.PENDING }) {
            "New stages must start in PENDING."
        }
        require(stages.all { it.attemptCount == 0 && it.maxAttempts > 0 }) {
            "New stages require zero attempts and a positive attempt limit."
        }
        require(stages.map { it.stageId }.distinct().size == stages.size) { "Stage ids must be unique." }
        require(stages.map { it.idempotencyKey }.distinct().size == stages.size) {
            "Stage idempotency keys must be unique."
        }

        insertJob(job)
        insertStages(stages)
        check(setCurrentStage(job.jobId, stages.first().stageId, job.createdAt) == 1)
    }

    @Transaction
    suspend fun transitionJob(
        jobId: String,
        expectedStatus: GenerationJobStatus,
        event: JobEvent,
        updatedAt: Long,
        leaseToken: GenerationLeaseToken? = null,
        leasePolicy: GenerationLeasePolicy = GenerationLeasePolicy(),
    ): GenerationJobEntity {
        require(event != JobEvent.LEASE_ACQUIRED) { "Use acquireJobLease for lease acquisition." }
        require(
            event !in setOf(
                JobEvent.PAUSE_REQUESTED,
                JobEvent.STOP_REQUESTED,
                JobEvent.SAFE_POINT_REACHED,
                JobEvent.RESUME_APPROVED,
            ),
        ) { "User generation controls must use GenerationControlRepository." }
        val current = requireNotNull(findJob(jobId)) { "Job $jobId does not exist." }
        if (current.status != expectedStatus) {
            throw StaleGenerationStateException("Job state changed before this transition.")
        }
        require(
            !(current.status == GenerationJobStatus.NEEDS_ACTION &&
                current.pauseOrStopReason in GenerationRecoveryReason.entries.map { it.name } &&
                event == JobEvent.ISSUE_RESOLVED),
        ) { "Generation recovery decisions must use GenerationUnknownResultRecoveryRepository." }
        requireActiveJobLeaseIfOwned(current, leaseToken, updatedAt, leasePolicy)
        require(updatedAt >= current.updatedAt) { "Job transition time cannot move backwards." }
        if (event == JobEvent.ALL_STAGES_COMPLETED) {
            require(countNonSucceededStages(jobId) == 0) {
                "A job cannot complete before every stage succeeds."
            }
        }
        val next = GenerationJobStateMachine.transition(current.status, event)
        if (compareAndSetJobStatus(jobId, current.status, next, updatedAt) != 1) {
            throw StaleGenerationStateException("Job transition lost a concurrent update.")
        }
        return requireNotNull(findJob(jobId))
    }

    @Transaction
    suspend fun acquireJobLease(jobId: String, leaseOwnerId: String, now: Long): GenerationJobEntity {
        require(leaseOwnerId.isNotBlank()) { "Lease owner id must not be blank." }
        val current = requireNotNull(findJob(jobId)) { "Job $jobId does not exist." }
        require(now > current.updatedAt) { "Job lease acquisition must advance persisted time." }
        val next = GenerationJobStateMachine.transition(current.status, JobEvent.LEASE_ACQUIRED)
        if (compareAndAcquireJobLease(jobId, current.status, next, leaseOwnerId, now) != 1) {
            throw StaleGenerationStateException("Job lease was acquired by another worker.")
        }
        return requireNotNull(findJob(jobId))
    }

    @Transaction
    suspend fun heartbeatJobLease(
        jobId: String,
        leaseToken: GenerationLeaseToken,
        now: Long,
        policy: GenerationLeasePolicy,
    ): GenerationJobEntity {
        val current = requireNotNull(findJob(jobId)) { "Job $jobId does not exist." }
        requireMatchingJobLeaseIfOwned(current, leaseToken)
        require(current.status in LEASE_OWNED_JOB_STATUSES) { "Job is not in a lease-owned state." }
        val heartbeatAt = requireNotNull(current.leaseHeartbeatAt)
        require(now >= current.updatedAt && now >= heartbeatAt) { "Job heartbeat time cannot move backwards." }
        if (policy.isExpired(heartbeatAt, now)) {
            throw StaleGenerationStateException("Job lease already expired and cannot be revived by a late heartbeat.")
        }
        if (
            compareAndHeartbeatJobLease(
                jobId = jobId,
                expectedStatus = current.status,
                leaseOwnerId = leaseToken.ownerId,
                leaseAcquiredAt = leaseToken.acquiredAt,
                expectedHeartbeatAt = heartbeatAt,
                now = now,
            ) != 1
        ) {
            throw StaleGenerationStateException("Job heartbeat lost lease ownership.")
        }
        return requireNotNull(findJob(jobId))
    }

    @Transaction
    suspend fun transitionStage(
        stageId: String,
        expectedStatus: GenerationStageStatus,
        event: StageEvent,
        errorCode: StandardErrorCode? = null,
        nextRetryAt: Long? = null,
        updatedAt: Long,
        leaseToken: GenerationLeaseToken? = null,
        leasePolicy: GenerationLeasePolicy = GenerationLeasePolicy(),
    ): GenerationStageEntity {
        val current = requireNotNull(findStage(stageId)) { "Stage $stageId does not exist." }
        if (current.status != expectedStatus) {
            throw StaleGenerationStateException("Stage state changed before this transition.")
        }
        requireActiveStageLeaseIfOwned(current, leaseToken, updatedAt, leasePolicy)
        require(updatedAt >= current.updatedAt) { "Stage transition time cannot move backwards." }
        if (event == StageEvent.RETRY_DELAY_ELAPSED) {
            require(current.nextRetryAt != null && updatedAt >= current.nextRetryAt) {
                "A retry cannot resume before nextRetryAt."
            }
        }
        validateStandaloneStageTransition(current.status, event, errorCode, nextRetryAt, updatedAt)
        val next = GenerationStageStateMachine.transition(current.status, event)
        if (compareAndSetStageStatus(stageId, current.status, next, errorCode, nextRetryAt, updatedAt) != 1) {
            throw StaleGenerationStateException("Stage transition lost a concurrent update.")
        }
        return requireNotNull(findStage(stageId))
    }

    @Transaction
    suspend fun acquireStageLease(stageId: String, leaseOwnerId: String, now: Long): GenerationStageEntity {
        require(leaseOwnerId.isNotBlank()) { "Lease owner id must not be blank." }
        val current = requireNotNull(findStage(stageId)) { "Stage $stageId does not exist." }
        require(now > current.updatedAt) { "Stage lease acquisition must advance persisted time." }
        val next = GenerationStageStateMachine.transition(current.status, StageEvent.LEASE_ACQUIRED)
        if (compareAndAcquireStageLease(stageId, current.status, next, leaseOwnerId, now) != 1) {
            throw StaleGenerationStateException("Stage lease was acquired by another worker.")
        }
        return requireNotNull(findStage(stageId))
    }

    @Transaction
    suspend fun heartbeatStageLease(
        stageId: String,
        leaseToken: GenerationLeaseToken,
        now: Long,
        policy: GenerationLeasePolicy,
    ): GenerationStageEntity {
        val current = requireNotNull(findStage(stageId)) { "Stage $stageId does not exist." }
        requireMatchingStageLeaseIfOwned(current, leaseToken)
        require(current.status in LEASE_OWNED_STAGE_STATUSES) { "Stage is not in a lease-owned state." }
        val heartbeatAt = requireNotNull(current.leaseHeartbeatAt)
        require(now >= current.updatedAt && now >= heartbeatAt) { "Stage heartbeat time cannot move backwards." }
        if (policy.isExpired(heartbeatAt, now)) {
            throw StaleGenerationStateException("Stage lease already expired and cannot be revived by a late heartbeat.")
        }
        if (
            compareAndHeartbeatStageLease(
                stageId = stageId,
                expectedStatus = current.status,
                leaseOwnerId = leaseToken.ownerId,
                leaseAcquiredAt = leaseToken.acquiredAt,
                expectedHeartbeatAt = heartbeatAt,
                now = now,
            ) != 1
        ) {
            throw StaleGenerationStateException("Stage heartbeat lost lease ownership.")
        }
        return requireNotNull(findStage(stageId))
    }

    @Transaction
    suspend fun reclaimExpiredStageLease(
        stageId: String,
        observedLease: GenerationLeaseToken,
        now: Long,
        policy: GenerationLeasePolicy,
    ): ExpiredStageLeaseResult {
        val current = requireNotNull(findStage(stageId)) { "Stage $stageId does not exist." }
        requireMatchingStageLeaseIfOwned(current, observedLease)
        require(current.status in LEASE_OWNED_STAGE_STATUSES) { "Stage is not in a lease-owned state." }
        val heartbeatAt = requireNotNull(current.leaseHeartbeatAt)
        require(now >= current.updatedAt && now >= heartbeatAt) { "Lease recovery time cannot move backwards." }
        if (!policy.isExpired(heartbeatAt, now)) {
            return ExpiredStageLeaseResult(
                disposition = ExpiredStageLeaseDisposition.ACTIVE,
                stage = current.toStoredState(),
            )
        }
        if (!current.status.canSafelyRequeueAfterLeaseExpiry()) {
            return ExpiredStageLeaseResult(
                disposition = ExpiredStageLeaseDisposition.RECOVERY_AUDIT_REQUIRED,
                stage = current.toStoredState(),
            )
        }
        val next = GenerationStageStateMachine.transition(
            current.status,
            StageEvent.LEASE_EXPIRED_BEFORE_REQUEST,
        )
        if (
            compareAndRequeueExpiredPreparingStage(
                stageId = stageId,
                leaseOwnerId = observedLease.ownerId,
                leaseAcquiredAt = observedLease.acquiredAt,
                expectedHeartbeatAt = heartbeatAt,
                nextStatus = next,
                now = now,
            ) != 1
        ) {
            throw StaleGenerationStateException("Expired stage lease was changed before recovery.")
        }
        return ExpiredStageLeaseResult(
            disposition = ExpiredStageLeaseDisposition.REQUEUED_BEFORE_REQUEST,
            stage = requireNotNull(findStage(stageId)).toStoredState(),
        )
    }

    @Transaction
    suspend fun recordRequestIntent(
        intent: NewRequestIntent,
        leaseToken: GenerationLeaseToken,
    ): RequestAttemptEntity {
        require(intent.attemptId.isNotBlank() && intent.usageLedgerId.isNotBlank()) {
            "Attempt and usage ledger ids must not be blank."
        }
        require(intent.inputHash.isNotBlank()) { "Input hash must not be blank." }
        require(intent.dailyPeriodKey.isNotBlank()) { "Daily period key must not be blank." }
        val stage = requireNotNull(findStage(intent.stageId)) { "Stage ${intent.stageId} does not exist." }
        val job = requireNotNull(findJob(stage.jobId)) { "Owning generation job does not exist." }
        if (stage.status != GenerationStageStatus.PREPARING) {
            throw StaleGenerationStateException("Request intent can only be recorded from PREPARING.")
        }
        if (job.status != GenerationJobStatus.RUNNING || job.currentStageId != stage.stageId) {
            throw StaleGenerationStateException("A paused, stopping, or superseded job cannot create a request intent.")
        }
        requireActiveStageLeaseIfOwned(stage, leaseToken, intent.createdAt)
        require(intent.createdAt >= stage.updatedAt && intent.createdAt >= job.updatedAt) {
            "Request intent time cannot move backwards."
        }
        if (stage.attemptCount >= stage.maxAttempts) {
            throw IllegalStateException("Stage attempt limit has been reached.")
        }
        val retryParent = intent.retryParentAttemptId?.let { parentId ->
            requireNotNull(findAttempt(parentId)) { "Retry parent attempt does not exist." }
        }
        require(retryParent == null || retryParent.stageId == stage.stageId) {
            "Retry parent must belong to the same stage."
        }
        val retryParentEligible = retryParent == null ||
            retryParent.status in setOf(
                RequestAttemptStatus.FAILED_RETRYABLE,
                RequestAttemptStatus.UNKNOWN_RESULT,
            ) ||
            (
                retryParent.status == RequestAttemptStatus.SUCCEEDED &&
                    retryParent.standardErrorCode in setOf(
                        StandardErrorCode.FORMAT_INVALID,
                        StandardErrorCode.OUTPUT_TRUNCATED,
                    )
                )
        require(retryParentEligible) {
            "Retry parent must be retryable, format-invalid, output-truncated, or an unknown result explicitly released by the user."
        }
        require(retryParent == null || attemptsForStage(stage.stageId).lastOrNull()?.attemptId == retryParent.attemptId) {
            "Retry parent must be the latest persisted attempt in the stage."
        }

        val attempt = RequestAttemptEntity(
            attemptId = intent.attemptId,
            jobId = stage.jobId,
            stageId = stage.stageId,
            attemptNo = stage.attemptCount + 1,
            status = RequestAttemptStatus.INTENT_RECORDED,
            requestIntentAt = intent.createdAt,
            connectionSnapshotJson = intent.connectionSnapshotJson,
            modelSnapshotJson = intent.modelSnapshotJson,
            protocolSnapshotJson = intent.protocolSnapshotJson,
            inputHash = intent.inputHash,
            streamDraftRef = intent.streamDraftRef,
            retryParentAttemptId = intent.retryParentAttemptId,
            createdAt = intent.createdAt,
            updatedAt = intent.createdAt,
        )
        val bookId = requireNotNull(findBookIdForStage(stage.stageId)) { "Owning book does not exist." }
        val ledger = UsageLedgerEntity(
            usageLedgerId = intent.usageLedgerId,
            attemptId = intent.attemptId,
            bookId = bookId,
            source = UsageSource.UNKNOWN,
            status = UsageLedgerStatus.PROVISIONAL,
            inputTokens = null,
            outputTokens = null,
            cachedTokens = null,
            reasoningTokens = null,
            totalTokens = null,
            currency = null,
            estimatedCostMicros = null,
            priceCatalogVersion = null,
            dailyPeriodKey = intent.dailyPeriodKey,
            finalizedAt = null,
            createdAt = intent.createdAt,
            updatedAt = intent.createdAt,
        )
        insertAttempt(attempt)
        insertUsageLedger(ledger)
        val next = GenerationStageStateMachine.transition(stage.status, StageEvent.INPUT_FROZEN)
        if (compareAndRecordIntent(stage.stageId, stage.status, next, intent.createdAt) != 1) {
            throw StaleGenerationStateException("Stage changed while recording request intent.")
        }
        return attempt
    }

    @Transaction
    suspend fun recordRequestSent(
        attemptId: String,
        providerRequestId: String?,
        sentAt: Long,
        leaseToken: GenerationLeaseToken,
    ): RequestAttemptEntity {
        val attempt = requireNotNull(findAttempt(attemptId)) { "Attempt $attemptId does not exist." }
        val stage = requireNotNull(findStage(attempt.stageId)) { "Owning stage does not exist." }
        val job = requireNotNull(findJob(stage.jobId)) { "Owning generation job does not exist." }
        if (job.status != GenerationJobStatus.RUNNING || job.currentStageId != stage.stageId) {
            throw StaleGenerationStateException("A paused or stopping job cannot mark a request as sent.")
        }
        requireActiveStageLeaseIfOwned(stage, leaseToken, sentAt)
        require(sentAt >= attempt.updatedAt && sentAt >= stage.updatedAt && sentAt >= job.updatedAt) {
            "Request send time cannot move backwards."
        }
        val nextAttempt = RequestAttemptStateMachine.transition(attempt.status, AttemptEvent.REQUEST_SENT)
        val nextStage = GenerationStageStateMachine.transition(stage.status, StageEvent.REQUEST_SENT)
        if (
            compareAndSetAttemptStatus(
                attemptId,
                attempt.status,
                nextAttempt,
                providerRequestId,
                null,
                null,
                null,
                sentAt,
            ) != 1 ||
            compareAndSetStageStatus(
                stage.stageId,
                stage.status,
                nextStage,
                null,
                null,
                sentAt,
            ) != 1
        ) {
            throw StaleGenerationStateException("Request send transition lost a concurrent update.")
        }
        return requireNotNull(findAttempt(attemptId))
    }

    @Transaction
    suspend fun recordStreamStarted(
        attemptId: String,
        updatedAt: Long,
        leaseToken: GenerationLeaseToken,
    ): RequestAttemptEntity {
        val attempt = requireNotNull(findAttempt(attemptId)) { "Attempt $attemptId does not exist." }
        val stage = requireNotNull(findStage(attempt.stageId)) { "Owning stage does not exist." }
        val job = requireNotNull(findJob(stage.jobId)) { "Owning generation job does not exist." }
        if (job.status != GenerationJobStatus.RUNNING || job.currentStageId != stage.stageId) {
            throw StaleGenerationStateException("A paused or stopping job cannot start a response stream.")
        }
        requireActiveStageLeaseIfOwned(stage, leaseToken, updatedAt)
        require(updatedAt >= attempt.updatedAt && updatedAt >= stage.updatedAt && updatedAt >= job.updatedAt) {
            "Stream-start time cannot move backwards."
        }
        val next = RequestAttemptStateMachine.transition(attempt.status, AttemptEvent.STREAM_STARTED)
        if (
            compareAndSetAttemptStatus(
                attemptId,
                attempt.status,
                next,
                null,
                null,
                null,
                null,
                updatedAt,
            ) != 1
        ) {
            throw StaleGenerationStateException("Stream-start transition lost a concurrent update.")
        }
        return requireNotNull(findAttempt(attemptId))
    }

    @Transaction
    suspend fun recordAttemptOutcome(
        attemptId: String,
        event: AttemptEvent,
        errorCode: StandardErrorCode?,
        httpStatus: Int?,
        outputHash: String?,
        nextRetryAt: Long?,
        updatedAt: Long,
        leaseToken: GenerationLeaseToken,
    ): RequestAttemptEntity {
        require(
            event in setOf(
                AttemptEvent.RESPONSE_COMPLETED,
                AttemptEvent.RETRYABLE_FAILURE,
                AttemptEvent.FINAL_FAILURE,
                AttemptEvent.POLICY_REFUSED,
            ),
        ) { "Use the dedicated send, stream, unknown-result, or cancellation transaction." }
        val attempt = requireNotNull(findAttempt(attemptId)) { "Attempt $attemptId does not exist." }
        val stage = requireNotNull(findStage(attempt.stageId)) { "Owning stage does not exist." }
        val job = requireNotNull(findJob(stage.jobId)) { "Owning generation job does not exist." }
        require(stage.status == GenerationStageStatus.STREAMING) {
            "Attempt outcome requires a STREAMING stage."
        }
        if (event == AttemptEvent.RESPONSE_COMPLETED) {
            require(
                job.status in setOf(GenerationJobStatus.RUNNING, GenerationJobStatus.PAUSING) &&
                    job.currentStageId == stage.stageId,
            ) { "A stopping or superseded job cannot publish a successful response." }
        }
        requireActiveStageLeaseIfOwned(stage, leaseToken, updatedAt)
        require(updatedAt >= attempt.updatedAt && updatedAt >= stage.updatedAt) {
            "Attempt outcome time cannot move backwards."
        }
        val nextAttempt = RequestAttemptStateMachine.transition(attempt.status, event)
        val failure = event != AttemptEvent.RESPONSE_COMPLETED
        require(!failure || errorCode != null) { "Failed or refused attempts require a standard error code." }
        require(event != AttemptEvent.RETRYABLE_FAILURE || nextRetryAt != null) {
            "Retryable failure requires a next retry time."
        }
        require(event != AttemptEvent.RESPONSE_COMPLETED || !outputHash.isNullOrBlank()) {
            "Successful response requires an output hash."
        }
        val nextStage = when (event) {
            AttemptEvent.RESPONSE_COMPLETED ->
                GenerationStageStateMachine.transition(stage.status, StageEvent.RESPONSE_COMPLETED)
            AttemptEvent.RETRYABLE_FAILURE ->
                GenerationStageStateMachine.transition(stage.status, StageEvent.RETRYABLE_FAILURE)
            AttemptEvent.FINAL_FAILURE,
            AttemptEvent.POLICY_REFUSED,
            -> {
                val validating = GenerationStageStateMachine.transition(
                    stage.status,
                    StageEvent.RESPONSE_COMPLETED,
                )
                GenerationStageStateMachine.transition(validating, StageEvent.USER_ACTION_REQUIRED)
            }
            else -> error("Event was validated above.")
        }

        if (
            compareAndSetAttemptStatus(
                attemptId,
                attempt.status,
                nextAttempt,
                null,
                errorCode,
                httpStatus,
                outputHash,
                updatedAt,
            ) != 1 ||
            compareAndSetStageStatus(
                stage.stageId,
                stage.status,
                nextStage,
                errorCode,
                nextRetryAt,
                updatedAt,
            ) != 1
        ) {
            throw StaleGenerationStateException("Attempt outcome lost a concurrent update.")
        }
        return requireNotNull(findAttempt(attemptId))
    }

    @Transaction
    suspend fun recordStructuredOutputInvalid(
        attemptId: String,
        expectedOutputHash: String,
        repairEligible: Boolean,
        updatedAt: Long,
        leaseToken: GenerationLeaseToken,
        usage: UsageUpdate,
    ): Boolean {
        val attempt = requireNotNull(findAttempt(attemptId)) { "Attempt $attemptId does not exist." }
        val stage = requireNotNull(findStage(attempt.stageId)) { "Owning stage does not exist." }
        val job = requireNotNull(findJob(stage.jobId)) { "Owning generation job does not exist." }
        require(attempt.status == RequestAttemptStatus.SUCCEEDED) {
            "Structured validation requires a successfully received response."
        }
        require(attempt.outputHash == expectedOutputHash && attempt.standardErrorCode == null) {
            "Structured validation evidence no longer matches the completed response."
        }
        require(stage.status == GenerationStageStatus.VALIDATING) {
            "Structured validation failure requires a VALIDATING stage."
        }
        require(
            job.status in setOf(GenerationJobStatus.RUNNING, GenerationJobStatus.PAUSING) &&
                job.currentStageId == stage.stageId,
        ) { "A stopped or superseded job cannot record a validation outcome." }
        requireActiveStageLeaseIfOwned(stage, leaseToken, updatedAt)
        require(updatedAt >= attempt.updatedAt && updatedAt >= stage.updatedAt && updatedAt >= job.updatedAt) {
            "Structured validation time cannot move backwards."
        }
        val attempts = attemptsForStage(stage.stageId)
        require(attempts.isNotEmpty() && attempts.last().attemptId == attempt.attemptId) {
            "Structured validation must apply to the latest attempt in its stage."
        }
        val priorFormatFailures = attempts.dropLast(1).count { previous ->
            previous.standardErrorCode == StandardErrorCode.FORMAT_INVALID
        }
        val repairRequired = repairEligible &&
            priorFormatFailures == 0 &&
            stage.attemptCount < stage.maxAttempts
        val nextEvent = if (repairRequired) {
            StageEvent.RETRYABLE_FAILURE
        } else {
            StageEvent.USER_ACTION_REQUIRED
        }
        val nextStage = GenerationStageStateMachine.transition(stage.status, nextEvent)
        val nextRetryAt = updatedAt.takeIf { repairRequired }
        if (
            markCompletedAttemptValidationError(
                attemptId = attempt.attemptId,
                expectedOutputHash = expectedOutputHash,
                errorCode = StandardErrorCode.FORMAT_INVALID,
                updatedAt = updatedAt,
            ) != 1 ||
            compareAndSetStageStatus(
                stageId = stage.stageId,
                expectedStatus = stage.status,
                nextStatus = nextStage,
                errorCode = StandardErrorCode.FORMAT_INVALID,
                nextRetryAt = nextRetryAt,
                updatedAt = updatedAt,
            ) != 1
        ) {
            throw StaleGenerationStateException("Structured validation failure lost a concurrent update.")
        }
        if (job.status == GenerationJobStatus.PAUSING) {
            check(
                GenerationJobStateMachine.transition(job.status, JobEvent.SAFE_POINT_REACHED) ==
                    GenerationJobStatus.PAUSED,
            )
            if (
                compareAndSetJobControlStatus(
                    jobId = job.jobId,
                    expectedStatus = job.status,
                    nextStatus = GenerationJobStatus.PAUSED,
                    reason = job.pauseOrStopReason,
                    updatedAt = updatedAt,
                ) != 1
            ) {
                throw StaleGenerationStateException("Validation pause settlement lost a concurrent job update.")
            }
        }
        recordUsage(attempt.attemptId, usage)
        return repairRequired
    }

    @Transaction
    suspend fun recordUsage(attemptId: String, update: UsageUpdate): UsageLedgerEntity {
        validateUsage(update)
        val current = requireNotNull(findUsageForAttempt(attemptId)) {
            "Usage ledger for attempt $attemptId does not exist."
        }
        if (current.status == UsageLedgerStatus.FINAL) {
            val replayMatches = current.source == update.source &&
                current.inputTokens == update.inputTokens &&
                current.outputTokens == update.outputTokens &&
                current.cachedTokens == update.cachedTokens &&
                current.reasoningTokens == update.reasoningTokens &&
                current.totalTokens == update.totalTokens &&
                current.currency == update.currency &&
                current.estimatedCostMicros == update.estimatedCostMicros &&
                current.priceCatalogVersion == update.priceCatalogVersion
            if (replayMatches && update.status == UsageLedgerStatus.FINAL) return current
            if (
                update.status == UsageLedgerStatus.FINAL &&
                update.source == UsageSource.PROVIDER_REPORTED &&
                sourceRank(update.source) > sourceRank(current.source)
            ) {
                val totalTokens = requireNotNull(update.totalTokens)
                if (
                    upgradeFinalUsageToProviderReport(
                        attemptId = attemptId,
                        inputTokens = update.inputTokens,
                        outputTokens = update.outputTokens,
                        cachedTokens = update.cachedTokens,
                        reasoningTokens = update.reasoningTokens,
                        totalTokens = totalTokens,
                        currency = update.currency,
                        estimatedCostMicros = update.estimatedCostMicros,
                        priceCatalogVersion = update.priceCatalogVersion,
                        updatedAt = update.updatedAt,
                    ) != 1
                ) {
                    throw StaleGenerationStateException("Final usage was upgraded concurrently.")
                }
                return requireNotNull(findUsageForAttempt(attemptId))
            }
            throw IllegalStateException("Final usage ledger is immutable.")
        }
        require(sourceRank(update.source) >= sourceRank(current.source)) {
            "Usage source cannot be downgraded."
        }
        if (updateProvisionalUsage(
                attemptId = attemptId,
                source = update.source,
                status = update.status,
                inputTokens = update.inputTokens,
                outputTokens = update.outputTokens,
                cachedTokens = update.cachedTokens,
                reasoningTokens = update.reasoningTokens,
                totalTokens = update.totalTokens,
                currency = update.currency,
                estimatedCostMicros = update.estimatedCostMicros,
                priceCatalogVersion = update.priceCatalogVersion,
                updatedAt = update.updatedAt,
            ) != 1
        ) {
            throw StaleGenerationStateException("Usage ledger was finalized concurrently.")
        }
        return requireNotNull(findUsageForAttempt(attemptId))
    }

    private fun validateUsage(update: UsageUpdate) {
        listOf(
            update.inputTokens,
            update.outputTokens,
            update.cachedTokens,
            update.reasoningTokens,
            update.totalTokens,
            update.estimatedCostMicros,
        ).forEach { value -> require(value == null || value >= 0) { "Usage values cannot be negative." } }
        require((update.currency == null) == (update.estimatedCostMicros == null)) {
            "Currency and cost must be present together."
        }
        require(update.currency == null || update.currency.matches(Regex("[A-Z]{3}"))) {
            "Currency must be a three-letter uppercase code."
        }
        if (update.source == UsageSource.UNKNOWN) {
            require(
                update.inputTokens == null && update.outputTokens == null &&
                    update.cachedTokens == null && update.reasoningTokens == null &&
                    update.totalTokens == null && update.estimatedCostMicros == null,
            ) { "Unknown usage must remain null rather than pretending to be zero." }
        } else {
            require(update.totalTokens != null) { "Known or estimated usage requires total tokens." }
        }
    }

    private fun sourceRank(source: UsageSource): Int = when (source) {
        UsageSource.UNKNOWN -> 0
        UsageSource.ESTIMATED -> 1
        UsageSource.PROVIDER_REPORTED -> 2
    }

    private fun requireMatchingJobLeaseIfOwned(
        job: GenerationJobEntity,
        leaseToken: GenerationLeaseToken?,
    ) {
        if (job.status !in LEASE_OWNED_JOB_STATUSES) {
            require(leaseToken == null) { "A non-running job transition must not attach a lease token." }
            return
        }
        if (job.leaseTokenOrNull() != leaseToken) {
            throw StaleGenerationStateException("Job lease ownership changed before this operation.")
        }
    }

    private fun requireMatchingStageLeaseIfOwned(
        stage: GenerationStageEntity,
        leaseToken: GenerationLeaseToken?,
    ) {
        if (stage.status !in LEASE_OWNED_STAGE_STATUSES) {
            require(leaseToken == null) { "A non-running stage transition must not attach a lease token." }
            return
        }
        if (stage.leaseTokenOrNull() != leaseToken) {
            throw StaleGenerationStateException("Stage lease ownership changed before this operation.")
        }
    }

    private fun requireActiveJobLeaseIfOwned(
        job: GenerationJobEntity,
        leaseToken: GenerationLeaseToken?,
        operationAt: Long,
        leasePolicy: GenerationLeasePolicy = GenerationLeasePolicy(),
    ) {
        requireMatchingJobLeaseIfOwned(job, leaseToken)
        if (job.status !in LEASE_OWNED_JOB_STATUSES) return
        val heartbeatAt = requireNotNull(job.leaseHeartbeatAt)
        require(operationAt >= job.updatedAt && operationAt >= heartbeatAt) {
            "Job operation time cannot move backwards."
        }
        if (leasePolicy.isExpired(heartbeatAt, operationAt)) {
            throw StaleGenerationStateException("Job lease expired before this operation.")
        }
    }

    private fun requireActiveStageLeaseIfOwned(
        stage: GenerationStageEntity,
        leaseToken: GenerationLeaseToken?,
        operationAt: Long,
        leasePolicy: GenerationLeasePolicy = GenerationLeasePolicy(),
    ) {
        requireMatchingStageLeaseIfOwned(stage, leaseToken)
        if (stage.status !in LEASE_OWNED_STAGE_STATUSES) return
        val heartbeatAt = requireNotNull(stage.leaseHeartbeatAt)
        require(operationAt >= stage.updatedAt && operationAt >= heartbeatAt) {
            "Stage operation time cannot move backwards."
        }
        if (leasePolicy.isExpired(heartbeatAt, operationAt)) {
            throw StaleGenerationStateException("Stage lease expired before this operation.")
        }
    }

    private fun validateStandaloneStageTransition(
        currentStatus: GenerationStageStatus,
        event: StageEvent,
        errorCode: StandardErrorCode?,
        nextRetryAt: Long?,
        updatedAt: Long,
    ) {
        require(event != StageEvent.LEASE_ACQUIRED) {
            "Use acquireStageLease for lease acquisition."
        }
        require(event != StageEvent.LEASE_EXPIRED_BEFORE_REQUEST) {
            "Use reclaimExpiredStageLease for expired pre-request work."
        }
        require(event != StageEvent.INPUT_FROZEN) {
            "Use recordRequestIntent so attempt and usage records are created atomically."
        }
        require(event != StageEvent.REQUEST_SENT) {
            "Use recordRequestSent so attempt and stage move atomically."
        }
        require(event != StageEvent.RESULT_UNCERTAIN) {
            "Use GenerationUnknownResultRecoveryRepository so attempt, usage, stage, and job move atomically."
        }
        require(event != StageEvent.RESPONSE_COMPLETED) {
            "Use recordAttemptOutcome so attempt and stage move atomically."
        }
        require(!(currentStatus == GenerationStageStatus.STREAMING && event == StageEvent.RETRYABLE_FAILURE)) {
            "Use recordAttemptOutcome for streaming failures."
        }
        require(event != StageEvent.COMMIT_SUCCEEDED) {
            "A commit may only succeed inside the output commit transaction."
        }
        require(event != StageEvent.PARENT_STOPPED) {
            "Parent stop must cancel eligible stages in one job-level transaction."
        }
        require(event != StageEvent.PAUSE_AT_SAFE_POINT) {
            "Pause settlement must update attempt, usage, stage, and job together."
        }
        require(event !in setOf(StageEvent.USER_CONFIRMED_RETRY, StageEvent.USER_CANCELLED)) {
            "Unknown-result decisions must use GenerationUnknownResultRecoveryRepository."
        }
        require(event !in setOf(
            StageEvent.RECOVERY_AUDIT_REQUIRED,
            StageEvent.PROVIDER_CONFIRMED_NOT_EXECUTED,
        )) {
            "Recovery evidence must use GenerationUnknownResultRecoveryRepository."
        }

        val errorEvent = event in setOf(
            StageEvent.PRECONDITION_BLOCKED,
            StageEvent.RETRYABLE_FAILURE,
            StageEvent.USER_ACTION_REQUIRED,
        )
        require(errorEvent || errorCode == null) {
            "This stage transition must not attach an unrelated error code."
        }
        if (errorEvent) {
            require(errorCode != null) { "Blocked, retry, and user-action states require an error code." }
        }
        if (event == StageEvent.RETRYABLE_FAILURE) {
            require(nextRetryAt != null && nextRetryAt >= updatedAt) {
                "A retry transition requires a non-past retry time."
            }
        } else {
            require(nextRetryAt == null) { "Only a retry transition may set nextRetryAt." }
        }
    }

    companion object {
        private val LEASE_OWNED_JOB_STATUSES = setOf(
            GenerationJobStatus.RUNNING,
            GenerationJobStatus.PAUSING,
            GenerationJobStatus.STOPPING,
        )

        private val LEASE_OWNED_STAGE_STATUSES = setOf(
            GenerationStageStatus.PREPARING,
            GenerationStageStatus.REQUEST_INTENT_RECORDED,
            GenerationStageStatus.STREAMING,
            GenerationStageStatus.VALIDATING,
            GenerationStageStatus.COMMITTING,
        )
    }
}

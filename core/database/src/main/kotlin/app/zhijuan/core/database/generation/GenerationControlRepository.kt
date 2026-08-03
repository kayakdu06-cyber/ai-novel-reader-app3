package app.zhijuan.core.database.generation

import androidx.room.withTransaction
import app.zhijuan.core.database.ZhijuanDatabase
import app.zhijuan.core.model.GenerationJobStatus
import app.zhijuan.core.model.GenerationStageStatus
import app.zhijuan.core.model.RequestAttemptStatus
import app.zhijuan.core.model.UsageLedgerStatus
import app.zhijuan.core.task.AttemptEvent
import app.zhijuan.core.task.GenerationJobStateMachine
import app.zhijuan.core.task.GenerationStageStateMachine
import app.zhijuan.core.task.JobEvent
import app.zhijuan.core.task.RequestAttemptStateMachine
import app.zhijuan.core.task.StageEvent

enum class GenerationControlReason {
    USER_PAUSE,
    USER_CANCEL_CURRENT,
    USER_STOP,
    SYSTEM_FGS_TIMEOUT,
}

enum class GenerationExecutionControl {
    PAUSE,
    CANCEL_CURRENT,
    STOP,
}

enum class GenerationControlDisposition {
    APPLIED,
    SAFE_POINT_REQUIRED,
    ALREADY_APPLIED,
    ALREADY_TERMINAL,
}

data class GenerationControlResult(
    val action: GenerationExecutionControl,
    val disposition: GenerationControlDisposition,
    val jobStatus: GenerationJobStatus,
    val stageStatus: GenerationStageStatus?,
) {
    override fun toString(): String =
        "GenerationControlResult(action=$action, disposition=$disposition, " +
            "jobStatus=$jobStatus, stageStatus=$stageStatus, identifiers=redacted)"
}

class GenerationControlRepository(
    private val database: ZhijuanDatabase,
    private val leasePolicy: GenerationLeasePolicy = GenerationLeasePolicy(),
) {
    suspend fun requestPause(jobId: String, requestedAt: Long): GenerationControlResult =
        requestPauseLike(jobId, GenerationControlReason.USER_PAUSE, requestedAt)

    suspend fun cancelCurrentChapter(jobId: String, requestedAt: Long): GenerationControlResult =
        requestPauseLike(jobId, GenerationControlReason.USER_CANCEL_CURRENT, requestedAt)

    suspend fun requestSystemForegroundTimeoutPause(
        jobId: String,
        requestedAt: Long,
    ): GenerationControlResult =
        requestPauseLike(jobId, GenerationControlReason.SYSTEM_FGS_TIMEOUT, requestedAt)

    suspend fun requestStop(jobId: String, requestedAt: Long): GenerationControlResult {
        validateRequest(jobId, requestedAt)
        return database.withTransaction {
            val dao = database.generationDao()
            var job = requireNotNull(dao.findJob(jobId)) { "Generation job does not exist." }
            val currentStage = job.currentStageId?.let { stageId ->
                requireNotNull(dao.findStage(stageId)) { "Current generation stage does not exist." }
            }
            require(requestedAt >= job.updatedAt) { "Stop request time cannot move backwards." }

            if (job.status == GenerationJobStatus.COMPLETED) {
                return@withTransaction result(
                    GenerationExecutionControl.STOP,
                    GenerationControlDisposition.ALREADY_TERMINAL,
                    job,
                    currentStage,
                )
            }
            if (job.status == GenerationJobStatus.STOPPED) {
                return@withTransaction result(
                    GenerationExecutionControl.STOP,
                    GenerationControlDisposition.ALREADY_APPLIED,
                    job,
                    currentStage,
                )
            }

            if (job.status != GenerationJobStatus.STOPPING) {
                val next = GenerationJobStateMachine.transition(job.status, JobEvent.STOP_REQUESTED)
                check(
                    dao.compareAndSetJobControlStatus(
                        jobId = job.jobId,
                        expectedStatus = job.status,
                        nextStatus = next,
                        reason = GenerationControlReason.USER_STOP.name,
                        updatedAt = requestedAt,
                    ) == 1,
                ) { "Stop request lost a concurrent job update." }
                job = requireNotNull(dao.findJob(job.jobId))
            } else {
                require(job.pauseOrStopReason == GenerationControlReason.USER_STOP.name) {
                    "Stopping job has an invalid persisted control reason."
                }
            }

            val observedStage = job.currentStageId?.let { requireNotNull(dao.findStage(it)) }
            if (observedStage?.status in NETWORK_ACTIVE_STAGE_STATUSES) {
                return@withTransaction result(
                    GenerationExecutionControl.STOP,
                    GenerationControlDisposition.SAFE_POINT_REQUIRED,
                    job,
                    observedStage,
                )
            }

            cancelAllUnfinishedStages(dao, job.jobId, requestedAt)
            if (job.status == GenerationJobStatus.STOPPING) {
                check(
                    GenerationJobStateMachine.transition(job.status, JobEvent.SAFE_POINT_REACHED) ==
                        GenerationJobStatus.STOPPED,
                )
                check(
                    dao.compareAndSetJobControlStatus(
                        jobId = job.jobId,
                        expectedStatus = job.status,
                        nextStatus = GenerationJobStatus.STOPPED,
                        reason = GenerationControlReason.USER_STOP.name,
                        updatedAt = requestedAt,
                    ) == 1,
                ) { "Stop safe point lost a concurrent job update." }
                job = requireNotNull(dao.findJob(job.jobId))
            }
            result(
                GenerationExecutionControl.STOP,
                GenerationControlDisposition.APPLIED,
                job,
                job.currentStageId?.let { requireNotNull(dao.findStage(it)) },
            )
        }
    }

    suspend fun resume(jobId: String, resumedAt: Long): GenerationControlResult {
        validateRequest(jobId, resumedAt)
        return database.withTransaction {
            val dao = database.generationDao()
            val job = requireNotNull(dao.findJob(jobId)) { "Generation job does not exist." }
            val stage = job.currentStageId?.let { requireNotNull(dao.findStage(it)) }
            require(resumedAt >= job.updatedAt) { "Resume time cannot move backwards." }
            require(job.status == GenerationJobStatus.PAUSED) { "Only a paused job can resume." }
            require(job.pauseOrStopReason in RESUMABLE_REASONS) {
                "Paused job does not contain a resumable control reason."
            }
            val next = GenerationJobStateMachine.transition(job.status, JobEvent.RESUME_APPROVED)
            check(
                dao.compareAndSetJobControlStatus(
                    jobId = job.jobId,
                    expectedStatus = job.status,
                    nextStatus = next,
                    reason = null,
                    updatedAt = resumedAt,
                ) == 1,
            ) { "Resume lost a concurrent job update." }
            result(
                action = if (job.pauseOrStopReason == GenerationControlReason.USER_CANCEL_CURRENT.name) {
                    GenerationExecutionControl.CANCEL_CURRENT
                } else {
                    GenerationExecutionControl.PAUSE
                },
                disposition = GenerationControlDisposition.APPLIED,
                job = requireNotNull(dao.findJob(job.jobId)),
                stage = stage,
            )
        }
    }

    suspend fun controlForAttempt(attemptId: String): GenerationExecutionControl? {
        require(IDENTIFIER.matches(attemptId)) { "Attempt id is invalid." }
        val dao = database.generationDao()
        val attempt = dao.findAttempt(attemptId) ?: return null
        val stage = requireNotNull(dao.findStage(attempt.stageId)) { "Owning stage does not exist." }
        val job = requireNotNull(dao.findJob(attempt.jobId)) { "Owning job does not exist." }
        if (job.currentStageId != stage.stageId || stage.status !in NETWORK_ACTIVE_STAGE_STATUSES) return null
        return when (job.status) {
            GenerationJobStatus.PAUSING -> when (requireControlReason(job)) {
                GenerationControlReason.USER_PAUSE -> GenerationExecutionControl.PAUSE
                GenerationControlReason.USER_CANCEL_CURRENT -> GenerationExecutionControl.CANCEL_CURRENT
                GenerationControlReason.USER_STOP -> error("A pause state cannot carry a stop reason.")
                GenerationControlReason.SYSTEM_FGS_TIMEOUT -> GenerationExecutionControl.PAUSE
            }
            GenerationJobStatus.STOPPING -> {
                require(requireControlReason(job) == GenerationControlReason.USER_STOP)
                GenerationExecutionControl.STOP
            }
            else -> null
        }
    }

    suspend fun settleActiveAttempt(
        attemptId: String,
        leaseToken: GenerationLeaseToken,
        action: GenerationExecutionControl,
        usage: FinalUsageCommit = FinalUsageCommit.UNKNOWN,
        settledAt: Long,
    ): GenerationControlResult {
        require(IDENTIFIER.matches(attemptId)) { "Attempt id is invalid." }
        require(settledAt >= 0L) { "Control safe-point time is invalid." }
        return database.withTransaction {
            settleActiveAttemptInTransaction(
                attemptId = attemptId,
                leaseToken = leaseToken,
                action = action,
                usage = usage,
                settledAt = settledAt,
                requireUnexpiredLease = true,
            )
        }
    }

    suspend fun settleExpiredControl(
        attemptId: String,
        observedLease: GenerationLeaseToken,
        now: Long,
    ): GenerationControlResult {
        require(IDENTIFIER.matches(attemptId)) { "Attempt id is invalid." }
        require(now >= 0L) { "Recovery time is invalid." }
        return database.withTransaction {
            val dao = database.generationDao()
            val attempt = requireNotNull(dao.findAttempt(attemptId)) { "Attempt does not exist." }
            val stage = requireNotNull(dao.findStage(attempt.stageId)) { "Owning stage does not exist." }
            require(stage.leaseTokenOrNull() == observedLease) { "Observed stage lease changed." }
            val heartbeatAt = requireNotNull(stage.leaseHeartbeatAt) { "Active stage lease is incomplete." }
            require(leasePolicy.isExpired(heartbeatAt, now)) {
                "A live execution must settle its own control request."
            }
            val action = requireNotNull(controlForAttempt(attemptId)) {
                "Attempt has no pending pause or stop control."
            }
            settleActiveAttemptInTransaction(
                attemptId = attemptId,
                leaseToken = observedLease,
                action = action,
                usage = FinalUsageCommit.UNKNOWN,
                settledAt = now,
                requireUnexpiredLease = false,
            )
        }
    }

    private suspend fun requestPauseLike(
        jobId: String,
        reason: GenerationControlReason,
        requestedAt: Long,
    ): GenerationControlResult {
        require(reason != GenerationControlReason.USER_STOP)
        validateRequest(jobId, requestedAt)
        val action = reason.toExecutionControl()
        return database.withTransaction {
            val dao = database.generationDao()
            var job = requireNotNull(dao.findJob(jobId)) { "Generation job does not exist." }
            var stage = job.currentStageId?.let { requireNotNull(dao.findStage(it)) }
            require(requestedAt >= job.updatedAt) { "Pause request time cannot move backwards." }

            if (job.status == GenerationJobStatus.PAUSED) {
                require(job.pauseOrStopReason == reason.name) {
                    "Job is paused for a different reason."
                }
                return@withTransaction result(
                    action,
                    GenerationControlDisposition.ALREADY_APPLIED,
                    job,
                    stage,
                )
            }
            if (job.status == GenerationJobStatus.PAUSING) {
                require(job.pauseOrStopReason == reason.name) {
                    "Job is already pausing for a different reason."
                }
                return@withTransaction result(
                    action,
                    GenerationControlDisposition.SAFE_POINT_REQUIRED,
                    job,
                    stage,
                )
            }
            require(job.status in setOf(GenerationJobStatus.READY, GenerationJobStatus.RUNNING)) {
                "Only a ready or running job can be paused."
            }
            require(stage != null) { "A pausable job must have a current stage." }
            if (reason == GenerationControlReason.USER_CANCEL_CURRENT) {
                require(stage.status in CANCELLABLE_CURRENT_STAGE_STATUSES) {
                    "There is no active chapter attempt left to cancel."
                }
            }

            val next = GenerationJobStateMachine.transition(job.status, JobEvent.PAUSE_REQUESTED)
            check(
                dao.compareAndSetJobControlStatus(
                    jobId = job.jobId,
                    expectedStatus = job.status,
                    nextStatus = next,
                    reason = reason.name,
                    updatedAt = requestedAt,
                ) == 1,
            ) { "Pause request lost a concurrent job update." }
            job = requireNotNull(dao.findJob(job.jobId))
            if (job.status == GenerationJobStatus.PAUSED) {
                return@withTransaction result(action, GenerationControlDisposition.APPLIED, job, stage)
            }

            if (stage.status in NETWORK_ACTIVE_STAGE_STATUSES ||
                stage.status in LOCAL_COMMIT_PIPELINE_STATUSES
            ) {
                return@withTransaction result(
                    action,
                    GenerationControlDisposition.SAFE_POINT_REQUIRED,
                    job,
                    stage,
                )
            }

            if (stage.status == GenerationStageStatus.PREPARING) {
                require(requestedAt >= stage.updatedAt) { "Pause safe-point time cannot move backwards." }
                val nextStage = GenerationStageStateMachine.transition(
                    stage.status,
                    StageEvent.PAUSE_AT_SAFE_POINT,
                )
                check(
                    dao.compareAndSetStageStatus(
                        stageId = stage.stageId,
                        expectedStatus = stage.status,
                        nextStatus = nextStage,
                        errorCode = null,
                        nextRetryAt = null,
                        updatedAt = requestedAt,
                    ) == 1,
                ) { "Pre-send pause lost the current stage." }
                stage = requireNotNull(dao.findStage(stage.stageId))
            }
            check(
                GenerationJobStateMachine.transition(job.status, JobEvent.SAFE_POINT_REACHED) ==
                    GenerationJobStatus.PAUSED,
            )
            check(
                dao.compareAndSetJobControlStatus(
                    jobId = job.jobId,
                    expectedStatus = job.status,
                    nextStatus = GenerationJobStatus.PAUSED,
                    reason = reason.name,
                    updatedAt = requestedAt,
                ) == 1,
            ) { "Pause safe point lost a concurrent job update." }
            job = requireNotNull(dao.findJob(job.jobId))
            result(action, GenerationControlDisposition.APPLIED, job, stage)
        }
    }

    private suspend fun settleActiveAttemptInTransaction(
        attemptId: String,
        leaseToken: GenerationLeaseToken,
        action: GenerationExecutionControl,
        usage: FinalUsageCommit,
        settledAt: Long,
        requireUnexpiredLease: Boolean,
    ): GenerationControlResult {
        val dao = database.generationDao()
        val attempt = requireNotNull(dao.findAttempt(attemptId)) { "Attempt does not exist." }
        val stage = requireNotNull(dao.findStage(attempt.stageId)) { "Owning stage does not exist." }
        var job = requireNotNull(dao.findJob(attempt.jobId)) { "Owning job does not exist." }
        require(job.currentStageId == stage.stageId) { "Controlled attempt is no longer the current stage." }
        require(dao.attemptsForStage(stage.stageId).lastOrNull()?.attemptId == attempt.attemptId) {
            "Only the latest attempt can reach a control safe point."
        }
        require(stage.status in NETWORK_ACTIVE_STAGE_STATUSES) {
            "Control safe point requires a request-intent or streaming stage."
        }
        require(attempt.status in ACTIVE_ATTEMPT_STATUSES) {
            "Control safe point requires an active attempt."
        }
        require(stage.leaseTokenOrNull() == leaseToken) { "Controlled stage lease changed." }
        val heartbeatAt = requireNotNull(stage.leaseHeartbeatAt)
        require(settledAt >= heartbeatAt && settledAt >= stage.updatedAt && settledAt >= attempt.updatedAt) {
            "Control safe-point time cannot move backwards."
        }
        if (requireUnexpiredLease && leasePolicy.isExpired(heartbeatAt, settledAt)) {
            throw StaleGenerationStateException("Stage lease expired before control settlement.")
        }

        val persistedReason = requireControlReason(job)
        val expectedJobStatus = if (action == GenerationExecutionControl.STOP) {
            GenerationJobStatus.STOPPING
        } else {
            GenerationJobStatus.PAUSING
        }
        require(job.status == expectedJobStatus && persistedReason.toExecutionControl() == action) {
            "Persisted job control no longer matches the active execution."
        }

        val nextAttempt = RequestAttemptStateMachine.transition(attempt.status, AttemptEvent.CANCELLED)
        check(
            dao.compareAndSetAttemptStatus(
                attemptId = attempt.attemptId,
                expectedStatus = attempt.status,
                nextStatus = nextAttempt,
                providerRequestId = null,
                errorCode = null,
                httpStatus = null,
                outputHash = null,
                updatedAt = settledAt,
            ) == 1,
        ) { "Attempt cancellation lost a concurrent outcome." }
        dao.recordUsage(attempt.attemptId, usage.toFinalUpdate(settledAt))

        if (action == GenerationExecutionControl.STOP) {
            cancelAllUnfinishedStages(dao, job.jobId, settledAt)
            check(
                GenerationJobStateMachine.transition(job.status, JobEvent.SAFE_POINT_REACHED) ==
                    GenerationJobStatus.STOPPED,
            )
            check(
                dao.compareAndSetJobControlStatus(
                    jobId = job.jobId,
                    expectedStatus = job.status,
                    nextStatus = GenerationJobStatus.STOPPED,
                    reason = persistedReason.name,
                    updatedAt = settledAt,
                ) == 1,
            ) { "Stop settlement lost a concurrent job update." }
        } else {
            val nextStage = GenerationStageStateMachine.transition(
                stage.status,
                StageEvent.PAUSE_AT_SAFE_POINT,
            )
            check(
                dao.compareAndSetStageStatus(
                    stageId = stage.stageId,
                    expectedStatus = stage.status,
                    nextStatus = nextStage,
                    errorCode = null,
                    nextRetryAt = null,
                    updatedAt = settledAt,
                ) == 1,
            ) { "Pause settlement lost a concurrent stage outcome." }
            check(
                GenerationJobStateMachine.transition(job.status, JobEvent.SAFE_POINT_REACHED) ==
                    GenerationJobStatus.PAUSED,
            )
            check(
                dao.compareAndSetJobControlStatus(
                    jobId = job.jobId,
                    expectedStatus = job.status,
                    nextStatus = GenerationJobStatus.PAUSED,
                    reason = persistedReason.name,
                    updatedAt = settledAt,
                ) == 1,
            ) { "Pause settlement lost a concurrent job update." }
        }
        job = requireNotNull(dao.findJob(job.jobId))
        return result(
            action = action,
            disposition = GenerationControlDisposition.APPLIED,
            job = job,
            stage = requireNotNull(dao.findStage(stage.stageId)),
        )
    }

    private suspend fun cancelAllUnfinishedStages(
        dao: GenerationDao,
        jobId: String,
        updatedAt: Long,
    ) {
        val unfinished = dao.stagesForJob(jobId).filter { stage ->
            stage.status !in TERMINAL_STAGE_STATUSES
        }
        unfinished.forEach { stage ->
            require(updatedAt >= stage.updatedAt) { "Stop time cannot move backwards across stages." }
            check(
                GenerationStageStateMachine.transition(stage.status, StageEvent.PARENT_STOPPED) ==
                    GenerationStageStatus.CANCELLED,
            )
        }
        check(dao.cancelUnfinishedStagesForStoppedJob(jobId, updatedAt) == unfinished.size) {
            "Stopping the job lost a concurrent stage update."
        }
    }

    private fun validateRequest(jobId: String, at: Long) {
        require(IDENTIFIER.matches(jobId)) { "Job id is invalid." }
        require(at >= 0L) { "Control request time is invalid." }
    }

    private fun requireControlReason(job: GenerationJobEntity): GenerationControlReason =
        GenerationControlReason.entries.singleOrNull { reason -> reason.name == job.pauseOrStopReason }
            ?: throw IllegalStateException("Generation job has an unknown control reason.")

    private fun GenerationControlReason.toExecutionControl(): GenerationExecutionControl = when (this) {
        GenerationControlReason.USER_PAUSE -> GenerationExecutionControl.PAUSE
        GenerationControlReason.USER_CANCEL_CURRENT -> GenerationExecutionControl.CANCEL_CURRENT
        GenerationControlReason.USER_STOP -> GenerationExecutionControl.STOP
        GenerationControlReason.SYSTEM_FGS_TIMEOUT -> GenerationExecutionControl.PAUSE
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

    private fun result(
        action: GenerationExecutionControl,
        disposition: GenerationControlDisposition,
        job: GenerationJobEntity,
        stage: GenerationStageEntity?,
    ) = GenerationControlResult(action, disposition, job.status, stage?.status)

    private companion object {
        val IDENTIFIER = Regex("[A-Za-z0-9._:-]{1,128}")
        val NETWORK_ACTIVE_STAGE_STATUSES = setOf(
            GenerationStageStatus.REQUEST_INTENT_RECORDED,
            GenerationStageStatus.STREAMING,
        )
        val LOCAL_COMMIT_PIPELINE_STATUSES = setOf(
            GenerationStageStatus.VALIDATING,
            GenerationStageStatus.COMMITTING,
        )
        val CANCELLABLE_CURRENT_STAGE_STATUSES = setOf(
            GenerationStageStatus.PREPARING,
            GenerationStageStatus.REQUEST_INTENT_RECORDED,
            GenerationStageStatus.STREAMING,
        )
        val ACTIVE_ATTEMPT_STATUSES = setOf(
            RequestAttemptStatus.INTENT_RECORDED,
            RequestAttemptStatus.SENT,
            RequestAttemptStatus.STREAMING,
        )
        val TERMINAL_STAGE_STATUSES = setOf(
            GenerationStageStatus.SUCCEEDED,
            GenerationStageStatus.CANCELLED,
        )
        val RESUMABLE_REASONS = setOf(
            GenerationControlReason.USER_PAUSE.name,
            GenerationControlReason.USER_CANCEL_CURRENT.name,
            GenerationControlReason.SYSTEM_FGS_TIMEOUT.name,
        )
    }
}

package app.zhijuan.feature.generation

import app.zhijuan.core.database.generation.GenerationLeaseToken
import app.zhijuan.core.database.generation.GenerationRunnerExecutionLeaseRepository
import app.zhijuan.core.database.generation.GenerationRunnerExecutionLeaseSnapshot
import app.zhijuan.core.database.generation.GenerationRunnerQueueCandidate
import app.zhijuan.core.database.generation.GenerationRunnerQueueClaimResult
import app.zhijuan.core.database.generation.GenerationRunnerQueueHeartbeatResult
import app.zhijuan.core.database.generation.GenerationRunnerQueueRepository
import app.zhijuan.core.database.generation.GenerationStateRepository
import app.zhijuan.core.database.generation.StaleGenerationStateException
import app.zhijuan.core.database.generation.StoredGenerationJobState
import app.zhijuan.core.database.generation.StoredGenerationStageState
import app.zhijuan.core.model.GenerationJobStatus
import app.zhijuan.core.model.GenerationStageStatus

enum class GenerationPersistentRunDisposition {
    COMPLETED,
    NOT_READY,
    ACTIVE,
    CONTROL_PENDING,
    NEEDS_ACTION,
    RECOVERY_REQUIRED,
    CONTESTED,
    STAGE_LIMIT_REACHED,
}

data class GenerationPersistentRunResult(
    val disposition: GenerationPersistentRunDisposition,
    val executedStageCount: Int,
) {
    init {
        require(executedStageCount >= 0)
    }
}

fun interface GenerationTotalRunnerPort {
    suspend fun runJob(jobId: String, runnerOwnerId: String): GenerationPersistentRunResult
}

internal data class GenerationPersistentTotalRunnerDependencies(
    val findReadyJob: suspend (String, Long) -> GenerationRunnerQueueCandidate?,
    val claimReadyJob: suspend (GenerationRunnerQueueCandidate, String, Long) -> GenerationRunnerQueueClaimResult,
    val acquireCurrentStage: suspend (GenerationRunnerQueueClaimResult, String, Long) -> GenerationRunnerExecutionLeaseSnapshot,
    val executeCurrentStage: suspend (GenerationRunnerExecutionLeaseSnapshot, Long) -> Unit,
    val heartbeatAndLoadCurrentStage: suspend (String, GenerationLeaseToken, Long) -> GenerationRunnerQueueHeartbeatResult,
    val inspectJob: suspend (String) -> StoredGenerationJobState?,
    val inspectStage: suspend (String) -> StoredGenerationStageState?,
    val nowMillis: () -> Long,
)

/** One exact-token Job loop. Business executors alone advance the persisted Stage cursor. */
class GenerationPersistentTotalRunnerV1 internal constructor(
    private val dependencies: GenerationPersistentTotalRunnerDependencies,
    private val maxStagesPerRun: Int = DEFAULT_MAX_STAGES_PER_RUN,
) : GenerationTotalRunnerPort {
    constructor(
        queue: GenerationRunnerQueueRepository,
        executionLeases: GenerationRunnerExecutionLeaseRepository,
        states: GenerationStateRepository,
        registry: GenerationRunnerExecutorRegistryV1,
        heartbeatEnvelope: GenerationRunnerHeartbeatEnvelope,
        clock: GenerationExecutionClock = SystemGenerationExecutionClock,
        maxStagesPerRun: Int = DEFAULT_MAX_STAGES_PER_RUN,
    ) : this(
        dependencies = productionDependencies(
            queue = queue,
            executionLeases = executionLeases,
            states = states,
            registry = registry,
            heartbeatEnvelope = heartbeatEnvelope,
            clock = clock,
        ),
        maxStagesPerRun = maxStagesPerRun,
    )

    init {
        require(maxStagesPerRun in 1..MAX_STAGES_PER_RUN)
    }

    override suspend fun runJob(
        jobId: String,
        runnerOwnerId: String,
    ): GenerationPersistentRunResult {
        val time = MonotonicRunnerTime(dependencies.nowMillis)
        val observedAt = time.next()
        val candidate = dependencies.findReadyJob(jobId, observedAt)
            ?: return inspectNonReady(jobId)
        val claim = try {
            dependencies.claimReadyJob(
                candidate,
                runnerOwnerId,
                time.nextAfter(candidate.jobUpdatedAt, candidate.stageUpdatedAt),
            )
        } catch (_: StaleGenerationStateException) {
            return GenerationPersistentRunResult(GenerationPersistentRunDisposition.CONTESTED, 0)
        }
        return runClaimed(claim, runnerOwnerId, time)
    }

    private suspend fun runClaimed(
        claim: GenerationRunnerQueueClaimResult,
        runnerOwnerId: String,
        time: MonotonicRunnerTime,
    ): GenerationPersistentRunResult {
        var current = claim
        var executed = 0
        repeat(maxStagesPerRun) {
            val lease = dependencies.acquireCurrentStage(
                current,
                runnerOwnerId,
                time.nextAfter(current.jobLeaseToken.acquiredAt),
            )
            dependencies.executeCurrentStage(
                lease,
                time.nextAfter(lease.jobHeartbeatAt, lease.stageHeartbeatAt),
            )
            executed += 1

            val job = dependencies.inspectJob(current.jobId)
                ?: throw StaleGenerationStateException("Runner Job disappeared after Stage execution.")
            terminalDisposition(job)?.let { disposition ->
                return GenerationPersistentRunResult(disposition, executed)
            }
            if (job.status != GenerationJobStatus.RUNNING || job.currentStageId == current.currentStage.stageId) {
                return inspectPersistedState(job, executed)
            }

            val heartbeat = dependencies.heartbeatAndLoadCurrentStage(
                current.jobId,
                current.jobLeaseToken,
                time.nextAfter(job.updatedAt),
            )
            if (
                heartbeat.jobStatus != GenerationJobStatus.RUNNING ||
                heartbeat.currentStage.status != GenerationStageStatus.READY
            ) {
                return inspectPersistedState(job, executed)
            }
            current = GenerationRunnerQueueClaimResult(
                jobId = current.jobId,
                jobStatus = heartbeat.jobStatus,
                jobLeaseToken = current.jobLeaseToken,
                currentStage = heartbeat.currentStage,
            )
        }
        return GenerationPersistentRunResult(
            GenerationPersistentRunDisposition.STAGE_LIMIT_REACHED,
            executed,
        )
    }

    private suspend fun inspectNonReady(jobId: String): GenerationPersistentRunResult {
        val job = dependencies.inspectJob(jobId)
            ?: return GenerationPersistentRunResult(GenerationPersistentRunDisposition.NOT_READY, 0)
        return inspectPersistedState(job, 0)
    }

    private suspend fun inspectPersistedState(
        job: StoredGenerationJobState,
        executed: Int,
    ): GenerationPersistentRunResult {
        terminalDisposition(job)?.let { return GenerationPersistentRunResult(it, executed) }
        if (job.status == GenerationJobStatus.PAUSING || job.status == GenerationJobStatus.STOPPING) {
            return GenerationPersistentRunResult(GenerationPersistentRunDisposition.CONTROL_PENDING, executed)
        }
        val stage = job.currentStageId?.let { dependencies.inspectStage(it) }
        val disposition = when (stage?.status) {
            GenerationStageStatus.PREPARING -> GenerationPersistentRunDisposition.ACTIVE
            GenerationStageStatus.REQUEST_INTENT_RECORDED,
            GenerationStageStatus.STREAMING,
            GenerationStageStatus.VALIDATING,
            GenerationStageStatus.COMMITTING,
            GenerationStageStatus.UNKNOWN_RESULT,
            GenerationStageStatus.RECOVERY_REQUIRED,
            -> GenerationPersistentRunDisposition.RECOVERY_REQUIRED
            else -> if (job.status == GenerationJobStatus.RUNNING && job.leaseToken != null) {
                GenerationPersistentRunDisposition.ACTIVE
            } else {
                GenerationPersistentRunDisposition.NOT_READY
            }
        }
        return GenerationPersistentRunResult(disposition, executed)
    }

    private fun terminalDisposition(job: StoredGenerationJobState): GenerationPersistentRunDisposition? =
        when (job.status) {
            GenerationJobStatus.COMPLETED -> GenerationPersistentRunDisposition.COMPLETED
            GenerationJobStatus.NEEDS_ACTION,
            GenerationJobStatus.BLOCKED,
            GenerationJobStatus.PAUSED,
            GenerationJobStatus.STOPPED,
            -> GenerationPersistentRunDisposition.NEEDS_ACTION
            else -> null
        }

    private companion object {
        const val DEFAULT_MAX_STAGES_PER_RUN = 32
        const val MAX_STAGES_PER_RUN = 128

        fun productionDependencies(
            queue: GenerationRunnerQueueRepository,
            executionLeases: GenerationRunnerExecutionLeaseRepository,
            states: GenerationStateRepository,
            registry: GenerationRunnerExecutorRegistryV1,
            heartbeatEnvelope: GenerationRunnerHeartbeatEnvelope,
            clock: GenerationExecutionClock,
        ) = GenerationPersistentTotalRunnerDependencies(
            findReadyJob = queue::findReadyJob,
            claimReadyJob = queue::claimReadyJob,
            acquireCurrentStage = { claim, owner, at ->
                executionLeases.acquireCurrentStageLease(
                    claim.jobId,
                    claim.jobLeaseToken,
                    claim.currentStage.stageId,
                    owner,
                    at,
                )
            },
            executeCurrentStage = { lease, requestedAt ->
                val route = executionLeases.resolveCurrentStageRoute(
                    lease.jobId,
                    lease.jobLeaseToken,
                    lease.stageId,
                    lease.stageLeaseToken,
                    requestedAt,
                )
                heartbeatEnvelope.run(
                    GenerationRunnerHeartbeatIdentity(
                        lease.jobId,
                        lease.jobLeaseToken,
                        lease.stageId,
                        lease.stageLeaseToken,
                    ),
                ) {
                    registry.execute(route, requestedAt)
                }
            },
            heartbeatAndLoadCurrentStage = queue::heartbeatAndLoadCurrentStage,
            inspectJob = states::findJob,
            inspectStage = states::findStage,
            nowMillis = clock::nowMillis,
        )
    }
}

private class MonotonicRunnerTime(
    private val nowMillis: () -> Long,
) {
    private var previous = -1L

    fun next(): Long = nextAfter()

    fun nextAfter(vararg persistedTimes: Long): Long {
        val now = nowMillis().also { require(it >= 0L) }
        val floor = maxOf(previous, persistedTimes.maxOrNull() ?: -1L)
        require(floor < Long.MAX_VALUE) { "Runner persisted time cannot advance." }
        return maxOf(now, floor + 1L).also { previous = it }
    }
}

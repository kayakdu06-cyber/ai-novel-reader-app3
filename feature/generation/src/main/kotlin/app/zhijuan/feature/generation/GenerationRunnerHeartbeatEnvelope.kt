package app.zhijuan.feature.generation

import app.zhijuan.core.database.generation.GenerationLeasePolicy
import app.zhijuan.core.database.generation.GenerationLeaseToken
import app.zhijuan.core.database.generation.GenerationRunnerExecutionLeaseRepository
import app.zhijuan.core.database.generation.GenerationRunnerExecutionLeaseSnapshot
import app.zhijuan.core.database.generation.GenerationStateRepository
import app.zhijuan.core.database.generation.StoredGenerationJobState
import app.zhijuan.core.model.GenerationJobStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.selects.select

private val RUNNER_HEARTBEAT_IDENTIFIER = Regex("[A-Za-z0-9._:-]{1,128}")

data class GenerationRunnerHeartbeatIdentity(
    val jobId: String,
    val jobLeaseToken: GenerationLeaseToken,
    val stageId: String,
    val stageLeaseToken: GenerationLeaseToken,
) {
    override fun toString(): String = "GenerationRunnerHeartbeatIdentity(identifiers=redacted)"
}

internal data class GenerationRunnerHeartbeatEnvelopeDependencies(
    val heartbeat: suspend (GenerationRunnerHeartbeatIdentity, Long) -> GenerationRunnerExecutionLeaseSnapshot,
    val inspectJob: suspend (String) -> StoredGenerationJobState?,
    val awaitNextHeartbeat: suspend () -> Unit,
    val nowMillis: () -> Long,
)

private sealed interface GenerationRunnerHeartbeatSignal<out T> {
    data class ActionCompleted<T>(val value: T) : GenerationRunnerHeartbeatSignal<T>
    data object HeartbeatDue : GenerationRunnerHeartbeatSignal<Nothing>
}

/** Keeps the exact Job and Stage leases alive only while one Stage action owns execution. */
class GenerationRunnerHeartbeatEnvelope internal constructor(
    private val dependencies: GenerationRunnerHeartbeatEnvelopeDependencies,
) {
    constructor(
        executionLeases: GenerationRunnerExecutionLeaseRepository,
        states: GenerationStateRepository,
        clock: GenerationExecutionClock = SystemGenerationExecutionClock,
        heartbeatIntervalMillis: Long = GenerationLeasePolicy.DEFAULT_HEARTBEAT_INTERVAL_MILLIS,
    ) : this(productionDependencies(executionLeases, states, clock, heartbeatIntervalMillis))

    suspend fun <T> run(
        identity: GenerationRunnerHeartbeatIdentity,
        action: suspend () -> T,
    ): T {
        validateIdentity(identity)
        return coroutineScope {
            val actionDeferred = async(start = CoroutineStart.UNDISPATCHED) { action() }
            while (true) {
                val heartbeatDue = async(start = CoroutineStart.UNDISPATCHED) {
                    dependencies.awaitNextHeartbeat()
                }
                val signal = try {
                    select<GenerationRunnerHeartbeatSignal<T>> {
                        actionDeferred.onAwait { GenerationRunnerHeartbeatSignal.ActionCompleted(it) }
                        heartbeatDue.onAwait { GenerationRunnerHeartbeatSignal.HeartbeatDue }
                    }
                } finally {
                    heartbeatDue.cancel()
                }
                when (signal) {
                    is GenerationRunnerHeartbeatSignal.ActionCompleted -> return@coroutineScope signal.value
                    GenerationRunnerHeartbeatSignal.HeartbeatDue -> {
                        try {
                            dependencies.heartbeat(identity, dependencies.nowMillis())
                        } catch (heartbeatFailure: Throwable) {
                            if (heartbeatFailure is CancellationException) throw heartbeatFailure
                            if (actionDeferred.isCompleted) return@coroutineScope actionDeferred.await()
                            val job = try {
                                dependencies.inspectJob(identity.jobId)
                            } catch (inspectionFailure: Throwable) {
                                heartbeatFailure.addSuppressed(inspectionFailure)
                                null
                            }
                            if (job.isDurableActionBoundary(identity)) {
                                return@coroutineScope actionDeferred.await()
                            }
                            val cancellation = CancellationException(
                                "Generation action lost its persisted execution lease.",
                            ).apply { initCause(heartbeatFailure) }
                            actionDeferred.cancel(cancellation)
                            try {
                                actionDeferred.await()
                            } catch (_: CancellationException) {
                                // Preserve the original lease failure as the runner-facing cause.
                            }
                            throw heartbeatFailure
                        }
                    }
                }
            }
            error("Unreachable runner heartbeat state.")
        }
    }

    private fun validateIdentity(identity: GenerationRunnerHeartbeatIdentity) {
        require(RUNNER_HEARTBEAT_IDENTIFIER.matches(identity.jobId)) { "Runner Job id is invalid." }
        require(RUNNER_HEARTBEAT_IDENTIFIER.matches(identity.stageId)) { "Runner Stage id is invalid." }
        require(RUNNER_HEARTBEAT_IDENTIFIER.matches(identity.jobLeaseToken.ownerId)) {
            "Runner lease owner is invalid."
        }
        require(identity.stageLeaseToken.ownerId == identity.jobLeaseToken.ownerId) {
            "Job and Stage leases must belong to the same runner owner."
        }
    }

    private companion object {
        fun productionDependencies(
            executionLeases: GenerationRunnerExecutionLeaseRepository,
            states: GenerationStateRepository,
            clock: GenerationExecutionClock,
            heartbeatIntervalMillis: Long,
        ): GenerationRunnerHeartbeatEnvelopeDependencies {
            require(heartbeatIntervalMillis > 0L && heartbeatIntervalMillis < GenerationLeasePolicy.DEFAULT_TIMEOUT_MILLIS)
            return GenerationRunnerHeartbeatEnvelopeDependencies(
                heartbeat = { identity, at ->
                    executionLeases.heartbeatCurrentExecutionLeases(
                        identity.jobId,
                        identity.jobLeaseToken,
                        identity.stageId,
                        identity.stageLeaseToken,
                        at,
                    )
                },
                inspectJob = states::findJob,
                awaitNextHeartbeat = { delay(heartbeatIntervalMillis) },
                nowMillis = clock::nowMillis,
            )
        }
    }
}

private fun StoredGenerationJobState?.isDurableActionBoundary(
    identity: GenerationRunnerHeartbeatIdentity,
): Boolean {
    this ?: return false
    if (jobId != identity.jobId) return false
    if (
        status in setOf(
            GenerationJobStatus.COMPLETED,
            GenerationJobStatus.PAUSED,
            GenerationJobStatus.STOPPED,
            GenerationJobStatus.NEEDS_ACTION,
            GenerationJobStatus.BLOCKED,
        )
    ) return leaseToken == null
    return status in setOf(
        GenerationJobStatus.RUNNING,
        GenerationJobStatus.PAUSING,
        GenerationJobStatus.STOPPING,
    ) && leaseToken == identity.jobLeaseToken && currentStageId != identity.stageId
}

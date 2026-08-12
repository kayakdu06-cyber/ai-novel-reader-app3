package app.zhijuan.feature.generation

import app.zhijuan.core.database.generation.GenerationLeaseToken
import app.zhijuan.core.database.generation.GenerationRunnerExecutionLeaseSnapshot
import app.zhijuan.core.database.generation.GenerationRunnerQueueCandidate
import app.zhijuan.core.database.generation.GenerationRunnerQueueClaimResult
import app.zhijuan.core.database.generation.GenerationRunnerQueueHeartbeatResult
import app.zhijuan.core.database.generation.GenerationRunnerQueueStageSnapshot
import app.zhijuan.core.database.generation.StaleGenerationStateException
import app.zhijuan.core.database.generation.StoredGenerationJobState
import app.zhijuan.core.database.generation.StoredGenerationStageState
import app.zhijuan.core.model.GenerationJobStatus
import app.zhijuan.core.model.GenerationPhase
import app.zhijuan.core.model.GenerationStageStatus
import app.zhijuan.core.model.GenerationTargetType
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class GenerationPersistentTotalRunnerTest {
    @Test
    fun `one claimed job advances every durable cursor until completed`() = runBlocking {
        val fixture = RunnerFixture(stageIds = listOf("stage-plan", "stage-body", "stage-post", "stage-final"))

        val result = fixture.runner().runJob(JOB_ID, OWNER_ID)

        assertEquals(GenerationPersistentRunDisposition.COMPLETED, result.disposition)
        assertEquals(4, result.executedStageCount)
        assertEquals(fixture.stageIds, fixture.executedStageIds)
    }

    @Test
    fun `persisted in-flight state requires recovery and never opens provider again`() = runBlocking {
        val fixture = RunnerFixture(stageIds = listOf("stage-body"))
        fixture.candidate = null
        fixture.job = fixture.job.copy(
            status = GenerationJobStatus.RUNNING,
            currentStageId = "stage-body",
            leaseToken = GenerationLeaseToken(OWNER_ID, 2L),
        )
        fixture.stageStatus = GenerationStageStatus.STREAMING

        val result = fixture.runner().runJob(JOB_ID, OWNER_ID)

        assertEquals(GenerationPersistentRunDisposition.RECOVERY_REQUIRED, result.disposition)
        assertEquals(0, result.executedStageCount)
        assertEquals(emptyList<String>(), fixture.executedStageIds)
    }

    @Test
    fun `pause request at durable boundary does not open the next stage`() = runBlocking {
        val fixture = RunnerFixture(stageIds = listOf("stage-plan", "stage-body"))
        fixture.afterExecute = { stageId ->
            fixture.job = fixture.job.copy(
                status = GenerationJobStatus.PAUSING,
                currentStageId = stageId,
                leaseToken = fixture.jobToken,
                updatedAt = fixture.job.updatedAt + 1L,
            )
        }

        val result = fixture.runner().runJob(JOB_ID, OWNER_ID)

        assertEquals(GenerationPersistentRunDisposition.CONTROL_PENDING, result.disposition)
        assertEquals(1, result.executedStageCount)
        assertEquals(listOf("stage-plan"), fixture.executedStageIds)
    }

    @Test
    fun `stale competing claim executes no stage`() = runBlocking {
        val fixture = RunnerFixture(stageIds = listOf("stage-plan"))
        fixture.claimFailure = StaleGenerationStateException("already claimed")

        val result = fixture.runner().runJob(JOB_ID, OWNER_ID)

        assertEquals(GenerationPersistentRunDisposition.CONTESTED, result.disposition)
        assertEquals(0, result.executedStageCount)
        assertEquals(emptyList<String>(), fixture.executedStageIds)
    }

    private class RunnerFixture(
        val stageIds: List<String>,
    ) {
        val jobToken = GenerationLeaseToken(OWNER_ID, 2L)
        var candidate: GenerationRunnerQueueCandidate? = candidate(stageIds.first())
        var job = storedJob(GenerationJobStatus.READY, stageIds.first(), leaseToken = null)
        var stageStatus = GenerationStageStatus.READY
        var claimFailure: Throwable? = null
        var afterExecute: ((String) -> Unit)? = null
        val executedStageIds = mutableListOf<String>()
        private var currentIndex = 0

        fun runner() = GenerationPersistentTotalRunnerV1(
            dependencies = GenerationPersistentTotalRunnerDependencies(
                findReadyJob = { _, _ -> candidate },
                claimReadyJob = { found, _, _ ->
                    claimFailure?.let { throw it }
                    job = job.copy(
                        status = GenerationJobStatus.RUNNING,
                        leaseToken = jobToken,
                        updatedAt = 2L,
                    )
                    claim(found.currentStageId)
                },
                acquireCurrentStage = { claim, _, acquiredAt ->
                    stageStatus = GenerationStageStatus.PREPARING
                    lease(claim.currentStage.stageId, acquiredAt)
                },
                executeCurrentStage = { lease, _ ->
                    executedStageIds += lease.stageId
                    afterExecute?.invoke(lease.stageId) ?: advance()
                },
                heartbeatAndLoadCurrentStage = { _, _, _ ->
                    GenerationRunnerQueueHeartbeatResult(
                        jobStatus = job.status,
                        jobUpdatedAt = job.updatedAt,
                        currentStage = stage(stageIds[currentIndex]),
                    )
                },
                inspectJob = { job },
                inspectStage = { stageId ->
                    StoredGenerationStageState(
                        stageId = stageId,
                        jobId = JOB_ID,
                        status = stageStatus,
                        attemptCount = 0,
                        maxAttempts = 2,
                        standardErrorCode = null,
                        nextRetryAt = null,
                        leaseToken = jobToken,
                        leaseHeartbeatAt = 2L,
                        updatedAt = job.updatedAt,
                    )
                },
                nowMillis = { 100L },
            ),
        )

        private fun advance() {
            if (currentIndex == stageIds.lastIndex) {
                job = job.copy(
                    status = GenerationJobStatus.COMPLETED,
                    leaseToken = null,
                    currentStageId = stageIds[currentIndex],
                    updatedAt = job.updatedAt + 1L,
                )
                stageStatus = GenerationStageStatus.SUCCEEDED
            } else {
                currentIndex += 1
                job = job.copy(
                    status = GenerationJobStatus.RUNNING,
                    currentStageId = stageIds[currentIndex],
                    updatedAt = job.updatedAt + 1L,
                )
                stageStatus = GenerationStageStatus.READY
            }
        }

        private fun claim(stageId: String) = GenerationRunnerQueueClaimResult(
            jobId = JOB_ID,
            jobStatus = GenerationJobStatus.RUNNING,
            jobLeaseToken = jobToken,
            currentStage = stage(stageId),
        )

        private fun lease(stageId: String, acquiredAt: Long) = GenerationRunnerExecutionLeaseSnapshot(
            jobId = JOB_ID,
            jobStatus = GenerationJobStatus.RUNNING,
            jobLeaseToken = jobToken,
            jobHeartbeatAt = acquiredAt,
            stageId = stageId,
            stageStatus = GenerationStageStatus.PREPARING,
            stageLeaseToken = GenerationLeaseToken(OWNER_ID, acquiredAt),
            stageHeartbeatAt = acquiredAt,
        )

        private fun stage(stageId: String) = GenerationRunnerQueueStageSnapshot(
            stageId = stageId,
            jobId = JOB_ID,
            phase = GenerationPhase.BUILD_CHAPTER_PLAN,
            targetType = GenerationTargetType.CHAPTER,
            status = GenerationStageStatus.READY,
            attemptCount = 0,
            maxAttempts = 2,
        )
    }

    private companion object {
        const val JOB_ID = "job-runner-128"
        const val OWNER_ID = "runner-128"

        fun candidate(stageId: String) = GenerationRunnerQueueCandidate(
            jobId = JOB_ID,
            jobStatus = GenerationJobStatus.READY,
            currentStageId = stageId,
            currentStageStatus = GenerationStageStatus.READY,
            jobUpdatedAt = 1L,
            stageUpdatedAt = 1L,
        )

        fun storedJob(
            status: GenerationJobStatus,
            stageId: String,
            leaseToken: GenerationLeaseToken?,
        ) = StoredGenerationJobState(
            jobId = JOB_ID,
            bookId = "book-runner-128",
            status = status,
            currentStageId = stageId,
            pauseOrStopReason = null,
            startedAt = null,
            finishedAt = null,
            leaseToken = leaseToken,
            leaseHeartbeatAt = leaseToken?.acquiredAt,
            updatedAt = 1L,
        )
    }
}

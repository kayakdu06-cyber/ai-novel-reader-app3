package app.zhijuan.reader.generation

import app.zhijuan.core.database.generation.GenerationLeaseToken
import app.zhijuan.core.database.generation.GenerationIdleJobLeaseCandidate
import app.zhijuan.core.database.generation.GenerationIdleJobLeaseScan
import app.zhijuan.core.database.generation.GenerationMaintenanceCandidate
import app.zhijuan.core.database.generation.GenerationMaintenanceScan
import app.zhijuan.core.database.generation.StaleGenerationStateException
import app.zhijuan.core.model.GenerationJobStatus
import app.zhijuan.core.model.GenerationStageStatus
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class GenerationRecoveryMaintenanceCoordinatorTest {
    @Test
    fun `expired idle job lease is requeued without opening a stage`() = runBlocking {
        val idle = GenerationIdleJobLeaseCandidate(
            jobId = "job-idle",
            jobStatus = GenerationJobStatus.RUNNING,
            currentStageId = "stage-idle",
            currentStageStatus = GenerationStageStatus.READY,
            observedJobLease = GenerationLeaseToken("runner-idle", 1L),
            jobLeaseHeartbeatAt = 1L,
        )
        val operations = RecordingOperations(emptyList(), idleCandidates = listOf(idle))

        val report = GenerationRecoveryMaintenanceCoordinator(operations).runBatch(70_000L)

        assertEquals(listOf("stage-idle"), operations.idleRequeued)
        assertEquals(1, report.requeuedIdleJobs)
        assertEquals(1, report.scanned)
    }

    @Test
    fun `one bounded batch routes safe recovery without any provider operation`() = runBlocking {
        val operations = RecordingOperations(
            candidates = listOf(
                candidate("prepare", GenerationJobStatus.RUNNING, GenerationStageStatus.PREPARING, null),
                candidate(
                    "network",
                    GenerationJobStatus.RUNNING,
                    GenerationStageStatus.STREAMING,
                    "attempt-network",
                ),
                candidate(
                    "control",
                    GenerationJobStatus.PAUSING,
                    GenerationStageStatus.REQUEST_INTENT_RECORDED,
                    "attempt-control",
                ),
                candidate(
                    "local-control",
                    GenerationJobStatus.STOPPING,
                    GenerationStageStatus.COMMITTING,
                    "attempt-local",
                ),
            ),
            cleanup = 2 to 1,
        )

        val report = GenerationRecoveryMaintenanceCoordinator(operations).runBatch(70_000L)

        assertEquals(listOf("prepare"), operations.requeued)
        assertEquals(listOf("network"), operations.audited)
        assertEquals(listOf("control"), operations.controls)
        assertEquals(4, report.scanned)
        assertEquals(1, report.deferred)
        assertEquals(2, report.deletedDrafts)
        assertEquals(1, report.skippedDraftCleanup)
        assertEquals(0, report.failed)
    }

    @Test
    fun `concurrent lease changes are counted as stale and never retried inside the batch`() = runBlocking {
        val operations = RecordingOperations(
            candidates = listOf(
                candidate("stale", GenerationJobStatus.RUNNING, GenerationStageStatus.PREPARING, null),
            ),
            staleStageId = "stale",
        )

        val report = GenerationRecoveryMaintenanceCoordinator(operations).runBatch(70_000L)

        assertEquals(1, operations.requeueCalls)
        assertEquals(1, report.stale)
        assertEquals(0, report.requeuedBeforeRequest)
        assertEquals(0, report.failed)
    }

    @Test
    fun `permanent maintenance failures are bounded and reported without identifiers`() = runBlocking {
        val operations = RecordingOperations(
            candidates = listOf(
                candidate(
                    "broken",
                    GenerationJobStatus.RUNNING,
                    GenerationStageStatus.STREAMING,
                    "attempt-broken",
                ),
            ),
            failedStageId = "broken",
            cleanupFailure = true,
        )

        val report = GenerationRecoveryMaintenanceCoordinator(operations).runBatch(70_000L)

        assertEquals(2, report.failed)
        assertEquals(0, report.auditedWithoutProvider)
        assertEquals(false, report.toString().contains("broken"))
    }

    private fun candidate(
        id: String,
        jobStatus: GenerationJobStatus,
        stageStatus: GenerationStageStatus,
        attemptId: String?,
    ) = GenerationMaintenanceCandidate(
        jobId = "job-$id",
        jobStatus = jobStatus,
        stageId = id,
        stageStatus = stageStatus,
        latestAttemptId = attemptId,
        observedJobLease = GenerationLeaseToken("job-worker-$id", 1L),
        jobLeaseHeartbeatAt = 1L,
        observedLease = GenerationLeaseToken("worker-$id", 1L),
        leaseHeartbeatAt = 1L,
    )

    private class RecordingOperations(
        private val candidates: List<GenerationMaintenanceCandidate>,
        private val idleCandidates: List<GenerationIdleJobLeaseCandidate> = emptyList(),
        private val cleanup: Pair<Int, Int> = 0 to 0,
        private val staleStageId: String? = null,
        private val failedStageId: String? = null,
        private val cleanupFailure: Boolean = false,
    ) : GenerationMaintenanceOperations {
        val requeued = mutableListOf<String>()
        val idleRequeued = mutableListOf<String>()
        val audited = mutableListOf<String>()
        val controls = mutableListOf<String>()
        var requeueCalls = 0

        override suspend fun scanIdleJobs(observedAt: Long, limit: Int) =
            GenerationIdleJobLeaseScan(idleCandidates.take(limit), idleCandidates.size > limit)

        override suspend fun requeueIdleJob(
            candidate: GenerationIdleJobLeaseCandidate,
            observedAt: Long,
        ) {
            idleRequeued += candidate.currentStageId
        }

        override suspend fun scan(observedAt: Long, limit: Int) =
            GenerationMaintenanceScan(candidates.take(limit), candidates.size > limit)

        override suspend fun requeueBeforeRequest(
            candidate: GenerationMaintenanceCandidate,
            observedAt: Long,
        ) {
            requeueCalls += 1
            failIfConfigured(candidate)
            requeued += candidate.stageId
        }

        override suspend fun auditWithoutProvider(
            candidate: GenerationMaintenanceCandidate,
            observedAt: Long,
        ) {
            failIfConfigured(candidate)
            audited += candidate.stageId
        }

        override suspend fun settleExpiredControl(
            candidate: GenerationMaintenanceCandidate,
            observedAt: Long,
        ) {
            failIfConfigured(candidate)
            controls += candidate.stageId
        }

        override suspend fun cleanupExpiredDrafts(observedAt: Long): Pair<Int, Int> {
            if (cleanupFailure) error("fixture cleanup failure")
            return cleanup
        }

        private fun failIfConfigured(candidate: GenerationMaintenanceCandidate) {
            if (candidate.stageId == staleStageId) {
                throw StaleGenerationStateException("fixture stale lease")
            }
            if (candidate.stageId == failedStageId) error("fixture maintenance failure")
        }
    }
}

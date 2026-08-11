package app.zhijuan.feature.generation

import app.zhijuan.core.database.generation.GenerationLeaseToken
import app.zhijuan.core.database.generation.GenerationRunnerExecutionLeaseSnapshot
import app.zhijuan.core.database.generation.StoredGenerationJobState
import app.zhijuan.core.model.GenerationJobStatus
import app.zhijuan.core.model.GenerationStageStatus
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GenerationRunnerHeartbeatEnvelopeTest {
    @Test
    fun heartbeatsWhileActionIsActiveAndStopsAfterCompletion() = runBlocking {
        val waiter = ManualHeartbeatWaiter()
        val now = AtomicLong(10L)
        val observed = Channel<Long>(Channel.UNLIMITED)
        val actionResult = CompletableDeferred<String>()
        val envelope = envelope(
            waiter = waiter,
            now = now,
            heartbeat = { _, at ->
                observed.send(at)
                snapshot(at)
            },
        )

        val running = async { envelope.run(identity()) { actionResult.await() } }
        now.set(20L)
        waiter.tick()
        assertEquals(20L, withTimeout(2_000L) { observed.receive() })
        now.set(35L)
        waiter.tick()
        assertEquals(35L, withTimeout(2_000L) { observed.receive() })

        actionResult.complete("done")
        assertEquals("done", withTimeout(2_000L) { running.await() })
        assertTrue(observed.tryReceive().isFailure)
    }

    @Test
    fun actionCompletionBeforeFirstTickProducesNoHeartbeat() = runBlocking {
        val waiter = ManualHeartbeatWaiter()
        val heartbeatCalled = AtomicBoolean(false)
        val envelope = envelope(
            waiter = waiter,
            heartbeat = { _, at ->
                heartbeatCalled.set(true)
                snapshot(at)
            },
        )

        assertEquals(42, envelope.run(identity()) { 42 })
        assertFalse(heartbeatCalled.get())
    }

    @Test
    fun lostLeaseCancelsActiveActionAndPropagatesHeartbeatFailure() = runBlocking {
        val waiter = ManualHeartbeatWaiter()
        val cancelled = CompletableDeferred<Unit>()
        val failure = IllegalStateException("lease-lost")
        val envelope = envelope(
            waiter = waiter,
            heartbeat = { _, _ -> throw failure },
            inspectJob = { activeJob(identity(), currentStageId = "stage-1") },
        )
        val running = async {
            runCatching {
                envelope.run(identity()) {
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled.complete(Unit)
                    }
                }
            }
        }

        waiter.tick()
        val result = withTimeout(2_000L) { running.await() }
        assertTrue(result.exceptionOrNull() is IllegalStateException)
        assertEquals("lease-lost", result.exceptionOrNull()?.message)
        withTimeout(2_000L) { cancelled.await() }
    }

    @Test
    fun durableCursorHandoffStopsHeartbeatsWithoutCancellingCommittedAction() = runBlocking {
        val waiter = ManualHeartbeatWaiter()
        val actionResult = CompletableDeferred<String>()
        val inspected = CompletableDeferred<Unit>()
        val failure = IllegalStateException("old-stage")
        val envelope = envelope(
            waiter = waiter,
            heartbeat = { _, _ -> throw failure },
            inspectJob = {
                inspected.complete(Unit)
                activeJob(identity(), currentStageId = "stage-2")
            },
        )
        val running = async { envelope.run(identity()) { actionResult.await() } }

        waiter.tick()
        withTimeout(2_000L) { inspected.await() }
        assertFalse(running.isCompleted)
        actionResult.complete("committed")
        assertEquals("committed", withTimeout(2_000L) { running.await() })
    }

    @Test
    fun terminalJobBoundaryStopsHeartbeatsAndMixedOwnersAreRejectedBeforeAction() = runBlocking {
        val waiter = ManualHeartbeatWaiter()
        val actionResult = CompletableDeferred<String>()
        val envelope = envelope(
            waiter = waiter,
            heartbeat = { _, _ -> throw IllegalStateException("job-completed") },
            inspectJob = {
                activeJob(identity(), currentStageId = "stage-1").copy(
                    status = GenerationJobStatus.COMPLETED,
                    leaseToken = null,
                    leaseHeartbeatAt = null,
                )
            },
        )
        val running = async { envelope.run(identity()) { actionResult.await() } }
        waiter.tick()
        actionResult.complete("completed")
        assertEquals("completed", withTimeout(2_000L) { running.await() })

        val actionCalled = AtomicBoolean(false)
        val invalid = identity().copy(stageLeaseToken = GenerationLeaseToken("runner-b", 4L))
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { envelope.run(invalid) { actionCalled.set(true) } }
        }
        assertFalse(actionCalled.get())
        assertFalse(invalid.toString().contains("runner-a"))
        assertFalse(invalid.toString().contains("stage-1"))
    }

    private fun envelope(
        waiter: ManualHeartbeatWaiter,
        now: AtomicLong = AtomicLong(10L),
        heartbeat: suspend (
            GenerationRunnerHeartbeatIdentity,
            Long,
        ) -> GenerationRunnerExecutionLeaseSnapshot,
        inspectJob: suspend (String) -> StoredGenerationJobState? = {
            activeJob(identity(), currentStageId = "stage-1")
        },
    ) = GenerationRunnerHeartbeatEnvelope(
        GenerationRunnerHeartbeatEnvelopeDependencies(
            heartbeat = heartbeat,
            inspectJob = inspectJob,
            awaitNextHeartbeat = waiter::awaitNext,
            nowMillis = now::get,
        ),
    )

    private fun identity() = GenerationRunnerHeartbeatIdentity(
        jobId = "job-1",
        jobLeaseToken = GenerationLeaseToken("runner-a", 3L),
        stageId = "stage-1",
        stageLeaseToken = GenerationLeaseToken("runner-a", 4L),
    )

    private fun snapshot(at: Long) = GenerationRunnerExecutionLeaseSnapshot(
        jobId = "job-1",
        jobStatus = GenerationJobStatus.RUNNING,
        jobLeaseToken = GenerationLeaseToken("runner-a", 3L),
        jobHeartbeatAt = at,
        stageId = "stage-1",
        stageStatus = GenerationStageStatus.STREAMING,
        stageLeaseToken = GenerationLeaseToken("runner-a", 4L),
        stageHeartbeatAt = at,
    )

    private fun activeJob(
        identity: GenerationRunnerHeartbeatIdentity,
        currentStageId: String,
    ) = StoredGenerationJobState(
        jobId = identity.jobId,
        bookId = "book-redacted",
        status = GenerationJobStatus.RUNNING,
        currentStageId = currentStageId,
        pauseOrStopReason = null,
        startedAt = 3L,
        finishedAt = null,
        leaseToken = identity.jobLeaseToken,
        leaseHeartbeatAt = 10L,
        updatedAt = 10L,
    )

    private class ManualHeartbeatWaiter {
        private val waiters = Channel<CompletableDeferred<Unit>>(Channel.UNLIMITED)

        suspend fun awaitNext() {
            val gate = CompletableDeferred<Unit>()
            waiters.send(gate)
            gate.await()
        }

        suspend fun tick() {
            withTimeout(2_000L) { waiters.receive() }.complete(Unit)
        }
    }
}

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
    fun `heartbeat is active only while action owns the stage`() = runBlocking {
        val waiter = ManualHeartbeatWaiter()
        val now = AtomicLong(10L)
        val observed = Channel<Long>(Channel.UNLIMITED)
        val actionResult = CompletableDeferred<String>()
        val envelope = envelope(
            waiter = waiter,
            now = now,
            heartbeat = { _, at -> observed.send(at); snapshot(at) },
        )

        val running = async { envelope.run(identity()) { actionResult.await() } }
        now.set(20L)
        waiter.tick()
        assertEquals(20L, withTimeout(2_000L) { observed.receive() })
        actionResult.complete("done")
        assertEquals("done", withTimeout(2_000L) { running.await() })
        assertTrue(observed.tryReceive().isFailure)
    }

    @Test
    fun `lost lease cancels active action but durable cursor handoff does not`() = runBlocking {
        val waiter = ManualHeartbeatWaiter()
        val cancelled = CompletableDeferred<Unit>()
        val lost = envelope(
            waiter = waiter,
            heartbeat = { _, _ -> throw IllegalStateException("lease-lost") },
        )
        val running = async {
            runCatching { lost.run(identity()) { try { awaitCancellation() } finally { cancelled.complete(Unit) } } }
        }
        waiter.tick()
        assertEquals("lease-lost", withTimeout(2_000L) { running.await() }.exceptionOrNull()?.message)
        withTimeout(2_000L) { cancelled.await() }

        val handoffWaiter = ManualHeartbeatWaiter()
        val result = CompletableDeferred<String>()
        val handoff = envelope(
            waiter = handoffWaiter,
            heartbeat = { _, _ -> throw IllegalStateException("old-stage") },
            inspectJob = { activeJob("stage-2") },
        )
        val handoffRun = async { handoff.run(identity()) { result.await() } }
        handoffWaiter.tick()
        assertFalse(handoffRun.isCompleted)
        result.complete("committed")
        assertEquals("committed", withTimeout(2_000L) { handoffRun.await() })
    }

    @Test
    fun `mixed owners are rejected before action`() {
        val actionCalled = AtomicBoolean(false)
        val envelope = envelope(
            waiter = ManualHeartbeatWaiter(),
            heartbeat = { _, at -> snapshot(at) },
        )
        val invalid = identity().copy(stageLeaseToken = GenerationLeaseToken("runner-b", 4L))
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { envelope.run(invalid) { actionCalled.set(true) } }
        }
        assertFalse(actionCalled.get())
    }

    private fun envelope(
        waiter: ManualHeartbeatWaiter,
        now: AtomicLong = AtomicLong(10L),
        heartbeat: suspend (GenerationRunnerHeartbeatIdentity, Long) -> GenerationRunnerExecutionLeaseSnapshot,
        inspectJob: suspend (String) -> StoredGenerationJobState? = { activeJob("stage-1") },
    ) = GenerationRunnerHeartbeatEnvelope(
        GenerationRunnerHeartbeatEnvelopeDependencies(
            heartbeat = heartbeat,
            inspectJob = inspectJob,
            awaitNextHeartbeat = waiter::awaitNext,
            nowMillis = now::get,
        ),
    )

    private fun identity() = GenerationRunnerHeartbeatIdentity(
        "job-1",
        GenerationLeaseToken("runner-a", 3L),
        "stage-1",
        GenerationLeaseToken("runner-a", 4L),
    )

    private fun snapshot(at: Long) = GenerationRunnerExecutionLeaseSnapshot(
        "job-1",
        GenerationJobStatus.RUNNING,
        GenerationLeaseToken("runner-a", 3L),
        at,
        "stage-1",
        GenerationStageStatus.STREAMING,
        GenerationLeaseToken("runner-a", 4L),
        at,
    )

    private fun activeJob(currentStageId: String) = StoredGenerationJobState(
        "job-1",
        "book-redacted",
        GenerationJobStatus.RUNNING,
        currentStageId,
        null,
        3L,
        null,
        GenerationLeaseToken("runner-a", 3L),
        10L,
        10L,
    )

    private class ManualHeartbeatWaiter {
        private val waiters = Channel<CompletableDeferred<Unit>>(Channel.UNLIMITED)
        suspend fun awaitNext() {
            val gate = CompletableDeferred<Unit>()
            waiters.send(gate)
            gate.await()
        }
        suspend fun tick() = withTimeout(2_000L) { waiters.receive() }.complete(Unit)
    }
}

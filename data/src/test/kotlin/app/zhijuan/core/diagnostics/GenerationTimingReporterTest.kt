package app.zhijuan.core.diagnostics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GenerationTimingReporterTest {
    @Test
    fun `normal body and merged post analysis stay within two total remote calls`() {
        val factory = GenerationTimingEventFactory()
        val events = listOf(
            providerOpened(factory, GenerationTimingPhase.BODY, "body-stage", "body-attempt", 10),
            providerOpened(factory, GenerationTimingPhase.MEMORY, "analysis-stage", "analysis-attempt", 20),
        )

        val report = GenerationTimingReporter().report(events)

        assertEquals(2, report.remoteProviderCallCount)
        assertTrue(report.remoteProviderCallCount <= NORMAL_CHAPTER_REMOTE_CALL_TARGET)
    }

    private fun providerOpened(
        factory: GenerationTimingEventFactory,
        phase: GenerationTimingPhase,
        stageId: String,
        attemptId: String,
        elapsed: Long,
    ) = factory.create(
        phase = phase,
        milestone = GenerationTimingMilestone.PROVIDER_OPENED,
        mark = GenerationTimingMark(elapsed, elapsed, "a".repeat(24)),
        runId = "run-1", bookId = "book-1", jobId = "job-1", stageId = stageId,
        attemptId = attemptId, attemptNo = 1, connectionId = "connection-1", modelId = "model-1",
    )

    companion object {
        /** One streaming body call plus one merged post-analysis call. */
        private const val NORMAL_CHAPTER_REMOTE_CALL_TARGET = 2
    }
}

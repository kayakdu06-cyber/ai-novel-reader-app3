package app.zhijuan.feature.generation

import app.zhijuan.core.diagnostics.GenerationTimingClock
import app.zhijuan.core.diagnostics.GenerationTimingMark
import app.zhijuan.core.diagnostics.GenerationTimingMilestone
import app.zhijuan.core.diagnostics.GenerationTimingOutcome
import app.zhijuan.core.diagnostics.GenerationTimingPhase
import app.zhijuan.provider.common.ProviderFinishReason
import app.zhijuan.provider.common.ProviderUsage
import app.zhijuan.provider.common.ProviderUsageQuality
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class GenerationTimingRecordingTest {
    @Test
    fun providerTrackerRecordsOnlyFiniteMilestonesCountsAndUsage() = runBlocking {
        val observations = mutableListOf<GenerationTimingObservation>()
        val tracker = ProviderGenerationTimingTracker(
            context = context(),
            clock = TimingClock(10L),
            recorder = GenerationTimingEventRecorder { observations += it },
        )

        tracker.providerOpened()
        tracker.firstByte()
        tracker.decodedBody(BODY_CANARY)
        tracker.decodedBody("。\n第二段")
        tracker.bodyEnded(
            completion = ProviderPayloadCompletion.COMPLETE,
            finishReason = ProviderFinishReason.STOP,
            usage = ProviderUsage(
                inputTokens = 100L,
                outputTokens = 200L,
                cachedInputTokens = null,
                cachedWriteTokens = null,
                reasoningTokens = null,
                totalTokens = 300L,
                quality = ProviderUsageQuality.PROVIDER_REPORTED,
            ),
        )

        assertEquals(
            listOf(
                GenerationTimingMilestone.PROVIDER_OPENED,
                GenerationTimingMilestone.FIRST_BYTE,
                GenerationTimingMilestone.FIRST_FULL_PARAGRAPH,
                GenerationTimingMilestone.BODY_STREAM_ENDED,
            ),
            observations.map(GenerationTimingObservation::milestone),
        )
        assertEquals(listOf(10L, 11L, 12L, 13L), observations.map { it.mark.elapsedRealtimeMillis })
        assertEquals(GenerationTimingOutcome.SUCCEEDED, observations.last().outcome)
        assertEquals(300L, observations.last().totalTokenCount)
        assertEquals((BODY_CANARY + "。\n第二段").codePointCount(0, (BODY_CANARY + "。\n第二段").length).toLong(), observations.last().characterCount)
        observations.forEach { observation ->
            assertFalse(observation.toString().contains(BODY_CANARY))
            assertFalse(observation.toString().contains(SECRET_CANARY))
        }
        assertFalse(context().toString().contains(SECRET_CANARY))
    }

    @Test
    fun cleanBodyEndCountsSingleParagraphButTruncationDoesNotInventOne() = runBlocking {
        val completed = mutableListOf<GenerationTimingObservation>()
        ProviderGenerationTimingTracker(
            context = context(),
            clock = TimingClock(20L),
            recorder = GenerationTimingEventRecorder { completed += it },
        ).also { tracker ->
            tracker.providerOpened()
            tracker.firstByte()
            tracker.decodedBody("单段正文")
            tracker.bodyEnded(ProviderPayloadCompletion.COMPLETE, ProviderFinishReason.STOP, usage = null)
        }
        assertEquals(1, completed.count { it.milestone == GenerationTimingMilestone.FIRST_FULL_PARAGRAPH })

        val truncated = mutableListOf<GenerationTimingObservation>()
        ProviderGenerationTimingTracker(
            context = context(),
            clock = TimingClock(30L),
            recorder = GenerationTimingEventRecorder { truncated += it },
        ).also { tracker ->
            tracker.providerOpened()
            tracker.firstByte()
            tracker.decodedBody("未闭合的单段")
            tracker.bodyEnded(
                ProviderPayloadCompletion.TRUNCATED_SAFE_PREFIX,
                ProviderFinishReason.LENGTH,
                usage = null,
            )
        }
        assertEquals(0, truncated.count { it.milestone == GenerationTimingMilestone.FIRST_FULL_PARAGRAPH })
        assertEquals(GenerationTimingOutcome.TRUNCATED, truncated.last().outcome)
    }

    @Test
    fun failedAttemptCanCloseWithoutFirstByteAndLateSettlementIsIdempotent() = runBlocking {
        val observations = mutableListOf<GenerationTimingObservation>()
        val tracker = ProviderGenerationTimingTracker(
            context = context(),
            clock = TimingClock(40L),
            recorder = GenerationTimingEventRecorder { observations += it },
        )

        tracker.providerOpened()
        tracker.settleIfOpen(GenerationTimingOutcome.FAILED_CLOSED, usage = null)
        tracker.settleIfOpen(GenerationTimingOutcome.UNKNOWN, usage = null)

        assertEquals(
            listOf(
                GenerationTimingMilestone.PROVIDER_OPENED,
                GenerationTimingMilestone.BODY_STREAM_ENDED,
            ),
            observations.map(GenerationTimingObservation::milestone),
        )
        assertEquals(GenerationTimingOutcome.FAILED_CLOSED, observations.last().outcome)
        assertEquals(0L, observations.last().characterCount)
    }

    private fun context() = GenerationTimingExecutionContext(
        runId = "run-$SECRET_CANARY",
        bookId = "book-$SECRET_CANARY",
        phase = GenerationTimingPhase.BODY,
        jobId = "job-$SECRET_CANARY",
        stageId = "stage-$SECRET_CANARY",
        attemptId = "attempt-$SECRET_CANARY",
        attemptNo = 1,
        connectionId = "connection-$SECRET_CANARY",
        modelId = "model-$SECRET_CANARY",
    )

    private class TimingClock(startAt: Long) : GenerationTimingClock {
        private val next = AtomicLong(startAt)
        override fun capture(): GenerationTimingMark {
            val elapsed = next.getAndIncrement()
            return GenerationTimingMark(
                epochMillis = 1_000L + elapsed,
                elapsedRealtimeMillis = elapsed,
                bootFingerprint = BOOT,
            )
        }
    }

    private companion object {
        const val BOOT = "111111111111111111111111"
        const val BODY_CANARY = "正文_TIMING_RECORDING_CANARY"
        val SECRET_CANARY = String(charArrayOf('s', 'k', '-')) + "timing-recording-canary"
    }
}

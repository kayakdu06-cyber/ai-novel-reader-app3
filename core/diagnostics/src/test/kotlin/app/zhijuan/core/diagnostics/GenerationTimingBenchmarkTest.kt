package app.zhijuan.core.diagnostics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class GenerationTimingBenchmarkTest {
    @Test
    fun twentyRunsReportNearestRankP50P95AndSlowest() {
        val reports = (1L..20L).map { run -> completeReport(run * 1_000L) }

        val benchmark = GenerationTimingBenchmarkReporter().report(reports)

        assertEquals(20, benchmark.runCount)
        assertEquals(10_000L, benchmark.providerToFirstParagraph.p50Millis)
        assertEquals(19_000L, benchmark.providerToFirstParagraph.p95Millis)
        assertEquals(20_000L, benchmark.providerToFirstParagraph.slowestMillis)
        assertEquals(20, benchmark.providerToFirstParagraph.availableRunCount)
        assertTrue(benchmark.providerToFirstParagraph.complete)
        assertTrue(benchmark.revision.entirelyNotApplicable)
        assertFalse(benchmark.revision.complete)
    }

    @Test
    fun failedAndMissingRunsRemainVisibleInsteadOfImprovingThePercentile() {
        val available = completeReport(100L)
        val failed = available.copy(
            bodyStream = GenerationTimingDuration.Unavailable(
                GenerationTimingUnavailableReason.TERMINAL_OUTCOME_NOT_SUCCESSFUL,
            ),
        )
        val missing = available.copy(
            bodyStream = GenerationTimingDuration.Unavailable(
                GenerationTimingUnavailableReason.MISSING_EVENT,
            ),
        )
        val notApplicable = available.copy(bodyStream = GenerationTimingDuration.NotApplicable)

        val distribution = GenerationTimingBenchmarkReporter()
            .report(listOf(available, failed, missing, notApplicable))
            .bodyStream

        assertEquals(4, distribution.totalRunCount)
        assertEquals(1, distribution.availableRunCount)
        assertEquals(1, distribution.notApplicableRunCount)
        assertEquals(
            mapOf(
                GenerationTimingUnavailableReason.MISSING_EVENT to 1,
                GenerationTimingUnavailableReason.TERMINAL_OUTCOME_NOT_SUCCESSFUL to 1,
            ),
            distribution.unavailableReasonCounts,
        )
        assertEquals(100L, distribution.p50Millis)
        assertEquals(100L, distribution.p95Millis)
        assertEquals(100L, distribution.slowestMillis)
        assertFalse(distribution.complete)
    }

    @Test
    fun emptyBenchmarkAndMetricWithoutAvailableRunsFailClosed() {
        assertThrows<IllegalArgumentException> {
            GenerationTimingBenchmarkReporter().report(emptyList())
        }
        val unavailable = GenerationTimingDuration.Unavailable(
            GenerationTimingUnavailableReason.DIFFERENT_BOOT_SESSION,
        )
        val distribution = GenerationTimingBenchmarkReporter()
            .report(listOf(completeReport(1L).copy(bodyStream = unavailable)))
            .bodyStream
        assertNull(distribution.p50Millis)
        assertNull(distribution.p95Millis)
        assertNull(distribution.slowestMillis)
        assertEquals(
            mapOf(GenerationTimingUnavailableReason.DIFFERENT_BOOT_SESSION to 1),
            distribution.unavailableReasonCounts,
        )
    }

    private fun completeReport(value: Long): GenerationTimingReport {
        val available = GenerationTimingDuration.Available(value)
        return GenerationTimingReport(
            queue = available,
            localPreparation = available,
            providerToFirstByte = available,
            providerToFirstParagraph = available,
            bodyStream = available,
            memory = available,
            tracking = available,
            consistency = available,
            revision = GenerationTimingDuration.NotApplicable,
            derivedTotal = available,
            commit = available,
            total = available,
            nextChapterDelay = GenerationTimingDuration.NotApplicable,
        )
    }
}

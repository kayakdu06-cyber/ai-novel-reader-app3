package app.zhijuan.core.diagnostics

/**
 * One metric across a bounded collection of chapter timing reports.
 *
 * Percentiles use the nearest-rank definition. Failed, cross-boot and missing
 * runs stay visible in [unavailableReasonCounts]; they are never silently
 * discarded to make a benchmark look faster.
 */
data class GenerationTimingDistribution(
    val totalRunCount: Int,
    val availableRunCount: Int,
    val notApplicableRunCount: Int,
    val unavailableReasonCounts: Map<GenerationTimingUnavailableReason, Int>,
    val p50Millis: Long?,
    val p95Millis: Long?,
    val slowestMillis: Long?,
) {
    init {
        require(totalRunCount > 0)
        require(availableRunCount >= 0 && notApplicableRunCount >= 0)
        require(unavailableReasonCounts.values.all { it > 0 })
        require(
            availableRunCount + notApplicableRunCount + unavailableReasonCounts.values.sum() ==
                totalRunCount,
        )
        require(
            if (availableRunCount == 0) {
                p50Millis == null && p95Millis == null && slowestMillis == null
            } else {
                p50Millis != null && p95Millis != null && slowestMillis != null &&
                    p50Millis >= 0L && p95Millis >= p50Millis && slowestMillis >= p95Millis
            },
        )
    }

    val complete: Boolean
        get() = availableRunCount == totalRunCount

    val entirelyNotApplicable: Boolean
        get() = notApplicableRunCount == totalRunCount
}

data class GenerationTimingBenchmarkReport(
    val runCount: Int,
    val queue: GenerationTimingDistribution,
    val localPreparation: GenerationTimingDistribution,
    val providerToFirstByte: GenerationTimingDistribution,
    val providerToFirstParagraph: GenerationTimingDistribution,
    val bodyStream: GenerationTimingDistribution,
    val memory: GenerationTimingDistribution,
    val tracking: GenerationTimingDistribution,
    val consistency: GenerationTimingDistribution,
    val revision: GenerationTimingDistribution,
    val derivedTotal: GenerationTimingDistribution,
    val commit: GenerationTimingDistribution,
    val total: GenerationTimingDistribution,
    val nextChapterDelay: GenerationTimingDistribution,
)

class GenerationTimingBenchmarkReporter {
    fun report(reports: List<GenerationTimingReport>): GenerationTimingBenchmarkReport {
        require(reports.isNotEmpty()) { "A generation benchmark requires at least one run." }
        require(reports.size <= MAX_RUNS) { "A generation benchmark contains too many runs." }
        return GenerationTimingBenchmarkReport(
            runCount = reports.size,
            queue = summarize(reports.map(GenerationTimingReport::queue)),
            localPreparation = summarize(reports.map(GenerationTimingReport::localPreparation)),
            providerToFirstByte = summarize(reports.map(GenerationTimingReport::providerToFirstByte)),
            providerToFirstParagraph = summarize(
                reports.map(GenerationTimingReport::providerToFirstParagraph),
            ),
            bodyStream = summarize(reports.map(GenerationTimingReport::bodyStream)),
            memory = summarize(reports.map(GenerationTimingReport::memory)),
            tracking = summarize(reports.map(GenerationTimingReport::tracking)),
            consistency = summarize(reports.map(GenerationTimingReport::consistency)),
            revision = summarize(reports.map(GenerationTimingReport::revision)),
            derivedTotal = summarize(reports.map(GenerationTimingReport::derivedTotal)),
            commit = summarize(reports.map(GenerationTimingReport::commit)),
            total = summarize(reports.map(GenerationTimingReport::total)),
            nextChapterDelay = summarize(reports.map(GenerationTimingReport::nextChapterDelay)),
        )
    }

    private fun summarize(values: List<GenerationTimingDuration>): GenerationTimingDistribution {
        val available = values.mapNotNull { value ->
            (value as? GenerationTimingDuration.Available)?.millis
        }.sorted()
        val notApplicable = values.count { it == GenerationTimingDuration.NotApplicable }
        val unavailableReasons = values
            .filterIsInstance<GenerationTimingDuration.Unavailable>()
            .groupingBy(GenerationTimingDuration.Unavailable::reason)
            .eachCount()
            .toSortedMap(compareBy(GenerationTimingUnavailableReason::name))
            .toMap()
        return GenerationTimingDistribution(
            totalRunCount = values.size,
            availableRunCount = available.size,
            notApplicableRunCount = notApplicable,
            unavailableReasonCounts = unavailableReasons,
            p50Millis = available.nearestRank(50),
            p95Millis = available.nearestRank(95),
            slowestMillis = available.lastOrNull(),
        )
    }

    private fun List<Long>.nearestRank(percent: Int): Long? {
        if (isEmpty()) return null
        val rank = (size * percent + 99) / 100
        return this[rank - 1]
    }

    private companion object {
        const val MAX_RUNS = 10_000
    }
}

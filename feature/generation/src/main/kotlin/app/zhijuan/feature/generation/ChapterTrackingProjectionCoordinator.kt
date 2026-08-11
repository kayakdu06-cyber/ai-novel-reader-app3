package app.zhijuan.feature.generation

import app.zhijuan.core.database.generation.PersistedStreamingRequest
import app.zhijuan.core.database.generation.ValidatedOutputCommitPermit
import app.zhijuan.provider.common.ProviderAdapter
import app.zhijuan.provider.common.ProviderConnectionProfile
import app.zhijuan.provider.common.ProviderFinishReason

sealed interface ChapterTrackingProjectionResult {
    data class Accepted(
        val tracking: ChapterStoryTrackingV1,
        val commitPermit: ValidatedOutputCommitPermit,
        val execution: AuditedStreamingExecutionResult.Completed,
    ) : ChapterTrackingProjectionResult

    data class RepairRequired(
        val report: StructuredOutputInvalidReport,
        val plan: StructuredOutputRepairPlan,
        val execution: AuditedStreamingExecutionResult.Completed,
    ) : ChapterTrackingProjectionResult

    data class NeedsAction(
        val report: StructuredOutputInvalidReport,
        val execution: AuditedStreamingExecutionResult.Completed,
    ) : ChapterTrackingProjectionResult

    data class Other(val execution: AuditedStreamingExecutionResult) : ChapterTrackingProjectionResult
}

class ChapterTrackingProjectionCoordinator(
    private val executor: AuditedStreamingProviderExecutor,
    private val validation: StructuredOutputValidationCoordinator,
    private val parser: ChapterTrackingOutputParser = ChapterTrackingOutputParser(),
    private val clock: GenerationExecutionClock = SystemGenerationExecutionClock,
) {
    suspend fun execute(
        persistedRequest: PersistedStreamingRequest,
        adapter: ProviderAdapter,
        profile: ProviderConnectionProfile,
        boundRequest: BoundChapterTrackingProjectionRequest,
    ): ChapterTrackingProjectionResult {
        val request = boundRequest.request
        require(request.stageId == persistedRequest.attempt.stageId)
        require(request.attemptId == persistedRequest.attempt.attemptId)
        require(request.stream) { "Story-tracking projection requests must stream." }
        val expectedSchema = ChapterTrackingOutputContractV1.providerSchema.withValue { it }
        require(request.structuredOutputSchema?.withValue { it == expectedSchema } == true) {
            "Story-tracking request does not use the exact chapter-story-tracking.v1 schema."
        }
        val execution = executor.execute(persistedRequest, adapter, profile, request)
        if (execution !is AuditedStreamingExecutionResult.Completed || execution.reason != ProviderFinishReason.STOP) {
            return ChapterTrackingProjectionResult.Other(execution)
        }
        return when (
            val decision = validation.validate(
                completed = execution,
                contract = boundRequest.outputContract,
                validatedAt = clock.nowMillis().also { require(it >= 0L) },
            )
        ) {
            is StructuredOutputValidationDecision.Accepted -> ChapterTrackingProjectionResult.Accepted(
                tracking = parser.fromValidated(decision.output),
                commitPermit = decision.commitPermit,
                execution = execution,
            )
            is StructuredOutputValidationDecision.RepairRequired -> ChapterTrackingProjectionResult.RepairRequired(
                report = decision.report,
                plan = decision.plan,
                execution = execution,
            )
            is StructuredOutputValidationDecision.NeedsAction -> ChapterTrackingProjectionResult.NeedsAction(
                report = decision.report,
                execution = execution,
            )
        }
    }
}

package app.zhijuan.feature.generation

import app.zhijuan.core.database.generation.PersistedStreamingRequest
import app.zhijuan.core.database.generation.ValidatedOutputCommitPermit
import app.zhijuan.provider.common.ProviderAdapter
import app.zhijuan.provider.common.ProviderConnectionProfile
import app.zhijuan.provider.common.ProviderFinishReason

sealed interface ChapterConsistencyCheckResultV1 {
    data class Accepted(
        val report: ChapterConsistencyReportV1,
        val commitPermit: ValidatedOutputCommitPermit,
        val execution: AuditedStreamingExecutionResult.Completed,
    ) : ChapterConsistencyCheckResultV1

    data class RepairRequired(
        val report: StructuredOutputInvalidReport,
        val plan: StructuredOutputRepairPlan,
        val execution: AuditedStreamingExecutionResult.Completed,
    ) : ChapterConsistencyCheckResultV1

    data class NeedsAction(
        val report: StructuredOutputInvalidReport,
        val execution: AuditedStreamingExecutionResult.Completed,
    ) : ChapterConsistencyCheckResultV1

    data class Other(
        val execution: AuditedStreamingExecutionResult,
    ) : ChapterConsistencyCheckResultV1
}

class ChapterConsistencyCheckCoordinatorV1(
    private val executor: AuditedStreamingProviderExecutor,
    private val validation: StructuredOutputValidationCoordinator,
    private val parser: ChapterConsistencyOutputParser = ChapterConsistencyOutputParser(),
    private val clock: GenerationExecutionClock = SystemGenerationExecutionClock,
) {
    suspend fun execute(
        persistedRequest: PersistedStreamingRequest,
        adapter: ProviderAdapter,
        profile: ProviderConnectionProfile,
        boundRequest: BoundChapterConsistencyCheckRequest,
    ): ChapterConsistencyCheckResultV1 {
        val request = boundRequest.request
        require(request.stageId == persistedRequest.attempt.stageId)
        require(request.attemptId == persistedRequest.attempt.attemptId)
        require(persistedRequest.inputHash == boundRequest.sourceBindingHash) {
            "Persisted consistency intent does not match the frozen check source."
        }
        require(request.stream) { "Chapter consistency checks must stream." }
        val expectedSchema = ChapterConsistencyOutputContractV1.providerSchema.withValue { it }
        require(request.structuredOutputSchema?.withValue { it == expectedSchema } == true) {
            "Consistency request does not use the exact chapter-consistency-report.v1 schema."
        }
        val execution = executor.execute(persistedRequest, adapter, profile, request)
        if (execution !is AuditedStreamingExecutionResult.Completed || execution.reason != ProviderFinishReason.STOP) {
            return ChapterConsistencyCheckResultV1.Other(execution)
        }
        return when (
            val decision = validation.validate(
                completed = execution,
                contract = boundRequest.outputContract,
                validatedAt = clock.nowMillis().also { require(it >= 0L) },
            )
        ) {
            is StructuredOutputValidationDecision.Accepted -> ChapterConsistencyCheckResultV1.Accepted(
                report = parser.fromValidated(decision.output),
                commitPermit = decision.commitPermit,
                execution = execution,
            )
            is StructuredOutputValidationDecision.RepairRequired -> ChapterConsistencyCheckResultV1.RepairRequired(
                report = decision.report,
                plan = decision.plan,
                execution = execution,
            )
            is StructuredOutputValidationDecision.NeedsAction -> ChapterConsistencyCheckResultV1.NeedsAction(
                report = decision.report,
                execution = execution,
            )
        }
    }
}

package app.zhijuan.feature.generation

import app.zhijuan.core.database.generation.PersistedStreamingRequest
import app.zhijuan.core.database.generation.ValidatedOutputCommitPermit
import app.zhijuan.provider.common.ProviderAdapter
import app.zhijuan.provider.common.ProviderConnectionProfile
import app.zhijuan.provider.common.ProviderFinishReason

sealed interface ChapterPostAnalysisResultV1 {
    data class Accepted(
        val analysis: ChapterPostAnalysisV1,
        val commitPermit: ValidatedOutputCommitPermit,
        val execution: AuditedStreamingExecutionResult.Completed,
    ) : ChapterPostAnalysisResultV1
    data class RevisionRequired(
        val analysis: ChapterPostAnalysisV1,
        val commitPermit: ValidatedOutputCommitPermit,
        val execution: AuditedStreamingExecutionResult.Completed,
    ) : ChapterPostAnalysisResultV1
    data class RepairRequired(
        val report: StructuredOutputInvalidReport,
        val plan: StructuredOutputRepairPlan,
        val execution: AuditedStreamingExecutionResult.Completed,
    ) : ChapterPostAnalysisResultV1
    data class NeedsAction(
        val report: StructuredOutputInvalidReport,
        val execution: AuditedStreamingExecutionResult.Completed,
    ) : ChapterPostAnalysisResultV1
    data class Other(val execution: AuditedStreamingExecutionResult) : ChapterPostAnalysisResultV1
}

class ChapterPostAnalysisCoordinatorV1(
    private val executor: AuditedStreamingProviderExecutor,
    private val validation: StructuredOutputValidationCoordinator,
    private val parser: ChapterPostAnalysisOutputParser = ChapterPostAnalysisOutputParser(),
    private val clock: GenerationExecutionClock = SystemGenerationExecutionClock,
) {
    suspend fun execute(
        persistedRequest: PersistedStreamingRequest,
        adapter: ProviderAdapter,
        profile: ProviderConnectionProfile,
        boundRequest: BoundChapterPostAnalysisRequestV1,
    ): ChapterPostAnalysisResultV1 {
        val request = boundRequest.request
        require(request.stageId == persistedRequest.attempt.stageId)
        require(request.attemptId == persistedRequest.attempt.attemptId)
        require(request.stream)
        val expectedSchema = ChapterPostAnalysisOutputContractV1.providerSchema.withValue { it }
        require(request.structuredOutputSchema?.withValue { it == expectedSchema } == true)

        // One coordinator execution opens exactly one Provider request. Every analysis capability is local after it.
        val execution = executor.execute(persistedRequest, adapter, profile, request)
        if (execution !is AuditedStreamingExecutionResult.Completed || execution.reason != ProviderFinishReason.STOP) {
            return ChapterPostAnalysisResultV1.Other(execution)
        }
        return when (val decision = validation.validate(
            completed = execution,
            contract = boundRequest.outputContract,
            validatedAt = clock.nowMillis().also { require(it >= 0L) },
        )) {
            is StructuredOutputValidationDecision.Accepted -> {
                val analysis = parser.fromValidated(decision.output)
                if (analysis.severeRevisionRequired) ChapterPostAnalysisResultV1.RevisionRequired(
                    analysis, decision.commitPermit, execution,
                ) else ChapterPostAnalysisResultV1.Accepted(analysis, decision.commitPermit, execution)
            }
            is StructuredOutputValidationDecision.RepairRequired -> ChapterPostAnalysisResultV1.RepairRequired(
                decision.report, decision.plan, execution,
            )
            is StructuredOutputValidationDecision.NeedsAction -> ChapterPostAnalysisResultV1.NeedsAction(
                decision.report, execution,
            )
        }
    }
}

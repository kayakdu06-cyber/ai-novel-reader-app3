package app.zhijuan.feature.generation

import app.zhijuan.core.database.generation.PersistedStreamingRequest
import app.zhijuan.core.database.generation.ValidatedOutputCommitPermit
import app.zhijuan.provider.common.ProviderAdapter
import app.zhijuan.provider.common.ProviderConnectionProfile
import app.zhijuan.provider.common.ProviderFinishReason

sealed interface ChapterMemoryExtractionResult {
    data class Accepted(
        val memory: ChapterMemoryV1,
        val commitPermit: ValidatedOutputCommitPermit,
        val execution: AuditedStreamingExecutionResult.Completed,
    ) : ChapterMemoryExtractionResult

    data class RepairRequired(
        val report: StructuredOutputInvalidReport,
        val plan: StructuredOutputRepairPlan,
        val execution: AuditedStreamingExecutionResult.Completed,
    ) : ChapterMemoryExtractionResult

    data class NeedsAction(
        val report: StructuredOutputInvalidReport,
        val execution: AuditedStreamingExecutionResult.Completed,
    ) : ChapterMemoryExtractionResult

    data class Other(val execution: AuditedStreamingExecutionResult) : ChapterMemoryExtractionResult
}

class ChapterMemoryExtractionCoordinator(
    private val executor: AuditedStreamingProviderExecutor,
    private val validation: StructuredOutputValidationCoordinator,
    private val parser: ChapterMemoryOutputParser = ChapterMemoryOutputParser(),
    private val clock: GenerationExecutionClock = SystemGenerationExecutionClock,
) {
    suspend fun execute(
        persistedRequest: PersistedStreamingRequest,
        adapter: ProviderAdapter,
        profile: ProviderConnectionProfile,
        boundRequest: BoundChapterMemoryExtractionRequest,
    ): ChapterMemoryExtractionResult {
        val request = boundRequest.request
        require(request.stageId == persistedRequest.attempt.stageId)
        require(request.attemptId == persistedRequest.attempt.attemptId)
        require(request.stream) { "Chapter-memory extraction requests must stream." }
        val expectedSchema = ChapterMemoryOutputContractV1.providerSchema.withValue { it }
        require(request.structuredOutputSchema?.withValue { it == expectedSchema } == true) {
            "Chapter-memory request does not use the exact chapter-memory.v1 schema."
        }
        val execution = executor.execute(persistedRequest, adapter, profile, request)
        if (execution !is AuditedStreamingExecutionResult.Completed || execution.reason != ProviderFinishReason.STOP) {
            return ChapterMemoryExtractionResult.Other(execution)
        }
        return when (
            val decision = validation.validate(
                completed = execution,
                contract = boundRequest.outputContract,
                validatedAt = clock.nowMillis().also { require(it >= 0L) },
            )
        ) {
            is StructuredOutputValidationDecision.Accepted -> ChapterMemoryExtractionResult.Accepted(
                memory = parser.fromValidated(decision.output),
                commitPermit = decision.commitPermit,
                execution = execution,
            )
            is StructuredOutputValidationDecision.RepairRequired -> ChapterMemoryExtractionResult.RepairRequired(
                report = decision.report,
                plan = decision.plan,
                execution = execution,
            )
            is StructuredOutputValidationDecision.NeedsAction -> ChapterMemoryExtractionResult.NeedsAction(
                report = decision.report,
                execution = execution,
            )
        }
    }
}

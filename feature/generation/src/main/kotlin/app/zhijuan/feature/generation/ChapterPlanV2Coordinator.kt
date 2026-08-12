package app.zhijuan.feature.generation

import app.zhijuan.core.database.generation.ChapterPlanV2CommitDraft
import app.zhijuan.core.database.generation.ChapterPlanV2CommitRepository
import app.zhijuan.core.database.generation.ChapterPlanV2CommitResult
import app.zhijuan.core.database.generation.PersistedStreamingRequest
import app.zhijuan.provider.common.ProviderAdapter
import app.zhijuan.provider.common.ProviderConnectionProfile
import app.zhijuan.provider.common.ProviderFinishReason

sealed interface ChapterPlanV2ExecutionResult {
    data class Accepted(
        val plan: ChapterPlanV2,
        val commit: ChapterPlanV2CommitResult,
        val execution: AuditedStreamingExecutionResult.Completed,
    ) : ChapterPlanV2ExecutionResult

    data class RepairRequired(
        val report: StructuredOutputInvalidReport,
        val plan: StructuredOutputRepairPlan,
        val execution: AuditedStreamingExecutionResult.Completed,
    ) : ChapterPlanV2ExecutionResult

    data class NeedsAction(
        val report: StructuredOutputInvalidReport,
        val execution: AuditedStreamingExecutionResult.Completed,
    ) : ChapterPlanV2ExecutionResult

    data class Other(val execution: AuditedStreamingExecutionResult) : ChapterPlanV2ExecutionResult
}

/** Executes one exact-token v2 plan request and commits only strictly accepted output. */
class ChapterPlanV2Coordinator(
    private val executor: AuditedStreamingProviderExecutor,
    private val validation: StructuredOutputValidationCoordinator,
    private val commits: ChapterPlanV2CommitRepository,
    private val parser: ChapterPlanV2Parser = ChapterPlanV2Parser(),
    private val clock: GenerationExecutionClock = SystemGenerationExecutionClock,
) {
    suspend fun execute(
        persistedRequest: PersistedStreamingRequest,
        adapter: ProviderAdapter,
        profile: ProviderConnectionProfile,
        boundRequest: BoundChapterPlanV2Request,
        initialDraftStageId: String,
        initialDraftMaxAttempts: Int,
    ): ChapterPlanV2ExecutionResult {
        val request = boundRequest.request
        require(request.stageId == persistedRequest.attempt.stageId)
        require(request.attemptId == persistedRequest.attempt.attemptId)
        require(request.stream)
        val expectedSchema = ChapterPlanOutputContractV2.providerSchema.withValue { it }
        require(request.structuredOutputSchema?.withValue { it == expectedSchema } == true)

        val execution = executor.execute(persistedRequest, adapter, profile, request)
        if (execution !is AuditedStreamingExecutionResult.Completed || execution.reason != ProviderFinishReason.STOP) {
            return ChapterPlanV2ExecutionResult.Other(execution)
        }
        val validatedAt = now()
        return when (val decision = validation.validate(execution, boundRequest.outputContract, validatedAt)) {
            is StructuredOutputValidationDecision.Accepted -> {
                val plan = parser.fromValidated(decision.output)
                val committed = commits.commit(
                    permit = decision.commitPermit,
                    draft = ChapterPlanV2CommitDraft(
                        canonicalPlanJson = plan.canonicalJson,
                        canonicalPlanHash = plan.contentHash,
                        requestBindingHash = boundRequest.requestBindingHash,
                        expectationHash = boundRequest.expectationHash,
                        activationManifestHash = boundRequest.activationManifestHash,
                        activationHash = boundRequest.activationHash,
                        policyManifestHash = boundRequest.policyManifestHash,
                        policyCompilationHash = boundRequest.policyCompilationHash,
                        contextEvidenceHash = boundRequest.contextEvidenceHash,
                        initialDraftStageId = initialDraftStageId,
                        initialDraftMaxAttempts = initialDraftMaxAttempts,
                        usage = execution.latestUsage.toFinalUsageCommit(),
                        committedAt = now().coerceAtLeast(validatedAt),
                    ),
                )
                ChapterPlanV2ExecutionResult.Accepted(plan, committed, execution)
            }
            is StructuredOutputValidationDecision.RepairRequired -> ChapterPlanV2ExecutionResult.RepairRequired(
                decision.report, decision.plan, execution,
            )
            is StructuredOutputValidationDecision.NeedsAction -> ChapterPlanV2ExecutionResult.NeedsAction(
                decision.report, execution,
            )
        }
    }

    private fun now(): Long = clock.nowMillis().also { require(it >= 0L) }
}

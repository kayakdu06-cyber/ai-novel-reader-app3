package app.zhijuan.feature.generation

import app.zhijuan.core.database.generation.ChapterDraftContinuationRepository
import app.zhijuan.core.database.generation.FinalUsageCommit
import app.zhijuan.core.database.generation.PersistedChapterDraftTruncation
import app.zhijuan.core.database.generation.PersistedInvalidChapterDraft
import app.zhijuan.core.database.generation.PersistedStreamingRequest
import app.zhijuan.core.database.generation.PreparedChapterDraftContinuation
import app.zhijuan.core.database.generation.CompletedStreamingResponse
import app.zhijuan.core.model.StandardErrorCode
import app.zhijuan.core.model.UsageSource
import app.zhijuan.provider.common.GenerationRequest
import app.zhijuan.provider.common.ProviderAdapter
import app.zhijuan.provider.common.ProviderConnectionProfile
import app.zhijuan.provider.common.ProviderFinishReason
import app.zhijuan.provider.common.ProviderUsage
import app.zhijuan.provider.common.ProviderUsageQuality

sealed interface ChapterDraftStreamingResult {
    data class ReadyForValidation(
        val response: CompletedStreamingResponse,
        val execution: AuditedStreamingExecutionResult.Completed,
    ) : ChapterDraftStreamingResult

    data class ContinuationSettled(
        val settlement: PersistedChapterDraftTruncation,
        val execution: AuditedStreamingExecutionResult.Completed,
    ) : ChapterDraftStreamingResult

    data class InvalidPayloadSettled(
        val settlement: PersistedInvalidChapterDraft,
        val execution: AuditedStreamingExecutionResult.Completed,
    ) : ChapterDraftStreamingResult

    data class Other(val execution: AuditedStreamingExecutionResult) : ChapterDraftStreamingResult
}

class ChapterDraftStreamingCoordinator(
    private val executor: AuditedStreamingProviderExecutor,
    private val continuations: ChapterDraftContinuationRepository,
    private val clock: GenerationExecutionClock = SystemGenerationExecutionClock,
) {
    suspend fun executeInitial(
        persistedRequest: PersistedStreamingRequest,
        adapter: ProviderAdapter,
        profile: ProviderConnectionProfile,
        request: GenerationRequest,
    ): ChapterDraftStreamingResult {
        requireChapterRequest(request)
        return execute(
            persistedRequest = persistedRequest,
            adapter = adapter,
            profile = profile,
            request = request,
            decoder = ChapterDraftV1StreamPayloadDecoder(),
        )
    }

    suspend fun executeContinuation(
        prepared: PreparedChapterDraftContinuation,
        adapter: ProviderAdapter,
        profile: ProviderConnectionProfile,
        request: GenerationRequest,
    ): ChapterDraftStreamingResult {
        require(request.attemptId == prepared.request.attempt.attemptId) {
            "Continuation Provider request does not match its persisted Attempt."
        }
        requireChapterRequest(request)
        ChapterDraftOutputContractV1.requireContinuationBinding(request.prompt, prepared)
        val decoder = prepared.withAnchor { anchor ->
            ChapterDraftV1StreamPayloadDecoder(
                expectedContinuationAnchor = anchor,
                initialUtf8Bytes = prepared.accumulatedUtf8Bytes,
            )
        }
        return execute(prepared.request, adapter, profile, request, decoder)
    }

    private suspend fun execute(
        persistedRequest: PersistedStreamingRequest,
        adapter: ProviderAdapter,
        profile: ProviderConnectionProfile,
        request: GenerationRequest,
        decoder: ChapterDraftV1StreamPayloadDecoder,
    ): ChapterDraftStreamingResult {
        require(request.stageId == persistedRequest.attempt.stageId)
        require(request.attemptId == persistedRequest.attempt.attemptId)
        val execution = executor.execute(persistedRequest, adapter, profile, request, decoder)
        if (execution !is AuditedStreamingExecutionResult.Completed) {
            return ChapterDraftStreamingResult.Other(execution)
        }
        val response = execution.response ?: return ChapterDraftStreamingResult.Other(execution)
        val settledAt = clock.nowMillis().also { require(it >= 0L) }
        if (execution.payloadCompletion == ProviderPayloadCompletion.INVALID) {
            return ChapterDraftStreamingResult.InvalidPayloadSettled(
                settlement = continuations.settleInvalidPayload(
                    response = response,
                    usage = execution.latestUsage.toFinalUsageCommit(),
                    settledAt = settledAt,
                ),
                execution = execution,
            )
        }
        return when (execution.reason) {
            ProviderFinishReason.STOP -> ChapterDraftStreamingResult.ReadyForValidation(response, execution)
            ProviderFinishReason.LENGTH -> ChapterDraftStreamingResult.ContinuationSettled(
                settlement = continuations.settleTruncated(
                    response = response,
                    usage = execution.latestUsage.toFinalUsageCommit(),
                    settledAt = settledAt,
                ),
                execution = execution,
            )
            else -> ChapterDraftStreamingResult.Other(execution)
        }
    }

    private fun requireChapterRequest(request: GenerationRequest) {
        require(request.stream) { "Chapter draft requests must stream." }
        require(ChapterDraftOutputContractV1.matches(request.structuredOutputSchema)) {
            "Chapter draft request does not use the exact chapter-draft.v1 schema."
        }
    }
}

internal fun ProviderUsage?.toFinalUsageCommit(): FinalUsageCommit {
    if (this == null || quality == ProviderUsageQuality.UNKNOWN) return FinalUsageCommit.UNKNOWN
    val derivedTotal = totalTokens ?: addOrNull(inputTokens, outputTokens)
    if (derivedTotal == null) return FinalUsageCommit.UNKNOWN
    val exactProviderTotal = totalTokens != null && quality == ProviderUsageQuality.PROVIDER_REPORTED
    return FinalUsageCommit(
        source = if (exactProviderTotal) UsageSource.PROVIDER_REPORTED else UsageSource.ESTIMATED,
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        cachedTokens = addOrNull(cachedInputTokens, cachedWriteTokens),
        reasoningTokens = reasoningTokens,
        totalTokens = derivedTotal,
    )
}

private fun addOrNull(first: Long?, second: Long?): Long? {
    if (first == null && second == null) return null
    return Math.addExact(first ?: 0L, second ?: 0L)
}

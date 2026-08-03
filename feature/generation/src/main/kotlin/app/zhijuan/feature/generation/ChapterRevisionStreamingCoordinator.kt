package app.zhijuan.feature.generation

import app.zhijuan.core.database.generation.ChapterRevisionNeedsActionSettlement
import app.zhijuan.core.database.generation.ChapterRevisionOutcomeRepository
import app.zhijuan.core.database.generation.GenerationOutputValidationRepository
import app.zhijuan.core.database.generation.PersistedStreamingRequest
import app.zhijuan.core.database.generation.ValidatedOutputCommitPermit
import app.zhijuan.core.task.ChapterRevisionPolicyV1
import app.zhijuan.core.task.ChapterRevisionResultDecisionV1
import app.zhijuan.provider.common.ProviderAdapter
import app.zhijuan.provider.common.ProviderConnectionProfile
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

sealed interface ChapterRevisionStreamingResultV1 {
    data class ReadyForReExtraction(
        val revisedCandidateContentHash: String,
        val revisedBodyCodePointCount: Int,
        val completedAutomaticRevisions: Int,
        val candidateContentHashHistory: List<String>,
        val commitPermit: ValidatedOutputCommitPermit,
        val execution: AuditedStreamingExecutionResult.Completed,
    ) : ChapterRevisionStreamingResultV1

    data class NeedsAction(
        val settlement: ChapterRevisionNeedsActionSettlement,
        val execution: AuditedStreamingExecutionResult.Completed,
    ) : ChapterRevisionStreamingResultV1

    data class Other(val draftResult: ChapterDraftStreamingResult) : ChapterRevisionStreamingResultV1
}

/** Runs one already-authorized finite revision and immediately rejects unchanged/cyclic output. */
class ChapterRevisionStreamingCoordinatorV1(
    private val drafts: ChapterDraftStreamingCoordinator,
    private val outputs: GenerationOutputValidationRepository,
    private val outcomes: ChapterRevisionOutcomeRepository,
    private val clock: GenerationExecutionClock = SystemGenerationExecutionClock,
) {
    suspend fun execute(
        persistedRequest: PersistedStreamingRequest,
        adapter: ProviderAdapter,
        profile: ProviderConnectionProfile,
        boundRequest: BoundChapterRevisionRequestV1,
    ): ChapterRevisionStreamingResultV1 {
        val request = boundRequest.request
        require(request.stageId == persistedRequest.attempt.stageId)
        require(request.attemptId == persistedRequest.attempt.attemptId)
        require(persistedRequest.inputHash == boundRequest.sourceBindingHash) {
            "Persisted revision intent does not match the frozen candidate and repair plan."
        }
        val result = drafts.executeInitial(persistedRequest, adapter, profile, request)
        if (result !is ChapterDraftStreamingResult.ReadyForValidation) {
            return ChapterRevisionStreamingResultV1.Other(result)
        }
        val response = result.response
        val bodyCodePoints = outputs.openForValidation(response, MAX_CHAPTER_BYTES).use { lease ->
            lease.withBytes { bytes ->
                val body = decodeStrictUtf8(bytes)
                body.codePointCount(0, body.length)
            }
        }
        val decision = ChapterRevisionPolicyV1.evaluateRevisedCandidate(
            plan = boundRequest.plan,
            revisedCandidateContentHash = response.persistedOutputHash,
            revisedBodyCodePointCount = bodyCodePoints,
        )
        val settledAt = clock.nowMillis().also { require(it >= 0L) }
        return when (decision) {
            is ChapterRevisionResultDecisionV1.ContinueWithCandidate -> {
                val permit = outputs.recordStructuredOutputValid(response, settledAt)
                ChapterRevisionStreamingResultV1.ReadyForReExtraction(
                    revisedCandidateContentHash = decision.revisedCandidateContentHash,
                    revisedBodyCodePointCount = bodyCodePoints,
                    completedAutomaticRevisions = decision.completedAutomaticRevisions,
                    candidateContentHashHistory = boundRequest.plan.priorCandidateContentHashes +
                        decision.revisedCandidateContentHash,
                    commitPermit = permit,
                    execution = result.execution,
                )
            }
            is ChapterRevisionResultDecisionV1.NeedsAction ->
                ChapterRevisionStreamingResultV1.NeedsAction(
                    settlement = outcomes.settleNeedsAction(
                        response = response,
                        reason = decision.reason,
                        usage = result.execution.latestUsage.toFinalUsageCommit(),
                        settledAt = settledAt,
                    ),
                    execution = result.execution,
                )
        }
    }

    private fun decodeStrictUtf8(bytes: ByteArray): String = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()

    private companion object {
        const val MAX_CHAPTER_BYTES = 4 * 1_024 * 1_024
    }
}

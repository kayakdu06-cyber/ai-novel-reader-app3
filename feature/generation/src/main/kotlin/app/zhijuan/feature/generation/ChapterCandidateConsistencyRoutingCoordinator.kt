package app.zhijuan.feature.generation

import app.zhijuan.core.database.generation.ChapterConsistencyOutcomeDraftV1
import app.zhijuan.core.database.generation.ChapterConsistencyOutcomeRepositoryV1
import app.zhijuan.core.database.generation.ChapterConsistencyOutcomeResultV1
import app.zhijuan.core.task.ChapterRevisionPolicyDecisionV1
import app.zhijuan.core.task.ChapterRevisionPolicyInputV1
import app.zhijuan.core.task.ChapterRevisionPolicyV1
import app.zhijuan.core.task.SceneExecutionContract
import app.zhijuan.provider.common.ProviderModelId
import app.zhijuan.provider.common.ProviderTimeoutPolicy
import java.security.MessageDigest

data class ChapterCandidateConsistencyRoutingSpecV1(
    val candidate: ChapterCandidatePipelineIdentityV1,
    val candidateContent: String,
    val candidateContentHashHistory: List<String>,
    val minimumBodyCodePoints: Int,
    val totalRevisionAttemptsUsed: Int,
    val revisionStageMaximumAttempts: Int,
    val nextStageId: String,
    val expectedCurrentVersionId: String? = null,
    val revisionRequest: ChapterCandidateRevisionRequestSeedV1?,
    val routedAt: Long,
) {
    init {
        require(candidateContent.isNotBlank() && utf8Size(candidateContent) <= MAX_CHAPTER_BYTES)
        require(sha256(candidateContent) == candidate.contentHash)
        require(candidateContentHashHistory.size == candidate.revisionIndex + 1)
        require(candidateContentHashHistory.last() == candidate.contentHash)
        require(candidateContentHashHistory.all(HASH::matches) && candidateContentHashHistory.distinct().size == candidateContentHashHistory.size)
        require(minimumBodyCodePoints in 1..1_000_000)
        require(totalRevisionAttemptsUsed >= candidate.revisionIndex)
        require(revisionStageMaximumAttempts in 1..16)
        require(IDENTIFIER.matches(nextStageId) && routedAt >= 0L)
        require(expectedCurrentVersionId == null || IDENTIFIER.matches(expectedCurrentVersionId))
    }

    override fun toString(): String =
        "ChapterCandidateConsistencyRoutingSpecV1(chapterIndex=${candidate.chapterIndex}, " +
            "revisionIndex=${candidate.revisionIndex}, content=redacted)"
}

data class ChapterCandidateRevisionRequestSeedV1(
    val requestId: String,
    val generationId: String,
    val attemptId: String,
    val modelId: ProviderModelId,
    val sceneExecutionContract: SceneExecutionContract,
    val sceneParticipantEntityIds: Set<String>,
    val requiredProcessNodeIds: Set<String>,
    val knownEntities: List<ChapterConsistencyKnownEntityV1>,
    val maximumOutputTokens: Int,
    val timeouts: ProviderTimeoutPolicy,
    val idempotencyKey: String? = null,
) {
    init {
        require(listOf(requestId, generationId, attemptId).all(IDENTIFIER::matches))
        require(sceneParticipantEntityIds.size <= 32 && sceneParticipantEntityIds.all(IDENTIFIER::matches))
        require(requiredProcessNodeIds.size <= 64 && requiredProcessNodeIds.all(IDENTIFIER::matches))
        require(knownEntities.size <= 256 && knownEntities.map { it.entityId }.distinct().size == knownEntities.size)
        require(maximumOutputTokens in 512..65_536)
    }
}

data class ChapterCandidateConsistencyRoutingPlanV1(
    val gate: ChapterConsistencyGateResultV1,
    val policyInput: ChapterRevisionPolicyInputV1,
    val policyDecision: ChapterRevisionPolicyDecisionV1,
    val revisionRequest: BoundChapterRevisionRequestV1?,
) {
    override fun toString(): String =
        "ChapterCandidateConsistencyRoutingPlanV1(decision=${policyDecision::class.simpleName}, content=redacted)"
}

data class ChapterCandidateConsistencyRoutingResultV1(
    val report: ChapterConsistencyReportV1,
    val plan: ChapterCandidateConsistencyRoutingPlanV1,
    val persistedRoute: ChapterConsistencyOutcomeResultV1,
)

/**
 * Single production route from one accepted consistency response to COMMIT,
 * REVISE, or NEEDS_ACTION. The gate, finite policy, revision request binding,
 * and database route are all derived from the same frozen inputs.
 */
class ChapterCandidateConsistencyRoutingCoordinatorV1(
    private val outcomes: ChapterConsistencyOutcomeRepositoryV1,
) {
    suspend fun route(
        accepted: ChapterConsistencyCheckResultV1.Accepted,
        boundRequest: BoundChapterConsistencyCheckRequest,
        spec: ChapterCandidateConsistencyRoutingSpecV1,
    ): ChapterCandidateConsistencyRoutingResultV1 {
        val plan = ChapterCandidateConsistencyRoutingPlannerV1.plan(
            report = accepted.report,
            boundRequest = boundRequest,
            spec = spec,
        )
        val mappingSnapshot = when (plan.policyDecision) {
            is ChapterRevisionPolicyDecisionV1.AcceptCandidate ->
                ChapterFinalConsistencyMappingSnapshotCodecV1.capture(boundRequest, spec)
            is ChapterRevisionPolicyDecisionV1.ReviseAutomatically,
            is ChapterRevisionPolicyDecisionV1.NeedsAction -> null
        }
        val persisted = outcomes.route(
            permit = accepted.commitPermit,
            draft = ChapterConsistencyOutcomeDraftV1(
                candidateChapterVersionId = spec.candidate.chapterVersionId,
                chapterId = spec.candidate.chapterId,
                chapterIndex = spec.candidate.chapterIndex,
                candidateContentHash = spec.candidate.contentHash,
                canonicalOutputHash = accepted.report.contentHash,
                sourceBindingHash = boundRequest.sourceBindingHash,
                revisionIndex = spec.candidate.revisionIndex,
                nextStageId = spec.nextStageId,
                expectedCurrentVersionId = spec.expectedCurrentVersionId,
                candidateRouteBindingHash = spec.candidate.routeBindingHash,
                revisionRequestSourceBindingHash = plan.revisionRequest?.sourceBindingHash,
                usage = accepted.execution.latestUsage.toFinalUsageCommit(),
                routedAt = spec.routedAt,
                consistencyMappingSnapshotJson = mappingSnapshot,
                consistencyMappingSnapshotContentHash = mappingSnapshot?.let(
                    ChapterFinalConsistencyMappingSnapshotCodecV1::contentHash,
                ),
            ),
            policyInput = plan.policyInput,
        )
        require(routeMatches(plan.policyDecision, persisted)) {
            "Persisted consistency route does not match the frozen policy decision."
        }
        return ChapterCandidateConsistencyRoutingResultV1(accepted.report, plan, persisted)
    }

    private fun routeMatches(
        decision: ChapterRevisionPolicyDecisionV1,
        route: ChapterConsistencyOutcomeResultV1,
    ): Boolean = when (decision) {
        is ChapterRevisionPolicyDecisionV1.AcceptCandidate -> route is ChapterConsistencyOutcomeResultV1.CommitReady
        is ChapterRevisionPolicyDecisionV1.ReviseAutomatically -> route is ChapterConsistencyOutcomeResultV1.RevisionReady
        is ChapterRevisionPolicyDecisionV1.NeedsAction -> route is ChapterConsistencyOutcomeResultV1.NeedsAction
    }
}

internal object ChapterCandidateConsistencyRoutingPlannerV1 {
    fun plan(
        report: ChapterConsistencyReportV1,
        boundRequest: BoundChapterConsistencyCheckRequest,
        spec: ChapterCandidateConsistencyRoutingSpecV1,
    ): ChapterCandidateConsistencyRoutingPlanV1 {
        require(boundRequest.request.stageId != spec.nextStageId) {
            "A consistency route cannot reuse the current Stage ID."
        }
        val expectation = boundRequest.expectation
        require(
            expectation.sourceChapterVersionId == spec.candidate.chapterVersionId &&
                expectation.sourceChapterContentHash == spec.candidate.contentHash &&
                expectation.chapterId == spec.candidate.chapterId &&
                expectation.chapterIndex == spec.candidate.chapterIndex &&
                expectation.bodyCodePointCount == spec.candidateContent.codePointCount(0, spec.candidateContent.length),
        ) { "Consistency request no longer belongs to the frozen current candidate." }
        val gate = ChapterConsistencyAcceptanceGateV1.evaluate(
            local = boundRequest.localReport,
            model = report,
            expectation = expectation,
            scene = boundRequest.sceneContract,
        )
        val input = ChapterRevisionPolicyInputV1(
            currentCandidateContentHash = spec.candidate.contentHash,
            candidateContentHashHistory = spec.candidateContentHashHistory,
            bodyCodePointCount = expectation.bodyCodePointCount,
            minimumBodyCodePoints = spec.minimumBodyCodePoints,
            completedAutomaticRevisions = spec.candidate.revisionIndex,
            totalRevisionAttemptsUsed = spec.totalRevisionAttemptsUsed,
            stageMaximumAttempts = spec.revisionStageMaximumAttempts,
            sceneContract = boundRequest.sceneContract,
            issues = ChapterRevisionRequestFactoryV1.issuesFrom(gate),
        )
        val decision = ChapterRevisionPolicyV1.evaluate(input)
        val revisionRequest = when (decision) {
            is ChapterRevisionPolicyDecisionV1.ReviseAutomatically -> {
                val seed = requireNotNull(spec.revisionRequest) {
                    "An authorized revision route requires a frozen revision request seed."
                }
                require(seed.generationId == boundRequest.request.generationId) {
                    "Revision request seed belongs to generationId=${seed.generationId}, " +
                        "not the frozen consistency Job generationId=${boundRequest.request.generationId}."
                }
                val preparation = ChapterRevisionRequestFactoryV1.prepare(
                    ChapterRevisionRequestSpecV1(
                        requestId = seed.requestId,
                        generationId = seed.generationId,
                        stageId = spec.nextStageId,
                        attemptId = seed.attemptId,
                        modelId = seed.modelId,
                        sourceChapterVersionId = spec.candidate.chapterVersionId,
                        chapterId = spec.candidate.chapterId,
                        chapterIndex = spec.candidate.chapterIndex,
                        sourceChapterContent = spec.candidateContent,
                        sourceConsistencyReportHash = report.contentHash,
                        policyInput = input,
                        sceneExecutionContract = seed.sceneExecutionContract,
                        sceneParticipantEntityIds = seed.sceneParticipantEntityIds,
                        requiredProcessNodeIds = seed.requiredProcessNodeIds,
                        knownEntities = seed.knownEntities,
                        maximumOutputTokens = seed.maximumOutputTokens,
                        timeouts = seed.timeouts,
                        idempotencyKey = seed.idempotencyKey,
                    ),
                )
                (preparation as? ChapterRevisionRequestPreparationV1.Ready)?.boundRequest
                    ?: throw IllegalArgumentException(
                        "Frozen consistency inputs authorize revision but cannot create the matching request: $preparation",
                    )
            }
            is ChapterRevisionPolicyDecisionV1.AcceptCandidate,
            is ChapterRevisionPolicyDecisionV1.NeedsAction,
            -> null
        }
        return ChapterCandidateConsistencyRoutingPlanV1(gate, input, decision, revisionRequest)
    }
}

private fun utf8Size(value: String): Int {
    val bytes = value.toByteArray(Charsets.UTF_8)
    return try {
        bytes.size
    } finally {
        bytes.fill(0)
    }
}

private fun sha256(value: String): String {
    val bytes = value.toByteArray(Charsets.UTF_8)
    return try {
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
    } finally {
        bytes.fill(0)
    }
}

private val IDENTIFIER = Regex("[A-Za-z0-9._:-]{1,128}")
private val HASH = Regex("[0-9a-f]{64}")
private const val MAX_CHAPTER_BYTES = 4 * 1_024 * 1_024

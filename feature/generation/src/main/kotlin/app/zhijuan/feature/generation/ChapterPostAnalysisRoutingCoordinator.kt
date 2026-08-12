package app.zhijuan.feature.generation

import app.zhijuan.core.database.generation.ChapterCandidateArtifactRoleV1
import app.zhijuan.core.database.generation.ChapterConsistencyOutcomeDraftV1
import app.zhijuan.core.database.generation.ChapterConsistencyOutcomeRepositoryV1
import app.zhijuan.core.database.generation.ChapterConsistencyOutcomeResultV1
import app.zhijuan.core.database.generation.ValidatedOutputCommitPermit
import app.zhijuan.core.model.ConsistencyIssueCode
import app.zhijuan.core.task.ChapterRevisionIssueRefV1
import app.zhijuan.core.task.ChapterRevisionPolicyDecisionV1
import app.zhijuan.core.task.ChapterRevisionPolicyInputV1
import app.zhijuan.core.task.ChapterRevisionPolicyV1

data class ChapterPostAnalysisRoutingResultV1(
    val analysis: ChapterPostAnalysisV1,
    val plan: ChapterCandidateConsistencyRoutingPlanV1,
    val persistedRoute: ChapterConsistencyOutcomeResultV1,
)

/** Routes one merged analysis artifact to final commit, finite revision, or needs-action. */
class ChapterPostAnalysisRoutingCoordinatorV1(
    private val outcomes: ChapterConsistencyOutcomeRepositoryV1,
) {
    suspend fun route(
        result: ChapterPostAnalysisResultV1.Accepted,
        boundRequest: BoundChapterPostAnalysisRequestV1,
        spec: ChapterCandidateConsistencyRoutingSpecV1,
    ): ChapterPostAnalysisRoutingResultV1 = routeValidated(
        result.analysis, result.commitPermit, result.execution, boundRequest, spec,
    )

    suspend fun route(
        result: ChapterPostAnalysisResultV1.RevisionRequired,
        boundRequest: BoundChapterPostAnalysisRequestV1,
        spec: ChapterCandidateConsistencyRoutingSpecV1,
    ): ChapterPostAnalysisRoutingResultV1 = routeValidated(
        result.analysis, result.commitPermit, result.execution, boundRequest, spec,
    )

    private suspend fun routeValidated(
        analysis: ChapterPostAnalysisV1,
        permit: ValidatedOutputCommitPermit,
        execution: AuditedStreamingExecutionResult.Completed,
        boundRequest: BoundChapterPostAnalysisRequestV1,
        spec: ChapterCandidateConsistencyRoutingSpecV1,
    ): ChapterPostAnalysisRoutingResultV1 {
        val plan = ChapterPostAnalysisRoutingPlannerV1.plan(analysis, boundRequest, spec)
        require(
            analysis.severeRevisionRequired ==
                (plan.policyDecision !is ChapterRevisionPolicyDecisionV1.AcceptCandidate),
        ) { "Merged analysis severity flag does not match the finite route." }
        val mappingSnapshot = when (plan.policyDecision) {
            is ChapterRevisionPolicyDecisionV1.AcceptCandidate ->
                ChapterFinalConsistencyMappingSnapshotCodecV1.capturePostAnalysis(boundRequest, spec)
            is ChapterRevisionPolicyDecisionV1.ReviseAutomatically,
            is ChapterRevisionPolicyDecisionV1.NeedsAction,
            -> null
        }
        val persisted = outcomes.route(
            permit,
            ChapterConsistencyOutcomeDraftV1(
                candidateChapterVersionId = spec.candidate.chapterVersionId,
                chapterId = spec.candidate.chapterId,
                chapterIndex = spec.candidate.chapterIndex,
                candidateContentHash = spec.candidate.contentHash,
                canonicalOutputHash = analysis.contentHash,
                sourceBindingHash = boundRequest.sourceBindingHash,
                revisionIndex = spec.candidate.revisionIndex,
                nextStageId = spec.nextStageId,
                expectedCurrentVersionId = spec.expectedCurrentVersionId,
                candidateRouteBindingHash = spec.candidate.routeBindingHash,
                revisionRequestSourceBindingHash = plan.revisionRequest?.sourceBindingHash,
                usage = execution.latestUsage.toFinalUsageCommit(),
                routedAt = spec.routedAt,
                consistencyMappingSnapshotJson = mappingSnapshot,
                consistencyMappingSnapshotContentHash = mappingSnapshot?.let(
                    ChapterFinalConsistencyMappingSnapshotCodecV1::contentHash,
                ),
                artifactRole = ChapterCandidateArtifactRoleV1.POST_ANALYSIS,
            ),
            plan.policyInput,
        )
        require(routeMatches(plan.policyDecision, persisted)) {
            "Persisted post-analysis route does not match the frozen finite decision."
        }
        return ChapterPostAnalysisRoutingResultV1(analysis, plan, persisted)
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

internal object ChapterPostAnalysisRoutingPlannerV1 {
    fun plan(
        analysis: ChapterPostAnalysisV1,
        boundRequest: BoundChapterPostAnalysisRequestV1,
        spec: ChapterCandidateConsistencyRoutingSpecV1,
    ): ChapterCandidateConsistencyRoutingPlanV1 {
        require(boundRequest.request.stageId != spec.nextStageId)
        val expectation = boundRequest.expectation.consistency
        require(
            analysis.sourceChapterVersionId == spec.candidate.chapterVersionId &&
                analysis.sourceChapterContentHash == spec.candidate.contentHash &&
                analysis.chapterId == spec.candidate.chapterId &&
                analysis.chapterIndex == spec.candidate.chapterIndex &&
                expectation.sourceChapterVersionId == spec.candidate.chapterVersionId &&
                expectation.sourceChapterContentHash == spec.candidate.contentHash &&
                expectation.bodyCodePointCount ==
                spec.candidateContent.codePointCount(0, spec.candidateContent.length),
        ) { "Merged analysis no longer belongs to the frozen candidate." }
        val report = analysis.asConsistencyReport()
        val gate = ChapterConsistencyAcceptanceGateV1.evaluate(
            boundRequest.localReport, report, expectation, boundRequest.sceneContract,
        )
        val issues = ChapterRevisionRequestFactoryV1.issuesFrom(gate) +
            ChapterPostAnalysisRevisionIssuesV1.from(analysis, gate)
        val input = ChapterRevisionPolicyInputV1(
            currentCandidateContentHash = spec.candidate.contentHash,
            candidateContentHashHistory = spec.candidateContentHashHistory,
            bodyCodePointCount = expectation.bodyCodePointCount,
            minimumBodyCodePoints = spec.minimumBodyCodePoints,
            completedAutomaticRevisions = spec.candidate.revisionIndex,
            totalRevisionAttemptsUsed = spec.totalRevisionAttemptsUsed,
            stageMaximumAttempts = spec.revisionStageMaximumAttempts,
            sceneContract = boundRequest.sceneContract,
            issues = issues,
        )
        val decision = ChapterRevisionPolicyV1.evaluate(input)
        val revisionRequest = when (decision) {
            is ChapterRevisionPolicyDecisionV1.ReviseAutomatically -> {
                val seed = requireNotNull(spec.revisionRequest) {
                    "An authorized post-analysis revision requires a frozen request seed."
                }
                require(seed.generationId == boundRequest.request.generationId)
                val prepared = ChapterRevisionRequestFactoryV1.prepare(
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
                        sourceConsistencyReportHash = analysis.contentHash,
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
                (prepared as? ChapterRevisionRequestPreparationV1.Ready)?.boundRequest
                    ?: throw IllegalArgumentException("Finite post-analysis route could not freeze its revision request.")
            }
            is ChapterRevisionPolicyDecisionV1.AcceptCandidate,
            is ChapterRevisionPolicyDecisionV1.NeedsAction,
            -> null
        }
        return ChapterCandidateConsistencyRoutingPlanV1(gate, input, decision, revisionRequest)
    }
}

internal object ChapterPostAnalysisRevisionIssuesV1 {
    fun from(
        analysis: ChapterPostAnalysisV1,
        gate: ChapterConsistencyGateResultV1,
    ): List<ChapterRevisionIssueRefV1> {
        val existingIds = gate.issues.mapTo(mutableSetOf()) { it.issueId }
        return analysis.repetitionFindings.map { finding ->
            require(existingIds.add(finding.findingId)) {
                "Post-analysis repetition and consistency issue ids must be globally unique."
            }
            ChapterRevisionIssueRefV1(
                issueId = finding.findingId,
                code = ConsistencyIssueCode.EXACT_DUPLICATE_PARAGRAPH,
                severity = finding.severity,
                startCodePointInclusive = finding.repeatedStartCodePointInclusive,
                endCodePointExclusive = finding.repeatedEndCodePointExclusive,
                repairAction = finding.repairAction,
            )
        }
    }
}

internal fun ChapterPostAnalysisV1.asConsistencyReport() = ChapterConsistencyReportV1(
    sourceChapterVersionId, sourceChapterContentHash, chapterId, chapterIndex,
    checkSourceSnapshotHash, sceneContractHash, criterionResults, requiredProcessResults,
    consistencyFindings, canonicalJson, contentHash,
)

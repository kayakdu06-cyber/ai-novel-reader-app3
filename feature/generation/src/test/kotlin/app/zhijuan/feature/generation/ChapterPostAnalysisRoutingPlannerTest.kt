package app.zhijuan.feature.generation

import app.zhijuan.core.model.ConsistencyIssueCode
import app.zhijuan.core.model.ConsistencyIssueSeverity
import app.zhijuan.core.model.ConsistencyRepairActionV1
import app.zhijuan.core.task.ChapterRevisionPolicyDecisionV1
import app.zhijuan.core.task.SceneExecutionContract
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChapterPostAnalysisRoutingPlannerTest {
    @Test
    fun `severe repetition routes to one finite revision with lineage and no final commit`() {
        val fixture = fixture()
        val repeated = fixture.analysis.copy(
            repetitionFindings = listOf(ChapterRepetitionFindingV1(
                findingId = "repeat-1",
                firstStartCodePointInclusive = 0,
                firstEndCodePointExclusive = 4,
                repeatedStartCodePointInclusive = 5,
                repeatedEndCodePointExclusive = 9,
                severity = ConsistencyIssueSeverity.MAJOR,
                repairAction = ConsistencyRepairActionV1.REMOVE_DUPLICATION,
            )),
            severeRevisionRequired = true,
        )

        val plan = ChapterPostAnalysisRoutingPlannerV1.plan(repeated, fixture.bound, fixture.routing)

        assertTrue(plan.policyDecision is ChapterRevisionPolicyDecisionV1.ReviseAutomatically)
        val revision = plan.policyDecision as ChapterRevisionPolicyDecisionV1.ReviseAutomatically
        assertEquals(1, revision.revisionIndex)
        assertEquals(listOf(fixture.routing.candidate.contentHash), revision.priorCandidateContentHashes)
        assertEquals(ConsistencyIssueCode.EXACT_DUPLICATE_PARAGRAPH, revision.issues.single().code)
        assertEquals("revision-stage", plan.revisionRequest?.request?.stageId)
        assertNotNull(plan.revisionRequest)
    }

    @Test
    fun `clean merged analysis accepts and freezes narrative state in v2 snapshot`() {
        val fixture = fixture()

        val plan = ChapterPostAnalysisRoutingPlannerV1.plan(fixture.analysis, fixture.bound, fixture.routing)
        val snapshotJson = ChapterFinalConsistencyMappingSnapshotCodecV1.capturePostAnalysis(
            fixture.bound,
            fixture.routing,
        )
        val snapshot = ChapterFinalConsistencyMappingSnapshotCodecV1.parseAndVerify(snapshotJson)

        assertTrue(plan.policyDecision is ChapterRevisionPolicyDecisionV1.AcceptCandidate)
        assertNull(plan.revisionRequest)
        assertEquals(fixture.bound.expectation.narrative, snapshot.narrativeExpectation)
        assertEquals(
            ChapterFinalConsistencyMappingSnapshotCodecV1.contentHash(snapshotJson),
            snapshotJson.sha256(),
        )
    }

    private fun fixture(): Fixture {
        val spec = ChapterPostAnalysisRequestTest().spec()
        val bound = (ChapterPostAnalysisRequestFactoryV1.prepare(spec) as
            ChapterPostAnalysisRequestPreparationV1.Ready).boundRequest
        val parsed = ChapterPostAnalysisOutputParser().parse(
            ChapterPostAnalysisStructuredOutputTest().validOutput().toByteArray(),
        ) as PlanningOutputValidationResult.Valid
        val expected = bound.expectation
        val analysis = parsed.value.copy(
            sourceChapterVersionId = expected.consistency.sourceChapterVersionId,
            sourceChapterContentHash = expected.consistency.sourceChapterContentHash,
            chapterId = expected.consistency.chapterId,
            chapterIndex = expected.consistency.chapterIndex,
            memorySnapshotHash = expected.tracking.memorySnapshotHash,
            priorForeshadowSnapshotHash = expected.tracking.priorForeshadowSnapshotHash,
            knownEntitySnapshotHash = expected.tracking.knownEntitySnapshotHash,
            checkSourceSnapshotHash = expected.consistency.checkSourceSnapshotHash,
            sceneContractHash = expected.consistency.sceneContractHash,
            criterionResults = expected.consistency.expectedCriteria.map {
                ConsistencyCriterionResultV1(it, ConsistencyCriterionStatusV1.PASS, emptyList())
            },
        )
        val candidate = ChapterCandidatePipelineIdentityV1(
            chapterVersionId = expected.consistency.sourceChapterVersionId,
            chapterId = expected.consistency.chapterId,
            chapterIndex = expected.consistency.chapterIndex,
            contentHash = expected.consistency.sourceChapterContentHash,
            revisionIndex = 0,
            routeBindingHash = null,
        )
        val routing = ChapterCandidateConsistencyRoutingSpecV1(
            candidate = candidate,
            candidateContent = spec.memory.chapterContent,
            candidateContentHashHistory = listOf(candidate.contentHash),
            minimumBodyCodePoints = 1,
            totalRevisionAttemptsUsed = 0,
            revisionStageMaximumAttempts = 2,
            nextStageId = "revision-stage",
            revisionRequest = ChapterCandidateRevisionRequestSeedV1(
                requestId = "revision-request",
                generationId = spec.memory.generationId,
                attemptId = "revision-attempt",
                modelId = spec.memory.modelId,
                sceneExecutionContract = SceneExecutionContract.NotApplicable,
                sceneParticipantEntityIds = emptySet(),
                requiredProcessNodeIds = emptySet(),
                knownEntities = spec.consistency.knownEntities,
                maximumOutputTokens = 4_096,
                timeouts = spec.memory.timeouts,
            ),
            routedAt = 100,
        )
        return Fixture(bound, analysis, routing)
    }

    private data class Fixture(
        val bound: BoundChapterPostAnalysisRequestV1,
        val analysis: ChapterPostAnalysisV1,
        val routing: ChapterCandidateConsistencyRoutingSpecV1,
    )

    private fun String.sha256() = java.security.MessageDigest.getInstance("SHA-256")
        .digest(toByteArray()).joinToString("") { "%02x".format(it) }
}

package app.zhijuan.core.task

import app.zhijuan.core.model.ConsistencyCriterionV1
import app.zhijuan.core.model.ConsistencyIssueCode
import app.zhijuan.core.model.ConsistencyIssueSeverity
import app.zhijuan.core.model.ConsistencyRepairActionV1
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class ChapterRevisionPolicyTest {
    @Test
    fun noBlockingIssueAcceptsCurrentCandidateWithoutRevision() {
        val decision = ChapterRevisionPolicyV1.evaluate(input(issues = emptyList()))

        assertEquals(
            HASH_A,
            assertInstanceOf(ChapterRevisionPolicyDecisionV1.AcceptCandidate::class.java, decision)
                .candidateContentHash,
        )
        assertEquals(
            1,
            assertInstanceOf(ChapterRevisionPolicyDecisionV1.AcceptCandidate::class.java, decision)
                .maximumAutomaticRevisions,
        )
    }

    @Test
    fun minorIssueDoesNotEscalateIntoAnAutomaticRevision() {
        val decision = ChapterRevisionPolicyV1.evaluate(
            input(
                issues = listOf(
                    issue("minor-1", 10).copy(severity = ConsistencyIssueSeverity.MINOR),
                ),
            ),
        )

        assertEquals(
            HASH_A,
            assertInstanceOf(ChapterRevisionPolicyDecisionV1.AcceptCandidate::class.java, decision)
                .candidateContentHash,
        )
        assertEquals(
            1,
            assertInstanceOf(ChapterRevisionPolicyDecisionV1.AcceptCandidate::class.java, decision)
                .maximumAutomaticRevisions,
        )
    }

    @Test
    fun proportionalModeAllowsExactlyOneAutomaticRevision() {
        val first = assertInstanceOf(
            ChapterRevisionPolicyDecisionV1.ReviseAutomatically::class.java,
            ChapterRevisionPolicyV1.evaluate(input()),
        )
        assertEquals(1, first.revisionIndex)
        assertEquals(1, first.maximumAutomaticRevisions)

        val exhausted = ChapterRevisionPolicyV1.evaluate(
            input(
                currentHash = HASH_B,
                history = listOf(HASH_A, HASH_B),
                completed = 1,
                attempts = 1,
            ),
        )
        assertEquals(
            ChapterRevisionNeedsActionReasonV1.AUTOMATIC_REVISION_LIMIT_REACHED,
            assertInstanceOf(ChapterRevisionPolicyDecisionV1.NeedsAction::class.java, exhausted).reason,
        )
    }

    @Test
    fun strictModeAllowsTwoButNeverThreeAutomaticRevisions() {
        val scene = strictScene()
        val second = assertInstanceOf(
            ChapterRevisionPolicyDecisionV1.ReviseAutomatically::class.java,
            ChapterRevisionPolicyV1.evaluate(
                input(
                    currentHash = HASH_B,
                    history = listOf(HASH_A, HASH_B),
                    completed = 1,
                    attempts = 1,
                    scene = scene,
                ),
            ),
        )
        assertEquals(2, second.revisionIndex)
        assertEquals(2, second.maximumAutomaticRevisions)

        val exhausted = ChapterRevisionPolicyV1.evaluate(
            input(
                currentHash = HASH_C,
                history = listOf(HASH_A, HASH_B, HASH_C),
                completed = 2,
                attempts = 2,
                scene = scene,
            ),
        )
        assertEquals(
            ChapterRevisionNeedsActionReasonV1.AUTOMATIC_REVISION_LIMIT_REACHED,
            assertInstanceOf(ChapterRevisionPolicyDecisionV1.NeedsAction::class.java, exhausted).reason,
        )
    }

    @Test
    fun attemptLimitStopsBeforeAnotherProviderRequest() {
        val decision = ChapterRevisionPolicyV1.evaluate(
            input(stageMaximumAttempts = 1, attempts = 1, scene = strictScene()),
        )

        assertEquals(
            ChapterRevisionNeedsActionReasonV1.STAGE_ATTEMPT_LIMIT_REACHED,
            assertInstanceOf(ChapterRevisionPolicyDecisionV1.NeedsAction::class.java, decision).reason,
        )
    }

    @Test
    fun unchangedOrPriorCandidateCannotFormARevisionLoop() {
        val first = assertInstanceOf(
            ChapterRevisionPolicyDecisionV1.ReviseAutomatically::class.java,
            ChapterRevisionPolicyV1.evaluate(input(scene = strictScene())),
        )
        assertEquals(
            ChapterRevisionNeedsActionReasonV1.REVISED_CANDIDATE_UNCHANGED,
            assertInstanceOf(
                ChapterRevisionResultDecisionV1.NeedsAction::class.java,
                ChapterRevisionPolicyV1.evaluateRevisedCandidate(first, HASH_A, 2_000),
            ).reason,
        )

        val second = assertInstanceOf(
            ChapterRevisionPolicyDecisionV1.ReviseAutomatically::class.java,
            ChapterRevisionPolicyV1.evaluate(
                input(
                    currentHash = HASH_B,
                    history = listOf(HASH_A, HASH_B),
                    completed = 1,
                    attempts = 1,
                    scene = strictScene(),
                ),
            ),
        )
        assertEquals(
            ChapterRevisionNeedsActionReasonV1.REVISED_CANDIDATE_CYCLE,
            assertInstanceOf(
                ChapterRevisionResultDecisionV1.NeedsAction::class.java,
                ChapterRevisionPolicyV1.evaluateRevisedCandidate(second, HASH_A, 2_000),
            ).reason,
        )
    }

    @Test
    fun repairPlanIsDeterministicAndDoesNotDependOnCallerIssueOrder() {
        val firstInput = input(issues = listOf(issue("b", 20), issue("a", 10)))
        val secondInput = input(issues = listOf(issue("a", 10), issue("b", 20)))
        val first = assertInstanceOf(
            ChapterRevisionPolicyDecisionV1.ReviseAutomatically::class.java,
            ChapterRevisionPolicyV1.evaluate(firstInput),
        )
        val second = assertInstanceOf(
            ChapterRevisionPolicyDecisionV1.ReviseAutomatically::class.java,
            ChapterRevisionPolicyV1.evaluate(secondInput),
        )

        assertEquals(first.repairPlanHash, second.repairPlanHash)
        assertEquals(
            ChapterRevisionPolicyV1.routingBindingHash(firstInput),
            ChapterRevisionPolicyV1.routingBindingHash(secondInput),
        )
        assertEquals(listOf("a", "b"), first.issues.map { it.issueId })
        assertNotEquals(first.sourceCandidateContentHash, HASH_B)
        assertNotEquals(
            ChapterRevisionPolicyV1.routingBindingHash(firstInput),
            ChapterRevisionPolicyV1.routingBindingHash(
                firstInput.copy(issues = listOf(issue("a", 10).copy(severity = ConsistencyIssueSeverity.MINOR))),
            ),
        )
    }

    private fun input(
        currentHash: String = HASH_A,
        history: List<String> = listOf(HASH_A),
        completed: Int = 0,
        attempts: Int = 0,
        stageMaximumAttempts: Int = 4,
        scene: ChapterSceneConsistencyContractV1 = proportionalScene(),
        issues: List<ChapterRevisionIssueRefV1> = listOf(issue("issue-1", 10)),
    ) = ChapterRevisionPolicyInputV1(
        currentCandidateContentHash = currentHash,
        candidateContentHashHistory = history,
        bodyCodePointCount = 2_000,
        minimumBodyCodePoints = 1_000,
        completedAutomaticRevisions = completed,
        totalRevisionAttemptsUsed = attempts,
        stageMaximumAttempts = stageMaximumAttempts,
        sceneContract = scene,
        issues = issues,
    )

    private fun issue(id: String, start: Int) = ChapterRevisionIssueRefV1(
        issueId = id,
        code = ConsistencyIssueCode.ACTION_REACTION_GAP,
        severity = ConsistencyIssueSeverity.MAJOR,
        startCodePointInclusive = start,
        endCodePointExclusive = start + 2,
        repairAction = ConsistencyRepairActionV1.RESTORE_CONTINUITY,
    )

    private fun proportionalScene() = ChapterSceneConsistencyContractV1(
        mode = ChapterSceneConsistencyModeV1.PROPORTIONAL,
        intimacyDetailLevel = 3,
        fadePolicy = app.zhijuan.core.model.FadePolicy.ALLOW,
        requiredKeyProcessCoveragePercent = null,
        fadeSubstitutionAllowed = true,
        requiresStateContinuity = true,
        requiresRelevantAftermath = true,
        requiredProcessNodeIds = emptyList(),
        expectedCriteria = listOf(ConsistencyCriterionV1.ACTION_REACTION),
        contractHash = "1".repeat(64),
    )

    private fun strictScene() = ChapterSceneConsistencyContractV1(
        mode = ChapterSceneConsistencyModeV1.STRICT,
        intimacyDetailLevel = 4,
        fadePolicy = app.zhijuan.core.model.FadePolicy.AVOID,
        requiredKeyProcessCoveragePercent = 100,
        fadeSubstitutionAllowed = false,
        requiresStateContinuity = true,
        requiresRelevantAftermath = true,
        requiredProcessNodeIds = listOf("process-1"),
        expectedCriteria = listOf(ConsistencyCriterionV1.REQUIRED_PROCESS_COVERAGE),
        contractHash = "2".repeat(64),
    )

    private companion object {
        val HASH_A = "a".repeat(64)
        val HASH_B = "b".repeat(64)
        val HASH_C = "c".repeat(64)
    }
}

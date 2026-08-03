package app.zhijuan.core.task

import app.zhijuan.core.model.ConsistencyCriterionV1
import app.zhijuan.core.model.FadePolicy
import app.zhijuan.core.model.RelevantSceneBlockReason
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChapterConsistencyPolicyTest {
    @Test
    fun strictSceneFreezesAllContinuityCriteriaAndPlanNodes() {
        val decision = ChapterConsistencyPolicyV1.resolve(
            strictScene(),
            requiredProcessNodeIds = listOf("process.2", "process.1"),
        ) as ChapterConsistencyPolicyDecisionV1.Ready

        assertEquals(ChapterSceneConsistencyModeV1.STRICT, decision.contract.mode)
        assertEquals(listOf("process.1", "process.2"), decision.contract.requiredProcessNodeIds)
        assertEquals(100, decision.contract.requiredKeyProcessCoveragePercent)
        assertFalse(decision.contract.fadeSubstitutionAllowed)
        assertTrue(ConsistencyCriterionV1.REQUIRED_PROCESS_COVERAGE in decision.contract.expectedCriteria)
        assertTrue(ConsistencyCriterionV1.NO_FADE_SUBSTITUTION in decision.contract.expectedCriteria)
        assertTrue(ConsistencyCriterionV1.SENSORY_CONTINUITY in decision.contract.expectedCriteria)
        assertTrue(ConsistencyCriterionV1.RELEVANT_AFTERMATH in decision.contract.expectedCriteria)
        assertTrue(ConsistencyCriterionV1.NON_MECHANICAL_DETAIL in decision.contract.expectedCriteria)
    }

    @Test
    fun relevantNonStrictSceneStillChecksStateAndAftermathWithoutInventingStrictCoverage() {
        val decision = ChapterConsistencyPolicyV1.resolve(proportionalScene()) as ChapterConsistencyPolicyDecisionV1.Ready

        assertEquals(ChapterSceneConsistencyModeV1.PROPORTIONAL, decision.contract.mode)
        assertTrue(ConsistencyCriterionV1.BODY_STATE_CONTINUITY in decision.contract.expectedCriteria)
        assertTrue(ConsistencyCriterionV1.RELEVANT_AFTERMATH in decision.contract.expectedCriteria)
        assertFalse(ConsistencyCriterionV1.REQUIRED_PROCESS_COVERAGE in decision.contract.expectedCriteria)
        assertFalse(ConsistencyCriterionV1.NO_FADE_SUBSTITUTION in decision.contract.expectedCriteria)
    }

    @Test
    fun blockedAdultGateCannotBecomeAReadyCheck() {
        val decision = ChapterConsistencyPolicyV1.resolve(
            SceneExecutionContract.Blocked(RelevantSceneBlockReason.ADULT_STATUS_UNKNOWN),
        )

        assertEquals(
            ChapterConsistencyPolicyDecisionV1.Blocked(RelevantSceneBlockReason.ADULT_STATUS_UNKNOWN),
            decision,
        )
    }

    @Test
    fun strictSceneCannotRunWithoutFrozenRequiredProcessNodes() {
        assertThrows(IllegalArgumentException::class.java) {
            ChapterConsistencyPolicyV1.resolve(strictScene())
        }
    }

    @Test
    fun contractHashIsIndependentOfCallerNodeOrdering() {
        val first = ChapterConsistencyPolicyV1.resolve(
            strictScene(),
            listOf("process.2", "process.1"),
        ) as ChapterConsistencyPolicyDecisionV1.Ready
        val second = ChapterConsistencyPolicyV1.resolve(
            strictScene(),
            listOf("process.1", "process.2"),
        ) as ChapterConsistencyPolicyDecisionV1.Ready

        assertEquals(first.contract.contractHash, second.contract.contractHash)
    }

    private fun strictScene() = SceneExecutionContract.Allowed(
        automatic = true,
        intimacyDetailLevel = 4,
        fadePolicy = FadePolicy.AVOID,
        strictBodyAndSensoryContinuity = true,
        requiredKeyProcessCoveragePercent = 100,
        fadeSubstitutionAllowed = false,
        requiresStateContinuity = true,
        requiresRelevantAftermath = true,
        instructions = listOf(PromptInstruction("scene.fixture", "fixture")),
    )

    private fun proportionalScene() = SceneExecutionContract.Allowed(
        automatic = true,
        intimacyDetailLevel = 2,
        fadePolicy = FadePolicy.ALLOW,
        strictBodyAndSensoryContinuity = false,
        requiredKeyProcessCoveragePercent = null,
        fadeSubstitutionAllowed = true,
        requiresStateContinuity = true,
        requiresRelevantAftermath = true,
        instructions = listOf(PromptInstruction("scene.fixture", "fixture")),
    )
}

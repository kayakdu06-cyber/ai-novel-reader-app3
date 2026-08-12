package app.zhijuan.feature.generation

import app.zhijuan.core.database.generation.GenerationRunnerStageRoute
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class GenerationRunnerExecutorRegistryTest {
    @Test
    fun `finite registration contains v2 plan and refuses every unfinished route`() {
        val policy = GenerationRunnerExecutorRegistryPolicyV1
        val expected = setOf(
            GenerationRunnerStageRoute.FINAL_CHAPTER_COMMIT_V3,
            GenerationRunnerStageRoute.CHAPTER_CONTEXT_ASSEMBLY_V1,
            GenerationRunnerStageRoute.CHAPTER_PLAN_V2,
            GenerationRunnerStageRoute.INITIAL_CHAPTER_DRAFT_V1,
        )
        assertEquals(expected, policy.registeredRoutes)
        expected.forEach(policy::requireRegistered)
        (GenerationRunnerStageRoute.entries - expected).forEach { route ->
            val error = assertThrows(GenerationRunnerRouteNotRegisteredException::class.java) {
                policy.requireRegistered(route)
            }
            assertEquals(route, error.route)
        }
    }

}

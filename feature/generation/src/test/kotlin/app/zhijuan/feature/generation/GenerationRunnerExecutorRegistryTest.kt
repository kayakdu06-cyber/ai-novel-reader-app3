package app.zhijuan.feature.generation

import app.zhijuan.core.database.generation.ChapterContextAssemblyBoundExecutorV1
import app.zhijuan.core.database.generation.GenerationRunnerStageRoute
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class GenerationRunnerExecutorRegistryTest {
    @Test
    fun onlyFinalCommitAndContextAssemblyAreDeclaredRegistered() {
        val registry = GenerationRunnerExecutorRegistryV1(
            ChapterFinalCandidateCommitStageExecutorV1(
                ChapterFinalCandidateCommitStageExecutorDependenciesV1(
                    findStage = { null },
                    acquireStageLease = { _, _, _ -> error("not called") },
                    commitFinalCandidate = { _, _, _ -> error("not called") },
                ),
            ),
            ChapterContextAssemblyBoundExecutorV1 { _, _ -> error("not called") },
        )

        assertEquals(
            setOf(
                GenerationRunnerStageRoute.FINAL_CHAPTER_COMMIT_V3,
                GenerationRunnerStageRoute.CHAPTER_CONTEXT_ASSEMBLY_V1,
            ),
            registry.registeredRoutes,
        )
        assertEquals(2, registry.registeredRoutes.size)
    }

    @Test
    fun notRegisteredFailureContainsOnlyTheFiniteRoute() {
        val failure = GenerationRunnerRouteNotRegisteredException(
            GenerationRunnerStageRoute.CHAPTER_PLAN_V1,
        )

        assertEquals(GenerationRunnerStageRoute.CHAPTER_PLAN_V1, failure.route)
        assertFalse(failure.toString().contains("job."))
        assertFalse(failure.toString().contains("stage."))
        assertFalse(failure.toString().contains("owner."))
    }
}

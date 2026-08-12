package app.zhijuan.feature.generation

import app.zhijuan.core.database.generation.ChapterContextAssemblyBoundExecutorV1
import app.zhijuan.core.database.generation.GenerationLeasePolicy
import app.zhijuan.core.database.generation.GenerationRunnerCurrentStageRouteSnapshot
import app.zhijuan.core.database.generation.GenerationRunnerStageRoute
import app.zhijuan.core.database.generation.PersistedChapterContextAssemblyResult
import app.zhijuan.core.database.generation.StaleGenerationStateException
import app.zhijuan.core.model.GenerationJobStatus
import app.zhijuan.core.model.GenerationStageStatus

fun interface ChapterPlanV2BoundExecutor {
    suspend fun executeBound(
        snapshot: GenerationRunnerCurrentStageRouteSnapshot,
        requestedAt: Long,
    ): ChapterPlanV2ExecutionResult
}

fun interface InitialChapterDraftBoundExecutor {
    suspend fun executeBound(
        snapshot: GenerationRunnerCurrentStageRouteSnapshot,
        requestedAt: Long,
    ): InitialChapterDraftExecutionResult
}

fun interface ChapterPostAnalysisBoundExecutor {
    suspend fun executeBound(
        snapshot: GenerationRunnerCurrentStageRouteSnapshot,
        requestedAt: Long,
    ): ChapterPostAnalysisRoutingResultV1
}

sealed interface GenerationRunnerRegisteredExecutionResultV1 {
    data class FinalChapterCommit(
        val result: ChapterFinalCandidateCommitStageExecutionResultV1,
    ) : GenerationRunnerRegisteredExecutionResultV1

    data class ChapterContextAssembly(
        val result: PersistedChapterContextAssemblyResult,
    ) : GenerationRunnerRegisteredExecutionResultV1

    data class ChapterPlanV2(
        val result: ChapterPlanV2ExecutionResult,
    ) : GenerationRunnerRegisteredExecutionResultV1

    data class InitialChapterDraft(
        val result: InitialChapterDraftExecutionResult,
    ) : GenerationRunnerRegisteredExecutionResultV1

    data class ChapterPostAnalysis(
        val result: ChapterPostAnalysisRoutingResultV1,
    ) : GenerationRunnerRegisteredExecutionResultV1
}

class GenerationRunnerRouteNotRegisteredException(
    val route: GenerationRunnerStageRoute,
) : IllegalStateException("Generation runner route is not registered: $route")

internal object GenerationRunnerExecutorRegistryPolicyV1 {
    val registeredRoutes = setOf(
        GenerationRunnerStageRoute.FINAL_CHAPTER_COMMIT_V3,
        GenerationRunnerStageRoute.CHAPTER_CONTEXT_ASSEMBLY_V1,
        GenerationRunnerStageRoute.CHAPTER_PLAN_V2,
        GenerationRunnerStageRoute.INITIAL_CHAPTER_DRAFT_V1,
        GenerationRunnerStageRoute.CANDIDATE_CHAPTER_POST_ANALYSIS_V1,
    )

    fun requireRegistered(route: GenerationRunnerStageRoute) {
        if (route !in registeredRoutes) throw GenerationRunnerRouteNotRegisteredException(route)
    }
}

/** Finite registry: no phase-based dispatch and no fallback executor. */
class GenerationRunnerExecutorRegistryV1(
    private val finalCommitExecutor: ChapterFinalCandidateCommitStageExecutorV1,
    private val contextAssemblyExecutor: ChapterContextAssemblyBoundExecutorV1,
    private val chapterPlanV2Executor: ChapterPlanV2BoundExecutor,
    private val initialChapterDraftExecutor: InitialChapterDraftBoundExecutor,
    private val chapterPostAnalysisExecutor: ChapterPostAnalysisBoundExecutor,
    private val leasePolicy: GenerationLeasePolicy = GenerationLeasePolicy(),
) {
    val registeredRoutes: Set<GenerationRunnerStageRoute>
        get() = GenerationRunnerExecutorRegistryPolicyV1.registeredRoutes

    suspend fun execute(
        snapshot: GenerationRunnerCurrentStageRouteSnapshot,
        requestedAt: Long,
    ): GenerationRunnerRegisteredExecutionResultV1 {
        GenerationRunnerExecutorRegistryPolicyV1.requireRegistered(snapshot.route)
        require(requestedAt >= 0L)
        val lease = snapshot.executionLease
        if (
            lease.jobStatus != GenerationJobStatus.RUNNING ||
            lease.stageStatus != GenerationStageStatus.PREPARING ||
            lease.jobLeaseToken.ownerId != lease.stageLeaseToken.ownerId
        ) throw StaleGenerationStateException("Runner registry lease snapshot is not executable.")
        require(requestedAt >= lease.jobHeartbeatAt && requestedAt >= lease.stageHeartbeatAt)
        if (
            leasePolicy.isExpired(lease.jobHeartbeatAt, requestedAt) ||
            leasePolicy.isExpired(lease.stageHeartbeatAt, requestedAt)
        ) throw StaleGenerationStateException("Runner registry lease snapshot expired.")

        return when (snapshot.route) {
            GenerationRunnerStageRoute.FINAL_CHAPTER_COMMIT_V3 ->
                GenerationRunnerRegisteredExecutionResultV1.FinalChapterCommit(
                    finalCommitExecutor.executeBound(
                        finalStageId = lease.stageId,
                        stageLeaseToken = lease.stageLeaseToken,
                        requestedAt = requestedAt,
                    ),
                )
            GenerationRunnerStageRoute.CHAPTER_CONTEXT_ASSEMBLY_V1 ->
                GenerationRunnerRegisteredExecutionResultV1.ChapterContextAssembly(
                    contextAssemblyExecutor.assembleBound(snapshot, requestedAt),
                )
            GenerationRunnerStageRoute.CHAPTER_PLAN_V2 ->
                GenerationRunnerRegisteredExecutionResultV1.ChapterPlanV2(
                    chapterPlanV2Executor.executeBound(snapshot, requestedAt),
                )
            GenerationRunnerStageRoute.INITIAL_CHAPTER_DRAFT_V1 ->
                GenerationRunnerRegisteredExecutionResultV1.InitialChapterDraft(
                    initialChapterDraftExecutor.executeBound(snapshot, requestedAt),
                )
            GenerationRunnerStageRoute.CANDIDATE_CHAPTER_POST_ANALYSIS_V1 ->
                GenerationRunnerRegisteredExecutionResultV1.ChapterPostAnalysis(
                    chapterPostAnalysisExecutor.executeBound(snapshot, requestedAt),
                )
            else -> notRegistered(snapshot.route)
        }
    }

    internal fun requireRegistered(route: GenerationRunnerStageRoute) {
        GenerationRunnerExecutorRegistryPolicyV1.requireRegistered(route)
    }

    private fun notRegistered(route: GenerationRunnerStageRoute): Nothing =
        throw GenerationRunnerRouteNotRegisteredException(route)
}

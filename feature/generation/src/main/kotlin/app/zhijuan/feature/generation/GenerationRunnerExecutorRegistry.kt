package app.zhijuan.feature.generation

import app.zhijuan.core.database.generation.ChapterContextAssemblyBoundExecutorV1
import app.zhijuan.core.database.generation.GenerationLeasePolicy
import app.zhijuan.core.database.generation.GenerationRunnerCurrentStageRouteSnapshot
import app.zhijuan.core.database.generation.GenerationRunnerStageRoute
import app.zhijuan.core.database.generation.PersistedChapterContextAssemblyResult
import app.zhijuan.core.database.generation.StaleGenerationStateException
import app.zhijuan.core.model.GenerationJobStatus
import app.zhijuan.core.model.GenerationStageStatus

/** Result of one route that is explicitly registered for total-runner execution. */
sealed interface GenerationRunnerRegisteredExecutionResultV1 {
    data class FinalChapterCommit(
        val result: ChapterFinalCandidateCommitStageExecutionResultV1,
    ) : GenerationRunnerRegisteredExecutionResultV1 {
        override fun toString(): String = "FinalChapterCommit(result=$result)"
    }

    data class ChapterContextAssembly(
        val result: PersistedChapterContextAssemblyResult,
    ) : GenerationRunnerRegisteredExecutionResultV1 {
        override fun toString(): String = "ChapterContextAssembly(result=$result)"
    }
}

/**
 * Signals a known finite route that deliberately has no production executor registration yet.
 * It is not a retryable Provider error and contains no Job/Stage/payload identity.
 */
class GenerationRunnerRouteNotRegisteredException(
    val route: GenerationRunnerStageRoute,
) : IllegalStateException("Generation runner route is not registered: $route")

/**
 * Finite executor registry for the current safe slice.
 *
 * The public entry accepts only the database-created bound snapshot from Phase 2B. There is no
 * phase-based or generic fallback. FINAL_CHAPTER_COMMIT_V3 and CHAPTER_CONTEXT_ASSEMBLY_V1 are the
 * only registered routes; every other finite route is named explicitly and fails before an
 * executor or Provider can be touched.
 */
class GenerationRunnerExecutorRegistryV1(
    private val finalCommitExecutor: ChapterFinalCandidateCommitStageExecutorV1,
    private val contextAssemblyExecutor: ChapterContextAssemblyBoundExecutorV1,
    private val leasePolicy: GenerationLeasePolicy = GenerationLeasePolicy(),
) {
    val registeredRoutes: Set<GenerationRunnerStageRoute>
        get() = REGISTERED_ROUTES

    suspend fun execute(
        snapshot: GenerationRunnerCurrentStageRouteSnapshot,
        requestedAt: Long,
    ): GenerationRunnerRegisteredExecutionResultV1 {
        require(requestedAt >= 0L) { "Runner registry execution time is invalid." }
        val lease = snapshot.executionLease
        if (
            lease.jobStatus != GenerationJobStatus.RUNNING ||
            lease.stageStatus != GenerationStageStatus.PREPARING ||
            lease.jobLeaseToken.ownerId != lease.stageLeaseToken.ownerId
        ) {
            throw StaleGenerationStateException("Runner registry lease snapshot is not executable.")
        }
        require(
            requestedAt >= lease.jobHeartbeatAt &&
                requestedAt >= lease.stageHeartbeatAt,
        ) { "Runner registry execution time cannot move backwards." }
        if (
            leasePolicy.isExpired(lease.jobHeartbeatAt, requestedAt) ||
            leasePolicy.isExpired(lease.stageHeartbeatAt, requestedAt)
        ) {
            throw StaleGenerationStateException("Runner registry lease snapshot expired.")
        }

        return when (snapshot.route) {
            GenerationRunnerStageRoute.FINAL_CHAPTER_COMMIT_V3 ->
                GenerationRunnerRegisteredExecutionResultV1.FinalChapterCommit(
                    finalCommitExecutor.executeBound(
                        finalStageId = lease.stageId,
                        stageLeaseToken = lease.stageLeaseToken,
                        requestedAt = requestedAt,
                    ),
                )
            GenerationRunnerStageRoute.FORMAL_CHAPTER_MEMORY_V1 ->
                notRegistered(snapshot.route)
            GenerationRunnerStageRoute.EDIT_REBUILD_CHAPTER_MEMORY_V2 ->
                notRegistered(snapshot.route)
            GenerationRunnerStageRoute.FORMAL_CHAPTER_TRACKING_V1 ->
                notRegistered(snapshot.route)
            GenerationRunnerStageRoute.EDIT_REBUILD_CHAPTER_TRACKING_V2 ->
                notRegistered(snapshot.route)
            GenerationRunnerStageRoute.CANDIDATE_CHAPTER_DRAFT_V1 ->
                notRegistered(snapshot.route)
            GenerationRunnerStageRoute.CANDIDATE_CHAPTER_MEMORY_V1 ->
                notRegistered(snapshot.route)
            GenerationRunnerStageRoute.CANDIDATE_CHAPTER_TRACKING_V1 ->
                notRegistered(snapshot.route)
            GenerationRunnerStageRoute.CANDIDATE_CHAPTER_CONSISTENCY_V1 ->
                notRegistered(snapshot.route)
            GenerationRunnerStageRoute.CANDIDATE_CHAPTER_REVISION_V1 ->
                notRegistered(snapshot.route)
            GenerationRunnerStageRoute.CHAPTER_CONTEXT_ASSEMBLY_V1 ->
                GenerationRunnerRegisteredExecutionResultV1.ChapterContextAssembly(
                    contextAssemblyExecutor.assembleBound(snapshot, requestedAt),
                )
            GenerationRunnerStageRoute.CHAPTER_PLAN_V1 ->
                notRegistered(snapshot.route)
        }
    }

    private fun notRegistered(route: GenerationRunnerStageRoute): Nothing =
        throw GenerationRunnerRouteNotRegisteredException(route)

    private companion object {
        val REGISTERED_ROUTES = setOf(
            GenerationRunnerStageRoute.FINAL_CHAPTER_COMMIT_V3,
            GenerationRunnerStageRoute.CHAPTER_CONTEXT_ASSEMBLY_V1,
        )
    }
}

package app.zhijuan.feature.generation

import app.zhijuan.core.database.generation.GenerationRunnerCurrentStageRouteSnapshot
import app.zhijuan.core.database.generation.GenerationRunnerStageRoute
import app.zhijuan.core.database.generation.GenerationStreamingDraftRepository
import app.zhijuan.core.database.generation.InitialChapterDraftPromptSources
import app.zhijuan.core.database.generation.InitialChapterDraftPromptSourcesRepository
import app.zhijuan.core.database.generation.RequestIntentDraft

fun interface InitialChapterDraftBoundSourceLoader {
    suspend fun load(
        snapshot: GenerationRunnerCurrentStageRouteSnapshot,
        loadedAt: Long,
    ): InitialChapterDraftPromptSources

    companion object {
        fun from(repository: InitialChapterDraftPromptSourcesRepository): InitialChapterDraftBoundSourceLoader =
            InitialChapterDraftBoundSourceLoader(repository::loadBound)
    }
}

/** One exact-token production path from a PREPARING first BODY Stage to post-analysis. */
class PersistentInitialChapterDraftBoundExecutorV1(
    private val sources: InitialChapterDraftBoundSourceLoader,
    private val remote: GenerationBoundRemoteExecutionProvider,
    private val requests: GenerationStreamingDraftRepository,
    private val coordinator: InitialChapterDraftCoordinator,
    private val postAnalysisMaximumAttempts: Int = 2,
) : InitialChapterDraftBoundExecutor {
    init {
        require(postAnalysisMaximumAttempts in 1..4)
    }

    override suspend fun executeBound(
        snapshot: GenerationRunnerCurrentStageRouteSnapshot,
        requestedAt: Long,
    ): InitialChapterDraftExecutionResult {
        require(snapshot.route == GenerationRunnerStageRoute.INITIAL_CHAPTER_DRAFT_V1)
        require(requestedAt >= 0L)
        val lease = snapshot.executionLease
        val boundSources = sources.load(snapshot, requestedAt)
        require(boundSources.stageId == lease.stageId)
        val execution = remote.resolve(snapshot, requestedAt)
        val ordinal = (snapshot.attemptCount + 1).toString()
        val attemptId = stableGenerationExecutionId("attempt", lease.jobId, lease.stageId, ordinal)
        val bound = InitialChapterDraftRequestFactory.create(InitialChapterDraftRequestSpec(
            requestId = stableGenerationExecutionId("request", lease.jobId, lease.stageId, ordinal),
            generationId = lease.jobId,
            attemptId = attemptId,
            modelId = execution.modelId,
            sources = boundSources,
            maximumOutputTokens = execution.maximumOutputTokens,
            timeouts = execution.timeouts,
            idempotencyKey = boundSources.stageIdempotencyKey,
        ))
        val persisted = requests.prepareBoundInitialChapterDraftBeforeSend(
            snapshot = snapshot,
            draft = RequestIntentDraft(
                attemptId = attemptId,
                usageLedgerId = stableGenerationExecutionId("usage", lease.jobId, lease.stageId, ordinal),
                stageId = lease.stageId,
                retryParentAttemptId = null,
                connectionSnapshotJson = execution.connectionSnapshotJson,
                modelSnapshotJson = execution.modelSnapshotJson,
                protocolSnapshotJson = execution.protocolSnapshotJson,
                inputHash = bound.sourceBindingHash,
                streamDraftRef = null,
                createdAt = requestedAt,
            ),
            budget = execution.budget(
                stableGenerationExecutionId("budget", lease.jobId, lease.stageId, ordinal),
            ),
        )
        return coordinator.execute(
            persisted = persisted,
            adapter = execution.adapter,
            profile = execution.profile,
            bound = bound,
            advance = InitialChapterDraftAdvanceSpec(
                jobId = lease.jobId,
                candidateChapterVersionId = stableGenerationExecutionId(
                    "candidate", lease.jobId, boundSources.chapterId,
                ),
                memoryStageId = stableGenerationExecutionId(
                    "stage-analysis", lease.jobId, boundSources.chapterId,
                ),
                memoryStageMaximumAttempts = postAnalysisMaximumAttempts,
                sealedAt = requestedAt,
            ),
        )
    }
}

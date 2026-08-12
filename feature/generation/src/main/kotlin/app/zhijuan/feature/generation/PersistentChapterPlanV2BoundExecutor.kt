package app.zhijuan.feature.generation

import app.zhijuan.core.database.generation.ChapterPlanV2PromptSources
import app.zhijuan.core.database.generation.ChapterPlanV2PromptSourcesRepository
import app.zhijuan.core.database.generation.GenerationRunnerCurrentStageRouteSnapshot
import app.zhijuan.core.database.generation.GenerationRunnerStageRoute
import app.zhijuan.core.database.generation.GenerationStreamingDraftRepository
import app.zhijuan.core.database.generation.RequestIntentDraft
import java.security.MessageDigest

fun interface ChapterPlanV2BoundSourceLoader {
    suspend fun load(
        snapshot: GenerationRunnerCurrentStageRouteSnapshot,
        loadedAt: Long,
    ): ChapterPlanV2PromptSources

    companion object {
        fun from(repository: ChapterPlanV2PromptSourcesRepository): ChapterPlanV2BoundSourceLoader =
            ChapterPlanV2BoundSourceLoader(repository::loadBound)
    }
}

/** One exact-token production path from a PREPARING plan Stage to its persisted result. */
class PersistentChapterPlanV2BoundExecutorV1(
    private val sources: ChapterPlanV2BoundSourceLoader,
    private val remote: GenerationBoundRemoteExecutionProvider,
    private val requests: GenerationStreamingDraftRepository,
    private val coordinator: ChapterPlanV2Coordinator,
    private val initialDraftMaximumAttempts: Int = 2,
) : ChapterPlanV2BoundExecutor {
    init {
        require(initialDraftMaximumAttempts in 1..4)
    }

    override suspend fun executeBound(
        snapshot: GenerationRunnerCurrentStageRouteSnapshot,
        requestedAt: Long,
    ): ChapterPlanV2ExecutionResult {
        require(snapshot.route == GenerationRunnerStageRoute.CHAPTER_PLAN_V2)
        require(requestedAt >= 0L)
        val lease = snapshot.executionLease
        val boundSources = sources.load(snapshot, requestedAt)
        require(boundSources.stageId == lease.stageId)
        val execution = remote.resolve(snapshot, requestedAt)
        val attemptOrdinal = snapshot.attemptCount + 1
        val attemptId = stableId("attempt", lease.jobId, lease.stageId, attemptOrdinal.toString())
        val usageId = stableId("usage", lease.jobId, lease.stageId, attemptOrdinal.toString())
        val reservationId = stableId("budget", lease.jobId, lease.stageId, attemptOrdinal.toString())
        val requestId = stableId("request", lease.jobId, lease.stageId, attemptOrdinal.toString())
        val boundRequest = ChapterPlanV2RequestFactory.restore(FrozenChapterPlanV2RequestSpec(
            requestId = requestId,
            generationId = lease.jobId,
            stageId = lease.stageId,
            attemptId = attemptId,
            modelId = execution.modelId,
            context = boundSources.context,
            frozen = boundSources.frozen,
            maximumOutputTokens = execution.maximumOutputTokens,
            timeouts = execution.timeouts,
            idempotencyKey = boundSources.stageIdempotencyKey,
        ))
        val persisted = requests.prepareBoundChapterPlanBeforeSend(
            snapshot = snapshot,
            draft = RequestIntentDraft(
                attemptId = attemptId,
                usageLedgerId = usageId,
                stageId = lease.stageId,
                retryParentAttemptId = null,
                connectionSnapshotJson = execution.connectionSnapshotJson,
                modelSnapshotJson = execution.modelSnapshotJson,
                protocolSnapshotJson = execution.protocolSnapshotJson,
                inputHash = boundRequest.requestBindingHash,
                streamDraftRef = null,
                createdAt = requestedAt,
            ),
            budget = execution.budget(reservationId),
        )
        return coordinator.execute(
            persistedRequest = persisted,
            adapter = execution.adapter,
            profile = execution.profile,
            boundRequest = boundRequest,
            initialDraftStageId = stableId("stage-draft", lease.jobId, boundSources.chapterId),
            initialDraftMaxAttempts = initialDraftMaximumAttempts,
        )
    }
}

internal fun stableGenerationExecutionId(prefix: String, vararg parts: String): String =
    stableId(prefix, *parts)

private fun stableId(prefix: String, vararg parts: String): String {
    require(prefix.matches(Regex("[a-z][a-z0-9-]{0,23}")))
    require(parts.isNotEmpty() && parts.all(String::isNotBlank))
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(parts.joinToString("\u0000").encodeToByteArray())
        .joinToString("") { "%02x".format(it) }
    return "$prefix-${digest.take(48)}"
}

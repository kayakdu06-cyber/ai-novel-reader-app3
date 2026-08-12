package app.zhijuan.core.database.generation

import androidx.room.withTransaction
import app.zhijuan.core.database.ZhijuanDatabase
import app.zhijuan.core.model.GenerationJobStatus
import app.zhijuan.core.model.GenerationStageStatus

data class ChapterPlanV2PromptSources(
    val stageId: String,
    val stageInputVersionHash: String,
    val stageIdempotencyKey: String,
    val chapterId: String,
    val chapterIndex: Int,
    val context: ReadyChapterContext,
    val frozen: ChapterPlanV2FrozenSources,
) {
    override fun toString(): String =
        "ChapterPlanV2PromptSources(chapterIndex=$chapterIndex, content=redacted)"
}

/** Loads one exact-token chapter-plan request solely from immutable Stage/context evidence. */
class ChapterPlanV2PromptSourcesRepository(
    private val database: ZhijuanDatabase,
    private val leasePolicy: GenerationLeasePolicy = GenerationLeasePolicy(),
) {
    suspend fun loadBound(
        snapshot: GenerationRunnerCurrentStageRouteSnapshot,
        loadedAt: Long,
    ): ChapterPlanV2PromptSources = database.withTransaction {
        require(snapshot.route == GenerationRunnerStageRoute.CHAPTER_PLAN_V2)
        val lease = snapshot.executionLease
        val dao = database.generationDao()
        val stage = requireNotNull(dao.findStage(lease.stageId)) { "Chapter-plan Stage is missing." }
        val job = requireNotNull(dao.findJob(lease.jobId)) { "Chapter-plan Job is missing." }
        val jobHeartbeat = requireNotNull(job.leaseHeartbeatAt) { "Chapter-plan Job heartbeat is missing." }
        val stageHeartbeat = requireNotNull(stage.leaseHeartbeatAt) { "Chapter-plan Stage heartbeat is missing." }
        if (
            job.jobId != lease.jobId || stage.jobId != job.jobId || stage.stageId != lease.stageId ||
            job.status != GenerationJobStatus.RUNNING || stage.status != GenerationStageStatus.PREPARING ||
            job.currentStageId != stage.stageId || job.pauseOrStopReason != null ||
            job.leaseTokenOrNull() != lease.jobLeaseToken ||
            stage.leaseTokenOrNull() != lease.stageLeaseToken ||
            jobHeartbeat < lease.jobHeartbeatAt || stageHeartbeat < lease.stageHeartbeatAt ||
            stage.attemptCount != snapshot.attemptCount || stage.maxAttempts != snapshot.maxAttempts ||
            GenerationRunnerStageRouteResolver.resolve(stage) != snapshot.route
        ) {
            throw StaleGenerationStateException("Chapter-plan bound source snapshot changed.")
        }
        require(
            loadedAt >= job.updatedAt && loadedAt >= stage.updatedAt &&
                loadedAt >= jobHeartbeat && loadedAt >= stageHeartbeat,
        ) { "Chapter-plan source load time cannot move backwards." }
        if (
            leasePolicy.isExpired(jobHeartbeat, loadedAt) ||
            leasePolicy.isExpired(stageHeartbeat, loadedAt)
        ) {
            throw StaleGenerationStateException("Chapter-plan execution lease expired before source load.")
        }
        val source = ChapterPlanV2StageBinding.parseAndVerify(stage)
        ChapterProgressionGateRepository(database).requireProviderOpenAllowed(stage, job)
        val context = ChapterContextAssemblyRepository(database).requireProviderOpenAllowed(stage, job)
        require(
            context.contextStageId == source.contextAssemblyStageId &&
                context.chapterPlanStageId == stage.stageId,
        ) { "Chapter-plan context source changed after freezing." }
        ChapterPlanV2PromptSources(
            stageId = stage.stageId,
            stageInputVersionHash = stage.inputVersionHash,
            stageIdempotencyKey = stage.idempotencyKey,
            chapterId = stage.targetId,
            chapterIndex = source.targetChapterIndex,
            context = context,
            frozen = source.frozen,
        )
    }
}

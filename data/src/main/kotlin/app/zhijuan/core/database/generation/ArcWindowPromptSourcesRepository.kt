package app.zhijuan.core.database.generation

import androidx.room.withTransaction
import app.zhijuan.core.database.ZhijuanDatabase
import app.zhijuan.core.model.GenerationJobStatus
import app.zhijuan.core.model.GenerationJobType
import app.zhijuan.core.model.GenerationStageStatus
import app.zhijuan.core.task.BoundPromptBundle

data class ArcWindowPromptSources(
    val jobId: String,
    val stageId: String,
    val bookId: String,
    val userIntentJson: String,
    val budgetSnapshotJson: String,
    val stageIdempotencyKey: String,
    val promptBundle: BoundPromptBundle,
    val frozen: FrozenArcWindowPlanningSource,
) {
    override fun toString(): String =
        "ArcWindowPromptSources(window=${frozen.selection.windowStartChapter}.." +
            "${frozen.selection.windowEndChapter}, content=redacted)"
}

/** Loads an arc-window request only from the exact runner lease and current outline head. */
class ArcWindowPromptSourcesRepository(
    private val database: ZhijuanDatabase,
    private val leasePolicy: GenerationLeasePolicy = GenerationLeasePolicy(),
) {
    suspend fun loadBound(
        snapshot: GenerationRunnerCurrentStageRouteSnapshot,
        loadedAt: Long,
    ): ArcWindowPromptSources = database.withTransaction {
        require(snapshot.route == GenerationRunnerStageRoute.ARC_WINDOW_V1)
        val lease = snapshot.executionLease
        val generation = database.generationDao()
        val stage = requireNotNull(generation.findStage(lease.stageId)) { "Arc-window Stage is missing." }
        val job = requireNotNull(generation.findJob(lease.jobId)) { "Arc-window Job is missing." }
        val jobHeartbeat = requireNotNull(job.leaseHeartbeatAt)
        val stageHeartbeat = requireNotNull(stage.leaseHeartbeatAt)
        if (
            job.jobType != GenerationJobType.CONTINUE_BOOK ||
            job.status != GenerationJobStatus.RUNNING || stage.status != GenerationStageStatus.PREPARING ||
            job.currentStageId != stage.stageId || stage.jobId != job.jobId || job.pauseOrStopReason != null ||
            job.leaseTokenOrNull() != lease.jobLeaseToken || stage.leaseTokenOrNull() != lease.stageLeaseToken ||
            jobHeartbeat < lease.jobHeartbeatAt || stageHeartbeat < lease.stageHeartbeatAt ||
            stage.attemptCount != snapshot.attemptCount || stage.maxAttempts != snapshot.maxAttempts ||
            GenerationRunnerStageRouteResolver.resolve(stage) != snapshot.route
        ) throw StaleGenerationStateException("Arc-window bound source snapshot changed.")
        require(
            loadedAt >= job.updatedAt && loadedAt >= stage.updatedAt &&
                loadedAt >= jobHeartbeat && loadedAt >= stageHeartbeat,
        ) { "Arc-window source time cannot move backwards." }
        if (leasePolicy.isExpired(jobHeartbeat, loadedAt) || leasePolicy.isExpired(stageHeartbeat, loadedAt)) {
            throw StaleGenerationStateException("Arc-window execution lease expired before source load.")
        }
        val frozen = ArcWindowPlanningJobFactory.parseAndVerify(stage)
        val head = requireNotNull(database.memoryDao().findMemoryHead(job.bookId)) {
            "Arc-window memory head is missing."
        }
        val parent = requireNotNull(database.memoryDao().findOutlineRevision(frozen.parentOutlineRevisionId)) {
            "Arc-window parent outline is missing."
        }
        require(
            head.currentOutlineRevisionId == parent.outlineRevisionId && parent.bookId == job.bookId &&
                parent.contentHash == frozen.parentOutlineContentHash,
        ) { "Arc-window outline head changed after freezing." }
        val master = requireNotNull(database.memoryDao().findOutlineRevision(frozen.masterOutlineRevisionId)) {
            "Arc-window master outline is missing."
        }
        require(
            master.bookId == job.bookId && master.revisionNo == 1 && master.parentRevisionId == null &&
                master.contentHash == frozen.masterOutlineContentHash,
        ) { "Arc-window master outline changed after freezing." }
        val bundle = PromptBundleBindingRepository(database).bindForBook(job.bookId)
        ArcWindowPromptSources(
            jobId = job.jobId,
            stageId = stage.stageId,
            bookId = job.bookId,
            userIntentJson = job.userIntentJson,
            budgetSnapshotJson = job.budgetSnapshotJson,
            stageIdempotencyKey = stage.idempotencyKey,
            promptBundle = bundle,
            frozen = frozen,
        )
    }
}

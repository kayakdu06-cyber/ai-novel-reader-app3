package app.zhijuan.core.database.generation

import androidx.room.withTransaction
import app.zhijuan.core.database.ZhijuanDatabase
import app.zhijuan.core.model.GenerationJobStatus
import app.zhijuan.core.model.GenerationPhase
import app.zhijuan.core.model.GenerationStageStatus

/** Upgrades the still-pending v1 plan placeholder before context assembly makes it READY. */
class ChapterPlanV2StageUpgradeRepository(
    private val database: ZhijuanDatabase,
) {
    suspend fun upgradePending(
        snapshot: GenerationRunnerCurrentStageRouteSnapshot,
        frozen: ChapterPlanV2FrozenSources,
        upgradedAt: Long,
    ) = database.withTransaction {
        require(snapshot.route == GenerationRunnerStageRoute.CHAPTER_CONTEXT_ASSEMBLY_V1)
        val lease = snapshot.executionLease
        val generation = database.generationDao()
        val context = requireNotNull(generation.findStage(lease.stageId))
        val job = requireNotNull(generation.findJob(lease.jobId))
        require(
            job.status in setOf(GenerationJobStatus.RUNNING, GenerationJobStatus.PAUSED) &&
                context.status == GenerationStageStatus.SUCCEEDED && context.jobId == job.jobId &&
                upgradedAt >= job.updatedAt && upgradedAt >= context.updatedAt,
        ) { "Chapter-plan v2 upgrade lost its bound context lease." }
        val plan = generation.stagesForJob(job.jobId).single {
            it.phase == GenerationPhase.BUILD_CHAPTER_PLAN
        }
        if (plan.inputSourcesJson.contains(ChapterPlanV2StageBinding.SOURCE_POLICY_VERSION)) {
            val current = ChapterPlanV2StageBinding.parseAndVerify(plan)
            require(current.frozen.expectationHash == frozen.expectationHash) {
                "Chapter-plan v2 replay changed its frozen authority."
            }
            return@withTransaction
        }
        require(
            plan.status == GenerationStageStatus.READY && plan.attemptCount == 0 &&
                job.currentStageId == plan.stageId && plan.leaseOwnerId == null,
        )
        val setup = GenerationJobSetup(
            jobId = job.jobId,
            bookId = job.bookId,
            jobType = job.jobType,
            userIntentJson = job.userIntentJson,
            budgetSnapshotJson = job.budgetSnapshotJson,
            promptBundleVersion = job.promptBundleVersion,
            stages = listOf(
                GenerationStageSetup(
                    stageId = plan.stageId,
                    phase = plan.phase,
                    targetType = plan.targetType,
                    targetId = plan.targetId,
                    inputVersionHash = plan.inputVersionHash,
                    idempotencyKey = plan.idempotencyKey,
                    maxAttempts = plan.maxAttempts,
                    inputSourcesJson = plan.inputSourcesJson,
                ),
            ),
            createdAt = job.createdAt,
        )
        val upgraded = ChapterPlanV2StageBinding.bind(setup, frozen).stages.single()
        if (
            generation.compareAndUpgradePendingStage(
                stageId = plan.stageId,
                jobId = job.jobId,
                expectedInputVersionHash = plan.inputVersionHash,
                inputVersionHash = upgraded.inputVersionHash,
                idempotencyKey = upgraded.idempotencyKey,
                inputSourcesJson = upgraded.inputSourcesJson,
                updatedAt = upgradedAt,
            ) != 1
        ) throw StaleGenerationStateException("Chapter-plan v2 upgrade lost a concurrent update.")
    }
}

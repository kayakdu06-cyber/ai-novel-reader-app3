package app.zhijuan.core.task

import app.zhijuan.core.model.GenerationJobStatus
import app.zhijuan.core.model.GenerationStageStatus

enum class GenerationMaintenanceAction {
    REQUEUE_BEFORE_REQUEST,
    AUDIT_WITHOUT_PROVIDER,
    SETTLE_EXPIRED_NETWORK_CONTROL,
    DEFER_UNSAFE_OR_INCOMPLETE,
}

data class GenerationMaintenanceContext(
    val jobStatus: GenerationJobStatus,
    val stageStatus: GenerationStageStatus,
    val hasLatestAttempt: Boolean,
)

object GenerationMaintenancePolicy {
    fun decide(context: GenerationMaintenanceContext): GenerationMaintenanceAction = when {
        context.jobStatus == GenerationJobStatus.RUNNING &&
            context.stageStatus == GenerationStageStatus.PREPARING ->
            GenerationMaintenanceAction.REQUEUE_BEFORE_REQUEST

        context.jobStatus == GenerationJobStatus.RUNNING &&
            context.hasLatestAttempt &&
            context.stageStatus in AUDITABLE_STAGE_STATUSES ->
            GenerationMaintenanceAction.AUDIT_WITHOUT_PROVIDER

        context.jobStatus in CONTROL_PENDING_JOB_STATUSES &&
            context.hasLatestAttempt &&
            context.stageStatus in NETWORK_ACTIVE_STAGE_STATUSES ->
            GenerationMaintenanceAction.SETTLE_EXPIRED_NETWORK_CONTROL

        else -> GenerationMaintenanceAction.DEFER_UNSAFE_OR_INCOMPLETE
    }

    private val AUDITABLE_STAGE_STATUSES = setOf(
        GenerationStageStatus.REQUEST_INTENT_RECORDED,
        GenerationStageStatus.STREAMING,
        GenerationStageStatus.VALIDATING,
        GenerationStageStatus.COMMITTING,
    )
    private val NETWORK_ACTIVE_STAGE_STATUSES = setOf(
        GenerationStageStatus.REQUEST_INTENT_RECORDED,
        GenerationStageStatus.STREAMING,
    )
    private val CONTROL_PENDING_JOB_STATUSES = setOf(
        GenerationJobStatus.PAUSING,
        GenerationJobStatus.STOPPING,
    )
}

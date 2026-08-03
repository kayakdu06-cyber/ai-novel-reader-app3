package app.zhijuan.core.task

import app.zhijuan.core.model.BudgetStatus
import app.zhijuan.core.model.GenerationJobStatus
import app.zhijuan.core.model.GenerationStageStatus

data class BackgroundRecoveryContext(
    val jobStatus: GenerationJobStatus,
    val stageStatus: GenerationStageStatus,
    val autoResumePaidGenerationEnabled: Boolean,
    val manuallyPaused: Boolean,
    val networkAvailable: Boolean,
    val retryWindowReached: Boolean,
    val leaseExpiredOrMissing: Boolean,
    val budgetStatus: BudgetStatus,
)

enum class RecoveryDeferralReason {
    USER_OPT_IN_REQUIRED,
    MANUALLY_PAUSED,
    NETWORK_UNAVAILABLE,
    RETRY_WINDOW_NOT_REACHED,
    ACTIVE_LEASE,
    BUDGET_EXHAUSTED,
    STAGE_BLOCKED,
    USER_ACTION_REQUIRED,
    TRANSITION_IN_PROGRESS,
}

sealed interface BackgroundRecoveryDecision {
    data object ResumeOneStage : BackgroundRecoveryDecision

    /** Inspect persisted intent/result state; never start a replacement paid request. */
    data object ReconcileWithoutNewRequest : BackgroundRecoveryDecision

    data class Defer(
        val reason: RecoveryDeferralReason,
    ) : BackgroundRecoveryDecision

    data object NoWork : BackgroundRecoveryDecision
}

/**
 * A scheduler may ask for one execution opportunity, but this policy remains the payment and
 * duplicate-execution gate. It never treats WorkManager state as the generation source of truth.
 */
object BackgroundRecoveryPolicy {
    fun evaluate(context: BackgroundRecoveryContext): BackgroundRecoveryDecision {
        if (context.jobStatus in TERMINAL_JOB_STATES || context.stageStatus in TERMINAL_STAGE_STATES) {
            return BackgroundRecoveryDecision.NoWork
        }
        if (context.jobStatus == GenerationJobStatus.CREATED ||
            context.stageStatus == GenerationStageStatus.PENDING
        ) {
            return BackgroundRecoveryDecision.NoWork
        }
        if (context.jobStatus in USER_ACTION_JOB_STATES) {
            return BackgroundRecoveryDecision.Defer(RecoveryDeferralReason.USER_ACTION_REQUIRED)
        }
        if (context.jobStatus in TRANSITION_JOB_STATES) {
            return BackgroundRecoveryDecision.Defer(RecoveryDeferralReason.TRANSITION_IN_PROGRESS)
        }
        if (context.stageStatus == GenerationStageStatus.BLOCKED) {
            return BackgroundRecoveryDecision.Defer(RecoveryDeferralReason.STAGE_BLOCKED)
        }
        if (context.manuallyPaused) {
            return BackgroundRecoveryDecision.Defer(RecoveryDeferralReason.MANUALLY_PAUSED)
        }
        if (!context.leaseExpiredOrMissing) {
            return BackgroundRecoveryDecision.Defer(RecoveryDeferralReason.ACTIVE_LEASE)
        }
        if (context.stageStatus in RECONCILIATION_STAGE_STATES) {
            return if (context.networkAvailable) {
                BackgroundRecoveryDecision.ReconcileWithoutNewRequest
            } else {
                BackgroundRecoveryDecision.Defer(RecoveryDeferralReason.NETWORK_UNAVAILABLE)
            }
        }
        if (!context.autoResumePaidGenerationEnabled) {
            return BackgroundRecoveryDecision.Defer(RecoveryDeferralReason.USER_OPT_IN_REQUIRED)
        }
        if (context.budgetStatus == BudgetStatus.EXHAUSTED) {
            return BackgroundRecoveryDecision.Defer(RecoveryDeferralReason.BUDGET_EXHAUSTED)
        }
        if (!context.retryWindowReached) {
            return BackgroundRecoveryDecision.Defer(RecoveryDeferralReason.RETRY_WINDOW_NOT_REACHED)
        }
        if (!context.networkAvailable) {
            return BackgroundRecoveryDecision.Defer(RecoveryDeferralReason.NETWORK_UNAVAILABLE)
        }
        return BackgroundRecoveryDecision.ResumeOneStage
    }

    private val TERMINAL_JOB_STATES = setOf(
        GenerationJobStatus.COMPLETED,
        GenerationJobStatus.STOPPED,
    )
    private val TERMINAL_STAGE_STATES = setOf(
        GenerationStageStatus.SUCCEEDED,
        GenerationStageStatus.CANCELLED,
    )
    private val USER_ACTION_JOB_STATES = setOf(
        GenerationJobStatus.NEEDS_ACTION,
        GenerationJobStatus.BLOCKED,
    )
    private val TRANSITION_JOB_STATES = setOf(
        GenerationJobStatus.PAUSING,
        GenerationJobStatus.STOPPING,
    )
    private val RECONCILIATION_STAGE_STATES = setOf(
        GenerationStageStatus.REQUEST_INTENT_RECORDED,
        GenerationStageStatus.STREAMING,
        GenerationStageStatus.VALIDATING,
        GenerationStageStatus.COMMITTING,
        GenerationStageStatus.UNKNOWN_RESULT,
        GenerationStageStatus.RECOVERY_REQUIRED,
    )
}

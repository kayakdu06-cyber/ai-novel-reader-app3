package app.zhijuan.core.task

import app.zhijuan.core.model.GenerationStageStatus
import app.zhijuan.core.model.RequestAttemptStatus

enum class RecoveryDraftEvidence {
    READABLE_EMPTY,
    CONTENT_PRESENT,
    MISSING_UNREADABLE_OR_CONFLICTING,
}

enum class ProviderRecoveryEvidence {
    NOT_AVAILABLE,
    INCONCLUSIVE,
    IN_PROGRESS,
    CONFIRMED_NOT_EXECUTED,
    COMPLETED_WITHOUT_LOCAL_OUTPUT,
}

enum class UnknownResultRecoveryDecision {
    REQUEUE_PROVEN_NOT_EXECUTED,
    RECONCILE_WITHOUT_NEW_REQUEST,
    REQUIRE_USER_RETRY_CONFIRMATION,
    RECOVER_LOCAL_RESULT_WITHOUT_NEW_REQUEST,
    NO_WORK,
}

data class UnknownResultRecoveryContext(
    val attemptStatus: RequestAttemptStatus,
    val stageStatus: GenerationStageStatus,
    val sentAtRecorded: Boolean,
    val providerRequestIdRecorded: Boolean,
    val draftEvidence: RecoveryDraftEvidence,
    val knownUsageObserved: Boolean,
    val providerEvidence: ProviderRecoveryEvidence,
)

/**
 * Decides only whether a replacement paid request is safe. An intent-only record is deliberately
 * ambiguous: the process may have opened the socket and crashed before persisting SENT.
 */
object UnknownResultRecoveryPolicy {
    fun evaluate(context: UnknownResultRecoveryContext): UnknownResultRecoveryDecision {
        if (
            context.attemptStatus == RequestAttemptStatus.SUCCEEDED &&
            context.stageStatus in LOCAL_RECOVERY_STAGES
        ) {
            return UnknownResultRecoveryDecision.RECOVER_LOCAL_RESULT_WITHOUT_NEW_REQUEST
        }
        if (context.stageStatus in TERMINAL_STAGES || context.attemptStatus in TERMINAL_NO_RECOVERY_ATTEMPTS) {
            return UnknownResultRecoveryDecision.NO_WORK
        }
        if (
            context.providerEvidence in setOf(
                ProviderRecoveryEvidence.IN_PROGRESS,
                ProviderRecoveryEvidence.INCONCLUSIVE,
            ) &&
            context.providerRequestIdRecorded
        ) {
            return UnknownResultRecoveryDecision.RECONCILE_WITHOUT_NEW_REQUEST
        }
        if (
            context.providerEvidence == ProviderRecoveryEvidence.CONFIRMED_NOT_EXECUTED &&
            context.providerRequestIdRecorded &&
            context.sentAtRecorded &&
            context.attemptStatus in PROVIDER_QUERYABLE_ATTEMPTS &&
            context.draftEvidence == RecoveryDraftEvidence.READABLE_EMPTY &&
            !context.knownUsageObserved
        ) {
            return UnknownResultRecoveryDecision.REQUEUE_PROVEN_NOT_EXECUTED
        }
        if (
            context.stageStatus in NETWORK_RECOVERY_STAGES ||
            context.attemptStatus == RequestAttemptStatus.UNKNOWN_RESULT
        ) {
            return UnknownResultRecoveryDecision.REQUIRE_USER_RETRY_CONFIRMATION
        }
        return UnknownResultRecoveryDecision.NO_WORK
    }

    private val LOCAL_RECOVERY_STAGES = setOf(
        GenerationStageStatus.VALIDATING,
        GenerationStageStatus.COMMITTING,
        GenerationStageStatus.RECOVERY_REQUIRED,
    )
    private val NETWORK_RECOVERY_STAGES = setOf(
        GenerationStageStatus.REQUEST_INTENT_RECORDED,
        GenerationStageStatus.STREAMING,
        GenerationStageStatus.RECOVERY_REQUIRED,
        GenerationStageStatus.UNKNOWN_RESULT,
    )
    private val PROVIDER_QUERYABLE_ATTEMPTS = setOf(
        RequestAttemptStatus.SENT,
        RequestAttemptStatus.STREAMING,
    )
    private val TERMINAL_STAGES = setOf(
        GenerationStageStatus.SUCCEEDED,
        GenerationStageStatus.CANCELLED,
    )
    private val TERMINAL_NO_RECOVERY_ATTEMPTS = setOf(
        RequestAttemptStatus.FAILED_RETRYABLE,
        RequestAttemptStatus.FAILED_FINAL,
        RequestAttemptStatus.REFUSED,
        RequestAttemptStatus.CANCELLED,
    )
}

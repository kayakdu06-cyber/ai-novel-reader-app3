package app.zhijuan.core.task

import app.zhijuan.core.model.BudgetStatus
import app.zhijuan.core.model.FailureRequestState
import app.zhijuan.core.model.StandardErrorCode

enum class RetryCondition {
    NETWORK_AVAILABLE,
}

enum class RepairKind {
    REDUCE_CONTEXT,
    REPAIR_FORMAT,
}

enum class RetryConfirmationReason {
    REQUEST_MAY_HAVE_INCURRED_COST,
    CONTENT_ALREADY_RECEIVED,
    AUTOMATIC_RETRY_LIMIT_REACHED,
    RETRY_WAIT_LIMIT_REACHED,
    REPAIR_LIMIT_REACHED,
}

sealed interface ExternalRequestRetryDecision {
    data class WaitForCondition(val condition: RetryCondition) : ExternalRequestRetryDecision

    data class RetryAfter(
        val delayMillis: Long,
        val mayHaveIncurredCost: Boolean,
    ) : ExternalRequestRetryDecision {
        init {
            require(delayMillis >= 0)
        }
    }

    data class RepairOnce(
        val kind: RepairKind,
        val mayHaveIncurredCost: Boolean,
    ) : ExternalRequestRetryDecision

    data object ContinueOutput : ExternalRequestRetryDecision

    data class RequireUserConfirmation(
        val reason: RetryConfirmationReason,
    ) : ExternalRequestRetryDecision

    data class Stop(val code: StandardErrorCode) : ExternalRequestRetryDecision
}

data class ExternalRequestRetryContext(
    val code: StandardErrorCode,
    val requestState: FailureRequestState,
    val contentObserved: Boolean,
    val automaticRetriesUsed: Int,
    val repairAttemptsUsed: Int,
    val retryWaitRemainingMillis: Long,
    val retryAfterMillis: Long? = null,
    val budgetStatus: BudgetStatus,
    val jitterPermille: Int = 1_000,
) {
    init {
        require(automaticRetriesUsed >= 0)
        require(repairAttemptsUsed >= 0)
        require(retryWaitRemainingMillis in 0..ExternalRequestRetryPolicy.MAXIMUM_RETRY_WAIT_MILLIS)
        require(retryAfterMillis == null || retryAfterMillis >= 0)
        require(jitterPermille in 500..1_000)
    }
}

object ExternalRequestRetryPolicy {
    const val MAXIMUM_AUTOMATIC_RETRIES = 3
    const val MAXIMUM_RETRY_WAIT_MILLIS = 15L * 60 * 1_000
    private const val MAXIMUM_SINGLE_BACKOFF_MILLIS = 60_000L

    fun evaluate(context: ExternalRequestRetryContext): ExternalRequestRetryDecision {
        if (context.budgetStatus == BudgetStatus.EXHAUSTED) {
            return ExternalRequestRetryDecision.Stop(StandardErrorCode.BUDGET_EXCEEDED)
        }
        if (context.contentObserved && context.code != StandardErrorCode.OUTPUT_TRUNCATED) {
            return ExternalRequestRetryDecision.RequireUserConfirmation(
                RetryConfirmationReason.CONTENT_ALREADY_RECEIVED,
            )
        }
        return when (context.code) {
            StandardErrorCode.NETWORK_OFFLINE -> networkOffline(context)
            StandardErrorCode.DNS_FAILED -> retryOnlyWhenNotSent(context, baseDelayMillis = 1_000)
            StandardErrorCode.RATE_LIMITED -> retryOnlyWhenRejected(context, baseDelayMillis = 2_000)
            StandardErrorCode.SERVER_OVERLOADED -> retryOnlyWhenRejected(context, baseDelayMillis = 2_000)
            StandardErrorCode.STREAM_INTERRUPTED -> boundedRetry(context, baseDelayMillis = 1_000)
            StandardErrorCode.CONTEXT_TOO_LARGE -> repair(context, RepairKind.REDUCE_CONTEXT)
            StandardErrorCode.FORMAT_INVALID -> repair(context, RepairKind.REPAIR_FORMAT)
            StandardErrorCode.OUTPUT_TRUNCATED -> ExternalRequestRetryDecision.ContinueOutput
            StandardErrorCode.TLS_FAILED,
            StandardErrorCode.AUTH_FAILED,
            StandardErrorCode.MODEL_NOT_FOUND,
            StandardErrorCode.PROTOCOL_MISMATCH,
            StandardErrorCode.QUOTA_EXHAUSTED,
            StandardErrorCode.POLICY_REFUSAL,
            StandardErrorCode.BUDGET_EXCEEDED,
            StandardErrorCode.CREDENTIAL_UNAVAILABLE,
            -> ExternalRequestRetryDecision.Stop(context.code)
            StandardErrorCode.UNKNOWN_RESULT -> requiresCostConfirmation()
        }
    }

    private fun networkOffline(context: ExternalRequestRetryContext): ExternalRequestRetryDecision =
        if (context.requestState == FailureRequestState.NOT_SENT) {
            ExternalRequestRetryDecision.WaitForCondition(RetryCondition.NETWORK_AVAILABLE)
        } else {
            requiresCostConfirmation()
        }

    private fun retryOnlyWhenNotSent(
        context: ExternalRequestRetryContext,
        baseDelayMillis: Long,
    ): ExternalRequestRetryDecision = if (context.requestState == FailureRequestState.NOT_SENT) {
        boundedRetry(context, baseDelayMillis)
    } else {
        requiresCostConfirmation()
    }

    private fun retryOnlyWhenRejected(
        context: ExternalRequestRetryContext,
        baseDelayMillis: Long,
    ): ExternalRequestRetryDecision = if (context.requestState == FailureRequestState.PROVIDER_REJECTED) {
        boundedRetry(context, baseDelayMillis)
    } else {
        requiresCostConfirmation()
    }

    private fun boundedRetry(
        context: ExternalRequestRetryContext,
        baseDelayMillis: Long,
    ): ExternalRequestRetryDecision {
        if (context.automaticRetriesUsed >= MAXIMUM_AUTOMATIC_RETRIES) {
            return ExternalRequestRetryDecision.RequireUserConfirmation(
                RetryConfirmationReason.AUTOMATIC_RETRY_LIMIT_REACHED,
            )
        }
        val exponential = exponentialDelay(baseDelayMillis, context.automaticRetriesUsed)
        val jittered = exponential * context.jitterPermille / 1_000
        val delay = maxOf(jittered, context.retryAfterMillis ?: 0)
        if (delay > context.retryWaitRemainingMillis) {
            return ExternalRequestRetryDecision.RequireUserConfirmation(
                RetryConfirmationReason.RETRY_WAIT_LIMIT_REACHED,
            )
        }
        return ExternalRequestRetryDecision.RetryAfter(
            delayMillis = delay,
            mayHaveIncurredCost = context.requestState != FailureRequestState.NOT_SENT &&
                context.requestState != FailureRequestState.PROVIDER_REJECTED,
        )
    }

    private fun repair(
        context: ExternalRequestRetryContext,
        kind: RepairKind,
    ): ExternalRequestRetryDecision {
        if (context.repairAttemptsUsed >= 1) {
            return ExternalRequestRetryDecision.RequireUserConfirmation(
                RetryConfirmationReason.REPAIR_LIMIT_REACHED,
            )
        }
        return ExternalRequestRetryDecision.RepairOnce(
            kind = kind,
            mayHaveIncurredCost = context.requestState != FailureRequestState.NOT_SENT &&
                context.requestState != FailureRequestState.PROVIDER_REJECTED,
        )
    }

    private fun exponentialDelay(baseDelayMillis: Long, retriesUsed: Int): Long {
        val shift = retriesUsed.coerceAtMost(30)
        val multiplier = 1L shl shift
        return runCatching { Math.multiplyExact(baseDelayMillis, multiplier) }
            .getOrDefault(MAXIMUM_SINGLE_BACKOFF_MILLIS)
            .coerceAtMost(MAXIMUM_SINGLE_BACKOFF_MILLIS)
    }

    private fun requiresCostConfirmation(): ExternalRequestRetryDecision =
        ExternalRequestRetryDecision.RequireUserConfirmation(
            RetryConfirmationReason.REQUEST_MAY_HAVE_INCURRED_COST,
        )
}

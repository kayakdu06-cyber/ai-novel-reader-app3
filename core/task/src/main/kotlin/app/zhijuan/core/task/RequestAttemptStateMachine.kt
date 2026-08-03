package app.zhijuan.core.task

import app.zhijuan.core.model.RequestAttemptStatus

enum class AttemptEvent {
    REQUEST_SENT,
    STREAM_STARTED,
    RESPONSE_COMPLETED,
    RETRYABLE_FAILURE,
    FINAL_FAILURE,
    POLICY_REFUSED,
    CANCELLED,
    RESULT_UNCERTAIN,
}

object RequestAttemptStateMachine {
    private val transitions = buildMap {
        put(RequestAttemptStatus.INTENT_RECORDED to AttemptEvent.REQUEST_SENT, RequestAttemptStatus.SENT)
        put(RequestAttemptStatus.INTENT_RECORDED to AttemptEvent.RESULT_UNCERTAIN, RequestAttemptStatus.UNKNOWN_RESULT)
        put(RequestAttemptStatus.INTENT_RECORDED to AttemptEvent.CANCELLED, RequestAttemptStatus.CANCELLED)

        put(RequestAttemptStatus.SENT to AttemptEvent.STREAM_STARTED, RequestAttemptStatus.STREAMING)
        put(RequestAttemptStatus.SENT to AttemptEvent.RESPONSE_COMPLETED, RequestAttemptStatus.SUCCEEDED)
        put(RequestAttemptStatus.SENT to AttemptEvent.RETRYABLE_FAILURE, RequestAttemptStatus.FAILED_RETRYABLE)
        put(RequestAttemptStatus.SENT to AttemptEvent.FINAL_FAILURE, RequestAttemptStatus.FAILED_FINAL)
        put(RequestAttemptStatus.SENT to AttemptEvent.POLICY_REFUSED, RequestAttemptStatus.REFUSED)
        put(RequestAttemptStatus.SENT to AttemptEvent.CANCELLED, RequestAttemptStatus.CANCELLED)
        put(RequestAttemptStatus.SENT to AttemptEvent.RESULT_UNCERTAIN, RequestAttemptStatus.UNKNOWN_RESULT)

        put(RequestAttemptStatus.STREAMING to AttemptEvent.RESPONSE_COMPLETED, RequestAttemptStatus.SUCCEEDED)
        put(RequestAttemptStatus.STREAMING to AttemptEvent.RETRYABLE_FAILURE, RequestAttemptStatus.FAILED_RETRYABLE)
        put(RequestAttemptStatus.STREAMING to AttemptEvent.FINAL_FAILURE, RequestAttemptStatus.FAILED_FINAL)
        put(RequestAttemptStatus.STREAMING to AttemptEvent.POLICY_REFUSED, RequestAttemptStatus.REFUSED)
        put(RequestAttemptStatus.STREAMING to AttemptEvent.CANCELLED, RequestAttemptStatus.CANCELLED)
        put(RequestAttemptStatus.STREAMING to AttemptEvent.RESULT_UNCERTAIN, RequestAttemptStatus.UNKNOWN_RESULT)
    }

    fun transition(
        current: RequestAttemptStatus,
        event: AttemptEvent,
    ): RequestAttemptStatus = transitions[current to event]
        ?: throw IllegalStateTransition("Attempt cannot handle $event while in $current.")
}

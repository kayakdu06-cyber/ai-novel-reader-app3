package app.zhijuan.core.task

import app.zhijuan.core.model.RequestAttemptStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class RequestAttemptStateMachineTest {
    @Test
    fun streamingRequestCanCompleteOrBecomeUnknown() {
        assertEquals(
            RequestAttemptStatus.SUCCEEDED,
            RequestAttemptStateMachine.transition(
                RequestAttemptStatus.STREAMING,
                AttemptEvent.RESPONSE_COMPLETED,
            ),
        )
        assertEquals(
            RequestAttemptStatus.UNKNOWN_RESULT,
            RequestAttemptStateMachine.transition(
                RequestAttemptStatus.STREAMING,
                AttemptEvent.RESULT_UNCERTAIN,
            ),
        )
    }

    @Test
    fun intentRecordedAttemptFailsRetryableWhenTheDailyBudgetPeriodExpired() {
        assertEquals(
            RequestAttemptStatus.FAILED_RETRYABLE,
            RequestAttemptStateMachine.transition(
                RequestAttemptStatus.INTENT_RECORDED,
                AttemptEvent.DAILY_BUDGET_PERIOD_EXPIRED,
            ),
        )
    }

    @Test
    fun terminalAttemptCannotBeRestarted() {
        RequestAttemptStatus.entries
            .filter {
                it in setOf(
                    RequestAttemptStatus.SUCCEEDED,
                    RequestAttemptStatus.FAILED_RETRYABLE,
                    RequestAttemptStatus.FAILED_FINAL,
                    RequestAttemptStatus.REFUSED,
                    RequestAttemptStatus.CANCELLED,
                    RequestAttemptStatus.UNKNOWN_RESULT,
                )
            }
            .forEach { terminal ->
                assertThrows(IllegalStateTransition::class.java) {
                    RequestAttemptStateMachine.transition(terminal, AttemptEvent.REQUEST_SENT)
                }
            }
    }
}

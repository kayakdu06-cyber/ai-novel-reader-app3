package app.zhijuan.core.task

import app.zhijuan.core.model.GenerationStageStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class GenerationStageStateMachineTest {
    @Test
    fun `every legal and illegal stage transition is explicit`() {
        val legal = mapOf(
            (GenerationStageStatus.PENDING to StageEvent.DEPENDENCIES_SATISFIED) to GenerationStageStatus.READY,
            (GenerationStageStatus.PENDING to StageEvent.PARENT_STOPPED) to GenerationStageStatus.CANCELLED,
            (GenerationStageStatus.READY to StageEvent.LEASE_ACQUIRED) to GenerationStageStatus.PREPARING,
            (GenerationStageStatus.READY to StageEvent.PARENT_STOPPED) to GenerationStageStatus.CANCELLED,
            (GenerationStageStatus.PREPARING to StageEvent.PRECONDITION_BLOCKED) to GenerationStageStatus.BLOCKED,
            (GenerationStageStatus.PREPARING to StageEvent.LOCAL_OUTPUT_READY) to GenerationStageStatus.COMMITTING,
            (GenerationStageStatus.PREPARING to StageEvent.LEASE_EXPIRED_BEFORE_REQUEST) to GenerationStageStatus.READY,
            (GenerationStageStatus.PREPARING to StageEvent.INPUT_FROZEN) to GenerationStageStatus.REQUEST_INTENT_RECORDED,
            (GenerationStageStatus.PREPARING to StageEvent.PAUSE_AT_SAFE_POINT) to GenerationStageStatus.READY,
            (GenerationStageStatus.PREPARING to StageEvent.PARENT_STOPPED) to GenerationStageStatus.CANCELLED,
            (GenerationStageStatus.BLOCKED to StageEvent.PARENT_STOPPED) to GenerationStageStatus.CANCELLED,
            (GenerationStageStatus.REQUEST_INTENT_RECORDED to StageEvent.REQUEST_SENT) to GenerationStageStatus.STREAMING,
            (GenerationStageStatus.REQUEST_INTENT_RECORDED to StageEvent.RESULT_UNCERTAIN) to GenerationStageStatus.UNKNOWN_RESULT,
            (GenerationStageStatus.REQUEST_INTENT_RECORDED to StageEvent.DAILY_BUDGET_PERIOD_EXPIRED_BEFORE_SEND) to GenerationStageStatus.READY,
            (GenerationStageStatus.REQUEST_INTENT_RECORDED to StageEvent.DAILY_BUDGET_ATTEMPTS_EXHAUSTED_BEFORE_SEND) to GenerationStageStatus.NEEDS_ACTION,
            (GenerationStageStatus.REQUEST_INTENT_RECORDED to StageEvent.RECOVERY_AUDIT_REQUIRED) to GenerationStageStatus.RECOVERY_REQUIRED,
            (GenerationStageStatus.REQUEST_INTENT_RECORDED to StageEvent.PAUSE_AT_SAFE_POINT) to GenerationStageStatus.READY,
            (GenerationStageStatus.REQUEST_INTENT_RECORDED to StageEvent.PARENT_STOPPED) to GenerationStageStatus.CANCELLED,
            (GenerationStageStatus.STREAMING to StageEvent.RESPONSE_COMPLETED) to GenerationStageStatus.VALIDATING,
            (GenerationStageStatus.STREAMING to StageEvent.RETRYABLE_FAILURE) to GenerationStageStatus.RETRY_WAIT,
            (GenerationStageStatus.STREAMING to StageEvent.RESULT_UNCERTAIN) to GenerationStageStatus.UNKNOWN_RESULT,
            (GenerationStageStatus.STREAMING to StageEvent.RECOVERY_AUDIT_REQUIRED) to GenerationStageStatus.RECOVERY_REQUIRED,
            (GenerationStageStatus.STREAMING to StageEvent.PROVIDER_CONFIRMED_NOT_EXECUTED) to GenerationStageStatus.READY,
            (GenerationStageStatus.STREAMING to StageEvent.PAUSE_AT_SAFE_POINT) to GenerationStageStatus.READY,
            (GenerationStageStatus.STREAMING to StageEvent.PARENT_STOPPED) to GenerationStageStatus.CANCELLED,
            (GenerationStageStatus.VALIDATING to StageEvent.OUTPUT_VALID) to GenerationStageStatus.COMMITTING,
            (GenerationStageStatus.VALIDATING to StageEvent.RETRYABLE_FAILURE) to GenerationStageStatus.RETRY_WAIT,
            (GenerationStageStatus.VALIDATING to StageEvent.USER_ACTION_REQUIRED) to GenerationStageStatus.NEEDS_ACTION,
            (GenerationStageStatus.VALIDATING to StageEvent.RECOVERY_AUDIT_REQUIRED) to GenerationStageStatus.RECOVERY_REQUIRED,
            (GenerationStageStatus.VALIDATING to StageEvent.PARENT_STOPPED) to GenerationStageStatus.CANCELLED,
            (GenerationStageStatus.COMMITTING to StageEvent.COMMIT_SUCCEEDED) to GenerationStageStatus.SUCCEEDED,
            (GenerationStageStatus.COMMITTING to StageEvent.USER_ACTION_REQUIRED) to GenerationStageStatus.NEEDS_ACTION,
            (GenerationStageStatus.COMMITTING to StageEvent.COMMIT_UNCERTAIN) to GenerationStageStatus.RECOVERY_REQUIRED,
            (GenerationStageStatus.COMMITTING to StageEvent.RECOVERY_AUDIT_REQUIRED) to GenerationStageStatus.RECOVERY_REQUIRED,
            (GenerationStageStatus.COMMITTING to StageEvent.PARENT_STOPPED) to GenerationStageStatus.CANCELLED,
            (GenerationStageStatus.RETRY_WAIT to StageEvent.RETRY_DELAY_ELAPSED) to GenerationStageStatus.READY,
            (GenerationStageStatus.RETRY_WAIT to StageEvent.PARENT_STOPPED) to GenerationStageStatus.CANCELLED,
            (GenerationStageStatus.BLOCKED to StageEvent.CONDITION_RECOVERED) to GenerationStageStatus.READY,
            (GenerationStageStatus.NEEDS_ACTION to StageEvent.ISSUE_RESOLVED) to GenerationStageStatus.READY,
            (GenerationStageStatus.NEEDS_ACTION to StageEvent.PARENT_STOPPED) to GenerationStageStatus.CANCELLED,
            (GenerationStageStatus.UNKNOWN_RESULT to StageEvent.USER_CONFIRMED_RETRY) to GenerationStageStatus.READY,
            (GenerationStageStatus.UNKNOWN_RESULT to StageEvent.USER_CANCELLED) to GenerationStageStatus.CANCELLED,
            (GenerationStageStatus.UNKNOWN_RESULT to StageEvent.PARENT_STOPPED) to GenerationStageStatus.CANCELLED,
            (GenerationStageStatus.RECOVERY_REQUIRED to StageEvent.PARENT_STOPPED) to GenerationStageStatus.CANCELLED,
            (GenerationStageStatus.RECOVERY_REQUIRED to StageEvent.RESULT_UNCERTAIN) to GenerationStageStatus.UNKNOWN_RESULT,
            (GenerationStageStatus.RECOVERY_REQUIRED to StageEvent.PROVIDER_CONFIRMED_NOT_EXECUTED) to GenerationStageStatus.READY,
        )

        GenerationStageStatus.entries.forEach { status ->
            StageEvent.entries.forEach { event ->
                val expected = legal[status to event]
                if (expected == null) {
                    assertThrows(IllegalStateTransition::class.java) {
                        GenerationStageStateMachine.transition(status, event)
                    }
                } else {
                    assertEquals(expected, GenerationStageStateMachine.transition(status, event))
                }
            }
        }
    }

    @Test
    fun `successful stage must validate and commit`() {
        var state = GenerationStageStatus.PENDING
        state = GenerationStageStateMachine.transition(state, StageEvent.DEPENDENCIES_SATISFIED)
        state = GenerationStageStateMachine.transition(state, StageEvent.LEASE_ACQUIRED)
        state = GenerationStageStateMachine.transition(state, StageEvent.INPUT_FROZEN)
        state = GenerationStageStateMachine.transition(state, StageEvent.REQUEST_SENT)
        state = GenerationStageStateMachine.transition(state, StageEvent.RESPONSE_COMPLETED)
        state = GenerationStageStateMachine.transition(state, StageEvent.OUTPUT_VALID)
        state = GenerationStageStateMachine.transition(state, StageEvent.COMMIT_SUCCEEDED)
        assertEquals(GenerationStageStatus.SUCCEEDED, state)
    }

    @Test
    fun `local stage reaches commit without creating a remote request state`() {
        val committing = GenerationStageStateMachine.transition(
            GenerationStageStatus.PREPARING,
            StageEvent.LOCAL_OUTPUT_READY,
        )
        assertEquals(GenerationStageStatus.COMMITTING, committing)
        assertEquals(
            GenerationStageStatus.SUCCEEDED,
            GenerationStageStateMachine.transition(committing, StageEvent.COMMIT_SUCCEEDED),
        )
    }

    @Test
    fun `unknown result requires explicit user decision`() {
        val unknown = GenerationStageStateMachine.transition(
            GenerationStageStatus.STREAMING,
            StageEvent.RESULT_UNCERTAIN,
        )
        assertEquals(GenerationStageStatus.UNKNOWN_RESULT, unknown)
        assertThrows(IllegalStateTransition::class.java) {
            GenerationStageStateMachine.transition(unknown, StageEvent.RETRY_DELAY_ELAPSED)
        }
        assertEquals(
            GenerationStageStatus.READY,
            GenerationStageStateMachine.transition(unknown, StageEvent.USER_CONFIRMED_RETRY),
        )
    }

    @Test
    fun `stream cannot skip validation`() {
        assertThrows(IllegalStateTransition::class.java) {
            GenerationStageStateMachine.transition(
                GenerationStageStatus.STREAMING,
                StageEvent.COMMIT_SUCCEEDED,
            )
        }
    }
}

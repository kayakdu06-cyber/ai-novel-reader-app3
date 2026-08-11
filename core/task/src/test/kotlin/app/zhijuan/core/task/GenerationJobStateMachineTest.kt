package app.zhijuan.core.task

import app.zhijuan.core.model.GenerationJobStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class GenerationJobStateMachineTest {
    @Test
    fun `every legal and illegal job transition is explicit`() {
        val legal = mapOf(
            (GenerationJobStatus.CREATED to JobEvent.VALIDATION_PASSED) to GenerationJobStatus.READY,
            (GenerationJobStatus.CREATED to JobEvent.BLOCKED_BY_CONFIGURATION) to GenerationJobStatus.BLOCKED,
            (GenerationJobStatus.CREATED to JobEvent.STOP_REQUESTED) to GenerationJobStatus.STOPPED,
            (GenerationJobStatus.READY to JobEvent.LEASE_ACQUIRED) to GenerationJobStatus.RUNNING,
            (GenerationJobStatus.READY to JobEvent.PAUSE_REQUESTED) to GenerationJobStatus.PAUSED,
            (GenerationJobStatus.READY to JobEvent.STOP_REQUESTED) to GenerationJobStatus.STOPPED,
            (GenerationJobStatus.RUNNING to JobEvent.PAUSE_REQUESTED) to GenerationJobStatus.PAUSING,
            (GenerationJobStatus.RUNNING to JobEvent.AUTO_PAUSED) to GenerationJobStatus.PAUSED,
            (GenerationJobStatus.PAUSING to JobEvent.SAFE_POINT_REACHED) to GenerationJobStatus.PAUSED,
            (GenerationJobStatus.PAUSING to JobEvent.ALL_STAGES_COMPLETED) to GenerationJobStatus.COMPLETED,
            (GenerationJobStatus.RUNNING to JobEvent.USER_ACTION_REQUIRED) to GenerationJobStatus.NEEDS_ACTION,
            (GenerationJobStatus.RUNNING to JobEvent.DAILY_BUDGET_ROLLOVER_COMPLETED) to GenerationJobStatus.READY,
            (GenerationJobStatus.RUNNING to JobEvent.ALL_STAGES_COMPLETED) to GenerationJobStatus.COMPLETED,
            (GenerationJobStatus.RUNNING to JobEvent.RECOVERY_REQUEUED) to GenerationJobStatus.READY,
            (GenerationJobStatus.RUNNING to JobEvent.STOP_REQUESTED) to GenerationJobStatus.STOPPING,
            (GenerationJobStatus.PAUSING to JobEvent.STOP_REQUESTED) to GenerationJobStatus.STOPPING,
            (GenerationJobStatus.STOPPING to JobEvent.SAFE_POINT_REACHED) to GenerationJobStatus.STOPPED,
            (GenerationJobStatus.PAUSED to JobEvent.RESUME_APPROVED) to GenerationJobStatus.READY,
            (GenerationJobStatus.PAUSED to JobEvent.STOP_REQUESTED) to GenerationJobStatus.STOPPED,
            (GenerationJobStatus.NEEDS_ACTION to JobEvent.ISSUE_RESOLVED) to GenerationJobStatus.READY,
            (GenerationJobStatus.NEEDS_ACTION to JobEvent.STOP_REQUESTED) to GenerationJobStatus.STOPPED,
            (GenerationJobStatus.BLOCKED to JobEvent.ISSUE_RESOLVED) to GenerationJobStatus.READY,
            (GenerationJobStatus.BLOCKED to JobEvent.STOP_REQUESTED) to GenerationJobStatus.STOPPED,
        )

        GenerationJobStatus.entries.forEach { status ->
            JobEvent.entries.forEach { event ->
                val expected = legal[status to event]
                if (expected == null) {
                    assertThrows(IllegalStateTransition::class.java) {
                        GenerationJobStateMachine.transition(status, event)
                    }
                } else {
                    assertEquals(expected, GenerationJobStateMachine.transition(status, event))
                }
            }
        }
    }

    @Test
    fun `normal job lifecycle reaches completed`() {
        var state = GenerationJobStatus.CREATED
        state = GenerationJobStateMachine.transition(state, JobEvent.VALIDATION_PASSED)
        state = GenerationJobStateMachine.transition(state, JobEvent.LEASE_ACQUIRED)
        state = GenerationJobStateMachine.transition(state, JobEvent.ALL_STAGES_COMPLETED)
        assertEquals(GenerationJobStatus.COMPLETED, state)
    }

    @Test
    fun `pause waits for a safe point`() {
        var state = GenerationJobStatus.RUNNING
        state = GenerationJobStateMachine.transition(state, JobEvent.PAUSE_REQUESTED)
        assertEquals(GenerationJobStatus.PAUSING, state)
        state = GenerationJobStateMachine.transition(state, JobEvent.SAFE_POINT_REACHED)
        assertEquals(GenerationJobStatus.PAUSED, state)
    }

    @Test
    fun `terminal job cannot be restarted`() {
        assertThrows(IllegalStateTransition::class.java) {
            GenerationJobStateMachine.transition(
                GenerationJobStatus.COMPLETED,
                JobEvent.RESUME_APPROVED,
            )
        }
    }

    @Test
    fun `stop supersedes an in-progress pause`() {
        val pausing = GenerationJobStateMachine.transition(
            GenerationJobStatus.RUNNING,
            JobEvent.PAUSE_REQUESTED,
        )
        val stopping = GenerationJobStateMachine.transition(pausing, JobEvent.STOP_REQUESTED)
        assertEquals(
            GenerationJobStatus.STOPPED,
            GenerationJobStateMachine.transition(stopping, JobEvent.SAFE_POINT_REACHED),
        )
    }
}

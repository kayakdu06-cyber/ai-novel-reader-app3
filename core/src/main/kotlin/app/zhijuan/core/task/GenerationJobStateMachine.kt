package app.zhijuan.core.task

import app.zhijuan.core.model.GenerationJobStatus

enum class JobEvent {
    VALIDATION_PASSED,
    BLOCKED_BY_CONFIGURATION,
    LEASE_ACQUIRED,
    PAUSE_REQUESTED,
    AUTO_PAUSED,
    SAFE_POINT_REACHED,
    USER_ACTION_REQUIRED,
    ALL_STAGES_COMPLETED,
    STOP_REQUESTED,
    RESUME_APPROVED,
    ISSUE_RESOLVED,
    RECOVERY_REQUEUED,
    DAILY_BUDGET_ROLLOVER_COMPLETED,
}

object GenerationJobStateMachine {
    private val transitions = mapOf(
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

    fun transition(
        current: GenerationJobStatus,
        event: JobEvent,
    ): GenerationJobStatus = transitions[current to event]
        ?: throw IllegalStateTransition("Job cannot handle $event while in $current.")
}

class IllegalStateTransition(message: String) : IllegalStateException(message)

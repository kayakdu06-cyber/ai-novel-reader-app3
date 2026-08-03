package app.zhijuan.core.model

enum class GenerationJobStatus {
    CREATED,
    READY,
    RUNNING,
    PAUSING,
    PAUSED,
    NEEDS_ACTION,
    BLOCKED,
    STOPPING,
    STOPPED,
    COMPLETED,
}

enum class GenerationStageStatus {
    PENDING,
    READY,
    PREPARING,
    BLOCKED,
    REQUEST_INTENT_RECORDED,
    STREAMING,
    VALIDATING,
    COMMITTING,
    RETRY_WAIT,
    UNKNOWN_RESULT,
    NEEDS_ACTION,
    RECOVERY_REQUIRED,
    SUCCEEDED,
    CANCELLED,
}

enum class RequestAttemptStatus {
    INTENT_RECORDED,
    SENT,
    STREAMING,
    SUCCEEDED,
    FAILED_RETRYABLE,
    FAILED_FINAL,
    REFUSED,
    CANCELLED,
    UNKNOWN_RESULT,
}

enum class BudgetStatus {
    AVAILABLE,
    WARNING,
    EXHAUSTED,
}

enum class GenerationJobType {
    CREATE_BOOK,
    CONTINUE_BOOK,
    REGENERATE_CHAPTER,
    REVISE_CHAPTER,
    REBUILD_MEMORY,
}

enum class GenerationTargetType {
    BOOK,
    CHAPTER,
    STORY_BIBLE,
    OUTLINE,
    MEMORY,
}

enum class UsageSource {
    UNKNOWN,
    ESTIMATED,
    PROVIDER_REPORTED,
}

enum class UsageLedgerStatus {
    PROVISIONAL,
    FINAL,
}

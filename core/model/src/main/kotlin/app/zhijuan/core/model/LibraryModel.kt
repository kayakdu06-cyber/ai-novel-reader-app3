package app.zhijuan.core.model

enum class BookStatus {
    DRAFT,
    GENERATING,
    PAUSED,
    COMPLETED,
    ARCHIVED,
    ERROR,
}

enum class BookLengthMode {
    SHORT,
    MEDIUM,
    LONG,
}

enum class TitleSource {
    USER,
    AI,
    SYSTEM_INFERRED,
}

enum class ChapterStatus {
    PLANNED,
    GENERATING,
    DRAFT_READY,
    CHECKING,
    REVISING,
    READY,
    ERROR,
    EDITED,
    CONSISTENCY_UNKNOWN,
}

enum class ConsistencyStatus {
    VALID,
    UNKNOWN,
    ISSUES,
}

enum class ChapterVersionSource {
    AI_GENERATED,
    USER_EDIT,
    IMPORTED,
    RECOVERED,
}

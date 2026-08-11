package app.zhijuan.core.model

enum class RevisionSource {
    USER,
    AI_GENERATED,
    IMPORTED,
    MIGRATED,
}

enum class OutlineNodeType {
    BOOK,
    ARC,
    CHAPTER,
}

enum class DerivedDataStatus {
    VALID,
    STALE,
    FAILED,
}

enum class StoryEntityType {
    CHARACTER,
    LOCATION,
    ITEM,
    ORGANIZATION,
    CONCEPT,
}

enum class AdultStatus {
    CONFIRMED_ADULT,
    UNKNOWN,
    NOT_ADULT,
    NOT_APPLICABLE,
}

enum class CanonLevel {
    HARD_CANON,
    STORY_CANON,
    PLAN_ONLY,
    INFERRED,
}

enum class ForeshadowStatus {
    PLANNED,
    PLANTED,
    DEVELOPING,
    RESOLVED,
    ABANDONED,
}

enum class MemorySource {
    USER,
    STORY_BIBLE,
    OUTLINE,
    CHAPTER_EXTRACTION,
    CONSISTENCY_REPAIR,
}

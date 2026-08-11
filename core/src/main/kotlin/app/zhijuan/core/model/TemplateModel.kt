package app.zhijuan.core.model

enum class TemplateOriginType {
    SYSTEM_PRESET,
    USER_CREATED,
    BOOK_DERIVED,
    TEMPLATE_FORK,
    IMPORTED,
}

enum class TemplateUseMode {
    ALL_SETTINGS,
    STYLE_ONLY,
}

enum class TemplateTagDimension {
    GENRE,
    STORY_MECHANISM,
    MOOD,
    PACING,
    LENGTH,
    PRESENTATION,
    USER_DEFINED,
}

enum class TemplateTagSource {
    SYSTEM_DERIVED,
    AI_INFERRED,
    USER,
}

package app.zhijuan.core.model

object BookLengthPolicy {
    const val SCHEMA_VERSION = 1
    const val SHORT_MINIMUM_CHAPTERS = 80
    const val MEDIUM_MINIMUM_CHAPTERS = 300
    const val LONG_MINIMUM_CHAPTERS = 301
    const val MAXIMUM_TARGET_CHAPTERS = 10_000

    fun minimumChapterCount(mode: BookLengthMode): Int = when (mode) {
        BookLengthMode.SHORT -> SHORT_MINIMUM_CHAPTERS
        BookLengthMode.MEDIUM -> MEDIUM_MINIMUM_CHAPTERS
        BookLengthMode.LONG -> LONG_MINIMUM_CHAPTERS
    }

    fun targetChapterCount(mode: BookLengthMode, customLongTarget: Int?): Int? = when (mode) {
        BookLengthMode.SHORT -> SHORT_MINIMUM_CHAPTERS
        BookLengthMode.MEDIUM -> MEDIUM_MINIMUM_CHAPTERS
        BookLengthMode.LONG -> customLongTarget?.takeIf {
            it in LONG_MINIMUM_CHAPTERS..MAXIMUM_TARGET_CHAPTERS
        }
    }

    fun requireValidSelection(
        mode: BookLengthMode,
        minimumChapterCount: Int,
        targetChapterCount: Int,
        schemaVersion: Int,
    ) {
        require(schemaVersion == SCHEMA_VERSION) { "Unsupported length policy schema." }
        require(minimumChapterCount == minimumChapterCount(mode)) {
            "Minimum chapter count does not match the selected length mode."
        }
        require(targetChapterCount in minimumChapterCount..MAXIMUM_TARGET_CHAPTERS) {
            "Target chapter count is outside the supported range."
        }
    }
}

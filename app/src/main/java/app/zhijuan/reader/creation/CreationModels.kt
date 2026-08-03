package app.zhijuan.reader.creation

import app.zhijuan.core.model.BookLengthMode
import app.zhijuan.core.model.BookPresentationPreset
import app.zhijuan.core.model.ContentPresentationDirective

data class GenreOption(
    val id: String,
    val label: String,
)

data class CreationOptionCatalog(
    val schemaVersion: Int,
    val quickGenreIds: Set<String>,
    val genres: List<GenreOption>,
)

data class AdvancedCreationDetails(
    val charactersAndRelationships: String = "",
    val worldAndBackground: String = "",
    val narrativeAndStyle: String = "",
    val requiredElements: String = "",
    val excludedElements: String = "",
) {
    val providedFieldCount: Int
        get() = listOf(
            charactersAndRelationships,
            worldAndBackground,
            narrativeAndStyle,
            requiredElements,
            excludedElements,
        ).count(String::isNotBlank)

}

data class MinimalBookDraft(
    val storyIdea: String,
    val genreId: String?,
    val lengthMode: BookLengthMode,
    val minimumChapterCount: Int,
    val targetChapterCount: Int,
    val lengthPolicySchemaVersion: Int,
    val presentationDirective: ContentPresentationDirective,
    val optionCatalogSchemaVersion: Int,
    val advancedDetails: AdvancedCreationDetails = AdvancedCreationDetails(),
) {
    val presentationPreset: BookPresentationPreset
        get() = presentationDirective.preset
}

object DefaultCreationOptionCatalog {
    val value = CreationOptionCatalog(
        schemaVersion = 1,
        quickGenreIds = setOf("urban", "romance", "mystery", "fantasy"),
        genres = listOf(
            GenreOption("urban", "都市"),
            GenreOption("romance", "言情"),
            GenreOption("mystery", "悬疑"),
            GenreOption("fantasy", "玄幻"),
            GenreOption("xianxia", "仙侠"),
            GenreOption("science-fiction", "科幻"),
            GenreOption("apocalypse", "末日"),
            GenreOption("alternate-history", "历史架空"),
            GenreOption("workplace", "职场"),
            GenreOption("campus", "校园"),
            GenreOption("infinite-flow", "无限流"),
            GenreOption("light-novel", "轻小说"),
            GenreOption("realism", "现实向"),
        ),
    )
}

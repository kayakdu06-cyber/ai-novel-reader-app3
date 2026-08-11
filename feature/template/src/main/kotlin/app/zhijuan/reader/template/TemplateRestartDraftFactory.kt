package app.zhijuan.reader.template

import app.zhijuan.core.database.template.StoredTemplateSource
import app.zhijuan.core.model.BookLengthMode
import app.zhijuan.core.model.BookLengthPolicy
import app.zhijuan.core.model.BookPresentationPreset
import app.zhijuan.core.model.ContentPresentationMappingV1
import app.zhijuan.core.model.TemplateOriginType
import app.zhijuan.reader.creation.AdvancedCreationDetails
import app.zhijuan.reader.creation.DefaultCreationOptionCatalog
import app.zhijuan.reader.creation.MinimalBookDraft
import javax.inject.Inject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

data class TemplateRestartProvenance(
    val templateId: String,
    val revisionId: String,
    val revisionNo: Int,
    val originType: TemplateOriginType,
    val sourceBookId: String?,
    val sourceBookTitle: String?,
    val categories: List<String>,
    val contentHash: String,
)

data class TemplateRestartDraft(
    val draft: MinimalBookDraft,
    val provenance: TemplateRestartProvenance,
)

/** Converts a frozen template revision into the existing creation flow without losing provenance. */
class TemplateRestartDraftFactory @Inject constructor() {
    fun create(source: StoredTemplateSource): TemplateRestartDraft {
        require(source.contentHash.isNotBlank()) { "Template content hash must not be blank." }
        val seed = parseObject(source.storySeedJson, "story seed")
        val genre = parseObject(source.genreJson, "genre")
        val structure = parseObject(source.structureJson, "structure")
        val presentation = parseObject(source.presentationJson, "presentation")

        val storyIdea = seed.firstString("storyIdea", "premise", "summary", "idea")
            ?: throw IllegalArgumentException("Template story seed does not contain a reusable idea.")
        val requestedGenre = genre.firstString("genreId", "id", "primaryGenreId")
            ?.takeIf(DefaultCreationOptionCatalog.value.genres.map { it.id }.toSet()::contains)
        val mode = structure.firstString("lengthMode")
            ?.let { value -> enumValueOf<BookLengthMode>(value.uppercase()) }
            ?: BookLengthMode.MEDIUM
        val minimum = structure.firstInt("minimumChapterCount")
            ?: BookLengthPolicy.minimumChapterCount(mode)
        val target = structure.firstInt("targetChapterCount")
            ?: BookLengthPolicy.targetChapterCount(mode, null)
            ?: throw IllegalArgumentException("A long template must contain a target chapter count.")
        val lengthSchema = structure.firstInt("lengthPolicySchemaVersion")
            ?: BookLengthPolicy.SCHEMA_VERSION
        BookLengthPolicy.requireValidSelection(mode, minimum, target, lengthSchema)

        val presetName = presentation.firstString("preset", "presentationPreset")
            ?: presentation.nestedObject("directive")?.firstString("preset")
            ?: BookPresentationPreset.BALANCED.name
        val preset = enumValueOf<BookPresentationPreset>(presetName.uppercase())

        val draft = MinimalBookDraft(
            storyIdea = storyIdea,
            genreId = requestedGenre,
            lengthMode = mode,
            minimumChapterCount = minimum,
            targetChapterCount = target,
            lengthPolicySchemaVersion = lengthSchema,
            presentationDirective = ContentPresentationMappingV1.directiveFor(preset),
            optionCatalogSchemaVersion = DefaultCreationOptionCatalog.value.schemaVersion,
            advancedDetails = AdvancedCreationDetails(
                charactersAndRelationships = readablePayload(source.stableCharactersJson),
                worldAndBackground = readablePayload(source.worldRulesJson),
                narrativeAndStyle = readablePayload(source.writingStyleJson),
                requiredElements = parseObject(source.contentRulesJson, "content rules")
                    .firstString("requiredElements", "required")
                    .orEmpty(),
                excludedElements = parseObject(source.contentRulesJson, "content rules")
                    .firstString("excludedElements", "excluded")
                    .orEmpty(),
            ),
        )
        return TemplateRestartDraft(
            draft = draft,
            provenance = TemplateRestartProvenance(
                templateId = source.templateId,
                revisionId = source.revisionId,
                revisionNo = source.revisionNo,
                originType = source.originType,
                sourceBookId = source.sourceBookId,
                sourceBookTitle = source.sourceBookTitle,
                categories = source.categoryTags.distinct(),
                contentHash = source.contentHash,
            ),
        )
    }

    private fun readablePayload(raw: String): String {
        val objectValue = parseObject(raw, "template detail")
        return objectValue.firstString("summary", "description", "text", "prompt")
            ?: raw.trim().takeUnless { it == "{}" }.orEmpty()
    }

    private fun parseObject(raw: String, label: String): JsonObject {
        require(raw.isNotBlank()) { "Template $label must not be blank." }
        return Json.parseToJsonElement(raw) as? JsonObject
            ?: throw IllegalArgumentException("Template $label must be a JSON object.")
    }

    private fun JsonObject.firstString(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
        (this[key] as? JsonPrimitive)?.content
            ?.trim()
            ?.takeIf(String::isNotBlank)
    }

    private fun JsonObject.firstInt(vararg keys: String): Int? = keys.firstNotNullOfOrNull { key ->
        (this[key] as? JsonPrimitive)?.intOrNull
    }

    private fun JsonObject.nestedObject(key: String): JsonObject? = this[key] as? JsonObject
}

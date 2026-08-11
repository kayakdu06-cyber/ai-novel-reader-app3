package app.zhijuan.core.database.generation

import androidx.room.withTransaction
import app.zhijuan.core.database.ZhijuanDatabase
import app.zhijuan.core.model.BookPresentationPreset
import app.zhijuan.core.model.ContentControlProfile
import app.zhijuan.core.model.ContentPresentationDirective
import app.zhijuan.core.model.FadePolicy
import app.zhijuan.core.model.GenreContentDimensionBaseline
import app.zhijuan.core.task.BoundPromptBundle
import app.zhijuan.core.task.PromptBundleCatalogV1
import app.zhijuan.core.task.PromptBundleSourceBinding
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Binds an immutable creation snapshot to the current prompt contract. It does not update the
 * snapshot, create a generation job, read a connection secret, or provide any network capability.
 */
class PromptBundleBindingRepository(
    private val database: ZhijuanDatabase,
) {
    suspend fun bindForBook(bookId: String): BoundPromptBundle = database.withTransaction {
        require(BOOK_ID.matches(bookId)) { "Prompt binding book id is invalid." }
        val library = database.libraryDao()
        val book = requireNotNull(library.findBook(bookId)) { "Prompt binding book is missing." }
        val snapshot = requireNotNull(library.findCreationSnapshot(book.creationSnapshotId)) {
            "Prompt binding creation snapshot is missing."
        }
        require(snapshot.snapshotId == book.creationSnapshotId) {
            "Prompt binding snapshot ownership is invalid."
        }
        require(
            snapshot.promptBundleVersion == PromptBundleCatalogV1.UNASSIGNED_CREATION_BUNDLE_VERSION,
        ) { "Creation snapshot prompt bundle state is unsupported." }

        val presentationRoot = parseObject(snapshot.presentationProfileJson, "Presentation profile")
        val directive = presentationRoot.requiredObject("directive").toPresentationDirective()
        val persistedProfile = presentationRoot.requiredObject("resolvedProfile").toContentProfile()
        val genreRoot = parseObject(snapshot.genrePayloadJson, "Genre payload")
        val baseline = genreRoot.requiredObject("contentDimensionBaseline").toGenreBaseline()
        require(snapshot.contentControlSchemaVersion == directive.contentControlSchemaVersion) {
            "Creation snapshot content control version is inconsistent."
        }

        val bound = PromptBundleCatalogV1.bind(
            PromptBundleSourceBinding(
                snapshotSchemaVersion = snapshot.schemaVersion,
                sourceContentHash = snapshot.contentHash,
                lengthMode = book.lengthMode,
                minimumChapterCount = book.minimumChapters,
                targetChapterCount = requireNotNull(book.targetChapters) {
                    "Prompt binding target chapter count is missing."
                },
                lengthPolicySchemaVersion = book.lengthPolicySchemaVersion,
                presentationDirective = directive,
                genreBaseline = baseline,
            ),
        )
        require(bound.contentProfile == persistedProfile) {
            "Persisted presentation profile does not match the frozen directive and genre baseline."
        }
        bound
    }

    private fun parseObject(value: String, label: String): JsonObject = runCatching {
        Json.parseToJsonElement(value).jsonObject
    }.getOrElse { throw IllegalArgumentException("$label JSON is invalid.") }

    private fun JsonObject.toPresentationDirective() = ContentPresentationDirective(
        preset = requiredEnum<BookPresentationPreset>("preset"),
        narrativeDetailLevel = requiredInt("narrativeDetailLevel"),
        intimacyDetailLevel = requiredInt("intimacyDetailLevel"),
        fadePolicy = requiredEnum<FadePolicy>("fadePolicy"),
        conflictDetailOverride = optionalInt("conflictDetailOverride"),
        graphicInjuryOverride = optionalInt("graphicInjuryOverride"),
        languageIntensityOverride = optionalInt("languageIntensityOverride"),
        emotionalPressureOverride = optionalInt("emotionalPressureOverride"),
        presentationMappingSchemaVersion = requiredInt("presentationMappingSchemaVersion"),
        contentControlSchemaVersion = requiredInt("contentControlSchemaVersion"),
    )

    private fun JsonObject.toContentProfile() = ContentControlProfile(
        preset = requiredEnum<BookPresentationPreset>("preset"),
        narrativeDetailLevel = requiredInt("narrativeDetailLevel"),
        intimacyDetailLevel = requiredInt("intimacyDetailLevel"),
        conflictDetailLevel = requiredInt("conflictDetailLevel"),
        graphicInjuryLevel = requiredInt("graphicInjuryLevel"),
        languageIntensityLevel = requiredInt("languageIntensityLevel"),
        emotionalPressureLevel = requiredInt("emotionalPressureLevel"),
        fadePolicy = requiredEnum<FadePolicy>("fadePolicy"),
        presentationMappingSchemaVersion = requiredInt("presentationMappingSchemaVersion"),
        contentControlSchemaVersion = requiredInt("contentControlSchemaVersion"),
    )

    private fun JsonObject.toGenreBaseline() = GenreContentDimensionBaseline(
        conflictDetailLevel = requiredInt("conflictDetailLevel"),
        graphicInjuryLevel = requiredInt("graphicInjuryLevel"),
        languageIntensityLevel = requiredInt("languageIntensityLevel"),
        emotionalPressureLevel = requiredInt("emotionalPressureLevel"),
    )

    private fun JsonObject.requiredObject(key: String): JsonObject =
        this[key]?.let { runCatching { it.jsonObject }.getOrNull() }
            ?: throw IllegalArgumentException("Prompt binding object field is missing or invalid.")

    private fun JsonObject.requiredInt(key: String): Int =
        this[key]?.let { runCatching { it.jsonPrimitive.int }.getOrNull() }
            ?: throw IllegalArgumentException("Prompt binding integer field is missing or invalid.")

    private fun JsonObject.optionalInt(key: String): Int? {
        val value = this[key] ?: throw IllegalArgumentException("Prompt binding optional field is missing.")
        if (value is JsonNull) return null
        return runCatching { value.jsonPrimitive.int }.getOrElse {
            throw IllegalArgumentException("Prompt binding optional integer field is invalid.")
        }
    }

    private inline fun <reified T : Enum<T>> JsonObject.requiredEnum(key: String): T {
        val value = this[key]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
            ?: throw IllegalArgumentException("Prompt binding enum field is missing or invalid.")
        return enumValues<T>().firstOrNull { it.name == value }
            ?: throw IllegalArgumentException("Prompt binding enum value is unsupported.")
    }

    private companion object {
        val BOOK_ID = Regex("[A-Za-z0-9._:-]{1,128}")
    }
}

package app.zhijuan.reader.creation

import app.zhijuan.core.model.BookLengthPolicy
import app.zhijuan.core.model.BookLengthMode
import app.zhijuan.core.model.BookStatus
import app.zhijuan.core.model.ContentControlProfile
import app.zhijuan.core.model.ContentPresentationDirective
import app.zhijuan.core.model.ContentPresentationMappingV1
import app.zhijuan.core.model.GenreContentDimensionBaseline
import app.zhijuan.core.model.RelevantCharacterAdultGate
import app.zhijuan.core.model.RelevantSceneBlockReason
import app.zhijuan.core.model.TitleSource
import app.zhijuan.core.task.PromptBundleCatalogV1
import java.security.MessageDigest
import java.text.Normalizer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class CreationConnectionSelection(
    val connectionId: String,
    val modelId: String,
)

data class PreparedBookCreation(
    val snapshotId: String,
    val bookId: String,
    val title: String,
    val titleSource: TitleSource,
    val status: BookStatus,
    val lengthMode: BookLengthMode,
    val minimumChapterCount: Int,
    val targetChapterCount: Int,
    val lengthPolicySchemaVersion: Int,
    val rawInputJson: String,
    val normalizedInputJson: String,
    val inferenceProvenanceJson: String,
    val genrePayloadJson: String,
    val presentationProfileJson: String,
    val modelPreferenceJson: String,
    val snapshotSchemaVersion: Int,
    val promptBundleVersion: String,
    val contentControlSchemaVersion: Int,
    val contentHash: String,
    val createdAt: Long,
)

object CreationStandardizerV1 {
    const val SNAPSHOT_SCHEMA_VERSION = 1
    const val INFERENCE_SCHEMA_VERSION = 1
    const val PROMPT_BUNDLE_VERSION_UNASSIGNED =
        PromptBundleCatalogV1.UNASSIGNED_CREATION_BUNDLE_VERSION

    fun prepare(
        draft: MinimalBookDraft,
        connection: CreationConnectionSelection,
        snapshotId: String,
        bookId: String,
        createdAt: Long,
    ): PreparedBookCreation {
        require(snapshotId.isNotBlank())
        require(bookId.isNotBlank())
        require(createdAt >= 0)
        require(connection.connectionId.isNotBlank())
        require(connection.modelId.isNotBlank())
        require(draft.optionCatalogSchemaVersion == DefaultCreationOptionCatalog.value.schemaVersion) {
            "Unsupported creation option catalog schema."
        }
        BookLengthPolicy.requireValidSelection(
            mode = draft.lengthMode,
            minimumChapterCount = draft.minimumChapterCount,
            targetChapterCount = draft.targetChapterCount,
            schemaVersion = draft.lengthPolicySchemaVersion,
        )

        val normalizedIdea = normalizeText(draft.storyIdea)
        require(normalizedIdea.isNotBlank()) { "Story idea must not be blank." }
        val normalizedDetails = draft.advancedDetails.normalizedWith(::normalizeText)
        val genre = resolveGenre(draft.genreId, normalizedIdea)
        val baseline = genreBaselines.getValue(genre.id)
        val profile = ContentPresentationMappingV1.resolve(
            directive = draft.presentationDirective,
            genreBaseline = baseline,
        )
        val title = deriveTitle(normalizedIdea)

        val rawInput = buildJsonObject {
            put("storyIdea", draft.storyIdea)
            putNullableString("requestedGenreId", draft.genreId)
            put("lengthMode", draft.lengthMode.name)
            put("minimumChapterCount", draft.minimumChapterCount)
            put("targetChapterCount", draft.targetChapterCount)
            put("lengthPolicySchemaVersion", draft.lengthPolicySchemaVersion)
            put("presentationPreset", draft.presentationDirective.preset.name)
            put("optionCatalogSchemaVersion", draft.optionCatalogSchemaVersion)
            put("advancedDetails", draft.advancedDetails.toJsonObject())
        }
        val normalizedInput = buildJsonObject {
            put("storyIdea", normalizedIdea)
            put("derivedTitle", title)
            put("titleSource", TitleSource.SYSTEM_INFERRED.name)
            put("genreId", genre.id)
            put("lengthMode", draft.lengthMode.name)
            put("minimumChapterCount", draft.minimumChapterCount)
            put("targetChapterCount", draft.targetChapterCount)
            put("lengthPolicySchemaVersion", draft.lengthPolicySchemaVersion)
            put("advancedDetails", normalizedDetails.toJsonObject())
        }
        val provenance = buildJsonObject {
            put("schemaVersion", INFERENCE_SCHEMA_VERSION)
            put("storyIdea", sourceObject("USER"))
            put("genre", buildJsonObject {
                put("source", genre.source)
                put("ruleId", genre.ruleId)
            })
            put("title", buildJsonObject {
                put("source", "SYSTEM_INFERRED")
                put("ruleId", "story-idea-prefix-v1")
            })
            put("length", buildJsonObject {
                put("source", "USER_SELECTION")
                put("policySchemaVersion", draft.lengthPolicySchemaVersion)
            })
            put("presentation", buildJsonObject {
                put("source", "USER_SELECTION")
                put("mappingSchemaVersion", draft.presentationDirective.presentationMappingSchemaVersion)
            })
            put("advancedDetails", normalizedDetails.provenanceJson())
        }
        val genrePayload = buildJsonObject {
            put("id", genre.id)
            put("label", genreLabels.getValue(genre.id))
            put("source", genre.source)
            put("ruleId", genre.ruleId)
            put("optionCatalogSchemaVersion", draft.optionCatalogSchemaVersion)
            put("contentDimensionBaseline", baseline.toJsonObject())
        }
        val presentationProfile = buildJsonObject {
            put("directive", draft.presentationDirective.toJsonObject())
            put("resolvedProfile", profile.toJsonObject())
            put("relevantCharacterAdultGate", RelevantCharacterAdultGate.UNKNOWN.name)
            put("relevantSceneExecution", buildJsonObject {
                put("status", "BLOCKED")
                put("reason", RelevantSceneBlockReason.ADULT_STATUS_UNKNOWN.name)
                put("strictBodyAndSensoryContinuity", false)
            })
        }
        val modelPreference = buildJsonObject {
            put("connectionId", connection.connectionId)
            put("modelId", connection.modelId)
            put("source", "CURRENT_CONNECTION")
        }
        val hashPayload = buildJsonObject {
            put("snapshotSchemaVersion", SNAPSHOT_SCHEMA_VERSION)
            put("rawInput", rawInput)
            put("normalizedInput", normalizedInput)
            put("inferenceProvenance", provenance)
            put("genrePayload", genrePayload)
            put("presentationProfile", presentationProfile)
            put("modelPreference", modelPreference)
            put("promptBundleVersion", PROMPT_BUNDLE_VERSION_UNASSIGNED)
            put("contentControlSchemaVersion", draft.presentationDirective.contentControlSchemaVersion)
        }

        return PreparedBookCreation(
            snapshotId = snapshotId,
            bookId = bookId,
            title = title,
            titleSource = TitleSource.SYSTEM_INFERRED,
            status = BookStatus.DRAFT,
            lengthMode = draft.lengthMode,
            minimumChapterCount = draft.minimumChapterCount,
            targetChapterCount = draft.targetChapterCount,
            lengthPolicySchemaVersion = draft.lengthPolicySchemaVersion,
            rawInputJson = rawInput.toString(),
            normalizedInputJson = normalizedInput.toString(),
            inferenceProvenanceJson = provenance.toString(),
            genrePayloadJson = genrePayload.toString(),
            presentationProfileJson = presentationProfile.toString(),
            modelPreferenceJson = modelPreference.toString(),
            snapshotSchemaVersion = SNAPSHOT_SCHEMA_VERSION,
            promptBundleVersion = PROMPT_BUNDLE_VERSION_UNASSIGNED,
            contentControlSchemaVersion = draft.presentationDirective.contentControlSchemaVersion,
            contentHash = sha256(hashPayload.toString()),
            createdAt = createdAt,
        )
    }

    private fun resolveGenre(requestedGenreId: String?, normalizedIdea: String): GenreResolution {
        if (requestedGenreId != null) {
            require(requestedGenreId in genreLabels) { "Unknown genre id." }
            return GenreResolution(requestedGenreId, "USER", "explicit-selection")
        }
        val matched = genreRules.firstOrNull { rule ->
            rule.keywords.any(normalizedIdea::contains)
        }
        return if (matched != null) {
            GenreResolution(matched.genreId, "KEYWORD_INFERENCE", matched.ruleId)
        } else {
            GenreResolution("urban", "SYSTEM_DEFAULT", "default-urban-v1")
        }
    }

    private fun deriveTitle(storyIdea: String): String {
        val firstClause = storyIdea.split(titleBoundary, limit = 2).first().trim()
        val source = firstClause.ifBlank { "未命名故事" }
        return if (source.codePointCount(0, source.length) <= MAX_TITLE_CODE_POINTS) {
            source
        } else {
            source.takeCodePoints(MAX_TITLE_CODE_POINTS) + "…"
        }
    }

    private fun normalizeText(value: String): String = Normalizer
        .normalize(value, Normalizer.Form.NFC)
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .lines()
        .joinToString("\n") { line -> line.trim().replace(horizontalWhitespace, " ") }
        .trim()
        .replace(excessBlankLines, "\n\n")

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun AdvancedCreationDetails.normalizedWith(
        normalize: (String) -> String,
    ) = AdvancedCreationDetails(
        charactersAndRelationships = normalize(charactersAndRelationships),
        worldAndBackground = normalize(worldAndBackground),
        narrativeAndStyle = normalize(narrativeAndStyle),
        requiredElements = normalize(requiredElements),
        excludedElements = normalize(excludedElements),
    )

    private fun AdvancedCreationDetails.toJsonObject(): JsonObject = buildJsonObject {
        put("charactersAndRelationships", charactersAndRelationships)
        put("worldAndBackground", worldAndBackground)
        put("narrativeAndStyle", narrativeAndStyle)
        put("requiredElements", requiredElements)
        put("excludedElements", excludedElements)
    }

    private fun AdvancedCreationDetails.provenanceJson(): JsonObject = buildJsonObject {
        put("charactersAndRelationships", fieldSource(charactersAndRelationships))
        put("worldAndBackground", fieldSource(worldAndBackground))
        put("narrativeAndStyle", fieldSource(narrativeAndStyle))
        put("requiredElements", fieldSource(requiredElements))
        put("excludedElements", fieldSource(excludedElements))
    }

    private fun GenreContentDimensionBaseline.toJsonObject(): JsonObject = buildJsonObject {
        put("conflictDetailLevel", conflictDetailLevel)
        put("graphicInjuryLevel", graphicInjuryLevel)
        put("languageIntensityLevel", languageIntensityLevel)
        put("emotionalPressureLevel", emotionalPressureLevel)
    }

    private fun ContentPresentationDirective.toJsonObject(): JsonObject = buildJsonObject {
        put("preset", preset.name)
        put("narrativeDetailLevel", narrativeDetailLevel)
        put("intimacyDetailLevel", intimacyDetailLevel)
        put("fadePolicy", fadePolicy.name)
        putNullableInt("conflictDetailOverride", conflictDetailOverride)
        putNullableInt("graphicInjuryOverride", graphicInjuryOverride)
        putNullableInt("languageIntensityOverride", languageIntensityOverride)
        putNullableInt("emotionalPressureOverride", emotionalPressureOverride)
        put("presentationMappingSchemaVersion", presentationMappingSchemaVersion)
        put("contentControlSchemaVersion", contentControlSchemaVersion)
    }

    private fun ContentControlProfile.toJsonObject(): JsonObject = buildJsonObject {
        put("preset", preset.name)
        put("narrativeDetailLevel", narrativeDetailLevel)
        put("intimacyDetailLevel", intimacyDetailLevel)
        put("conflictDetailLevel", conflictDetailLevel)
        put("graphicInjuryLevel", graphicInjuryLevel)
        put("languageIntensityLevel", languageIntensityLevel)
        put("emotionalPressureLevel", emotionalPressureLevel)
        put("fadePolicy", fadePolicy.name)
        put("presentationMappingSchemaVersion", presentationMappingSchemaVersion)
        put("contentControlSchemaVersion", contentControlSchemaVersion)
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putNullableString(
        key: String,
        value: String?,
    ) {
        put(key, value?.let(::JsonPrimitive) ?: JsonNull)
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putNullableInt(
        key: String,
        value: Int?,
    ) {
        put(key, value?.let(::JsonPrimitive) ?: JsonNull)
    }

    private fun sourceObject(source: String): JsonElement = buildJsonObject {
        put("source", source)
    }

    private fun fieldSource(value: String): String = if (value.isBlank()) "EMPTY" else "USER"

    private fun String.takeCodePoints(count: Int): String {
        val end = offsetByCodePoints(0, count)
        return substring(0, end)
    }

    private data class GenreResolution(
        val id: String,
        val source: String,
        val ruleId: String,
    )

    private data class GenreRule(
        val ruleId: String,
        val genreId: String,
        val keywords: List<String>,
    )

    private val genreLabels = DefaultCreationOptionCatalog.value.genres.associate { it.id to it.label }
    private val genreRules = listOf(
        GenreRule("keyword-infinite-flow-v1", "infinite-flow", listOf("无限流", "轮回空间", "副本")),
        GenreRule("keyword-apocalypse-v1", "apocalypse", listOf("末日", "丧尸", "废土")),
        GenreRule("keyword-xianxia-v1", "xianxia", listOf("仙侠", "修仙", "宗门", "灵根", "飞升")),
        GenreRule("keyword-science-fiction-v1", "science-fiction", listOf("科幻", "星际", "宇宙", "机甲", "赛博")),
        GenreRule("keyword-mystery-v1", "mystery", listOf("悬疑", "推理", "案件", "凶手", "侦探")),
        GenreRule("keyword-alternate-history-v1", "alternate-history", listOf("架空历史", "穿越古代", "王朝")),
        GenreRule("keyword-campus-v1", "campus", listOf("校园", "高中", "大学", "同学")),
        GenreRule("keyword-workplace-v1", "workplace", listOf("职场", "公司", "创业", "办公室")),
        GenreRule("keyword-light-novel-v1", "light-novel", listOf("轻小说", "二次元")),
        GenreRule("keyword-fantasy-v1", "fantasy", listOf("玄幻", "魔法", "异世界")),
        GenreRule("keyword-romance-v1", "romance", listOf("爱情", "恋爱", "重逢", "相亲", "暗恋")),
        GenreRule("keyword-realism-v1", "realism", listOf("现实", "家庭", "乡村", "时代变迁")),
        GenreRule("keyword-urban-v1", "urban", listOf("都市", "城市", "租房", "打工")),
    )
    private val genreBaselines = mapOf(
        "urban" to GenreContentDimensionBaseline(1, 0, 1, 1),
        "romance" to GenreContentDimensionBaseline(0, 0, 1, 2),
        "mystery" to GenreContentDimensionBaseline(2, 1, 1, 3),
        "fantasy" to GenreContentDimensionBaseline(2, 1, 1, 2),
        "xianxia" to GenreContentDimensionBaseline(2, 1, 1, 2),
        "science-fiction" to GenreContentDimensionBaseline(2, 1, 1, 2),
        "apocalypse" to GenreContentDimensionBaseline(3, 2, 1, 3),
        "alternate-history" to GenreContentDimensionBaseline(2, 1, 1, 2),
        "workplace" to GenreContentDimensionBaseline(1, 0, 1, 2),
        "campus" to GenreContentDimensionBaseline(0, 0, 1, 1),
        "infinite-flow" to GenreContentDimensionBaseline(3, 2, 1, 3),
        "light-novel" to GenreContentDimensionBaseline(1, 0, 1, 1),
        "realism" to GenreContentDimensionBaseline(1, 0, 1, 2),
    )
    private val horizontalWhitespace = Regex("[\\t\\u000B\\u000C ]+")
    private val excessBlankLines = Regex("\\n{3,}")
    private val titleBoundary = Regex("[。！？!?；;\\n]")
    private const val MAX_TITLE_CODE_POINTS = 24
}

package app.zhijuan.feature.generation

import app.zhijuan.core.model.CanonLevel
import app.zhijuan.provider.common.ProviderJsonSchema
import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

enum class ChapterMemoryAttributeV1 {
    LOCATION,
    PHYSICAL_STATE,
    EMOTIONAL_STATE,
    GOAL,
    KNOWLEDGE,
    RELATIONSHIP,
    POSSESSION,
    COMMITMENT,
    SECRET,
}

enum class ChapterMemoryFactKindV1 {
    WORLD_EVENT,
    CHARACTER_STATE,
    RELATIONSHIP,
    DISCOVERY,
    POSSESSION,
    COMMITMENT,
    SECRET,
    LOCATION,
}

data class ChapterMemorySummaryV1(
    val objectiveOutcome: String,
    val keyEvents: List<String>,
    val decisions: List<String>,
    val relationshipChanges: List<String>,
    val endingState: String,
    val unresolvedQuestions: List<String>,
    val importance: Int,
)

data class ChapterMemoryEntityEventV1(
    val entityId: String,
    val attribute: ChapterMemoryAttributeV1,
    val relatedEntityId: String?,
    val oldValue: String?,
    val newValue: String,
    val storyTimeExpression: String?,
    val confidenceMicros: Int,
    val canonLevel: CanonLevel,
    val evidence: String,
)

data class ChapterMemoryFactV1(
    val factKind: ChapterMemoryFactKindV1,
    val entityId: String?,
    val text: String,
    val canonLevel: CanonLevel,
    val confidenceMicros: Int,
    val conflictGroupId: String?,
)

data class ChapterMemoryV1(
    val sourceChapterVersionId: String,
    val sourceChapterContentHash: String,
    val chapterId: String,
    val chapterIndex: Int,
    val summary: ChapterMemorySummaryV1,
    val entityEvents: List<ChapterMemoryEntityEventV1>,
    val facts: List<ChapterMemoryFactV1>,
    val canonicalJson: String,
    val contentHash: String,
) {
    override fun toString(): String =
        "ChapterMemoryV1(chapterIndex=$chapterIndex, eventCount=${entityEvents.size}, " +
            "factCount=${facts.size}, content=redacted)"
}

data class ChapterMemoryExtractionExpectation(
    val sourceChapterVersionId: String,
    val sourceChapterContentHash: String,
    val chapterId: String,
    val chapterIndex: Int,
    val allowedEntityIds: Set<String>,
) {
    init {
        require(IDENTIFIER.matches(sourceChapterVersionId) && IDENTIFIER.matches(chapterId))
        require(HASH.matches(sourceChapterContentHash))
        require(chapterIndex in 1..10_000)
        require(allowedEntityIds.size in 1..256 && allowedEntityIds.all(IDENTIFIER::matches))
    }
}

enum class ChapterMemoryCrossIssueCode {
    SOURCE_VERSION_MISMATCH,
    SOURCE_CONTENT_HASH_MISMATCH,
    CHAPTER_ID_MISMATCH,
    CHAPTER_INDEX_MISMATCH,
    UNKNOWN_ENTITY,
    INVALID_RELATIONSHIP_TARGET,
    DUPLICATE_ENTITY_EVENT,
    DUPLICATE_FACT,
}

data class ChapterMemoryCrossIssue(
    val code: ChapterMemoryCrossIssueCode,
    val reference: String,
) {
    init {
        require(reference.length in 1..256 && reference.none(Char::isISOControl))
    }
}

sealed interface ChapterMemoryValidationResult {
    data class Valid(val memory: ChapterMemoryV1) : ChapterMemoryValidationResult
    data class Invalid(val issues: List<ChapterMemoryCrossIssue>) : ChapterMemoryValidationResult {
        init {
            require(issues.isNotEmpty() && issues.size <= 64)
        }

        override fun toString(): String =
            "ChapterMemoryValidationResult.Invalid(issueCodes=${issues.map { it.code }.distinct()}, content=redacted)"
    }
}

class ChapterMemoryOutputParser(
    private val validator: StructuredOutputValidator = StructuredOutputValidator(),
) {
    fun parse(source: ByteArray): PlanningOutputValidationResult<ChapterMemoryV1> =
        when (val result = validator.validate(source, ChapterMemoryOutputContractV1)) {
            is StructuredOutputValidationResult.Invalid -> PlanningOutputValidationResult.Invalid(result.report)
            is StructuredOutputValidationResult.Valid -> PlanningOutputValidationResult.Valid(
                result.output.withDocument(::fromDocument),
            )
        }

    internal fun fromValidated(output: ValidatedStructuredOutput): ChapterMemoryV1 {
        require(output.schemaId == ChapterMemoryOutputContractV1.schemaId)
        require(output.schemaVersion == ChapterMemoryOutputContractV1.currentSchemaVersion)
        return output.withDocument(::fromDocument)
    }

    internal fun fromDocument(document: JsonObject): ChapterMemoryV1 {
        val summary = document.objectValue("summary")
        val canonical = document.toString()
        return ChapterMemoryV1(
            sourceChapterVersionId = document.stringValue("sourceChapterVersionId"),
            sourceChapterContentHash = document.stringValue("sourceChapterContentHash"),
            chapterId = document.stringValue("chapterId"),
            chapterIndex = document.intValue("chapterIndex"),
            summary = ChapterMemorySummaryV1(
                objectiveOutcome = summary.stringValue("objectiveOutcome"),
                keyEvents = summary.stringValues("keyEvents"),
                decisions = summary.stringValues("decisions"),
                relationshipChanges = summary.stringValues("relationshipChanges"),
                endingState = summary.stringValue("endingState"),
                unresolvedQuestions = summary.stringValues("unresolvedQuestions"),
                importance = summary.intValue("importance"),
            ),
            entityEvents = document.objectValues("entityEvents").map { event ->
                ChapterMemoryEntityEventV1(
                    entityId = event.stringValue("entityId"),
                    attribute = ChapterMemoryAttributeV1.valueOf(event.stringValue("attribute")),
                    relatedEntityId = event.nullableStringValue("relatedEntityId"),
                    oldValue = event.nullableStringValue("oldValue"),
                    newValue = event.stringValue("newValue"),
                    storyTimeExpression = event.nullableStringValue("storyTimeExpression"),
                    confidenceMicros = event.intValue("confidenceMicros"),
                    canonLevel = CanonLevel.valueOf(event.stringValue("canonLevel")),
                    evidence = event.stringValue("evidence"),
                )
            },
            facts = document.objectValues("facts").map { fact ->
                ChapterMemoryFactV1(
                    factKind = ChapterMemoryFactKindV1.valueOf(fact.stringValue("factKind")),
                    entityId = fact.nullableStringValue("entityId"),
                    text = fact.stringValue("text"),
                    canonLevel = CanonLevel.valueOf(fact.stringValue("canonLevel")),
                    confidenceMicros = fact.intValue("confidenceMicros"),
                    conflictGroupId = fact.nullableStringValue("conflictGroupId"),
                )
            },
            canonicalJson = canonical,
            contentHash = sha256(canonical),
        )
    }
}

internal class BoundChapterMemoryOutputContract(
    private val expectation: ChapterMemoryExtractionExpectation,
    private val parser: ChapterMemoryOutputParser = ChapterMemoryOutputParser(),
) : StructuredOutputContract {
    override val schemaId = ChapterMemoryOutputContractV1.schemaId
    override val currentSchemaVersion = ChapterMemoryOutputContractV1.currentSchemaVersion
    override val providerSchema = ChapterMemoryOutputContractV1.providerSchema
    override val limits = ChapterMemoryOutputContractV1.limits

    override fun validate(document: JsonObject): List<StructuredOutputIssue> {
        val structural = ChapterMemoryOutputContractV1.validate(document)
        if (structural.isNotEmpty()) return structural
        return when (val result = ChapterMemoryValidatorV1.validate(parser.fromDocument(document), expectation)) {
            is ChapterMemoryValidationResult.Valid -> emptyList()
            is ChapterMemoryValidationResult.Invalid -> result.issues.map { issue ->
                StructuredOutputIssue(
                    code = StructuredOutputIssueCode.VALUE_INVALID,
                    path = "$.${issue.reference}",
                )
            }
        }
    }
}

object ChapterMemoryValidatorV1 {
    fun validate(
        memory: ChapterMemoryV1,
        expected: ChapterMemoryExtractionExpectation,
    ): ChapterMemoryValidationResult {
        val issues = buildList {
            if (memory.sourceChapterVersionId != expected.sourceChapterVersionId) {
                add(ChapterMemoryCrossIssue(ChapterMemoryCrossIssueCode.SOURCE_VERSION_MISMATCH, "sourceChapterVersionId"))
            }
            if (memory.sourceChapterContentHash != expected.sourceChapterContentHash) {
                add(ChapterMemoryCrossIssue(ChapterMemoryCrossIssueCode.SOURCE_CONTENT_HASH_MISMATCH, "sourceChapterContentHash"))
            }
            if (memory.chapterId != expected.chapterId) {
                add(ChapterMemoryCrossIssue(ChapterMemoryCrossIssueCode.CHAPTER_ID_MISMATCH, "chapterId"))
            }
            if (memory.chapterIndex != expected.chapterIndex) {
                add(ChapterMemoryCrossIssue(ChapterMemoryCrossIssueCode.CHAPTER_INDEX_MISMATCH, "chapterIndex"))
            }
            memory.entityEvents.forEachIndexed { index, event ->
                if (event.entityId !in expected.allowedEntityIds) {
                    add(ChapterMemoryCrossIssue(ChapterMemoryCrossIssueCode.UNKNOWN_ENTITY, "entityEvents[$index].entityId"))
                }
                val relationshipTargetValid = if (event.attribute == ChapterMemoryAttributeV1.RELATIONSHIP) {
                    event.relatedEntityId != null && event.relatedEntityId in expected.allowedEntityIds &&
                        event.relatedEntityId != event.entityId
                } else {
                    event.relatedEntityId == null
                }
                if (!relationshipTargetValid) {
                    add(
                        ChapterMemoryCrossIssue(
                            ChapterMemoryCrossIssueCode.INVALID_RELATIONSHIP_TARGET,
                            "entityEvents[$index].relatedEntityId",
                        ),
                    )
                }
            }
            memory.facts.forEachIndexed { index, fact ->
                if (fact.entityId != null && fact.entityId !in expected.allowedEntityIds) {
                    add(ChapterMemoryCrossIssue(ChapterMemoryCrossIssueCode.UNKNOWN_ENTITY, "facts[$index].entityId"))
                }
            }
            val eventKeys = memory.entityEvents.map {
                listOf(it.entityId, it.attribute.name, it.relatedEntityId.orEmpty(), it.newValue.trim()).joinToString("\u0000")
            }
            if (eventKeys.distinct().size != eventKeys.size) {
                add(ChapterMemoryCrossIssue(ChapterMemoryCrossIssueCode.DUPLICATE_ENTITY_EVENT, "entityEvents"))
            }
            val factKeys = memory.facts.map { it.text.trim().lowercase() }
            if (factKeys.distinct().size != factKeys.size) {
                add(ChapterMemoryCrossIssue(ChapterMemoryCrossIssueCode.DUPLICATE_FACT, "facts"))
            }
        }.distinct().take(64)
        return if (issues.isEmpty()) ChapterMemoryValidationResult.Valid(memory)
        else ChapterMemoryValidationResult.Invalid(issues)
    }
}

object ChapterMemoryOutputContractV1 : StructuredOutputContract {
    override val schemaId = "chapter-memory.v1"
    override val currentSchemaVersion = 1
    override val providerSchema = ProviderJsonSchema.from(CHAPTER_MEMORY_SCHEMA)
    override val limits = StructuredOutputLimits(
        maximumBytes = 512 * 1_024,
        maximumRepairSourceBytes = 256 * 1_024,
        maximumDepth = 12,
        maximumNodes = 8_192,
        maximumObjectMembers = 16,
        maximumArrayItems = 512,
        maximumStringCharacters = 256 * 1_024,
    )

    override fun validate(document: JsonObject): List<StructuredOutputIssue> = buildList {
        val root = MemoryContractReader(document, "$", this)
        root.exactKeys(ROOT_KEYS)
        root.exactInt("schemaVersion", 1)
        root.identifier("sourceChapterVersionId")
        root.hash("sourceChapterContentHash")
        root.identifier("chapterId")
        root.int("chapterIndex", 1..10_000)

        val summary = root.objectValue("summary")
        val summaryReader = MemoryContractReader(summary, "$.summary", this)
        summaryReader.exactKeys(SUMMARY_KEYS)
        summaryReader.string("objectiveOutcome", 1..4_000)
        summaryReader.strings("keyEvents", 1..24, 1..1_500)
        summaryReader.strings("decisions", 0..16, 1..1_500)
        summaryReader.strings("relationshipChanges", 0..16, 1..1_500)
        summaryReader.string("endingState", 1..4_000)
        summaryReader.strings("unresolvedQuestions", 0..16, 1..1_500)
        summaryReader.int("importance", 0..100)

        val events = root.objects("entityEvents", 0..128)
        events.forEachIndexed { index, event ->
            val reader = MemoryContractReader(event, "$.entityEvents[$index]", this)
            reader.exactKeys(EVENT_KEYS)
            reader.identifier("entityId")
            reader.enumString("attribute", ChapterMemoryAttributeV1.entries.map { it.name }.toSet())
            reader.nullableIdentifier("relatedEntityId")
            reader.nullableString("oldValue", 1..2_000)
            reader.string("newValue", 1..2_000)
            reader.nullableString("storyTimeExpression", 1..500)
            reader.int("confidenceMicros", 0..1_000_000)
            reader.enumString("canonLevel", ALLOWED_CANON_LEVELS)
            reader.string("evidence", 1..2_000)
        }
        val facts = root.objects("facts", 0..128)
        facts.forEachIndexed { index, fact ->
            val reader = MemoryContractReader(fact, "$.facts[$index]", this)
            reader.exactKeys(FACT_KEYS)
            reader.enumString("factKind", ChapterMemoryFactKindV1.entries.map { it.name }.toSet())
            reader.nullableIdentifier("entityId")
            reader.string("text", 1..2_000)
            reader.enumString("canonLevel", ALLOWED_CANON_LEVELS)
            reader.int("confidenceMicros", 0..1_000_000)
            reader.nullableIdentifier("conflictGroupId")
        }
    }
}

internal class MemoryContractReader(
    private val value: JsonObject,
    private val path: String,
    private val issues: MutableList<StructuredOutputIssue>,
) {
    fun exactKeys(expected: Set<String>) {
        expected.filterNot(value::containsKey).forEach {
            issues += StructuredOutputIssue(StructuredOutputIssueCode.REQUIRED_FIELD_MISSING, "$path.$it")
        }
        value.keys.filterNot(expected::contains).forEach {
            issues += StructuredOutputIssue(StructuredOutputIssueCode.UNKNOWN_FIELD, "$path.$it")
        }
    }

    fun exactInt(key: String, expected: Int) {
        val actual = int(key, expected..expected)
        if (actual != null && actual != expected) invalid(key)
    }

    fun string(key: String, range: IntRange): String? {
        val text = (value[key] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull
        if (text == null) type(key) else if (text.length !in range || text.isBlank()) invalid(key)
        return text
    }

    fun nullableString(key: String, range: IntRange): String? {
        val element = value[key]
        if (element == null) missing(key)
        if (element == null || element is JsonNull) return null
        val text = (element as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull
        if (text == null) type(key) else if (text.length !in range || text.isBlank()) invalid(key)
        return text
    }

    fun identifier(key: String) {
        val text = string(key, 1..128)
        if (text != null && !IDENTIFIER.matches(text)) invalid(key)
    }

    fun nullableIdentifier(key: String) {
        val text = nullableString(key, 1..128)
        if (text != null && !IDENTIFIER.matches(text)) invalid(key)
    }

    fun hash(key: String) {
        val text = string(key, 64..64)
        if (text != null && !HASH.matches(text)) invalid(key)
    }

    fun enumString(key: String, allowed: Set<String>) {
        val text = string(key, 1..64)
        if (text != null && text !in allowed) invalid(key)
    }

    fun int(key: String, range: IntRange): Int? {
        val number = (value[key] as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.intOrNull
        if (number == null) type(key) else if (number !in range) invalid(key)
        return number
    }

    fun nullableInt(key: String, range: IntRange): Int? {
        val element = value[key]
        if (element == null) missing(key)
        if (element == null || element is JsonNull) return null
        val number = (element as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.intOrNull
        if (number == null) type(key) else if (number !in range) invalid(key)
        return number
    }

    fun objectValue(key: String): JsonObject {
        val result = value[key] as? JsonObject
        if (result == null) type(key)
        return result ?: JsonObject(emptyMap())
    }

    fun objects(key: String, range: IntRange): List<JsonObject> {
        val array = value[key] as? JsonArray
        if (array == null) {
            type(key)
            return emptyList()
        }
        if (array.size !in range) invalid(key)
        return array.mapIndexedNotNull { index, element ->
            (element as? JsonObject).also {
                if (it == null) issues += StructuredOutputIssue(StructuredOutputIssueCode.TYPE_MISMATCH, "$path.$key[$index]")
            }
        }
    }

    fun strings(key: String, range: IntRange, itemRange: IntRange) {
        val array = value[key] as? JsonArray
        if (array == null) {
            type(key)
            return
        }
        if (array.size !in range) invalid(key)
        array.forEachIndexed { index, element ->
            val text = (element as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull
            if (text == null) {
                issues += StructuredOutputIssue(StructuredOutputIssueCode.TYPE_MISMATCH, "$path.$key[$index]")
            } else if (text.length !in itemRange || text.isBlank()) {
                issues += StructuredOutputIssue(StructuredOutputIssueCode.VALUE_INVALID, "$path.$key[$index]")
            }
        }
    }

    private fun missing(key: String) {
        issues += StructuredOutputIssue(StructuredOutputIssueCode.REQUIRED_FIELD_MISSING, "$path.$key")
    }

    private fun type(key: String) {
        issues += StructuredOutputIssue(StructuredOutputIssueCode.TYPE_MISMATCH, "$path.$key")
    }

    private fun invalid(key: String) {
        issues += StructuredOutputIssue(StructuredOutputIssueCode.VALUE_INVALID, "$path.$key")
    }
}

private fun JsonObject.objectValue(key: String): JsonObject = getValue(key) as JsonObject
private fun JsonObject.objectValues(key: String): List<JsonObject> = (getValue(key) as JsonArray).map { it as JsonObject }
private fun JsonObject.stringValue(key: String): String = (getValue(key) as JsonPrimitive).content
private fun JsonObject.nullableStringValue(key: String): String? =
    (get(key) as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull
private fun JsonObject.stringValues(key: String): List<String> =
    (getValue(key) as JsonArray).map { (it as JsonPrimitive).content }
private fun JsonObject.intValue(key: String): Int = (getValue(key) as JsonPrimitive).intOrNull!!

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString(separator = "") { byte -> "%02x".format(byte) }

private val IDENTIFIER = Regex("[A-Za-z0-9._:-]{1,128}")
private val HASH = Regex("[0-9a-f]{64}")
private val ALLOWED_CANON_LEVELS = setOf(CanonLevel.STORY_CANON.name, CanonLevel.INFERRED.name)
private val ROOT_KEYS = setOf(
    "schemaVersion", "sourceChapterVersionId", "sourceChapterContentHash", "chapterId",
    "chapterIndex", "summary", "entityEvents", "facts",
)
private val SUMMARY_KEYS = setOf(
    "objectiveOutcome", "keyEvents", "decisions", "relationshipChanges", "endingState",
    "unresolvedQuestions", "importance",
)
private val EVENT_KEYS = setOf(
    "entityId", "attribute", "relatedEntityId", "oldValue", "newValue",
    "storyTimeExpression", "confidenceMicros", "canonLevel", "evidence",
)
private val FACT_KEYS = setOf(
    "factKind", "entityId", "text", "canonLevel", "confidenceMicros", "conflictGroupId",
)

private val CHAPTER_MEMORY_SCHEMA = """
{"type":"object","additionalProperties":false,"required":["schemaVersion","sourceChapterVersionId","sourceChapterContentHash","chapterId","chapterIndex","summary","entityEvents","facts"],"properties":{"schemaVersion":{"const":1},"sourceChapterVersionId":{"type":"string","minLength":1,"maxLength":128,"pattern":"^[A-Za-z0-9._:-]+$"},"sourceChapterContentHash":{"type":"string","pattern":"^[0-9a-f]{64}$"},"chapterId":{"type":"string","minLength":1,"maxLength":128,"pattern":"^[A-Za-z0-9._:-]+$"},"chapterIndex":{"type":"integer","minimum":1,"maximum":10000},"summary":{"type":"object","additionalProperties":false,"required":["objectiveOutcome","keyEvents","decisions","relationshipChanges","endingState","unresolvedQuestions","importance"],"properties":{"objectiveOutcome":{"type":"string","minLength":1,"maxLength":4000},"keyEvents":{"type":"array","minItems":1,"maxItems":24,"items":{"type":"string","minLength":1,"maxLength":1500}},"decisions":{"type":"array","maxItems":16,"items":{"type":"string","minLength":1,"maxLength":1500}},"relationshipChanges":{"type":"array","maxItems":16,"items":{"type":"string","minLength":1,"maxLength":1500}},"endingState":{"type":"string","minLength":1,"maxLength":4000},"unresolvedQuestions":{"type":"array","maxItems":16,"items":{"type":"string","minLength":1,"maxLength":1500}},"importance":{"type":"integer","minimum":0,"maximum":100}}},"entityEvents":{"type":"array","maxItems":128,"items":{"type":"object","additionalProperties":false,"required":["entityId","attribute","relatedEntityId","oldValue","newValue","storyTimeExpression","confidenceMicros","canonLevel","evidence"],"properties":{"entityId":{"type":"string","minLength":1,"maxLength":128,"pattern":"^[A-Za-z0-9._:-]+$"},"attribute":{"enum":["LOCATION","PHYSICAL_STATE","EMOTIONAL_STATE","GOAL","KNOWLEDGE","RELATIONSHIP","POSSESSION","COMMITMENT","SECRET"]},"relatedEntityId":{"type":["string","null"],"minLength":1,"maxLength":128,"pattern":"^[A-Za-z0-9._:-]+$"},"oldValue":{"type":["string","null"],"minLength":1,"maxLength":2000},"newValue":{"type":"string","minLength":1,"maxLength":2000},"storyTimeExpression":{"type":["string","null"],"minLength":1,"maxLength":500},"confidenceMicros":{"type":"integer","minimum":0,"maximum":1000000},"canonLevel":{"enum":["STORY_CANON","INFERRED"]},"evidence":{"type":"string","minLength":1,"maxLength":2000}}}},"facts":{"type":"array","maxItems":128,"items":{"type":"object","additionalProperties":false,"required":["factKind","entityId","text","canonLevel","confidenceMicros","conflictGroupId"],"properties":{"factKind":{"enum":["WORLD_EVENT","CHARACTER_STATE","RELATIONSHIP","DISCOVERY","POSSESSION","COMMITMENT","SECRET","LOCATION"]},"entityId":{"type":["string","null"],"minLength":1,"maxLength":128,"pattern":"^[A-Za-z0-9._:-]+$"},"text":{"type":"string","minLength":1,"maxLength":2000},"canonLevel":{"enum":["STORY_CANON","INFERRED"]},"confidenceMicros":{"type":"integer","minimum":0,"maximum":1000000},"conflictGroupId":{"type":["string","null"],"minLength":1,"maxLength":128,"pattern":"^[A-Za-z0-9._:-]+$"}}}}}
""".trimIndent()

package app.zhijuan.feature.generation

import app.zhijuan.core.model.AdultStatus
import app.zhijuan.core.task.FirstChapterProgressionPolicyV1
import app.zhijuan.provider.common.ProviderJsonSchema
import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

data class FirstChapterBootstrapCharacterV1(
    val entityId: String,
    val ageYears: Int?,
    val adultStatus: AdultStatus,
    val realIdentifiablePerson: Boolean,
    val intimacyRole: Boolean,
)

data class FirstChapterRoughPlanV1(
    val chapterIndex: Int,
    val goal: String,
    val conflict: String,
    val turn: String,
    val outcome: String,
    val hook: String,
)

data class FirstChapterBootstrapV1(
    val contractVersion: String,
    val seedContentHash: String,
    val characters: List<FirstChapterBootstrapCharacterV1>,
    val coreWorldRules: List<String>,
    val endingDirection: String,
    val roughChapters: List<FirstChapterRoughPlanV1>,
    val pointOfViewEntityId: String,
    val openingState: String,
    val sceneSequence: List<String>,
    val closingState: String,
    val finalHook: String,
    val canonicalJson: String,
    val contentHash: String,
) {
    override fun toString(): String =
        "FirstChapterBootstrapV1(characterCount=${characters.size}, roughChapterCount=${roughChapters.size}, " +
            "sceneCount=${sceneSequence.size}, content=redacted)"
}

enum class FirstChapterBootstrapCrossIssueCode {
    CONTRACT_VERSION_MISMATCH,
    SEED_HASH_MISMATCH,
    ENDING_DIRECTION_MISMATCH,
    CHARACTER_SET_MISMATCH,
    CHARACTER_FACT_MISMATCH,
    INTIMACY_ROLE_NOT_CONFIRMED_ADULT,
    REAL_PERSON_IN_INTIMACY_ROLE,
    POV_CHARACTER_MISSING,
    ROUGH_CHAPTER_SEQUENCE_MISMATCH,
}

data class FirstChapterBootstrapCrossIssue(
    val code: FirstChapterBootstrapCrossIssueCode,
    val reference: String,
)

sealed interface FirstChapterBootstrapValidationResult {
    data class Valid(val bootstrap: FirstChapterBootstrapV1) : FirstChapterBootstrapValidationResult

    data class Invalid(val issues: List<FirstChapterBootstrapCrossIssue>) : FirstChapterBootstrapValidationResult {
        init {
            require(issues.isNotEmpty() && issues.size <= 32)
        }

        override fun toString(): String =
            "FirstChapterBootstrapValidationResult.Invalid(issueCodes=${issues.map { it.code }.distinct()}, " +
                "content=redacted)"
    }
}

class FirstChapterBootstrapOutputParser(
    private val validator: StructuredOutputValidator = StructuredOutputValidator(),
) {
    fun parse(source: ByteArray): PlanningOutputValidationResult<FirstChapterBootstrapV1> =
        when (val result = validator.validate(source, FirstChapterBootstrapOutputContractV1)) {
            is StructuredOutputValidationResult.Invalid -> PlanningOutputValidationResult.Invalid(result.report)
            is StructuredOutputValidationResult.Valid -> PlanningOutputValidationResult.Valid(
                result.output.withDocument(::toBootstrap),
            )
        }

    private fun toBootstrap(document: JsonObject): FirstChapterBootstrapV1 {
        val chapterOnePlan = document.objectValue("chapterOnePlan")
        val canonical = document.toString()
        return FirstChapterBootstrapV1(
            contractVersion = document.stringValue("contractVersion"),
            seedContentHash = document.stringValue("seedContentHash"),
            characters = document.objectValues("characters").map { character ->
                FirstChapterBootstrapCharacterV1(
                    entityId = character.stringValue("entityId"),
                    ageYears = character.nullableIntValue("ageYears"),
                    adultStatus = AdultStatus.valueOf(character.stringValue("adultStatus")),
                    realIdentifiablePerson = character.booleanValue("realIdentifiablePerson"),
                    intimacyRole = character.booleanValue("intimacyRole"),
                )
            },
            coreWorldRules = document.stringValues("coreWorldRules"),
            endingDirection = document.stringValue("endingDirection"),
            roughChapters = document.objectValues("roughChapters").map { chapter ->
                FirstChapterRoughPlanV1(
                    chapterIndex = chapter.intValue("chapterIndex"),
                    goal = chapter.stringValue("goal"),
                    conflict = chapter.stringValue("conflict"),
                    turn = chapter.stringValue("turn"),
                    outcome = chapter.stringValue("outcome"),
                    hook = chapter.stringValue("hook"),
                )
            },
            pointOfViewEntityId = chapterOnePlan.stringValue("pointOfViewEntityId"),
            openingState = chapterOnePlan.stringValue("openingState"),
            sceneSequence = chapterOnePlan.stringValues("sceneSequence"),
            closingState = chapterOnePlan.stringValue("closingState"),
            finalHook = chapterOnePlan.stringValue("finalHook"),
            canonicalJson = canonical,
            contentHash = sha256(canonical),
        )
    }
}

object FirstChapterBootstrapValidator {
    fun validate(
        bootstrap: FirstChapterBootstrapV1,
        seed: StorySeedV1,
    ): FirstChapterBootstrapValidationResult {
        val expectedCharacters = seed.characters.associateBy(StorySeedCharacterV1::entityId)
        val actualCharacters = bootstrap.characters.associateBy(FirstChapterBootstrapCharacterV1::entityId)
        val issues = buildList {
            if (bootstrap.contractVersion != FirstChapterProgressionPolicyV1.FAST_LANE_CONTRACT_VERSION) {
                add(issue(FirstChapterBootstrapCrossIssueCode.CONTRACT_VERSION_MISMATCH, "contractVersion"))
            }
            if (bootstrap.seedContentHash != seed.contentHash) {
                add(issue(FirstChapterBootstrapCrossIssueCode.SEED_HASH_MISMATCH, "seedContentHash"))
            }
            if (bootstrap.endingDirection != seed.endingDirection) {
                add(issue(FirstChapterBootstrapCrossIssueCode.ENDING_DIRECTION_MISMATCH, "endingDirection"))
            }
            if (actualCharacters.keys != expectedCharacters.keys) {
                add(issue(FirstChapterBootstrapCrossIssueCode.CHARACTER_SET_MISMATCH, "characters"))
            }
            expectedCharacters.forEach { (entityId, expected) ->
                val actual = actualCharacters[entityId] ?: return@forEach
                if (
                    actual.ageYears != expected.ageYears ||
                    actual.adultStatus != expected.adultStatus ||
                    actual.realIdentifiablePerson != expected.realIdentifiablePerson ||
                    actual.intimacyRole != expected.intimacyRole
                ) {
                    add(issue(FirstChapterBootstrapCrossIssueCode.CHARACTER_FACT_MISMATCH, entityId))
                }
                if (
                    actual.intimacyRole &&
                    (actual.adultStatus != AdultStatus.CONFIRMED_ADULT || actual.ageYears == null || actual.ageYears < 18)
                ) {
                    add(
                        issue(
                            FirstChapterBootstrapCrossIssueCode.INTIMACY_ROLE_NOT_CONFIRMED_ADULT,
                            entityId,
                        ),
                    )
                }
                if (actual.intimacyRole && actual.realIdentifiablePerson) {
                    add(issue(FirstChapterBootstrapCrossIssueCode.REAL_PERSON_IN_INTIMACY_ROLE, entityId))
                }
            }
            if (bootstrap.pointOfViewEntityId !in actualCharacters) {
                add(issue(FirstChapterBootstrapCrossIssueCode.POV_CHARACTER_MISSING, "pointOfViewEntityId"))
            }
            if (
                bootstrap.roughChapters.map(FirstChapterRoughPlanV1::chapterIndex) !=
                (1..FirstChapterProgressionPolicyV1.REQUIRED_ROUGH_CHAPTER_COUNT).toList()
            ) {
                add(issue(FirstChapterBootstrapCrossIssueCode.ROUGH_CHAPTER_SEQUENCE_MISMATCH, "roughChapters"))
            }
        }.distinct().take(32)
        return if (issues.isEmpty()) {
            FirstChapterBootstrapValidationResult.Valid(bootstrap)
        } else {
            FirstChapterBootstrapValidationResult.Invalid(issues)
        }
    }

    private fun issue(code: FirstChapterBootstrapCrossIssueCode, reference: String) =
        FirstChapterBootstrapCrossIssue(code, reference)
}

object FirstChapterBootstrapOutputContractV1 : StructuredOutputContract {
    override val schemaId = FirstChapterProgressionPolicyV1.FAST_LANE_OUTPUT_SCHEMA_ID
    override val currentSchemaVersion = 1
    override val providerSchema = ProviderJsonSchema.from(FIRST_CHAPTER_BOOTSTRAP_SCHEMA)

    override fun validate(document: JsonObject): List<StructuredOutputIssue> = buildList {
        val root = BootstrapReader(document, "$", this)
        root.exactKeys(ROOT_KEYS)
        root.exactInt("schemaVersion", 1)
        root.exactString("contractVersion", FirstChapterProgressionPolicyV1.FAST_LANE_CONTRACT_VERSION)
        root.hash("seedContentHash")
        root.string("endingDirection", 1..4_000)
        root.strings("coreWorldRules", 1..24, 1..2_000)

        val characters = root.objects("characters", 1..16)
        characters.forEachIndexed { index, character ->
            val reader = BootstrapReader(character, "$.characters[$index]", this)
            reader.exactKeys(CHARACTER_KEYS)
            reader.identifier("entityId")
            val age = reader.nullableInt("ageYears", 0..200)
            val adultStatus = reader.enum(
                "adultStatus",
                setOf(
                    AdultStatus.CONFIRMED_ADULT.name,
                    AdultStatus.UNKNOWN.name,
                    AdultStatus.NOT_ADULT.name,
                ),
            )
            reader.boolean("realIdentifiablePerson")
            val intimacyRole = reader.boolean("intimacyRole")
            if (
                intimacyRole == true &&
                (adultStatus != AdultStatus.CONFIRMED_ADULT.name || age == null || age < 18)
            ) {
                add(StructuredOutputIssue(StructuredOutputIssueCode.VALUE_INVALID, "$.characters[$index]"))
            }
        }
        duplicate(characters.mapNotNull { it.stringOrNull("entityId") }, "$.characters", this)

        val rough = root.objects("roughChapters", 3..3)
        rough.forEachIndexed { index, chapter ->
            val reader = BootstrapReader(chapter, "$.roughChapters[$index]", this)
            reader.exactKeys(ROUGH_CHAPTER_KEYS)
            reader.int("chapterIndex", 1..3)
            listOf("goal", "conflict", "turn", "outcome", "hook").forEach {
                reader.string(it, 1..1_500)
            }
        }
        if (rough.mapNotNull { it.intOrNull("chapterIndex") } != listOf(1, 2, 3)) {
            add(StructuredOutputIssue(StructuredOutputIssueCode.VALUE_INVALID, "$.roughChapters"))
        }

        val plan = root.objectValue("chapterOnePlan")
        val planReader = BootstrapReader(plan, "$.chapterOnePlan", this)
        planReader.exactKeys(CHAPTER_ONE_PLAN_KEYS)
        planReader.identifier("pointOfViewEntityId")
        planReader.string("openingState", 1..2_000)
        planReader.strings("sceneSequence", 1..12, 1..2_000)
        planReader.string("closingState", 1..2_000)
        planReader.string("finalHook", 1..1_500)
    }
}

private class BootstrapReader(
    private val value: JsonObject,
    private val path: String,
    private val issues: MutableList<StructuredOutputIssue>,
) {
    fun exactKeys(expected: Set<String>) {
        expected.filterNot(value::containsKey).forEach { missing(it) }
        value.keys.filterNot(expected::contains).forEach {
            issues += StructuredOutputIssue(StructuredOutputIssueCode.UNKNOWN_FIELD, "$path.$it")
        }
    }

    fun exactInt(key: String, expected: Int) {
        if (int(key, expected..expected) != expected) invalid(key)
    }

    fun exactString(key: String, expected: String) {
        if (string(key, expected.length..expected.length) != expected) invalid(key)
    }

    fun string(key: String, range: IntRange): String? {
        val result = (value[key] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull
        if (result == null) type(key) else if (result.isBlank() || result.length !in range) invalid(key)
        return result
    }

    fun identifier(key: String) {
        val result = string(key, 1..128)
        if (result != null && !IDENTIFIER.matches(result)) invalid(key)
    }

    fun hash(key: String) {
        val result = string(key, 64..64)
        if (result != null && !HASH.matches(result)) invalid(key)
    }

    fun enum(key: String, allowed: Set<String>): String? {
        val result = string(key, 1..64)
        if (result != null && result !in allowed) invalid(key)
        return result
    }

    fun int(key: String, range: IntRange): Int? {
        val result = (value[key] as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.intOrNull
        if (result == null) type(key) else if (result !in range) invalid(key)
        return result
    }

    fun nullableInt(key: String, range: IntRange): Int? {
        val element = value[key] ?: run { missing(key); return null }
        if (element is kotlinx.serialization.json.JsonNull) return null
        val result = (element as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.intOrNull
        if (result == null) type(key) else if (result !in range) invalid(key)
        return result
    }

    fun boolean(key: String): Boolean? {
        val result = (value[key] as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.booleanOrNull
        if (result == null) type(key)
        return result
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
                if (it == null) {
                    issues += StructuredOutputIssue(StructuredOutputIssueCode.TYPE_MISMATCH, "$path.$key[$index]")
                }
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
            } else if (text.isBlank() || text.length !in itemRange) {
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

    private companion object {
        val IDENTIFIER = Regex("[A-Za-z0-9._:-]{1,128}")
        val HASH = Regex("[0-9a-f]{64}")
    }
}

private fun JsonObject.objectValue(key: String): JsonObject = getValue(key) as JsonObject
private fun JsonObject.objectValues(key: String): List<JsonObject> = (getValue(key) as JsonArray).map { it as JsonObject }
private fun JsonObject.stringValue(key: String): String = (getValue(key) as JsonPrimitive).content
private fun JsonObject.stringValues(key: String): List<String> = (getValue(key) as JsonArray).map { (it as JsonPrimitive).content }
private fun JsonObject.intValue(key: String): Int = (getValue(key) as JsonPrimitive).intOrNull!!
private fun JsonObject.nullableIntValue(key: String): Int? = (getValue(key) as? JsonPrimitive)?.intOrNull
private fun JsonObject.booleanValue(key: String): Boolean = (getValue(key) as JsonPrimitive).booleanOrNull!!
private fun JsonObject.stringOrNull(key: String): String? =
    (get(key) as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull
private fun JsonObject.intOrNull(key: String): Int? =
    (get(key) as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.intOrNull

private fun duplicate(values: List<String>, path: String, issues: MutableList<StructuredOutputIssue>) {
    if (values.distinct().size != values.size) {
        issues += StructuredOutputIssue(StructuredOutputIssueCode.VALUE_INVALID, path)
    }
}

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString(separator = "") { byte -> "%02x".format(byte) }

private val ROOT_KEYS = setOf(
    "schemaVersion", "contractVersion", "seedContentHash", "characters", "coreWorldRules",
    "endingDirection", "roughChapters", "chapterOnePlan",
)
private val CHARACTER_KEYS = setOf(
    "entityId", "ageYears", "adultStatus", "realIdentifiablePerson", "intimacyRole",
)
private val ROUGH_CHAPTER_KEYS = setOf("chapterIndex", "goal", "conflict", "turn", "outcome", "hook")
private val CHAPTER_ONE_PLAN_KEYS = setOf(
    "pointOfViewEntityId", "openingState", "sceneSequence", "closingState", "finalHook",
)

private val FIRST_CHAPTER_BOOTSTRAP_SCHEMA = """
{"type":"object","additionalProperties":false,"required":["schemaVersion","contractVersion","seedContentHash","characters","coreWorldRules","endingDirection","roughChapters","chapterOnePlan"],"properties":{"schemaVersion":{"const":1},"contractVersion":{"const":"zhijuan.first-chapter-fast-lane.v1"},"seedContentHash":{"type":"string","pattern":"^[0-9a-f]{64}$"},"characters":{"type":"array","minItems":1,"maxItems":16,"items":{"type":"object","additionalProperties":false,"required":["entityId","ageYears","adultStatus","realIdentifiablePerson","intimacyRole"],"properties":{"entityId":{"type":"string","minLength":1,"maxLength":128,"pattern":"^[A-Za-z0-9._:-]+$"},"ageYears":{"type":["integer","null"],"minimum":0,"maximum":200},"adultStatus":{"enum":["CONFIRMED_ADULT","UNKNOWN","NOT_ADULT"]},"realIdentifiablePerson":{"type":"boolean"},"intimacyRole":{"type":"boolean"}}}},"coreWorldRules":{"type":"array","minItems":1,"maxItems":24,"items":{"type":"string","minLength":1,"maxLength":2000}},"endingDirection":{"type":"string","minLength":1,"maxLength":4000},"roughChapters":{"type":"array","minItems":3,"maxItems":3,"items":{"type":"object","additionalProperties":false,"required":["chapterIndex","goal","conflict","turn","outcome","hook"],"properties":{"chapterIndex":{"type":"integer","minimum":1,"maximum":3},"goal":{"type":"string","minLength":1,"maxLength":1500},"conflict":{"type":"string","minLength":1,"maxLength":1500},"turn":{"type":"string","minLength":1,"maxLength":1500},"outcome":{"type":"string","minLength":1,"maxLength":1500},"hook":{"type":"string","minLength":1,"maxLength":1500}}}},"chapterOnePlan":{"type":"object","additionalProperties":false,"required":["pointOfViewEntityId","openingState","sceneSequence","closingState","finalHook"],"properties":{"pointOfViewEntityId":{"type":"string","minLength":1,"maxLength":128,"pattern":"^[A-Za-z0-9._:-]+$"},"openingState":{"type":"string","minLength":1,"maxLength":2000},"sceneSequence":{"type":"array","minItems":1,"maxItems":12,"items":{"type":"string","minLength":1,"maxLength":2000}},"closingState":{"type":"string","minLength":1,"maxLength":2000},"finalHook":{"type":"string","minLength":1,"maxLength":1500}}}}}
""".trimIndent()

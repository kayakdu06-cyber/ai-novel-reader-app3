package app.zhijuan.feature.generation

import app.zhijuan.core.model.AdultStatus
import app.zhijuan.provider.common.ProviderJsonSchema
import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

data class StorySeedCharacterV1(
    val entityId: String,
    val name: String,
    val ageYears: Int?,
    val adultStatus: AdultStatus,
    val realIdentifiablePerson: Boolean,
    val intimacyRole: Boolean,
    val storyRole: String,
    val desire: String,
    val obstacle: String,
)

data class StorySeedV1(
    val targetChapterCount: Int,
    val premise: String,
    val centralConflict: String,
    val storyPromise: String,
    val endingDirection: String,
    val characters: List<StorySeedCharacterV1>,
    val openQuestions: List<String>,
    val canonicalJson: String,
    val contentHash: String,
) {
    override fun toString(): String =
        "StorySeedV1(targetChapterCount=$targetChapterCount, characterCount=${characters.size}, content=redacted)"
}

data class StoryBibleCharacterV1(
    val entityId: String,
    val canonicalName: String,
    val aliases: List<String>,
    val ageYears: Int?,
    val adultStatus: AdultStatus,
    val realIdentifiablePerson: Boolean,
    val storyRole: String,
    val stableTraits: List<String>,
    val goals: List<String>,
    val boundaries: List<String>,
)

data class StoryBibleWorldRuleV1(
    val ruleId: String,
    val text: String,
)

data class StoryBibleHardFactV1(
    val factId: String,
    val entityId: String?,
    val text: String,
)

data class StoryBibleV1(
    val seedContentHash: String,
    val characters: List<StoryBibleCharacterV1>,
    val worldRules: List<StoryBibleWorldRuleV1>,
    val hardFacts: List<StoryBibleHardFactV1>,
    val themes: List<String>,
    val writingStyle: List<String>,
    val forbiddenChanges: List<String>,
    val canonicalJson: String,
    val contentHash: String,
) {
    override fun toString(): String =
        "StoryBibleV1(characterCount=${characters.size}, worldRuleCount=${worldRules.size}, " +
            "hardFactCount=${hardFacts.size}, content=redacted)"
}

data class MasterOutlineBeatV1(
    val beatId: String,
    val title: String,
    val startChapter: Int,
    val endChapter: Int,
    val goal: String,
    val turningPoint: String,
    val outcome: String,
)

data class MasterOutlineV1(
    val bibleContentHash: String,
    val targetChapterCount: Int,
    val title: String,
    val endingPromise: String,
    val beats: List<MasterOutlineBeatV1>,
    val canonicalJson: String,
    val contentHash: String,
) {
    override fun toString(): String =
        "MasterOutlineV1(targetChapterCount=$targetChapterCount, beatCount=${beats.size}, content=redacted)"
}

sealed interface PlanningOutputValidationResult<out T> {
    data class Valid<T>(val value: T) : PlanningOutputValidationResult<T>
    data class Invalid(val report: StructuredOutputInvalidReport) : PlanningOutputValidationResult<Nothing>
}

enum class InitialPlanningCrossIssueCode {
    TARGET_CHAPTER_COUNT_MISMATCH,
    SEED_HASH_MISMATCH,
    BIBLE_HASH_MISMATCH,
    SEED_CHARACTER_MISSING_FROM_BIBLE,
    CHARACTER_FACT_MISMATCH,
    INTIMACY_ROLE_NOT_CONFIRMED_ADULT,
    REAL_PERSON_IN_INTIMACY_ROLE,
    FACT_REFERENCES_UNKNOWN_ENTITY,
    OUTLINE_RANGE_NOT_CONTIGUOUS,
}

data class InitialPlanningCrossIssue(
    val code: InitialPlanningCrossIssueCode,
    val reference: String,
) {
    init {
        require(reference.matches(REFERENCE)) { "Planning cross-document issue reference is invalid." }
    }

    private companion object {
        val REFERENCE = Regex("[A-Za-z0-9._:-]{1,128}")
    }
}

sealed interface InitialPlanningBundleValidationResult {
    data class Valid(
        val seed: StorySeedV1,
        val bible: StoryBibleV1,
        val outline: MasterOutlineV1,
    ) : InitialPlanningBundleValidationResult

    data class Invalid(val issues: List<InitialPlanningCrossIssue>) : InitialPlanningBundleValidationResult {
        init {
            require(issues.isNotEmpty() && issues.size <= 64)
        }

        override fun toString(): String =
            "InitialPlanningBundleValidationResult.Invalid(issueCodes=${issues.map { it.code }.distinct()}, content=redacted)"
    }
}

class InitialPlanningOutputParser(
    private val validator: StructuredOutputValidator = StructuredOutputValidator(),
) {
    fun storySeed(source: ByteArray): PlanningOutputValidationResult<StorySeedV1> =
        validate(source, StorySeedOutputContractV1) { document ->
            StorySeedV1(
                targetChapterCount = document.requiredInt("targetChapterCount"),
                premise = document.requiredString("premise"),
                centralConflict = document.requiredString("centralConflict"),
                storyPromise = document.requiredString("storyPromise"),
                endingDirection = document.requiredString("endingDirection"),
                characters = document.requiredObjects("characters").map { character ->
                    StorySeedCharacterV1(
                        entityId = character.requiredString("entityId"),
                        name = character.requiredString("name"),
                        ageYears = character.optionalInt("ageYears"),
                        adultStatus = AdultStatus.valueOf(character.requiredString("adultStatus")),
                        realIdentifiablePerson = character.requiredBoolean("realIdentifiablePerson"),
                        intimacyRole = character.requiredBoolean("intimacyRole"),
                        storyRole = character.requiredString("storyRole"),
                        desire = character.requiredString("desire"),
                        obstacle = character.requiredString("obstacle"),
                    )
                },
                openQuestions = document.requiredStrings("openQuestions"),
                canonicalJson = document.toString(),
                contentHash = sha256(document.toString()),
            )
        }

    fun storyBible(source: ByteArray): PlanningOutputValidationResult<StoryBibleV1> =
        validate(source, StoryBibleOutputContractV1) { document ->
            StoryBibleV1(
                seedContentHash = document.requiredString("seedContentHash"),
                characters = document.requiredObjects("characters").map { character ->
                    StoryBibleCharacterV1(
                        entityId = character.requiredString("entityId"),
                        canonicalName = character.requiredString("canonicalName"),
                        aliases = character.requiredStrings("aliases"),
                        ageYears = character.optionalInt("ageYears"),
                        adultStatus = AdultStatus.valueOf(character.requiredString("adultStatus")),
                        realIdentifiablePerson = character.requiredBoolean("realIdentifiablePerson"),
                        storyRole = character.requiredString("storyRole"),
                        stableTraits = character.requiredStrings("stableTraits"),
                        goals = character.requiredStrings("goals"),
                        boundaries = character.requiredStrings("boundaries"),
                    )
                },
                worldRules = document.requiredObjects("worldRules").map { rule ->
                    StoryBibleWorldRuleV1(rule.requiredString("ruleId"), rule.requiredString("text"))
                },
                hardFacts = document.requiredObjects("hardFacts").map { fact ->
                    StoryBibleHardFactV1(
                        factId = fact.requiredString("factId"),
                        entityId = fact.optionalString("entityId"),
                        text = fact.requiredString("text"),
                    )
                },
                themes = document.requiredStrings("themes"),
                writingStyle = document.requiredStrings("writingStyle"),
                forbiddenChanges = document.requiredStrings("forbiddenChanges"),
                canonicalJson = document.toString(),
                contentHash = sha256(document.toString()),
            )
        }

    fun masterOutline(source: ByteArray): PlanningOutputValidationResult<MasterOutlineV1> =
        validate(source, MasterOutlineOutputContractV1) { document ->
            MasterOutlineV1(
                bibleContentHash = document.requiredString("bibleContentHash"),
                targetChapterCount = document.requiredInt("targetChapterCount"),
                title = document.requiredString("title"),
                endingPromise = document.requiredString("endingPromise"),
                beats = document.requiredObjects("beats").map { beat ->
                    MasterOutlineBeatV1(
                        beatId = beat.requiredString("beatId"),
                        title = beat.requiredString("title"),
                        startChapter = beat.requiredInt("startChapter"),
                        endChapter = beat.requiredInt("endChapter"),
                        goal = beat.requiredString("goal"),
                        turningPoint = beat.requiredString("turningPoint"),
                        outcome = beat.requiredString("outcome"),
                    )
                },
                canonicalJson = document.toString(),
                contentHash = sha256(document.toString()),
            )
        }

    private fun <T> validate(
        source: ByteArray,
        contract: StructuredOutputContract,
        parse: (JsonObject) -> T,
    ): PlanningOutputValidationResult<T> = when (val result = validator.validate(source, contract)) {
        is StructuredOutputValidationResult.Invalid -> PlanningOutputValidationResult.Invalid(result.report)
        is StructuredOutputValidationResult.Valid -> PlanningOutputValidationResult.Valid(
            result.output.withDocument(parse),
        )
    }
}

object InitialPlanningBundleValidator {
    fun validate(
        seed: StorySeedV1,
        bible: StoryBibleV1,
        outline: MasterOutlineV1,
        expectedTargetChapterCount: Int,
    ): InitialPlanningBundleValidationResult {
        require(expectedTargetChapterCount in 80..10_000)
        val issues = (
            validateSeed(seed, expectedTargetChapterCount) +
                validateBible(seed, bible) +
                validateOutline(bible, outline, expectedTargetChapterCount)
            ).distinct().take(64)
        return if (issues.isEmpty()) {
            InitialPlanningBundleValidationResult.Valid(seed, bible, outline)
        } else {
            InitialPlanningBundleValidationResult.Invalid(issues)
        }
    }

    fun validateSeed(
        seed: StorySeedV1,
        expectedTargetChapterCount: Int,
    ): List<InitialPlanningCrossIssue> {
        require(expectedTargetChapterCount in 80..10_000)
        return buildList {
            if (seed.targetChapterCount != expectedTargetChapterCount) {
                add(
                    InitialPlanningCrossIssue(
                        InitialPlanningCrossIssueCode.TARGET_CHAPTER_COUNT_MISMATCH,
                        "targetChapterCount",
                    ),
                )
            }
            seed.characters.filter(StorySeedCharacterV1::intimacyRole).forEach { character ->
                if (
                    character.adultStatus != AdultStatus.CONFIRMED_ADULT ||
                    character.ageYears == null || character.ageYears < 18
                ) {
                    add(
                        InitialPlanningCrossIssue(
                            InitialPlanningCrossIssueCode.INTIMACY_ROLE_NOT_CONFIRMED_ADULT,
                            character.entityId,
                        ),
                    )
                }
                if (character.realIdentifiablePerson) {
                    add(
                        InitialPlanningCrossIssue(
                            InitialPlanningCrossIssueCode.REAL_PERSON_IN_INTIMACY_ROLE,
                            character.entityId,
                        ),
                    )
                }
            }
        }.distinct()
    }

    fun validateBible(
        seed: StorySeedV1,
        bible: StoryBibleV1,
    ): List<InitialPlanningCrossIssue> = buildList {
        if (bible.seedContentHash != seed.contentHash) {
            add(InitialPlanningCrossIssue(InitialPlanningCrossIssueCode.SEED_HASH_MISMATCH, "seedContentHash"))
        }
        val bibleCharacters = bible.characters.associateBy(StoryBibleCharacterV1::entityId)
        seed.characters.forEach { seedCharacter ->
            val bibleCharacter = bibleCharacters[seedCharacter.entityId]
            if (bibleCharacter == null) {
                add(
                    InitialPlanningCrossIssue(
                        InitialPlanningCrossIssueCode.SEED_CHARACTER_MISSING_FROM_BIBLE,
                        seedCharacter.entityId,
                    ),
                )
            } else if (
                seedCharacter.name != bibleCharacter.canonicalName ||
                seedCharacter.ageYears != bibleCharacter.ageYears ||
                seedCharacter.adultStatus != bibleCharacter.adultStatus ||
                seedCharacter.realIdentifiablePerson != bibleCharacter.realIdentifiablePerson
            ) {
                add(
                    InitialPlanningCrossIssue(
                        InitialPlanningCrossIssueCode.CHARACTER_FACT_MISMATCH,
                        seedCharacter.entityId,
                    ),
                )
            }
        }
        bible.hardFacts.forEach { fact ->
            if (fact.entityId != null && fact.entityId !in bibleCharacters) {
                add(
                    InitialPlanningCrossIssue(
                        InitialPlanningCrossIssueCode.FACT_REFERENCES_UNKNOWN_ENTITY,
                        fact.factId,
                    ),
                )
            }
        }
    }.distinct()

    fun validateOutline(
        bible: StoryBibleV1,
        outline: MasterOutlineV1,
        expectedTargetChapterCount: Int,
    ): List<InitialPlanningCrossIssue> {
        require(expectedTargetChapterCount in 80..10_000)
        return buildList {
            if (outline.targetChapterCount != expectedTargetChapterCount) {
                add(
                    InitialPlanningCrossIssue(
                        InitialPlanningCrossIssueCode.TARGET_CHAPTER_COUNT_MISMATCH,
                        "targetChapterCount",
                    ),
                )
            }
            if (outline.bibleContentHash != bible.contentHash) {
                add(
                    InitialPlanningCrossIssue(
                        InitialPlanningCrossIssueCode.BIBLE_HASH_MISMATCH,
                        "bibleContentHash",
                    ),
                )
            }
            val contiguous = outline.beats.first().startChapter == 1 &&
                outline.beats.last().endChapter == expectedTargetChapterCount &&
                outline.beats.zipWithNext().all { (first, second) ->
                    first.endChapter + 1 == second.startChapter
                }
            if (!contiguous) {
                add(
                    InitialPlanningCrossIssue(
                        InitialPlanningCrossIssueCode.OUTLINE_RANGE_NOT_CONTIGUOUS,
                        "beats",
                    ),
                )
            }
        }.distinct()
    }
}

object StorySeedOutputContractV1 : StructuredOutputContract {
    override val schemaId = "story-seed.v1"
    override val currentSchemaVersion = 1
    override val providerSchema = ProviderJsonSchema.from(STORY_SEED_SCHEMA)

    override fun validate(document: JsonObject): List<StructuredOutputIssue> = buildList {
        val root = ContractReader(document, "$", this)
        root.exactKeys(SEED_ROOT_KEYS)
        root.exactInt("schemaVersion", 1)
        root.int("targetChapterCount", 80..10_000)
        listOf("premise", "centralConflict", "storyPromise", "endingDirection").forEach {
            root.string(it, 1..4_000)
        }
        val characters = root.objects("characters", 1..16)
        characters.forEachIndexed { index, character ->
            val reader = ContractReader(character, "$.characters[$index]", this)
            reader.exactKeys(SEED_CHARACTER_KEYS)
            reader.identifier("entityId")
            reader.string("name", 1..120)
            reader.optionalAge("ageYears")
            reader.adultStatus("adultStatus")
            reader.boolean("realIdentifiablePerson")
            reader.boolean("intimacyRole")
            listOf("storyRole", "desire", "obstacle").forEach { reader.string(it, 1..1_000) }
        }
        duplicateValues(characters.mapNotNull { it.stringOrNull("entityId") }, "$.characters", this)
        root.strings("openQuestions", 0..16, 1..1_000)
    }
}

object StoryBibleOutputContractV1 : StructuredOutputContract {
    override val schemaId = "story-bible.v1"
    override val currentSchemaVersion = 1
    override val providerSchema = ProviderJsonSchema.from(STORY_BIBLE_SCHEMA)

    override fun validate(document: JsonObject): List<StructuredOutputIssue> = buildList {
        val root = ContractReader(document, "$", this)
        root.exactKeys(BIBLE_ROOT_KEYS)
        root.exactInt("schemaVersion", 1)
        root.hash("seedContentHash")
        val characters = root.objects("characters", 1..64)
        characters.forEachIndexed { index, character ->
            val reader = ContractReader(character, "$.characters[$index]", this)
            reader.exactKeys(BIBLE_CHARACTER_KEYS)
            reader.identifier("entityId")
            reader.string("canonicalName", 1..120)
            reader.strings("aliases", 0..16, 1..120)
            reader.optionalAge("ageYears")
            reader.adultStatus("adultStatus")
            reader.boolean("realIdentifiablePerson")
            reader.string("storyRole", 1..1_000)
            reader.strings("stableTraits", 1..24, 1..500)
            reader.strings("goals", 1..16, 1..1_000)
            reader.strings("boundaries", 0..24, 1..1_000)
        }
        duplicateValues(characters.mapNotNull { it.stringOrNull("entityId") }, "$.characters", this)
        val rules = root.objects("worldRules", 1..128)
        rules.forEachIndexed { index, value ->
            val reader = ContractReader(value, "$.worldRules[$index]", this)
            reader.exactKeys(setOf("ruleId", "text"))
            reader.identifier("ruleId")
            reader.string("text", 1..2_000)
        }
        duplicateValues(rules.mapNotNull { it.stringOrNull("ruleId") }, "$.worldRules", this)
        val facts = root.objects("hardFacts", 1..256)
        facts.forEachIndexed { index, value ->
            val reader = ContractReader(value, "$.hardFacts[$index]", this)
            reader.exactKeys(setOf("factId", "entityId", "text"))
            reader.identifier("factId")
            reader.optionalIdentifier("entityId")
            reader.string("text", 1..2_000)
        }
        duplicateValues(facts.mapNotNull { it.stringOrNull("factId") }, "$.hardFacts", this)
        root.strings("themes", 1..16, 1..500)
        root.strings("writingStyle", 1..32, 1..1_000)
        root.strings("forbiddenChanges", 1..64, 1..2_000)
    }
}

object MasterOutlineOutputContractV1 : StructuredOutputContract {
    override val schemaId = "master-outline.v1"
    override val currentSchemaVersion = 1
    override val providerSchema = ProviderJsonSchema.from(MASTER_OUTLINE_SCHEMA)

    override fun validate(document: JsonObject): List<StructuredOutputIssue> = buildList {
        val root = ContractReader(document, "$", this)
        root.exactKeys(OUTLINE_ROOT_KEYS)
        root.exactInt("schemaVersion", 1)
        root.hash("bibleContentHash")
        root.int("targetChapterCount", 80..10_000)
        root.string("title", 1..200)
        root.string("endingPromise", 1..4_000)
        val beats = root.objects("beats", 3..16)
        beats.forEachIndexed { index, value ->
            val reader = ContractReader(value, "$.beats[$index]", this)
            reader.exactKeys(OUTLINE_BEAT_KEYS)
            reader.identifier("beatId")
            reader.string("title", 1..200)
            val start = reader.int("startChapter", 1..10_000)
            val end = reader.int("endChapter", 1..10_000)
            if (start != null && end != null && start > end) {
                add(StructuredOutputIssue(StructuredOutputIssueCode.VALUE_INVALID, "$.beats[$index].endChapter"))
            }
            listOf("goal", "turningPoint", "outcome").forEach { reader.string(it, 1..2_000) }
        }
        duplicateValues(beats.mapNotNull { it.stringOrNull("beatId") }, "$.beats", this)
    }
}

private class ContractReader(
    private val objectValue: JsonObject,
    private val path: String,
    private val issues: MutableList<StructuredOutputIssue>,
) {
    fun exactKeys(allowed: Set<String>) {
        allowed.filterNot(objectValue::containsKey).forEach { missing ->
            issues += StructuredOutputIssue(StructuredOutputIssueCode.REQUIRED_FIELD_MISSING, "$path.$missing")
        }
        objectValue.keys.filterNot(allowed::contains).forEach { unknown ->
            issues += StructuredOutputIssue(StructuredOutputIssueCode.UNKNOWN_FIELD, "$path.$unknown")
        }
    }

    fun exactInt(key: String, expected: Int): Int? {
        val value = int(key, expected..expected)
        return value
    }

    fun int(key: String, range: IntRange): Int? {
        val value = (objectValue[key] as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.intOrNull
        if (value == null) {
            typeIssue(key)
        } else if (value !in range) {
            valueIssue(key)
        }
        return value
    }

    fun optionalAge(key: String): Int? {
        val element = objectValue[key]
        if (element == null) return missing(key)
        if (element === JsonNull) return null
        val value = (element as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.intOrNull
        if (value == null) typeIssue(key) else if (value !in 0..200) valueIssue(key)
        return value
    }

    fun string(key: String, range: IntRange): String? {
        val value = (objectValue[key] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull
        if (value == null) {
            typeIssue(key)
        } else if (value.length !in range || value != value.trim() || value.any(Char::isISOControl)) {
            valueIssue(key)
        }
        return value
    }

    fun identifier(key: String): String? {
        val value = string(key, 1..128)
        if (value != null && !IDENTIFIER.matches(value)) valueIssue(key)
        return value
    }

    fun optionalIdentifier(key: String): String? {
        val element = objectValue[key]
        if (element == null) return missing(key)
        if (element === JsonNull) return null
        val value = (element as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull
        if (value == null) typeIssue(key) else if (!IDENTIFIER.matches(value)) valueIssue(key)
        return value
    }

    fun hash(key: String): String? {
        val value = string(key, 64..64)
        if (value != null && !HASH.matches(value)) valueIssue(key)
        return value
    }

    fun adultStatus(key: String): AdultStatus? {
        val value = string(key, 1..64)
        val status = value?.let { runCatching { AdultStatus.valueOf(it) }.getOrNull() }
        if (value != null && status == null) valueIssue(key)
        if (status == AdultStatus.NOT_APPLICABLE) valueIssue(key)
        val age = optionalIntValue("ageYears")
        if (status == AdultStatus.CONFIRMED_ADULT && (age == null || age < 18)) valueIssue(key)
        if (status == AdultStatus.NOT_ADULT && (age == null || age >= 18)) valueIssue(key)
        if (status == AdultStatus.UNKNOWN && age != null) valueIssue(key)
        return status
    }

    fun boolean(key: String): Boolean? {
        val value = (objectValue[key] as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.booleanOrNull
        if (value == null) typeIssue(key)
        return value
    }

    fun objects(key: String, range: IntRange): List<JsonObject> {
        val array = objectValue[key] as? JsonArray
        if (array == null) {
            typeIssue(key)
            return emptyList()
        }
        if (array.size !in range) valueIssue(key)
        return array.mapIndexedNotNull { index, element ->
            (element as? JsonObject).also {
                if (it == null) issues += StructuredOutputIssue(
                    StructuredOutputIssueCode.TYPE_MISMATCH,
                    "$path.$key[$index]",
                )
            }
        }
    }

    fun strings(key: String, countRange: IntRange, lengthRange: IntRange): List<String> {
        val array = objectValue[key] as? JsonArray
        if (array == null) {
            typeIssue(key)
            return emptyList()
        }
        if (array.size !in countRange) valueIssue(key)
        val values = array.mapIndexedNotNull { index, element ->
            val value = (element as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull
            if (value == null) {
                issues += StructuredOutputIssue(StructuredOutputIssueCode.TYPE_MISMATCH, "$path.$key[$index]")
                null
            } else {
                if (value.length !in lengthRange || value != value.trim() || value.any(Char::isISOControl)) {
                    issues += StructuredOutputIssue(StructuredOutputIssueCode.VALUE_INVALID, "$path.$key[$index]")
                }
                value
            }
        }
        duplicateValues(values, "$path.$key", issues)
        return values
    }

    private fun optionalIntValue(key: String): Int? {
        val value = objectValue[key]
        if (value == null || value === JsonNull) return null
        return (value as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.intOrNull
    }

    private fun missing(key: String): Nothing? {
        issues += StructuredOutputIssue(StructuredOutputIssueCode.REQUIRED_FIELD_MISSING, "$path.$key")
        return null
    }

    private fun typeIssue(key: String) {
        issues += StructuredOutputIssue(StructuredOutputIssueCode.TYPE_MISMATCH, "$path.$key")
    }

    private fun valueIssue(key: String) {
        issues += StructuredOutputIssue(StructuredOutputIssueCode.VALUE_INVALID, "$path.$key")
    }
}

private fun JsonObject.requiredString(key: String): String = (getValue(key) as JsonPrimitive).content
private fun JsonObject.requiredInt(key: String): Int = (getValue(key) as JsonPrimitive).intOrNull!!
private fun JsonObject.requiredBoolean(key: String): Boolean = (getValue(key) as JsonPrimitive).booleanOrNull!!
private fun JsonObject.optionalInt(key: String): Int? = getValue(key).takeUnless { it === JsonNull }
    ?.let { (it as JsonPrimitive).intOrNull!! }
private fun JsonObject.optionalString(key: String): String? = getValue(key).takeUnless { it === JsonNull }
    ?.let { (it as JsonPrimitive).content }
private fun JsonObject.requiredObjects(key: String): List<JsonObject> = (getValue(key) as JsonArray).map { it as JsonObject }
private fun JsonObject.requiredStrings(key: String): List<String> = (getValue(key) as JsonArray).map { (it as JsonPrimitive).content }
private fun JsonObject.stringOrNull(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull

private fun duplicateValues(values: List<String>, path: String, issues: MutableList<StructuredOutputIssue>) {
    if (values.distinct().size != values.size) {
        issues += StructuredOutputIssue(StructuredOutputIssueCode.VALUE_INVALID, path)
    }
}

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString(separator = "") { byte -> "%02x".format(byte) }

private val IDENTIFIER = Regex("[A-Za-z0-9._:-]{1,128}")
private val HASH = Regex("[0-9a-f]{64}")
private val SEED_ROOT_KEYS = setOf(
    "schemaVersion", "targetChapterCount", "premise", "centralConflict", "storyPromise",
    "endingDirection", "characters", "openQuestions",
)
private val SEED_CHARACTER_KEYS = setOf(
    "entityId", "name", "ageYears", "adultStatus", "realIdentifiablePerson", "intimacyRole",
    "storyRole", "desire", "obstacle",
)
private val BIBLE_ROOT_KEYS = setOf(
    "schemaVersion", "seedContentHash", "characters", "worldRules", "hardFacts", "themes",
    "writingStyle", "forbiddenChanges",
)
private val BIBLE_CHARACTER_KEYS = setOf(
    "entityId", "canonicalName", "aliases", "ageYears", "adultStatus", "realIdentifiablePerson",
    "storyRole", "stableTraits", "goals", "boundaries",
)
private val OUTLINE_ROOT_KEYS = setOf(
    "schemaVersion", "bibleContentHash", "targetChapterCount", "title", "endingPromise", "beats",
)
private val OUTLINE_BEAT_KEYS = setOf(
    "beatId", "title", "startChapter", "endChapter", "goal", "turningPoint", "outcome",
)

private val STORY_SEED_SCHEMA = """
{"type":"object","additionalProperties":false,"required":["schemaVersion","targetChapterCount","premise","centralConflict","storyPromise","endingDirection","characters","openQuestions"],"properties":{"schemaVersion":{"const":1},"targetChapterCount":{"type":"integer","minimum":80,"maximum":10000},"premise":{"type":"string","minLength":1,"maxLength":4000},"centralConflict":{"type":"string","minLength":1,"maxLength":4000},"storyPromise":{"type":"string","minLength":1,"maxLength":4000},"endingDirection":{"type":"string","minLength":1,"maxLength":4000},"characters":{"type":"array","minItems":1,"maxItems":16,"items":{"type":"object","additionalProperties":false,"required":["entityId","name","ageYears","adultStatus","realIdentifiablePerson","intimacyRole","storyRole","desire","obstacle"],"properties":{"entityId":{"type":"string","minLength":1,"maxLength":128,"pattern":"^[A-Za-z0-9._:-]+$"},"name":{"type":"string","minLength":1,"maxLength":120},"ageYears":{"type":["integer","null"],"minimum":0,"maximum":200},"adultStatus":{"enum":["CONFIRMED_ADULT","UNKNOWN","NOT_ADULT"]},"realIdentifiablePerson":{"type":"boolean"},"intimacyRole":{"type":"boolean"},"storyRole":{"type":"string","minLength":1,"maxLength":1000},"desire":{"type":"string","minLength":1,"maxLength":1000},"obstacle":{"type":"string","minLength":1,"maxLength":1000}}}},"openQuestions":{"type":"array","maxItems":16,"items":{"type":"string","minLength":1,"maxLength":1000}}}}
""".trimIndent()

private val STORY_BIBLE_SCHEMA = """
{"type":"object","additionalProperties":false,"required":["schemaVersion","seedContentHash","characters","worldRules","hardFacts","themes","writingStyle","forbiddenChanges"],"properties":{"schemaVersion":{"const":1},"seedContentHash":{"type":"string","pattern":"^[0-9a-f]{64}$"},"characters":{"type":"array","minItems":1,"maxItems":64,"items":{"type":"object","additionalProperties":false,"required":["entityId","canonicalName","aliases","ageYears","adultStatus","realIdentifiablePerson","storyRole","stableTraits","goals","boundaries"],"properties":{"entityId":{"type":"string","minLength":1,"maxLength":128,"pattern":"^[A-Za-z0-9._:-]+$"},"canonicalName":{"type":"string","minLength":1,"maxLength":120},"aliases":{"type":"array","maxItems":16,"items":{"type":"string","minLength":1,"maxLength":120}},"ageYears":{"type":["integer","null"],"minimum":0,"maximum":200},"adultStatus":{"enum":["CONFIRMED_ADULT","UNKNOWN","NOT_ADULT"]},"realIdentifiablePerson":{"type":"boolean"},"storyRole":{"type":"string","minLength":1,"maxLength":1000},"stableTraits":{"type":"array","minItems":1,"maxItems":24,"items":{"type":"string","minLength":1,"maxLength":500}},"goals":{"type":"array","minItems":1,"maxItems":16,"items":{"type":"string","minLength":1,"maxLength":1000}},"boundaries":{"type":"array","maxItems":24,"items":{"type":"string","minLength":1,"maxLength":1000}}}}},"worldRules":{"type":"array","minItems":1,"maxItems":128,"items":{"type":"object","additionalProperties":false,"required":["ruleId","text"],"properties":{"ruleId":{"type":"string","minLength":1,"maxLength":128,"pattern":"^[A-Za-z0-9._:-]+$"},"text":{"type":"string","minLength":1,"maxLength":2000}}}},"hardFacts":{"type":"array","minItems":1,"maxItems":256,"items":{"type":"object","additionalProperties":false,"required":["factId","entityId","text"],"properties":{"factId":{"type":"string","minLength":1,"maxLength":128,"pattern":"^[A-Za-z0-9._:-]+$"},"entityId":{"type":["string","null"],"maxLength":128},"text":{"type":"string","minLength":1,"maxLength":2000}}}},"themes":{"type":"array","minItems":1,"maxItems":16,"items":{"type":"string","minLength":1,"maxLength":500}},"writingStyle":{"type":"array","minItems":1,"maxItems":32,"items":{"type":"string","minLength":1,"maxLength":1000}},"forbiddenChanges":{"type":"array","minItems":1,"maxItems":64,"items":{"type":"string","minLength":1,"maxLength":2000}}}}
""".trimIndent()

private val MASTER_OUTLINE_SCHEMA = """
{"type":"object","additionalProperties":false,"required":["schemaVersion","bibleContentHash","targetChapterCount","title","endingPromise","beats"],"properties":{"schemaVersion":{"const":1},"bibleContentHash":{"type":"string","pattern":"^[0-9a-f]{64}$"},"targetChapterCount":{"type":"integer","minimum":80,"maximum":10000},"title":{"type":"string","minLength":1,"maxLength":200},"endingPromise":{"type":"string","minLength":1,"maxLength":4000},"beats":{"type":"array","minItems":3,"maxItems":16,"items":{"type":"object","additionalProperties":false,"required":["beatId","title","startChapter","endChapter","goal","turningPoint","outcome"],"properties":{"beatId":{"type":"string","minLength":1,"maxLength":128,"pattern":"^[A-Za-z0-9._:-]+$"},"title":{"type":"string","minLength":1,"maxLength":200},"startChapter":{"type":"integer","minimum":1,"maximum":10000},"endChapter":{"type":"integer","minimum":1,"maximum":10000},"goal":{"type":"string","minLength":1,"maxLength":2000},"turningPoint":{"type":"string","minLength":1,"maxLength":2000},"outcome":{"type":"string","minLength":1,"maxLength":2000}}}}}}
""".trimIndent()

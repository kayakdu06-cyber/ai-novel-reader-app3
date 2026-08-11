package app.zhijuan.feature.generation

import app.zhijuan.core.model.FadePolicy
import app.zhijuan.core.task.SceneExecutionContract
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

data class ChapterPlanProcessNodeV1(
    val nodeId: String,
    val sequence: Int,
    val action: String,
    val reaction: String,
    val spatialStateAfter: String,
    val bodyStateAfter: String,
    val clothingAndObjectStateAfter: String,
    val sensoryChange: String,
) {
    override fun toString(): String =
        "ChapterPlanProcessNodeV1(sequence=$sequence, content=redacted)"
}

data class ChapterPlanSceneV1(
    val sceneId: String,
    val sequence: Int,
    val purpose: String,
    val location: String,
    val pointOfViewCharacterId: String,
    val participantCharacterIds: List<String>,
    val openingState: String,
    val turn: String,
    val closingState: String,
    val continuityCarry: List<String>,
    val intimacyRelevant: Boolean,
    val requiredProcessNodes: List<ChapterPlanProcessNodeV1>,
    val aftermath: String?,
) {
    override fun toString(): String =
        "ChapterPlanSceneV1(sequence=$sequence, participantCount=${participantCharacterIds.size}, " +
            "intimacyRelevant=$intimacyRelevant, processNodeCount=${requiredProcessNodes.size}, content=redacted)"
}

data class ChapterPlanV1(
    val policyVersion: String,
    val chapterId: String,
    val chapterIndex: Int,
    val contextContentHash: String,
    val contextSourceManifestHash: String,
    val openingState: String,
    val chapterGoal: String,
    val closingState: String,
    val finalHook: String,
    val continuityConstraints: List<String>,
    val scenes: List<ChapterPlanSceneV1>,
    val canonicalJson: String,
    val contentHash: String,
) {
    val requiredProcessNodeIds: List<String>
        get() = scenes.flatMap(ChapterPlanSceneV1::requiredProcessNodes)
            .map(ChapterPlanProcessNodeV1::nodeId)
            .sorted()

    override fun toString(): String =
        "ChapterPlanV1(chapterIndex=$chapterIndex, sceneCount=${scenes.size}, " +
            "processNodeCount=${requiredProcessNodeIds.size}, hashes=redacted, content=redacted)"
}

enum class ChapterPlanCrossIssueCode {
    POLICY_VERSION_MISMATCH,
    CHAPTER_ID_MISMATCH,
    CHAPTER_INDEX_MISMATCH,
    CONTEXT_CONTENT_HASH_MISMATCH,
    CONTEXT_MANIFEST_HASH_MISMATCH,
    SCENE_SEQUENCE_MISMATCH,
    DUPLICATE_SCENE_ID,
    UNKNOWN_CHARACTER_REFERENCE,
    POV_NOT_PARTICIPANT,
    UNEXPECTED_INTIMACY_SCENE,
    REQUIRED_INTIMACY_SCENE_MISSING,
    ADULT_FICTIONAL_GATE_MISMATCH,
    PROCESS_NODES_FORBIDDEN,
    REQUIRED_PROCESS_NODES_MISSING,
    PROCESS_NODE_SEQUENCE_MISMATCH,
    DUPLICATE_PROCESS_NODE_ID,
    PROCESS_NODE_LIMIT_EXCEEDED,
    AFTERMATH_MISSING,
}

data class ChapterPlanCrossIssue(
    val code: ChapterPlanCrossIssueCode,
    val reference: String,
) {
    init {
        require(REFERENCE.matches(reference)) { "Chapter-plan cross issue reference is invalid." }
    }

    private companion object {
        val REFERENCE = Regex("[A-Za-z0-9._:-]{1,128}")
    }
}

data class ChapterPlanExpectationV1(
    val chapterId: String,
    val chapterIndex: Int,
    val contextContentHash: String,
    val contextSourceManifestHash: String,
    val knownCharacterIds: Set<String>,
    val confirmedAdultFictionalCharacterIds: Set<String>,
    val sceneExecutionContract: SceneExecutionContract,
) {
    init {
        require(IDENTIFIER.matches(chapterId)) { "Chapter-plan expectation chapter id is invalid." }
        require(chapterIndex in 1..10_000) { "Chapter-plan expectation chapter index is invalid." }
        require(HASH.matches(contextContentHash) && HASH.matches(contextSourceManifestHash)) {
            "Chapter-plan expectation context hashes are invalid."
        }
        require(knownCharacterIds.isNotEmpty() && knownCharacterIds.size <= 512) {
            "Chapter-plan expectation known characters are invalid."
        }
        require(knownCharacterIds.all(IDENTIFIER::matches)) {
            "Chapter-plan expectation contains an invalid known character id."
        }
        require(
            confirmedAdultFictionalCharacterIds.size <= 512 &&
                confirmedAdultFictionalCharacterIds.all(IDENTIFIER::matches) &&
                knownCharacterIds.containsAll(confirmedAdultFictionalCharacterIds),
        ) { "Confirmed adult fictional characters must be a subset of known characters." }
        when (sceneExecutionContract) {
            SceneExecutionContract.NotApplicable -> Unit
            is SceneExecutionContract.Blocked -> error(
                "A blocked scene contract cannot create a chapter-plan expectation.",
            )
            is SceneExecutionContract.Allowed -> {
                require(confirmedAdultFictionalCharacterIds.isNotEmpty()) {
                    "An allowed intimacy-relevant plan requires confirmed adult fictional characters."
                }
                require(sceneExecutionContract.intimacyDetailLevel in 0..4)
                if (sceneExecutionContract.strictBodyAndSensoryContinuity) {
                    require(
                        sceneExecutionContract.fadePolicy == FadePolicy.AVOID &&
                            sceneExecutionContract.requiredKeyProcessCoveragePercent == 100 &&
                            !sceneExecutionContract.fadeSubstitutionAllowed &&
                            sceneExecutionContract.requiresStateContinuity &&
                            sceneExecutionContract.requiresRelevantAftermath,
                    ) { "Strict scene execution contract is internally inconsistent." }
                }
            }
        }
    }

    override fun toString(): String =
        "ChapterPlanExpectationV1(chapterIndex=$chapterIndex, knownCharacterCount=${knownCharacterIds.size}, " +
            "confirmedAdultFictionalCount=${confirmedAdultFictionalCharacterIds.size}, hashes=redacted)"
}

sealed interface ChapterPlanValidationResult {
    data class Valid(val plan: ChapterPlanV1) : ChapterPlanValidationResult

    data class Invalid(val issues: List<ChapterPlanCrossIssue>) : ChapterPlanValidationResult {
        init {
            require(issues.isNotEmpty() && issues.size <= MAXIMUM_CROSS_ISSUES)
        }

        override fun toString(): String =
            "ChapterPlanValidationResult.Invalid(issueCodes=${issues.map { it.code }.distinct()}, content=redacted)"
    }
}

class ChapterPlanOutputParser(
    private val validator: StructuredOutputValidator = StructuredOutputValidator(),
) {
    fun parse(source: ByteArray): PlanningOutputValidationResult<ChapterPlanV1> =
        when (val result = validator.validate(source, ChapterPlanOutputContractV1)) {
            is StructuredOutputValidationResult.Invalid -> PlanningOutputValidationResult.Invalid(result.report)
            is StructuredOutputValidationResult.Valid -> PlanningOutputValidationResult.Valid(
                result.output.withDocument(::toPlan),
            )
        }

    private fun toPlan(document: JsonObject): ChapterPlanV1 {
        val canonical = canonicalize(document).toString()
        return ChapterPlanV1(
            policyVersion = document.stringValue("policyVersion"),
            chapterId = document.stringValue("chapterId"),
            chapterIndex = document.intValue("chapterIndex"),
            contextContentHash = document.stringValue("contextContentHash"),
            contextSourceManifestHash = document.stringValue("contextSourceManifestHash"),
            openingState = document.stringValue("openingState"),
            chapterGoal = document.stringValue("chapterGoal"),
            closingState = document.stringValue("closingState"),
            finalHook = document.stringValue("finalHook"),
            continuityConstraints = document.stringValues("continuityConstraints"),
            scenes = document.objectValues("scenes").map { scene ->
                ChapterPlanSceneV1(
                    sceneId = scene.stringValue("sceneId"),
                    sequence = scene.intValue("sequence"),
                    purpose = scene.stringValue("purpose"),
                    location = scene.stringValue("location"),
                    pointOfViewCharacterId = scene.stringValue("pointOfViewCharacterId"),
                    participantCharacterIds = scene.stringValues("participantCharacterIds"),
                    openingState = scene.stringValue("openingState"),
                    turn = scene.stringValue("turn"),
                    closingState = scene.stringValue("closingState"),
                    continuityCarry = scene.stringValues("continuityCarry"),
                    intimacyRelevant = scene.booleanValue("intimacyRelevant"),
                    requiredProcessNodes = scene.objectValues("requiredProcessNodes").map { node ->
                        ChapterPlanProcessNodeV1(
                            nodeId = node.stringValue("nodeId"),
                            sequence = node.intValue("sequence"),
                            action = node.stringValue("action"),
                            reaction = node.stringValue("reaction"),
                            spatialStateAfter = node.stringValue("spatialStateAfter"),
                            bodyStateAfter = node.stringValue("bodyStateAfter"),
                            clothingAndObjectStateAfter = node.stringValue("clothingAndObjectStateAfter"),
                            sensoryChange = node.stringValue("sensoryChange"),
                        )
                    },
                    aftermath = scene.nullableStringValue("aftermath"),
                )
            },
            canonicalJson = canonical,
            contentHash = sha256(canonical),
        )
    }
}

object ChapterPlanBusinessValidatorV1 {
    fun validate(
        plan: ChapterPlanV1,
        expected: ChapterPlanExpectationV1,
    ): ChapterPlanValidationResult {
        val issues = buildList {
            if (plan.policyVersion != ChapterPlanOutputContractV1.POLICY_VERSION) {
                add(ChapterPlanCrossIssue(ChapterPlanCrossIssueCode.POLICY_VERSION_MISMATCH, "policyVersion"))
            }
            if (plan.chapterId != expected.chapterId) {
                add(ChapterPlanCrossIssue(ChapterPlanCrossIssueCode.CHAPTER_ID_MISMATCH, "chapterId"))
            }
            if (plan.chapterIndex != expected.chapterIndex) {
                add(ChapterPlanCrossIssue(ChapterPlanCrossIssueCode.CHAPTER_INDEX_MISMATCH, "chapterIndex"))
            }
            if (plan.contextContentHash != expected.contextContentHash) {
                add(
                    ChapterPlanCrossIssue(
                        ChapterPlanCrossIssueCode.CONTEXT_CONTENT_HASH_MISMATCH,
                        "contextContentHash",
                    ),
                )
            }
            if (plan.contextSourceManifestHash != expected.contextSourceManifestHash) {
                add(
                    ChapterPlanCrossIssue(
                        ChapterPlanCrossIssueCode.CONTEXT_MANIFEST_HASH_MISMATCH,
                        "contextSourceManifestHash",
                    ),
                )
            }
            if (plan.scenes.map(ChapterPlanSceneV1::sequence) != (1..plan.scenes.size).toList()) {
                add(ChapterPlanCrossIssue(ChapterPlanCrossIssueCode.SCENE_SEQUENCE_MISMATCH, "scenes"))
            }
            if (plan.scenes.map(ChapterPlanSceneV1::sceneId).distinct().size != plan.scenes.size) {
                add(ChapterPlanCrossIssue(ChapterPlanCrossIssueCode.DUPLICATE_SCENE_ID, "scenes"))
            }

            plan.scenes.forEachIndexed { sceneIndex, scene ->
                val sceneReference = "scene.$sceneIndex"
                val referencedCharacters = scene.participantCharacterIds + scene.pointOfViewCharacterId
                if (referencedCharacters.any { it !in expected.knownCharacterIds }) {
                    add(
                        ChapterPlanCrossIssue(
                            ChapterPlanCrossIssueCode.UNKNOWN_CHARACTER_REFERENCE,
                            sceneReference,
                        ),
                    )
                }
                if (scene.pointOfViewCharacterId !in scene.participantCharacterIds) {
                    add(
                        ChapterPlanCrossIssue(
                            ChapterPlanCrossIssueCode.POV_NOT_PARTICIPANT,
                            sceneReference,
                        ),
                    )
                }
                if (
                    scene.requiredProcessNodes.map(ChapterPlanProcessNodeV1::sequence) !=
                    (1..scene.requiredProcessNodes.size).toList()
                ) {
                    add(
                        ChapterPlanCrossIssue(
                            ChapterPlanCrossIssueCode.PROCESS_NODE_SEQUENCE_MISMATCH,
                            sceneReference,
                        ),
                    )
                }
            }

            val processNodes = plan.scenes.flatMap(ChapterPlanSceneV1::requiredProcessNodes)
            if (processNodes.size > ChapterPlanOutputContractV1.MAXIMUM_TOTAL_PROCESS_NODES) {
                add(
                    ChapterPlanCrossIssue(
                        ChapterPlanCrossIssueCode.PROCESS_NODE_LIMIT_EXCEEDED,
                        "requiredProcessNodes",
                    ),
                )
            }
            if (processNodes.map(ChapterPlanProcessNodeV1::nodeId).distinct().size != processNodes.size) {
                add(
                    ChapterPlanCrossIssue(
                        ChapterPlanCrossIssueCode.DUPLICATE_PROCESS_NODE_ID,
                        "requiredProcessNodes",
                    ),
                )
            }

            when (val sceneContract = expected.sceneExecutionContract) {
                SceneExecutionContract.NotApplicable -> plan.scenes.forEachIndexed { index, scene ->
                    if (scene.intimacyRelevant) {
                        add(
                            ChapterPlanCrossIssue(
                                ChapterPlanCrossIssueCode.UNEXPECTED_INTIMACY_SCENE,
                                "scene.$index",
                            ),
                        )
                    }
                    if (scene.requiredProcessNodes.isNotEmpty()) {
                        add(
                            ChapterPlanCrossIssue(
                                ChapterPlanCrossIssueCode.PROCESS_NODES_FORBIDDEN,
                                "scene.$index",
                            ),
                        )
                    }
                }
                is SceneExecutionContract.Blocked -> error(
                    "Blocked scene contracts are rejected by ChapterPlanExpectationV1.",
                )
                is SceneExecutionContract.Allowed -> {
                    val relevantScenes = plan.scenes.withIndex().filter { it.value.intimacyRelevant }
                    if (relevantScenes.isEmpty()) {
                        add(
                            ChapterPlanCrossIssue(
                                ChapterPlanCrossIssueCode.REQUIRED_INTIMACY_SCENE_MISSING,
                                "scenes",
                            ),
                        )
                    }
                    plan.scenes.withIndex().forEach { indexed ->
                        val scene = indexed.value
                        val reference = "scene.${indexed.index}"
                        if (!scene.intimacyRelevant && scene.requiredProcessNodes.isNotEmpty()) {
                            add(
                                ChapterPlanCrossIssue(
                                    ChapterPlanCrossIssueCode.PROCESS_NODES_FORBIDDEN,
                                    reference,
                                ),
                            )
                        }
                    }
                    relevantScenes.forEach { indexed ->
                        val scene = indexed.value
                        val reference = "scene.${indexed.index}"
                        if (
                            scene.participantCharacterIds.any {
                                it !in expected.confirmedAdultFictionalCharacterIds
                            }
                        ) {
                            add(
                                ChapterPlanCrossIssue(
                                    ChapterPlanCrossIssueCode.ADULT_FICTIONAL_GATE_MISMATCH,
                                    reference,
                                ),
                            )
                        }
                        if (sceneContract.requiresRelevantAftermath && scene.aftermath == null) {
                            add(
                                ChapterPlanCrossIssue(
                                    ChapterPlanCrossIssueCode.AFTERMATH_MISSING,
                                    reference,
                                ),
                            )
                        }
                        if (sceneContract.strictBodyAndSensoryContinuity) {
                            if (
                                scene.requiredProcessNodes.size <
                                ChapterPlanOutputContractV1.MINIMUM_STRICT_PROCESS_NODES_PER_SCENE
                            ) {
                                add(
                                    ChapterPlanCrossIssue(
                                        ChapterPlanCrossIssueCode.REQUIRED_PROCESS_NODES_MISSING,
                                        reference,
                                    ),
                                )
                            }
                        } else if (scene.requiredProcessNodes.isNotEmpty()) {
                            add(
                                ChapterPlanCrossIssue(
                                    ChapterPlanCrossIssueCode.PROCESS_NODES_FORBIDDEN,
                                    reference,
                                ),
                            )
                        }
                    }
                }
            }
        }.distinct().take(MAXIMUM_CROSS_ISSUES)

        return if (issues.isEmpty()) {
            ChapterPlanValidationResult.Valid(plan)
        } else {
            ChapterPlanValidationResult.Invalid(issues)
        }
    }
}

object ChapterPlanOutputContractV1 : StructuredOutputContract {
    const val POLICY_VERSION = "zhijuan.chapter-plan-output-policy.v1"
    const val MAXIMUM_OUTPUT_BYTES = 48 * 1_024
    const val MAXIMUM_TOTAL_PROCESS_NODES = 64
    const val MINIMUM_STRICT_PROCESS_NODES_PER_SCENE = 3

    override val schemaId = "chapter-plan.v1"
    override val currentSchemaVersion = 1
    override val providerSchema = ProviderJsonSchema.from(CHAPTER_PLAN_SCHEMA)
    override val limits = StructuredOutputLimits(
        maximumBytes = MAXIMUM_OUTPUT_BYTES,
        maximumRepairSourceBytes = MAXIMUM_OUTPUT_BYTES,
        maximumDepth = 8,
        maximumNodes = 4_096,
        maximumObjectMembers = 16,
        maximumArrayItems = 64,
        maximumStringCharacters = MAXIMUM_OUTPUT_BYTES,
        maximumNumberCharacters = 16,
    )

    override fun validate(document: JsonObject): List<StructuredOutputIssue> = buildList {
        val root = ChapterPlanContractReader(document, "$", this)
        root.exactKeys(ROOT_KEYS)
        root.exactInt("schemaVersion", 1)
        root.exactString("policyVersion", POLICY_VERSION)
        root.identifier("chapterId")
        root.int("chapterIndex", 1..10_000)
        root.hash("contextContentHash")
        root.hash("contextSourceManifestHash")
        listOf("openingState", "chapterGoal", "closingState", "finalHook").forEach {
            root.string(it, 1..2_000)
        }
        root.strings("continuityConstraints", 1..24, 1..1_000)

        val scenes = root.objects("scenes", 1..12)
        scenes.forEachIndexed { sceneIndex, scene ->
            val path = "$.scenes[$sceneIndex]"
            val reader = ChapterPlanContractReader(scene, path, this)
            reader.exactKeys(SCENE_KEYS)
            reader.identifier("sceneId")
            reader.int("sequence", 1..12)
            reader.string("purpose", 1..2_000)
            reader.string("location", 1..1_000)
            reader.identifier("pointOfViewCharacterId")
            val participants = reader.strings("participantCharacterIds", 1..24, 1..128)
            participants.forEachIndexed { index, id ->
                if (!IDENTIFIER.matches(id)) {
                    add(
                        StructuredOutputIssue(
                            StructuredOutputIssueCode.VALUE_INVALID,
                            "$path.participantCharacterIds[$index]",
                        ),
                    )
                }
            }
            if (participants.distinct().size != participants.size) {
                add(StructuredOutputIssue(StructuredOutputIssueCode.VALUE_INVALID, "$path.participantCharacterIds"))
            }
            listOf("openingState", "turn", "closingState").forEach {
                reader.string(it, 1..2_000)
            }
            reader.strings("continuityCarry", 1..16, 1..1_000)
            reader.boolean("intimacyRelevant")
            reader.nullableString("aftermath", 1..1_200)

            val nodes = reader.objects("requiredProcessNodes", 0..12)
            nodes.forEachIndexed { nodeIndex, node ->
                val nodePath = "$path.requiredProcessNodes[$nodeIndex]"
                val nodeReader = ChapterPlanContractReader(node, nodePath, this)
                nodeReader.exactKeys(PROCESS_NODE_KEYS)
                nodeReader.identifier("nodeId")
                nodeReader.int("sequence", 1..12)
                listOf(
                    "action",
                    "reaction",
                    "spatialStateAfter",
                    "bodyStateAfter",
                    "clothingAndObjectStateAfter",
                    "sensoryChange",
                ).forEach { nodeReader.string(it, 1..800) }
            }
            val nodeSequences = nodes.mapNotNull { it.intOrNull("sequence") }
            if (nodeSequences != (1..nodes.size).toList()) {
                add(StructuredOutputIssue(StructuredOutputIssueCode.VALUE_INVALID, "$path.requiredProcessNodes"))
            }
        }

        val sceneSequences = scenes.mapNotNull { it.intOrNull("sequence") }
        if (sceneSequences != (1..scenes.size).toList()) {
            add(StructuredOutputIssue(StructuredOutputIssueCode.VALUE_INVALID, "$.scenes"))
        }
        if (scenes.mapNotNull { it.stringOrNull("sceneId") }.let { it.distinct().size != it.size }) {
            add(StructuredOutputIssue(StructuredOutputIssueCode.VALUE_INVALID, "$.scenes"))
        }
        val processNodeIds = scenes.flatMap { scene ->
            val nodes = scene["requiredProcessNodes"] as? JsonArray ?: JsonArray(emptyList())
            nodes.mapNotNull { (it as? JsonObject)?.stringOrNull("nodeId") }
        }
        if (
            processNodeIds.size > MAXIMUM_TOTAL_PROCESS_NODES ||
            processNodeIds.distinct().size != processNodeIds.size
        ) {
            add(StructuredOutputIssue(StructuredOutputIssueCode.VALUE_INVALID, "$.scenes.requiredProcessNodes"))
        }
    }
}

private class ChapterPlanContractReader(
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

    fun exactString(key: String, expected: String) {
        val actual = string(key, expected.length..expected.length)
        if (actual != null && actual != expected) invalid(key)
    }

    fun string(key: String, range: IntRange): String? {
        val primitive = value[key] as? JsonPrimitive
        val text = primitive?.takeIf(JsonPrimitive::isString)?.contentOrNull
        if (text == null) type(key) else if (text.length !in range || text.isBlank()) invalid(key)
        return text
    }

    fun identifier(key: String) {
        val text = string(key, 1..128)
        if (text != null && !IDENTIFIER.matches(text)) invalid(key)
    }

    fun hash(key: String) {
        val text = string(key, 64..64)
        if (text != null && !HASH.matches(text)) invalid(key)
    }

    fun int(key: String, range: IntRange): Int? {
        val primitive = value[key] as? JsonPrimitive
        val number = primitive?.takeUnless(JsonPrimitive::isString)?.intOrNull
        if (number == null) type(key) else if (number !in range) invalid(key)
        return number
    }

    fun boolean(key: String): Boolean? {
        val primitive = value[key] as? JsonPrimitive
        val result = primitive?.takeUnless(JsonPrimitive::isString)?.booleanOrNull
        if (result == null) type(key)
        return result
    }

    fun nullableString(key: String, range: IntRange): String? {
        val element = value[key]
        if (element == null) {
            issues += StructuredOutputIssue(StructuredOutputIssueCode.REQUIRED_FIELD_MISSING, "$path.$key")
            return null
        }
        if (element is JsonNull) return null
        val primitive = element as? JsonPrimitive
        val text = primitive?.takeIf(JsonPrimitive::isString)?.contentOrNull
        if (text == null) type(key) else if (text.length !in range || text.isBlank()) invalid(key)
        return text
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
                    issues += StructuredOutputIssue(
                        StructuredOutputIssueCode.TYPE_MISMATCH,
                        "$path.$key[$index]",
                    )
                }
            }
        }
    }

    fun strings(key: String, range: IntRange, itemRange: IntRange): List<String> {
        val array = value[key] as? JsonArray
        if (array == null) {
            type(key)
            return emptyList()
        }
        if (array.size !in range) invalid(key)
        return array.mapIndexedNotNull { index, element ->
            val text = (element as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull
            if (text == null) {
                issues += StructuredOutputIssue(StructuredOutputIssueCode.TYPE_MISMATCH, "$path.$key[$index]")
                null
            } else {
                if (text.length !in itemRange || text.isBlank()) {
                    issues += StructuredOutputIssue(StructuredOutputIssueCode.VALUE_INVALID, "$path.$key[$index]")
                }
                text
            }
        }
    }

    private fun type(key: String) {
        issues += StructuredOutputIssue(StructuredOutputIssueCode.TYPE_MISMATCH, "$path.$key")
    }

    private fun invalid(key: String) {
        issues += StructuredOutputIssue(StructuredOutputIssueCode.VALUE_INVALID, "$path.$key")
    }
}

private fun JsonObject.objectValues(key: String): List<JsonObject> =
    (getValue(key) as JsonArray).map { it as JsonObject }

private fun JsonObject.stringValue(key: String): String = (getValue(key) as JsonPrimitive).content
private fun JsonObject.stringValues(key: String): List<String> =
    (getValue(key) as JsonArray).map { (it as JsonPrimitive).content }

private fun JsonObject.intValue(key: String): Int = (getValue(key) as JsonPrimitive).intOrNull!!
private fun JsonObject.booleanValue(key: String): Boolean = (getValue(key) as JsonPrimitive).booleanOrNull!!
private fun JsonObject.nullableStringValue(key: String): String? =
    (getValue(key) as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull

private fun JsonObject.stringOrNull(key: String): String? =
    (get(key) as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull

private fun JsonObject.intOrNull(key: String): Int? =
    (get(key) as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.intOrNull

private fun canonicalize(element: JsonElement): JsonElement = when (element) {
    is JsonObject -> JsonObject(
        element.entries.sortedBy(Map.Entry<String, JsonElement>::key)
            .associate { (key, value) -> key to canonicalize(value) },
    )
    is JsonArray -> JsonArray(element.map(::canonicalize))
    else -> element
}

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString(separator = "") { byte -> "%02x".format(byte) }

private const val MAXIMUM_CROSS_ISSUES = 32
private val IDENTIFIER = Regex("[A-Za-z0-9._:-]{1,128}")
private val HASH = Regex("[0-9a-f]{64}")

private val ROOT_KEYS = setOf(
    "schemaVersion",
    "policyVersion",
    "chapterId",
    "chapterIndex",
    "contextContentHash",
    "contextSourceManifestHash",
    "openingState",
    "chapterGoal",
    "closingState",
    "finalHook",
    "continuityConstraints",
    "scenes",
)

private val SCENE_KEYS = setOf(
    "sceneId",
    "sequence",
    "purpose",
    "location",
    "pointOfViewCharacterId",
    "participantCharacterIds",
    "openingState",
    "turn",
    "closingState",
    "continuityCarry",
    "intimacyRelevant",
    "requiredProcessNodes",
    "aftermath",
)

private val PROCESS_NODE_KEYS = setOf(
    "nodeId",
    "sequence",
    "action",
    "reaction",
    "spatialStateAfter",
    "bodyStateAfter",
    "clothingAndObjectStateAfter",
    "sensoryChange",
)

private val CHAPTER_PLAN_SCHEMA = """
{"type":"object","additionalProperties":false,"required":["schemaVersion","policyVersion","chapterId","chapterIndex","contextContentHash","contextSourceManifestHash","openingState","chapterGoal","closingState","finalHook","continuityConstraints","scenes"],"properties":{"schemaVersion":{"const":1},"policyVersion":{"const":"zhijuan.chapter-plan-output-policy.v1"},"chapterId":{"type":"string","minLength":1,"maxLength":128,"pattern":"^[A-Za-z0-9._:-]+$"},"chapterIndex":{"type":"integer","minimum":1,"maximum":10000},"contextContentHash":{"type":"string","pattern":"^[0-9a-f]{64}$"},"contextSourceManifestHash":{"type":"string","pattern":"^[0-9a-f]{64}$"},"openingState":{"type":"string","minLength":1,"maxLength":2000},"chapterGoal":{"type":"string","minLength":1,"maxLength":2000},"closingState":{"type":"string","minLength":1,"maxLength":2000},"finalHook":{"type":"string","minLength":1,"maxLength":2000},"continuityConstraints":{"type":"array","minItems":1,"maxItems":24,"items":{"type":"string","minLength":1,"maxLength":1000}},"scenes":{"type":"array","minItems":1,"maxItems":12,"items":{"type":"object","additionalProperties":false,"required":["sceneId","sequence","purpose","location","pointOfViewCharacterId","participantCharacterIds","openingState","turn","closingState","continuityCarry","intimacyRelevant","requiredProcessNodes","aftermath"],"properties":{"sceneId":{"type":"string","minLength":1,"maxLength":128,"pattern":"^[A-Za-z0-9._:-]+$"},"sequence":{"type":"integer","minimum":1,"maximum":12},"purpose":{"type":"string","minLength":1,"maxLength":2000},"location":{"type":"string","minLength":1,"maxLength":1000},"pointOfViewCharacterId":{"type":"string","minLength":1,"maxLength":128,"pattern":"^[A-Za-z0-9._:-]+$"},"participantCharacterIds":{"type":"array","minItems":1,"maxItems":24,"items":{"type":"string","minLength":1,"maxLength":128,"pattern":"^[A-Za-z0-9._:-]+$"}},"openingState":{"type":"string","minLength":1,"maxLength":2000},"turn":{"type":"string","minLength":1,"maxLength":2000},"closingState":{"type":"string","minLength":1,"maxLength":2000},"continuityCarry":{"type":"array","minItems":1,"maxItems":16,"items":{"type":"string","minLength":1,"maxLength":1000}},"intimacyRelevant":{"type":"boolean"},"requiredProcessNodes":{"type":"array","minItems":0,"maxItems":12,"items":{"type":"object","additionalProperties":false,"required":["nodeId","sequence","action","reaction","spatialStateAfter","bodyStateAfter","clothingAndObjectStateAfter","sensoryChange"],"properties":{"nodeId":{"type":"string","minLength":1,"maxLength":128,"pattern":"^[A-Za-z0-9._:-]+$"},"sequence":{"type":"integer","minimum":1,"maximum":12},"action":{"type":"string","minLength":1,"maxLength":800},"reaction":{"type":"string","minLength":1,"maxLength":800},"spatialStateAfter":{"type":"string","minLength":1,"maxLength":800},"bodyStateAfter":{"type":"string","minLength":1,"maxLength":800},"clothingAndObjectStateAfter":{"type":"string","minLength":1,"maxLength":800},"sensoryChange":{"type":"string","minLength":1,"maxLength":800}}}},"aftermath":{"type":["string","null"],"minLength":1,"maxLength":1200}}}}}}
""".trimIndent()

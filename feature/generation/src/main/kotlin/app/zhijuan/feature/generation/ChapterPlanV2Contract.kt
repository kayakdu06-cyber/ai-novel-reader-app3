package app.zhijuan.feature.generation

import app.zhijuan.provider.common.ProviderJsonSchema
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

enum class PlannedObligationActionV2 { CARRY_FORWARD, PROGRESS, FULFILL, POSTPONE, CANCEL }

data class PlannedObligationActionEntryV2(
    val obligationId: String,
    val action: PlannedObligationActionV2,
    val plannedEvidence: String,
    val nextDueChapterIndex: Int?,
)

data class PlannedStateDeltaV2(
    val namespace: String,
    val entityId: String,
    val relatedEntityId: String?,
    val attribute: String,
    val oldValueJson: String?,
    val newValueJson: String,
    val plannedEvidence: String,
)

data class SceneCauseEffectV2(
    val sceneId: String,
    val cause: String,
    val effect: String,
)

data class ChapterPlanV2(
    val chapterId: String,
    val chapterIndex: Int,
    val contextContentHash: String,
    val contextSourceManifestHash: String,
    val activationHash: String,
    val policyCompilationHash: String,
    val contextEvidenceHash: String,
    val chapterObjective: String,
    val activeCapabilityIds: List<String>,
    val obligationActions: List<PlannedObligationActionEntryV2>,
    val expectedStateDeltas: List<PlannedStateDeltaV2>,
    val prohibitedRepetitions: List<String>,
    val requiredCallbacks: List<String>,
    val sceneCauseEffect: List<SceneCauseEffectV2>,
    val endHook: String,
    val basePlan: ChapterPlanV1,
    val canonicalJson: String,
    val contentHash: String,
) {
    override fun toString(): String =
        "ChapterPlanV2(chapterIndex=$chapterIndex, capabilityCount=${activeCapabilityIds.size}, " +
            "obligationCount=${obligationActions.size}, deltaCount=${expectedStateDeltas.size}, content=redacted)"
}

data class ChapterPlanExpectationV2(
    val base: ChapterPlanExpectationV1,
    val activationHash: String,
    val policyCompilationHash: String,
    val contextEvidenceHash: String,
    val activeCapabilityIds: Set<String>,
    val activeStateNamespaces: Set<String>,
    val priorObligationIds: Set<String>,
) {
    init {
        require(listOf(activationHash, policyCompilationHash, contextEvidenceHash).all(HASH::matches))
        require(activeCapabilityIds.all(ID::matches) && activeStateNamespaces.all(STATE_NAMESPACE::matches))
        require(priorObligationIds.all(ID::matches))
    }
}

enum class ChapterPlanV2IssueCode {
    ACTIVATION_HASH_MISMATCH,
    POLICY_COMPILATION_HASH_MISMATCH,
    CONTEXT_EVIDENCE_HASH_MISMATCH,
    ACTIVE_CAPABILITY_MISMATCH,
    OBLIGATION_DISAPPEARED,
    DUPLICATE_OBLIGATION_ACTION,
    INACTIVE_STATE_NAMESPACE,
    DUPLICATE_STATE_DELTA,
    SCENE_CAUSE_EFFECT_MISMATCH,
    CHAPTER_OBJECTIVE_NOT_ADVANCED,
    BASE_V1_CONTRACT_INVALID,
}

data class ChapterPlanV2Issue(val code: ChapterPlanV2IssueCode, val reference: String)

sealed interface ChapterPlanV2BusinessResult {
    data class Valid(val plan: ChapterPlanV2) : ChapterPlanV2BusinessResult
    data class Invalid(val issues: List<ChapterPlanV2Issue>) : ChapterPlanV2BusinessResult
}

class ChapterPlanV2Parser(
    private val validator: StructuredOutputValidator = StructuredOutputValidator(),
) {
    fun parse(source: ByteArray): PlanningOutputValidationResult<ChapterPlanV2> =
        when (val result = validator.validate(source, ChapterPlanOutputContractV2)) {
            is StructuredOutputValidationResult.Invalid -> PlanningOutputValidationResult.Invalid(result.report)
            is StructuredOutputValidationResult.Valid -> PlanningOutputValidationResult.Valid(
                result.output.withDocument(::toPlan),
            )
        }

    internal fun fromValidated(output: ValidatedStructuredOutput): ChapterPlanV2 {
        require(output.schemaId == ChapterPlanOutputContractV2.schemaId)
        require(output.schemaVersion == ChapterPlanOutputContractV2.currentSchemaVersion)
        return output.withDocument(::toPlan)
    }

    internal fun toPlan(document: JsonObject): ChapterPlanV2 {
        val baseDocument = JsonObject(V1_ROOT_KEYS.associateWith(document::getValue).toMutableMap().apply {
            this["schemaVersion"] = JsonPrimitive(1)
            this["policyVersion"] = JsonPrimitive(ChapterPlanOutputContractV1.POLICY_VERSION)
        })
        val base = (ChapterPlanOutputParser().parse(baseDocument.toString().toByteArray())
            as PlanningOutputValidationResult.Valid).value
        val canonical = canonicalizeV2(document).toString()
        return ChapterPlanV2(
            chapterId = document.string("chapterId"),
            chapterIndex = document.int("chapterIndex"),
            contextContentHash = document.string("contextContentHash"),
            contextSourceManifestHash = document.string("contextSourceManifestHash"),
            activationHash = document.string("activationHash"),
            policyCompilationHash = document.string("policyCompilationHash"),
            chapterObjective = document.string("chapterObjective"),
            activeCapabilityIds = document.strings("activeCapabilityIds"),
            obligationActions = document.objects("obligationActions").map { item ->
                PlannedObligationActionEntryV2(
                    obligationId = item.string("obligationId"),
                    action = PlannedObligationActionV2.valueOf(item.string("action")),
                    plannedEvidence = item.string("plannedEvidence"),
                    nextDueChapterIndex = item.nullableInt("nextDueChapterIndex"),
                )
            },
            expectedStateDeltas = document.objects("expectedStateDeltas").map { item ->
                PlannedStateDeltaV2(
                    namespace = item.string("namespace"),
                    entityId = item.string("entityId"),
                    relatedEntityId = item.nullableString("relatedEntityId"),
                    attribute = item.string("attribute"),
                    oldValueJson = item.nullableString("oldValueJson"),
                    newValueJson = item.string("newValueJson"),
                    plannedEvidence = item.string("plannedEvidence"),
                )
            },
            prohibitedRepetitions = document.strings("prohibitedRepetitions"),
            requiredCallbacks = document.strings("requiredCallbacks"),
            sceneCauseEffect = document.objects("sceneCauseEffect").map { item ->
                SceneCauseEffectV2(item.string("sceneId"), item.string("cause"), item.string("effect"))
            },
            endHook = document.string("endHook"),
            contextEvidenceHash = document.string("contextEvidenceHash"),
            basePlan = base,
            canonicalJson = canonical,
            contentHash = sha256V2(canonical),
        )
    }
}

internal class BoundChapterPlanV2OutputContract(
    private val expectation: ChapterPlanExpectationV2,
    private val parser: ChapterPlanV2Parser = ChapterPlanV2Parser(),
) : StructuredOutputContract {
    override val schemaId = ChapterPlanOutputContractV2.schemaId
    override val currentSchemaVersion = ChapterPlanOutputContractV2.currentSchemaVersion
    override val providerSchema = ChapterPlanOutputContractV2.providerSchema
    override val limits = ChapterPlanOutputContractV2.limits

    override fun validate(document: JsonObject): List<StructuredOutputIssue> {
        val structural = ChapterPlanOutputContractV2.validate(document)
        if (structural.isNotEmpty()) return structural
        return when (val result = ChapterPlanV2BusinessValidator.validate(parser.toPlan(document), expectation)) {
            is ChapterPlanV2BusinessResult.Valid -> emptyList()
            is ChapterPlanV2BusinessResult.Invalid -> result.issues.map { issue ->
                StructuredOutputIssue(StructuredOutputIssueCode.VALUE_INVALID, "$.${issue.reference}")
            }
        }
    }
}

object ChapterPlanV2BusinessValidator {
    fun validate(plan: ChapterPlanV2, expected: ChapterPlanExpectationV2): ChapterPlanV2BusinessResult {
        val baseResult = ChapterPlanBusinessValidatorV1.validate(plan.basePlan, expected.base)
        if (baseResult is ChapterPlanValidationResult.Invalid) {
            return ChapterPlanV2BusinessResult.Invalid(baseResult.issues.map {
                ChapterPlanV2Issue(ChapterPlanV2IssueCode.BASE_V1_CONTRACT_INVALID, it.reference)
            })
        }
        val issues = buildList {
            if (plan.activationHash != expected.activationHash) add(issue(ChapterPlanV2IssueCode.ACTIVATION_HASH_MISMATCH, "activationHash"))
            if (plan.policyCompilationHash != expected.policyCompilationHash) add(issue(ChapterPlanV2IssueCode.POLICY_COMPILATION_HASH_MISMATCH, "policyCompilationHash"))
            if (plan.contextEvidenceHash != expected.contextEvidenceHash) add(issue(ChapterPlanV2IssueCode.CONTEXT_EVIDENCE_HASH_MISMATCH, "contextEvidenceHash"))
            if (plan.activeCapabilityIds.toSet() != expected.activeCapabilityIds) add(issue(ChapterPlanV2IssueCode.ACTIVE_CAPABILITY_MISMATCH, "activeCapabilityIds"))
            val actionIds = plan.obligationActions.map(PlannedObligationActionEntryV2::obligationId)
            if (!actionIds.containsAll(expected.priorObligationIds)) add(issue(ChapterPlanV2IssueCode.OBLIGATION_DISAPPEARED, "obligationActions"))
            if (actionIds.distinct().size != actionIds.size) add(issue(ChapterPlanV2IssueCode.DUPLICATE_OBLIGATION_ACTION, "obligationActions"))
            if (plan.expectedStateDeltas.any { it.namespace !in expected.activeStateNamespaces }) add(issue(ChapterPlanV2IssueCode.INACTIVE_STATE_NAMESPACE, "expectedStateDeltas"))
            val deltaKeys = plan.expectedStateDeltas.map { listOf(it.namespace, it.entityId, it.relatedEntityId.orEmpty(), it.attribute) }
            if (deltaKeys.distinct().size != deltaKeys.size) add(issue(ChapterPlanV2IssueCode.DUPLICATE_STATE_DELTA, "expectedStateDeltas"))
            if (plan.sceneCauseEffect.map(SceneCauseEffectV2::sceneId) != plan.basePlan.scenes.map(ChapterPlanSceneV1::sceneId)) {
                add(issue(ChapterPlanV2IssueCode.SCENE_CAUSE_EFFECT_MISMATCH, "sceneCauseEffect"))
            }
            if (plan.chapterObjective.isBlank() || plan.chapterObjective == plan.endHook ||
                plan.basePlan.openingState == plan.basePlan.closingState
            ) add(issue(ChapterPlanV2IssueCode.CHAPTER_OBJECTIVE_NOT_ADVANCED, "chapterObjective"))
        }.distinct()
        return if (issues.isEmpty()) ChapterPlanV2BusinessResult.Valid(plan)
        else ChapterPlanV2BusinessResult.Invalid(issues)
    }

    private fun issue(code: ChapterPlanV2IssueCode, reference: String) = ChapterPlanV2Issue(code, reference)
}

object ChapterPlanOutputContractV2 : StructuredOutputContract {
    override val schemaId = "chapter-plan.v2"
    override val currentSchemaVersion = 2
    override val providerSchema = chapterPlanV2ProviderSchema()
    override val limits = StructuredOutputLimits(maximumBytes = 64 * 1_024, maximumRepairSourceBytes = 64 * 1_024)

    override fun validate(document: JsonObject): List<StructuredOutputIssue> = buildList {
        exactKeys(document, V2_ROOT_KEYS, "$", this)
        exactInt(document, "schemaVersion", 2, "$", this)
        exactString(document, "policyVersion", "zhijuan.chapter-plan-output-policy.v2", "$", this)
        HASH_FIELDS.forEach { hash(document, it, "$", this) }
        string(document, "chapterObjective", 1..2_000, "$", this)
        string(document, "endHook", 1..2_000, "$", this)
        val capabilities = strings(document, "activeCapabilityIds", 2..32, 1..128, "$", this)
        if (capabilities.any { !ID.matches(it) } || capabilities.distinct().size != capabilities.size) invalid("$.activeCapabilityIds", this)
        strings(document, "prohibitedRepetitions", 0..24, 1..1_000, "$", this)
        strings(document, "requiredCallbacks", 0..24, 1..1_000, "$", this)
        objects(document, "obligationActions", 0..64, "$", this).forEachIndexed { index, item ->
            val path = "$.obligationActions[$index]"
            exactKeys(item, OBLIGATION_KEYS, path, this)
            identifier(item, "obligationId", path, this)
            enum(item, "action", PlannedObligationActionV2.entries.map { it.name }.toSet(), path, this)
            string(item, "plannedEvidence", 1..1_500, path, this)
            nullableInt(item, "nextDueChapterIndex", 1..10_000, path, this)
        }
        objects(document, "expectedStateDeltas", 0..64, "$", this).forEachIndexed { index, item ->
            val path = "$.expectedStateDeltas[$index]"
            exactKeys(item, DELTA_KEYS, path, this)
            enum(item, "namespace", STATE_NAMESPACES, path, this)
            identifier(item, "entityId", path, this)
            nullableIdentifier(item, "relatedEntityId", path, this)
            patternString(item, "attribute", STATE_ATTRIBUTE, path, this)
            nullableString(item, "oldValueJson", 1..4_000, path, this)
            string(item, "newValueJson", 1..4_000, path, this)
            string(item, "plannedEvidence", 1..1_500, path, this)
        }
        val causes = objects(document, "sceneCauseEffect", 1..12, "$", this)
        causes.forEachIndexed { index, item ->
            val path = "$.sceneCauseEffect[$index]"
            exactKeys(item, CAUSE_KEYS, path, this)
            identifier(item, "sceneId", path, this)
            string(item, "cause", 1..1_500, path, this)
            string(item, "effect", 1..1_500, path, this)
        }
        val v1 = JsonObject(V1_ROOT_KEYS.associateWith(document::getValue).toMutableMap().apply {
            this["schemaVersion"] = JsonPrimitive(1)
            this["policyVersion"] = JsonPrimitive(ChapterPlanOutputContractV1.POLICY_VERSION)
        })
        addAll(ChapterPlanOutputContractV1.validate(v1))
    }
}

private val V1_ROOT_KEYS = setOf("schemaVersion", "policyVersion", "chapterId", "chapterIndex", "contextContentHash", "contextSourceManifestHash", "openingState", "chapterGoal", "closingState", "finalHook", "continuityConstraints", "scenes")
private val V2_ROOT_KEYS = V1_ROOT_KEYS + setOf("activationHash", "policyCompilationHash", "chapterObjective", "activeCapabilityIds", "obligationActions", "expectedStateDeltas", "prohibitedRepetitions", "requiredCallbacks", "sceneCauseEffect", "endHook", "contextEvidenceHash")
private val HASH_FIELDS = setOf("contextContentHash", "contextSourceManifestHash", "activationHash", "policyCompilationHash", "contextEvidenceHash")
private val OBLIGATION_KEYS = setOf("obligationId", "action", "plannedEvidence", "nextDueChapterIndex")
private val DELTA_KEYS = setOf("namespace", "entityId", "relatedEntityId", "attribute", "oldValueJson", "newValueJson", "plannedEvidence")
private val CAUSE_KEYS = setOf("sceneId", "cause", "effect")
private val STATE_NAMESPACES = setOf("character", "relationship", "item", "system", "cultivation", "world")
private val ID = Regex("[A-Za-z0-9._:-]{1,128}")
private val HASH = Regex("[0-9a-f]{64}")
private val STATE_NAMESPACE = Regex("[a-z][a-z0-9-]{0,63}")
private val STATE_ATTRIBUTE = Regex("[a-z][a-z0-9._-]{0,95}")

private fun exactKeys(value: JsonObject, expected: Set<String>, path: String, issues: MutableList<StructuredOutputIssue>) {
    expected.filterNot(value::containsKey).forEach { issues += StructuredOutputIssue(StructuredOutputIssueCode.REQUIRED_FIELD_MISSING, "$path.$it") }
    value.keys.filterNot(expected::contains).forEach { issues += StructuredOutputIssue(StructuredOutputIssueCode.UNKNOWN_FIELD, "$path.$it") }
}
private fun string(value: JsonObject, key: String, range: IntRange, path: String, issues: MutableList<StructuredOutputIssue>): String? {
    val text = (value[key] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull
    if (text == null) issues += StructuredOutputIssue(StructuredOutputIssueCode.TYPE_MISMATCH, "$path.$key")
    else if (text.isBlank() || text.length !in range) invalid("$path.$key", issues)
    return text
}
private fun exactString(value: JsonObject, key: String, expected: String, path: String, issues: MutableList<StructuredOutputIssue>) {
    if (string(value, key, expected.length..expected.length, path, issues) != expected) invalid("$path.$key", issues)
}
private fun exactInt(value: JsonObject, key: String, expected: Int, path: String, issues: MutableList<StructuredOutputIssue>) {
    if ((value[key] as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.intOrNull != expected) invalid("$path.$key", issues)
}
private fun hash(value: JsonObject, key: String, path: String, issues: MutableList<StructuredOutputIssue>) {
    val text = string(value, key, 64..64, path, issues); if (text != null && !HASH.matches(text)) invalid("$path.$key", issues)
}
private fun identifier(value: JsonObject, key: String, path: String, issues: MutableList<StructuredOutputIssue>) {
    val text = string(value, key, 1..128, path, issues); if (text != null && !ID.matches(text)) invalid("$path.$key", issues)
}
private fun nullableIdentifier(value: JsonObject, key: String, path: String, issues: MutableList<StructuredOutputIssue>) {
    if (value[key] is JsonNull) return; identifier(value, key, path, issues)
}
private fun patternString(value: JsonObject, key: String, pattern: Regex, path: String, issues: MutableList<StructuredOutputIssue>) {
    val text = string(value, key, 1..128, path, issues); if (text != null && !pattern.matches(text)) invalid("$path.$key", issues)
}
private fun enum(value: JsonObject, key: String, allowed: Set<String>, path: String, issues: MutableList<StructuredOutputIssue>) {
    val text = string(value, key, 1..128, path, issues); if (text != null && text !in allowed) invalid("$path.$key", issues)
}
private fun nullableString(value: JsonObject, key: String, range: IntRange, path: String, issues: MutableList<StructuredOutputIssue>) {
    if (value[key] is JsonNull) return; string(value, key, range, path, issues)
}
private fun nullableInt(value: JsonObject, key: String, range: IntRange, path: String, issues: MutableList<StructuredOutputIssue>) {
    if (value[key] is JsonNull) return
    val number = (value[key] as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.intOrNull
    if (number == null || number !in range) invalid("$path.$key", issues)
}
private fun objects(value: JsonObject, key: String, range: IntRange, path: String, issues: MutableList<StructuredOutputIssue>): List<JsonObject> {
    val array = value[key] as? JsonArray ?: run { invalid("$path.$key", issues); return emptyList() }
    if (array.size !in range) invalid("$path.$key", issues)
    return array.mapIndexedNotNull { index, element -> (element as? JsonObject).also { if (it == null) invalid("$path.$key[$index]", issues) } }
}
private fun strings(value: JsonObject, key: String, range: IntRange, itemRange: IntRange, path: String, issues: MutableList<StructuredOutputIssue>): List<String> {
    val array = value[key] as? JsonArray ?: run { invalid("$path.$key", issues); return emptyList() }
    if (array.size !in range) invalid("$path.$key", issues)
    return array.mapIndexedNotNull { index, element ->
        (element as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull.also { if (it == null || it.isBlank() || it.length !in itemRange) invalid("$path.$key[$index]", issues) }
    }
}
private fun invalid(path: String, issues: MutableList<StructuredOutputIssue>) { issues += StructuredOutputIssue(StructuredOutputIssueCode.VALUE_INVALID, path) }
private fun JsonObject.string(key: String) = (getValue(key) as JsonPrimitive).content
private fun JsonObject.int(key: String) = (getValue(key) as JsonPrimitive).intOrNull!!
private fun JsonObject.nullableInt(key: String) = (getValue(key) as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.intOrNull
private fun JsonObject.nullableString(key: String) = (getValue(key) as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull
private fun JsonObject.objects(key: String) = (getValue(key) as JsonArray).map { it as JsonObject }
private fun JsonObject.strings(key: String) = (getValue(key) as JsonArray).map { (it as JsonPrimitive).content }
private fun canonicalizeV2(element: JsonElement): JsonElement = when (element) {
    is JsonObject -> JsonObject(element.entries.sortedBy { it.key }.associate { it.key to canonicalizeV2(it.value) })
    is JsonArray -> JsonArray(element.map(::canonicalizeV2)); else -> element
}
private fun sha256V2(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

private fun chapterPlanV2ProviderSchema(): ProviderJsonSchema {
    val base = ChapterPlanOutputContractV1.providerSchema.withValue {
        Json.parseToJsonElement(it).jsonObject
    }
    val additions = mapOf(
        "activationHash" to schema("""{"type":"string","pattern":"^[0-9a-f]{64}$"}"""),
        "policyCompilationHash" to schema("""{"type":"string","pattern":"^[0-9a-f]{64}$"}"""),
        "chapterObjective" to schema("""{"type":"string","minLength":1,"maxLength":2000}"""),
        "activeCapabilityIds" to schema("""{"type":"array","minItems":2,"maxItems":32,"uniqueItems":true,"items":{"type":"string","minLength":1,"maxLength":128,"pattern":"^[A-Za-z0-9._:-]+$"}}"""),
        "obligationActions" to schema("""{"type":"array","maxItems":64,"items":{"type":"object","additionalProperties":false,"required":["obligationId","action","plannedEvidence","nextDueChapterIndex"],"properties":{"obligationId":{"type":"string","minLength":1,"maxLength":128,"pattern":"^[A-Za-z0-9._:-]+$"},"action":{"enum":["CARRY_FORWARD","PROGRESS","FULFILL","POSTPONE","CANCEL"]},"plannedEvidence":{"type":"string","minLength":1,"maxLength":1500},"nextDueChapterIndex":{"type":["integer","null"],"minimum":1,"maximum":10000}}}}"""),
        "expectedStateDeltas" to schema("""{"type":"array","maxItems":64,"items":{"type":"object","additionalProperties":false,"required":["namespace","entityId","relatedEntityId","attribute","oldValueJson","newValueJson","plannedEvidence"],"properties":{"namespace":{"enum":["character","relationship","item","system","cultivation","world"]},"entityId":{"type":"string","minLength":1,"maxLength":128,"pattern":"^[A-Za-z0-9._:-]+$"},"relatedEntityId":{"type":["string","null"],"minLength":1,"maxLength":128,"pattern":"^[A-Za-z0-9._:-]+$"},"attribute":{"type":"string","minLength":1,"maxLength":96,"pattern":"^[a-z][a-z0-9._-]*$"},"oldValueJson":{"type":["string","null"],"minLength":1,"maxLength":4000},"newValueJson":{"type":"string","minLength":1,"maxLength":4000},"plannedEvidence":{"type":"string","minLength":1,"maxLength":1500}}}}"""),
        "prohibitedRepetitions" to schema("""{"type":"array","maxItems":24,"items":{"type":"string","minLength":1,"maxLength":1000}}"""),
        "requiredCallbacks" to schema("""{"type":"array","maxItems":24,"items":{"type":"string","minLength":1,"maxLength":1000}}"""),
        "sceneCauseEffect" to schema("""{"type":"array","minItems":1,"maxItems":12,"items":{"type":"object","additionalProperties":false,"required":["sceneId","cause","effect"],"properties":{"sceneId":{"type":"string","minLength":1,"maxLength":128,"pattern":"^[A-Za-z0-9._:-]+$"},"cause":{"type":"string","minLength":1,"maxLength":1500},"effect":{"type":"string","minLength":1,"maxLength":1500}}}}"""),
        "endHook" to schema("""{"type":"string","minLength":1,"maxLength":2000}"""),
        "contextEvidenceHash" to schema("""{"type":"string","pattern":"^[0-9a-f]{64}$"}"""),
    )
    val required = base.getValue("required").jsonArray + additions.keys.map(::JsonPrimitive)
    val properties = LinkedHashMap(base.getValue("properties").jsonObject).apply {
        this["schemaVersion"] = schema("""{"const":2}""")
        this["policyVersion"] = schema("""{"const":"zhijuan.chapter-plan-output-policy.v2"}""")
        putAll(additions)
    }
    return ProviderJsonSchema.from(JsonObject(LinkedHashMap(base).apply {
        this["required"] = JsonArray(required)
        this["properties"] = JsonObject(properties)
    }).toString())
}

private fun schema(value: String): JsonObject = Json.parseToJsonElement(value).jsonObject

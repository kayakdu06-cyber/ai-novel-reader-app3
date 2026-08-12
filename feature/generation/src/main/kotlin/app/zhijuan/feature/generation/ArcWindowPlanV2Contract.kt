package app.zhijuan.feature.generation

import app.zhijuan.provider.common.ProviderJsonSchema
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

data class ArcWindowChapterContractV2(
    val chapterIndex: Int,
    val objective: String,
    val capabilityHints: List<String>,
    val obligationIds: List<String>,
    val prohibitedRepetitions: List<String>,
)

data class ArcWindowPlanV2(
    val basePlan: ArcWindowPlanV1,
    val policyCompilationHash: String,
    val contextEvidenceHash: String,
    val chapterContracts: List<ArcWindowChapterContractV2>,
    val canonicalJson: String,
    val contentHash: String,
)

data class ArcWindowExpectationV2(
    val base: ArcWindowPlanningExpectation,
    val policyCompilationHash: String,
    val contextEvidenceHash: String,
) {
    init { require(listOf(policyCompilationHash, contextEvidenceHash).all(ARC_HASH::matches)) }
}

enum class ArcWindowV2IssueCode {
    BASE_V1_CONTRACT_INVALID,
    POLICY_COMPILATION_HASH_MISMATCH,
    CONTEXT_EVIDENCE_HASH_MISMATCH,
    CHAPTER_CONTRACT_SEQUENCE_MISMATCH,
}

data class ArcWindowV2Issue(val code: ArcWindowV2IssueCode, val reference: String)

sealed interface ArcWindowV2BusinessResult {
    data class Valid(val plan: ArcWindowPlanV2) : ArcWindowV2BusinessResult
    data class Invalid(val issues: List<ArcWindowV2Issue>) : ArcWindowV2BusinessResult
}

class ArcWindowPlanV2Parser(private val validator: StructuredOutputValidator = StructuredOutputValidator()) {
    fun parse(source: ByteArray): PlanningOutputValidationResult<ArcWindowPlanV2> =
        when (val result = validator.validate(source, ArcWindowPlanOutputContractV2)) {
            is StructuredOutputValidationResult.Invalid -> PlanningOutputValidationResult.Invalid(result.report)
            is StructuredOutputValidationResult.Valid -> PlanningOutputValidationResult.Valid(result.output.withDocument(::toPlan))
        }

    internal fun toPlan(document: JsonObject): ArcWindowPlanV2 {
        val v1Document = JsonObject(ARC_V1_KEYS.associateWith(document::getValue).toMutableMap().apply {
            this["schemaVersion"] = JsonPrimitive(1)
            this["policyVersion"] = JsonPrimitive(app.zhijuan.core.task.ArcPlanningWindowPolicyV1.POLICY_VERSION)
        })
        val base = (ArcWindowPlanningOutputParser().parse(v1Document.toString().toByteArray())
            as PlanningOutputValidationResult.Valid).value
        val canonical = canonicalizeArc(document).toString()
        return ArcWindowPlanV2(
            basePlan = base,
            policyCompilationHash = document.stringArc("policyCompilationHash"),
            contextEvidenceHash = document.stringArc("contextEvidenceHash"),
            chapterContracts = document.objectsArc("chapterContracts").map { item ->
                ArcWindowChapterContractV2(
                    chapterIndex = item.intArc("chapterIndex"),
                    objective = item.stringArc("objective"),
                    capabilityHints = item.stringsArc("capabilityHints"),
                    obligationIds = item.stringsArc("obligationIds"),
                    prohibitedRepetitions = item.stringsArc("prohibitedRepetitions"),
                )
            },
            canonicalJson = canonical,
            contentHash = hashArc(canonical),
        )
    }
}

internal class BoundArcWindowPlanV2OutputContract(
    private val expectation: ArcWindowExpectationV2,
    private val parser: ArcWindowPlanV2Parser = ArcWindowPlanV2Parser(),
) : StructuredOutputContract {
    override val schemaId = ArcWindowPlanOutputContractV2.schemaId
    override val currentSchemaVersion = ArcWindowPlanOutputContractV2.currentSchemaVersion
    override val providerSchema = ArcWindowPlanOutputContractV2.providerSchema
    override val limits = ArcWindowPlanOutputContractV2.limits

    override fun validate(document: JsonObject): List<StructuredOutputIssue> {
        val structural = ArcWindowPlanOutputContractV2.validate(document)
        if (structural.isNotEmpty()) return structural
        return when (val result = ArcWindowPlanV2BusinessValidator.validate(parser.toPlan(document), expectation)) {
            is ArcWindowV2BusinessResult.Valid -> emptyList()
            is ArcWindowV2BusinessResult.Invalid -> result.issues.map { issue ->
                StructuredOutputIssue(StructuredOutputIssueCode.VALUE_INVALID, "$.${issue.reference}")
            }
        }
    }
}

object ArcWindowPlanV2BusinessValidator {
    fun validate(plan: ArcWindowPlanV2, expected: ArcWindowExpectationV2): ArcWindowV2BusinessResult {
        val base = ArcWindowPlanningValidator.validate(plan.basePlan, expected.base)
        if (base is ArcWindowPlanningValidationResult.Invalid) {
            return ArcWindowV2BusinessResult.Invalid(base.issues.map {
                ArcWindowV2Issue(ArcWindowV2IssueCode.BASE_V1_CONTRACT_INVALID, it.reference)
            })
        }
        val issues = buildList {
            if (plan.policyCompilationHash != expected.policyCompilationHash) add(ArcWindowV2Issue(ArcWindowV2IssueCode.POLICY_COMPILATION_HASH_MISMATCH, "policyCompilationHash"))
            if (plan.contextEvidenceHash != expected.contextEvidenceHash) add(ArcWindowV2Issue(ArcWindowV2IssueCode.CONTEXT_EVIDENCE_HASH_MISMATCH, "contextEvidenceHash"))
            if (plan.chapterContracts.map(ArcWindowChapterContractV2::chapterIndex) != plan.basePlan.chapters.map(WindowChapterBriefV1::chapterIndex)) {
                add(ArcWindowV2Issue(ArcWindowV2IssueCode.CHAPTER_CONTRACT_SEQUENCE_MISMATCH, "chapterContracts"))
            }
        }
        return if (issues.isEmpty()) ArcWindowV2BusinessResult.Valid(plan) else ArcWindowV2BusinessResult.Invalid(issues)
    }
}

object ArcWindowPlanOutputContractV2 : StructuredOutputContract {
    override val schemaId = "arc-plan.v2"
    override val currentSchemaVersion = 2
    override val providerSchema: ProviderJsonSchema = arcV2Schema()

    override fun validate(document: JsonObject): List<StructuredOutputIssue> = buildList {
        val expected = ARC_V1_KEYS + setOf("policyCompilationHash", "contextEvidenceHash", "chapterContracts")
        expected.filterNot(document::containsKey).forEach { add(StructuredOutputIssue(StructuredOutputIssueCode.REQUIRED_FIELD_MISSING, "$.${it}")) }
        document.keys.filterNot(expected::contains).forEach { add(StructuredOutputIssue(StructuredOutputIssueCode.UNKNOWN_FIELD, "$.${it}")) }
        if (document.intArcOrNull("schemaVersion") != 2) add(StructuredOutputIssue(StructuredOutputIssueCode.VALUE_INVALID, "$.schemaVersion"))
        if (document.stringArcOrNull("policyVersion") != "zhijuan.arc-window-policy.v2") add(StructuredOutputIssue(StructuredOutputIssueCode.VALUE_INVALID, "$.policyVersion"))
        listOf("policyCompilationHash", "contextEvidenceHash").forEach { key ->
            if (document.stringArcOrNull(key)?.let(ARC_HASH::matches) != true) add(StructuredOutputIssue(StructuredOutputIssueCode.VALUE_INVALID, "$.${key}"))
        }
        val contracts = document["chapterContracts"] as? JsonArray
        if (contracts == null || contracts.size !in 1..8) add(StructuredOutputIssue(StructuredOutputIssueCode.VALUE_INVALID, "$.chapterContracts"))
        contracts?.forEachIndexed { index, element ->
            val item = element as? JsonObject
            val path = "$.chapterContracts[$index]"
            if (item == null) add(StructuredOutputIssue(StructuredOutputIssueCode.TYPE_MISMATCH, path)) else {
                val keys = setOf("chapterIndex", "objective", "capabilityHints", "obligationIds", "prohibitedRepetitions")
                if (item.keys != keys) add(StructuredOutputIssue(StructuredOutputIssueCode.VALUE_INVALID, path))
                if (item.intArcOrNull("chapterIndex") !in 1..10_000) add(StructuredOutputIssue(StructuredOutputIssueCode.VALUE_INVALID, "$path.chapterIndex"))
                if (item.stringArcOrNull("objective").isNullOrBlank()) add(StructuredOutputIssue(StructuredOutputIssueCode.VALUE_INVALID, "$path.objective"))
                listOf("capabilityHints", "obligationIds", "prohibitedRepetitions").forEach { key ->
                    val values = item[key] as? JsonArray
                    val maximum = if (key == "capabilityHints") 16 else 24
                    if (values == null || values.size > maximum || values.any { (it as? JsonPrimitive)?.contentOrNull.isNullOrBlank() }) {
                        add(StructuredOutputIssue(StructuredOutputIssueCode.VALUE_INVALID, "$path.$key"))
                    }
                }
            }
        }
        val v1 = JsonObject(ARC_V1_KEYS.associateWith(document::getValue).toMutableMap().apply {
            this["schemaVersion"] = JsonPrimitive(1)
            this["policyVersion"] = JsonPrimitive(app.zhijuan.core.task.ArcPlanningWindowPolicyV1.POLICY_VERSION)
        })
        addAll(ArcWindowPlanOutputContractV1.validate(v1))
    }
}

private fun arcV2Schema(): ProviderJsonSchema {
    val base = ArcWindowPlanOutputContractV1.providerSchema.withValue { Json.parseToJsonElement(it).jsonObject }
    val additions = linkedMapOf(
        "policyCompilationHash" to arcSchema("""{"type":"string","pattern":"^[0-9a-f]{64}$"}"""),
        "contextEvidenceHash" to arcSchema("""{"type":"string","pattern":"^[0-9a-f]{64}$"}"""),
        "chapterContracts" to arcSchema("""{"type":"array","minItems":1,"maxItems":8,"items":{"type":"object","additionalProperties":false,"required":["chapterIndex","objective","capabilityHints","obligationIds","prohibitedRepetitions"],"properties":{"chapterIndex":{"type":"integer","minimum":1,"maximum":10000},"objective":{"type":"string","minLength":1,"maxLength":1500},"capabilityHints":{"type":"array","maxItems":16,"items":{"type":"string","minLength":1,"maxLength":128}},"obligationIds":{"type":"array","maxItems":24,"items":{"type":"string","minLength":1,"maxLength":128}},"prohibitedRepetitions":{"type":"array","maxItems":24,"items":{"type":"string","minLength":1,"maxLength":1000}}}}}"""),
    )
    return ProviderJsonSchema.from(JsonObject(LinkedHashMap(base).apply {
        this["required"] = JsonArray(base.getValue("required").jsonArray + additions.keys.map(::JsonPrimitive))
        this["properties"] = JsonObject(LinkedHashMap(base.getValue("properties").jsonObject).apply {
            this["schemaVersion"] = arcSchema("""{"const":2}""")
            this["policyVersion"] = arcSchema("""{"const":"zhijuan.arc-window-policy.v2"}""")
            putAll(additions)
        })
    }).toString())
}

private fun arcSchema(value: String) = Json.parseToJsonElement(value).jsonObject
private fun JsonObject.stringArc(key: String) = (getValue(key) as JsonPrimitive).content
private fun JsonObject.stringArcOrNull(key: String) = (get(key) as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull
private fun JsonObject.intArc(key: String) = (getValue(key) as JsonPrimitive).intOrNull!!
private fun JsonObject.intArcOrNull(key: String) = (get(key) as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.intOrNull
private fun JsonObject.objectsArc(key: String) = (getValue(key) as JsonArray).map { it as JsonObject }
private fun JsonObject.stringsArc(key: String) = (getValue(key) as JsonArray).map { (it as JsonPrimitive).content }
private fun canonicalizeArc(value: JsonElement): JsonElement = when (value) {
    is JsonObject -> JsonObject(value.entries.sortedBy { it.key }.associate { it.key to canonicalizeArc(it.value) })
    is JsonArray -> JsonArray(value.map(::canonicalizeArc)); else -> value
}
private fun hashArc(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
private val ARC_V1_KEYS = setOf("schemaVersion", "policyVersion", "masterOutlineContentHash", "parentOutlineContentHash", "targetChapterCount", "arc", "chapterWindow", "nextWindowStartChapter")
private val ARC_HASH = Regex("[0-9a-f]{64}")

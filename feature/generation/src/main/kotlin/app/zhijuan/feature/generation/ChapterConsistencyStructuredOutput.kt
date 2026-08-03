package app.zhijuan.feature.generation

import app.zhijuan.core.model.ConsistencyCriterionV1
import app.zhijuan.core.model.ConsistencyIssueCode
import app.zhijuan.core.model.ConsistencyIssueSeverity
import app.zhijuan.core.model.ConsistencyRepairActionV1
import app.zhijuan.provider.common.ProviderJsonSchema
import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

enum class ConsistencyCriterionStatusV1 {
    PASS,
    ISSUE,
}

data class ConsistencyCriterionResultV1(
    val criterion: ConsistencyCriterionV1,
    val status: ConsistencyCriterionStatusV1,
    val issueIds: List<String>,
)

enum class RequiredProcessStatusV1 {
    COVERED,
    MISSING,
}

data class RequiredProcessResultV1(
    val requiredProcessNodeId: String,
    val status: RequiredProcessStatusV1,
    val issueId: String?,
)

data class ModelConsistencyIssueV1(
    val issueId: String,
    val code: ConsistencyIssueCode,
    val severity: ConsistencyIssueSeverity,
    val startCodePointInclusive: Int,
    val endCodePointExclusive: Int,
    val relatedEntityIds: List<String>,
    val relatedForeshadowItemIds: List<String>,
    val relatedRequiredProcessNodeIds: List<String>,
    val repairAction: ConsistencyRepairActionV1,
)

data class ChapterConsistencyReportV1(
    val sourceChapterVersionId: String,
    val sourceChapterContentHash: String,
    val chapterId: String,
    val chapterIndex: Int,
    val checkSourceSnapshotHash: String,
    val sceneContractHash: String,
    val criterionResults: List<ConsistencyCriterionResultV1>,
    val requiredProcessResults: List<RequiredProcessResultV1>,
    val issues: List<ModelConsistencyIssueV1>,
    val canonicalJson: String,
    val contentHash: String,
) {
    val blockerCount: Int = issues.count { it.severity == ConsistencyIssueSeverity.BLOCKER }
    val majorCount: Int = issues.count { it.severity == ConsistencyIssueSeverity.MAJOR }
    val minorCount: Int = issues.count { it.severity == ConsistencyIssueSeverity.MINOR }

    override fun toString(): String =
        "ChapterConsistencyReportV1(chapterIndex=$chapterIndex, criterionCount=${criterionResults.size}, " +
            "issueCount=${issues.size}, content=redacted)"
}

data class ChapterConsistencyExpectation(
    val sourceChapterVersionId: String,
    val sourceChapterContentHash: String,
    val chapterId: String,
    val chapterIndex: Int,
    val checkSourceSnapshotHash: String,
    val sceneContractHash: String,
    val bodyCodePointCount: Int,
    val expectedCriteria: List<ConsistencyCriterionV1>,
    val knownEntityIds: Set<String>,
    val knownForeshadowItemIds: Set<String>,
    val requiredProcessNodeIds: Set<String>,
) {
    init {
        require(IDENTIFIER.matches(sourceChapterVersionId) && IDENTIFIER.matches(chapterId))
        require(listOf(sourceChapterContentHash, checkSourceSnapshotHash, sceneContractHash).all(HASH::matches))
        require(chapterIndex in 1..10_000)
        require(bodyCodePointCount in 1..4_194_304)
        require(expectedCriteria.isNotEmpty() && expectedCriteria.distinct().size == expectedCriteria.size)
        require(expectedCriteria == expectedCriteria.sortedBy { it.ordinal })
        require(knownEntityIds.size <= 256 && knownEntityIds.all(IDENTIFIER::matches))
        require(knownForeshadowItemIds.size <= 256 && knownForeshadowItemIds.all(IDENTIFIER::matches))
        require(requiredProcessNodeIds.size <= 64 && requiredProcessNodeIds.all(IDENTIFIER::matches))
        require(
            requiredProcessNodeIds.isEmpty() ||
                ConsistencyCriterionV1.REQUIRED_PROCESS_COVERAGE in expectedCriteria,
        )
    }

    override fun toString(): String =
        "ChapterConsistencyExpectation(chapterIndex=$chapterIndex, criterionCount=${expectedCriteria.size}, " +
            "entityCount=${knownEntityIds.size}, content=redacted)"
}

class ChapterConsistencyOutputParser(
    private val validator: StructuredOutputValidator = StructuredOutputValidator(),
) {
    fun parse(source: ByteArray): PlanningOutputValidationResult<ChapterConsistencyReportV1> =
        when (val result = validator.validate(source, ChapterConsistencyOutputContractV1)) {
            is StructuredOutputValidationResult.Invalid -> PlanningOutputValidationResult.Invalid(result.report)
            is StructuredOutputValidationResult.Valid -> PlanningOutputValidationResult.Valid(
                result.output.withDocument(::fromDocument),
            )
        }

    internal fun fromValidated(output: ValidatedStructuredOutput): ChapterConsistencyReportV1 {
        require(output.schemaId == ChapterConsistencyOutputContractV1.schemaId)
        require(output.schemaVersion == ChapterConsistencyOutputContractV1.currentSchemaVersion)
        return output.withDocument(::fromDocument)
    }

    internal fun fromDocument(document: JsonObject): ChapterConsistencyReportV1 {
        val canonical = document.toString()
        return ChapterConsistencyReportV1(
            sourceChapterVersionId = document.stringValue("sourceChapterVersionId"),
            sourceChapterContentHash = document.stringValue("sourceChapterContentHash"),
            chapterId = document.stringValue("chapterId"),
            chapterIndex = document.intValue("chapterIndex"),
            checkSourceSnapshotHash = document.stringValue("checkSourceSnapshotHash"),
            sceneContractHash = document.stringValue("sceneContractHash"),
            criterionResults = document.objectValues("criterionResults").map { result ->
                ConsistencyCriterionResultV1(
                    criterion = ConsistencyCriterionV1.valueOf(result.stringValue("criterion")),
                    status = ConsistencyCriterionStatusV1.valueOf(result.stringValue("status")),
                    issueIds = result.stringValues("issueIds"),
                )
            },
            requiredProcessResults = document.objectValues("requiredProcessResults").map { result ->
                RequiredProcessResultV1(
                    requiredProcessNodeId = result.stringValue("requiredProcessNodeId"),
                    status = RequiredProcessStatusV1.valueOf(result.stringValue("status")),
                    issueId = result.optionalStringValue("issueId"),
                )
            },
            issues = document.objectValues("issues").map { issue ->
                ModelConsistencyIssueV1(
                    issueId = issue.stringValue("issueId"),
                    code = ConsistencyIssueCode.valueOf(issue.stringValue("code")),
                    severity = ConsistencyIssueSeverity.valueOf(issue.stringValue("severity")),
                    startCodePointInclusive = issue.intValue("startCodePointInclusive"),
                    endCodePointExclusive = issue.intValue("endCodePointExclusive"),
                    relatedEntityIds = issue.stringValues("relatedEntityIds"),
                    relatedForeshadowItemIds = issue.stringValues("relatedForeshadowItemIds"),
                    relatedRequiredProcessNodeIds = issue.stringValues("relatedRequiredProcessNodeIds"),
                    repairAction = ConsistencyRepairActionV1.valueOf(issue.stringValue("repairAction")),
                )
            },
            canonicalJson = canonical,
            contentHash = sha256(canonical),
        )
    }
}

internal class BoundChapterConsistencyOutputContract(
    private val expectation: ChapterConsistencyExpectation,
    private val parser: ChapterConsistencyOutputParser = ChapterConsistencyOutputParser(),
) : StructuredOutputContract {
    override val schemaId = ChapterConsistencyOutputContractV1.schemaId
    override val currentSchemaVersion = ChapterConsistencyOutputContractV1.currentSchemaVersion
    override val providerSchema = ChapterConsistencyOutputContractV1.providerSchema
    override val limits = ChapterConsistencyOutputContractV1.limits

    override fun validate(document: JsonObject): List<StructuredOutputIssue> {
        val structural = ChapterConsistencyOutputContractV1.validate(document)
        if (structural.isNotEmpty()) return structural
        return ChapterConsistencyCrossValidator.validate(parser.fromDocument(document), expectation)
    }
}

object ChapterConsistencyCrossValidator {
    fun validate(
        report: ChapterConsistencyReportV1,
        expected: ChapterConsistencyExpectation,
    ): List<StructuredOutputIssue> = buildList {
        fun issue(path: String) {
            if (size < 128) add(StructuredOutputIssue(StructuredOutputIssueCode.VALUE_INVALID, path))
        }
        if (report.sourceChapterVersionId != expected.sourceChapterVersionId) issue("$.sourceChapterVersionId")
        if (report.sourceChapterContentHash != expected.sourceChapterContentHash) issue("$.sourceChapterContentHash")
        if (report.chapterId != expected.chapterId) issue("$.chapterId")
        if (report.chapterIndex != expected.chapterIndex) issue("$.chapterIndex")
        if (report.checkSourceSnapshotHash != expected.checkSourceSnapshotHash) issue("$.checkSourceSnapshotHash")
        if (report.sceneContractHash != expected.sceneContractHash) issue("$.sceneContractHash")

        val issueIds = report.issues.map(ModelConsistencyIssueV1::issueId)
        if (issueIds.distinct().size != issueIds.size) issue("$.issues")
        report.issues.forEachIndexed { index, finding ->
            val base = "$.issues[$index]"
            val criterion = ConsistencyIssuePolicyV1.criterionFor(finding.code)
            if (criterion !in expected.expectedCriteria) issue("$base.code")
            if (finding.severity != ConsistencyIssuePolicyV1.requiredSeverity(finding.code)) {
                issue("$base.severity")
            }
            if (finding.repairAction != ConsistencyIssuePolicyV1.requiredRepairAction(finding.code)) {
                issue("$base.repairAction")
            }
            if (
                finding.startCodePointInclusive < 0 ||
                finding.endCodePointExclusive <= finding.startCodePointInclusive ||
                finding.endCodePointExclusive > expected.bodyCodePointCount
            ) issue("$base.endCodePointExclusive")
            if (finding.relatedEntityIds.distinct().size != finding.relatedEntityIds.size ||
                finding.relatedEntityIds.any { it !in expected.knownEntityIds }
            ) issue("$base.relatedEntityIds")
            if (finding.relatedForeshadowItemIds.distinct().size != finding.relatedForeshadowItemIds.size ||
                finding.relatedForeshadowItemIds.any { it !in expected.knownForeshadowItemIds }
            ) issue("$base.relatedForeshadowItemIds")
            if (finding.relatedRequiredProcessNodeIds.distinct().size != finding.relatedRequiredProcessNodeIds.size ||
                finding.relatedRequiredProcessNodeIds.any { it !in expected.requiredProcessNodeIds }
            ) issue("$base.relatedRequiredProcessNodeIds")
            if (finding.code == ConsistencyIssueCode.REQUIRED_PROCESS_MISSING &&
                finding.relatedRequiredProcessNodeIds.isEmpty()
            ) issue("$base.relatedRequiredProcessNodeIds")
        }

        val criteria = report.criterionResults.map(ConsistencyCriterionResultV1::criterion)
        if (criteria != expected.expectedCriteria) issue("$.criterionResults")
        val issueById = report.issues.associateBy(ModelConsistencyIssueV1::issueId)
        report.criterionResults.forEachIndexed { index, result ->
            val base = "$.criterionResults[$index]"
            if (result.issueIds.distinct().size != result.issueIds.size || result.issueIds.any { it !in issueById }) {
                issue("$base.issueIds")
            }
            val actualIds = report.issues.filter {
                ConsistencyIssuePolicyV1.criterionFor(it.code) == result.criterion
            }.map(ModelConsistencyIssueV1::issueId)
            if (result.issueIds != actualIds) issue("$base.issueIds")
            if ((result.status == ConsistencyCriterionStatusV1.PASS) != actualIds.isEmpty()) issue("$base.status")
        }
        val referencedIds = report.criterionResults.flatMap(ConsistencyCriterionResultV1::issueIds)
        if (referencedIds.toSet() != issueIds.toSet()) issue("$.criterionResults")
        val expectedNodes = expected.requiredProcessNodeIds.sorted()
        if (report.requiredProcessResults.map { it.requiredProcessNodeId } != expectedNodes) {
            issue("$.requiredProcessResults")
        }
        val processIssues = report.issues.filter { it.code == ConsistencyIssueCode.REQUIRED_PROCESS_MISSING }
            .associateBy { it.issueId }
        report.requiredProcessResults.forEachIndexed { index, result ->
            val base = "$.requiredProcessResults[$index]"
            when (result.status) {
                RequiredProcessStatusV1.COVERED -> if (result.issueId != null) issue("$base.issueId")
                RequiredProcessStatusV1.MISSING -> {
                    val linked = result.issueId?.let(processIssues::get)
                    if (linked == null || result.requiredProcessNodeId !in linked.relatedRequiredProcessNodeIds) {
                        issue("$base.issueId")
                    }
                }
            }
        }
        processIssues.values.forEach { finding ->
            finding.relatedRequiredProcessNodeIds.forEach { nodeId ->
                if (report.requiredProcessResults.none {
                        it.requiredProcessNodeId == nodeId &&
                            it.status == RequiredProcessStatusV1.MISSING && it.issueId == finding.issueId
                    }
                ) {
                    issue("$.requiredProcessResults")
                }
            }
        }
    }.distinct().take(128)
}

object ConsistencyIssuePolicyV1 {
    fun criterionFor(code: ConsistencyIssueCode): ConsistencyCriterionV1 = when (code) {
        ConsistencyIssueCode.SOURCE_CONTENT_MISMATCH -> ConsistencyCriterionV1.SOURCE_INTEGRITY
        ConsistencyIssueCode.BODY_EMPTY,
        ConsistencyIssueCode.BODY_BELOW_PLAN_MINIMUM,
        ConsistencyIssueCode.BODY_SIZE_LIMIT_EXCEEDED,
        ConsistencyIssueCode.CODE_FENCE_WRAPPER,
        ConsistencyIssueCode.UNEXPECTED_CHAPTER_HEADING,
        ConsistencyIssueCode.EXACT_DUPLICATE_PARAGRAPH,
        -> ConsistencyCriterionV1.BASIC_READABILITY
        ConsistencyIssueCode.ADULT_FACT_CONFLICT -> ConsistencyCriterionV1.ADULT_AND_IDENTITY_FACTS
        ConsistencyIssueCode.HARD_FACT_CONFLICT -> ConsistencyCriterionV1.HARD_FACTS
        ConsistencyIssueCode.UNKNOWN_ENTITY_REFERENCE -> ConsistencyCriterionV1.ENTITY_REFERENCES
        ConsistencyIssueCode.POV_KNOWLEDGE_VIOLATION -> ConsistencyCriterionV1.POV_KNOWLEDGE
        ConsistencyIssueCode.TIMELINE_ORDER_CONFLICT -> ConsistencyCriterionV1.TIMELINE_ORDER
        ConsistencyIssueCode.LOCATION_TRAVEL_CONFLICT,
        ConsistencyIssueCode.SPATIAL_CONTINUITY_BREAK,
        -> ConsistencyCriterionV1.LOCATION_AND_SPATIAL_CONTINUITY
        ConsistencyIssueCode.ITEM_OWNERSHIP_CONFLICT -> ConsistencyCriterionV1.ITEM_OWNERSHIP
        ConsistencyIssueCode.DEAD_OR_EXITED_CHARACTER_RETURN -> ConsistencyCriterionV1.CHARACTER_AVAILABILITY
        ConsistencyIssueCode.REQUIRED_EVENT_MISSING -> ConsistencyCriterionV1.REQUIRED_EVENT_COVERAGE
        ConsistencyIssueCode.MOTIVATION_CAUSALITY_BREAK -> ConsistencyCriterionV1.MOTIVATION_CAUSALITY
        ConsistencyIssueCode.RELATIONSHIP_CONTINUITY_BREAK -> ConsistencyCriterionV1.RELATIONSHIP_CONTINUITY
        ConsistencyIssueCode.VOICE_CONTINUITY_BREAK -> ConsistencyCriterionV1.VOICE_CONTINUITY
        ConsistencyIssueCode.FORESHADOW_CONTINUITY_BREAK -> ConsistencyCriterionV1.FORESHADOW_CONTINUITY
        ConsistencyIssueCode.ACTION_REACTION_GAP -> ConsistencyCriterionV1.ACTION_REACTION
        ConsistencyIssueCode.BODY_STATE_CONTINUITY_BREAK -> ConsistencyCriterionV1.BODY_STATE_CONTINUITY
        ConsistencyIssueCode.PRESENTATION_PROFILE_DRIFT -> ConsistencyCriterionV1.PRESENTATION_PROPORTIONALITY
        ConsistencyIssueCode.REQUIRED_PROCESS_MISSING -> ConsistencyCriterionV1.REQUIRED_PROCESS_COVERAGE
        ConsistencyIssueCode.FADE_SUBSTITUTION -> ConsistencyCriterionV1.NO_FADE_SUBSTITUTION
        ConsistencyIssueCode.SENSORY_CONTINUITY_BREAK -> ConsistencyCriterionV1.SENSORY_CONTINUITY
        ConsistencyIssueCode.RELEVANT_AFTERMATH_MISSING -> ConsistencyCriterionV1.RELEVANT_AFTERMATH
        ConsistencyIssueCode.MECHANICAL_DETAIL_LIST -> ConsistencyCriterionV1.NON_MECHANICAL_DETAIL
    }

    fun requiredSeverity(code: ConsistencyIssueCode): ConsistencyIssueSeverity = when (code) {
        ConsistencyIssueCode.SOURCE_CONTENT_MISMATCH,
        ConsistencyIssueCode.BODY_EMPTY,
        ConsistencyIssueCode.BODY_SIZE_LIMIT_EXCEEDED,
        ConsistencyIssueCode.CODE_FENCE_WRAPPER,
        ConsistencyIssueCode.ADULT_FACT_CONFLICT,
        ConsistencyIssueCode.HARD_FACT_CONFLICT,
        ConsistencyIssueCode.UNKNOWN_ENTITY_REFERENCE,
        ConsistencyIssueCode.TIMELINE_ORDER_CONFLICT,
        ConsistencyIssueCode.LOCATION_TRAVEL_CONFLICT,
        ConsistencyIssueCode.ITEM_OWNERSHIP_CONFLICT,
        ConsistencyIssueCode.DEAD_OR_EXITED_CHARACTER_RETURN,
        ConsistencyIssueCode.REQUIRED_EVENT_MISSING,
        ConsistencyIssueCode.SPATIAL_CONTINUITY_BREAK,
        ConsistencyIssueCode.BODY_STATE_CONTINUITY_BREAK,
        ConsistencyIssueCode.REQUIRED_PROCESS_MISSING,
        ConsistencyIssueCode.FADE_SUBSTITUTION,
        -> ConsistencyIssueSeverity.BLOCKER
        ConsistencyIssueCode.BODY_BELOW_PLAN_MINIMUM,
        ConsistencyIssueCode.UNEXPECTED_CHAPTER_HEADING,
        ConsistencyIssueCode.EXACT_DUPLICATE_PARAGRAPH,
        ConsistencyIssueCode.POV_KNOWLEDGE_VIOLATION,
        ConsistencyIssueCode.MOTIVATION_CAUSALITY_BREAK,
        ConsistencyIssueCode.RELATIONSHIP_CONTINUITY_BREAK,
        ConsistencyIssueCode.FORESHADOW_CONTINUITY_BREAK,
        ConsistencyIssueCode.ACTION_REACTION_GAP,
        ConsistencyIssueCode.SENSORY_CONTINUITY_BREAK,
        ConsistencyIssueCode.RELEVANT_AFTERMATH_MISSING,
        ConsistencyIssueCode.PRESENTATION_PROFILE_DRIFT,
        -> ConsistencyIssueSeverity.MAJOR
        ConsistencyIssueCode.VOICE_CONTINUITY_BREAK,
        ConsistencyIssueCode.MECHANICAL_DETAIL_LIST,
        -> ConsistencyIssueSeverity.MINOR
    }

    fun requiredRepairAction(code: ConsistencyIssueCode): ConsistencyRepairActionV1 = when (code) {
        ConsistencyIssueCode.SOURCE_CONTENT_MISMATCH,
        ConsistencyIssueCode.BODY_SIZE_LIMIT_EXCEEDED,
        -> ConsistencyRepairActionV1.REVIEW_MANUALLY
        ConsistencyIssueCode.ADULT_FACT_CONFLICT,
        ConsistencyIssueCode.HARD_FACT_CONFLICT,
        ConsistencyIssueCode.UNKNOWN_ENTITY_REFERENCE,
        ConsistencyIssueCode.ITEM_OWNERSHIP_CONFLICT,
        -> ConsistencyRepairActionV1.RESTORE_FACT
        ConsistencyIssueCode.POV_KNOWLEDGE_VIOLATION,
        ConsistencyIssueCode.TIMELINE_ORDER_CONFLICT,
        ConsistencyIssueCode.LOCATION_TRAVEL_CONFLICT,
        ConsistencyIssueCode.DEAD_OR_EXITED_CHARACTER_RETURN,
        ConsistencyIssueCode.MOTIVATION_CAUSALITY_BREAK,
        ConsistencyIssueCode.RELATIONSHIP_CONTINUITY_BREAK,
        ConsistencyIssueCode.FORESHADOW_CONTINUITY_BREAK,
        ConsistencyIssueCode.ACTION_REACTION_GAP,
        ConsistencyIssueCode.SPATIAL_CONTINUITY_BREAK,
        ConsistencyIssueCode.BODY_STATE_CONTINUITY_BREAK,
        ConsistencyIssueCode.SENSORY_CONTINUITY_BREAK,
        ConsistencyIssueCode.PRESENTATION_PROFILE_DRIFT,
        -> ConsistencyRepairActionV1.RESTORE_CONTINUITY
        ConsistencyIssueCode.BODY_EMPTY,
        ConsistencyIssueCode.BODY_BELOW_PLAN_MINIMUM,
        ConsistencyIssueCode.CODE_FENCE_WRAPPER,
        ConsistencyIssueCode.UNEXPECTED_CHAPTER_HEADING,
        ConsistencyIssueCode.REQUIRED_EVENT_MISSING,
        ConsistencyIssueCode.VOICE_CONTINUITY_BREAK,
        ConsistencyIssueCode.FADE_SUBSTITUTION,
        ConsistencyIssueCode.MECHANICAL_DETAIL_LIST,
        -> ConsistencyRepairActionV1.REWRITE_RANGE
        ConsistencyIssueCode.EXACT_DUPLICATE_PARAGRAPH -> ConsistencyRepairActionV1.REMOVE_DUPLICATION
        ConsistencyIssueCode.REQUIRED_PROCESS_MISSING -> ConsistencyRepairActionV1.EXPAND_REQUIRED_PROCESS
        ConsistencyIssueCode.RELEVANT_AFTERMATH_MISSING -> ConsistencyRepairActionV1.ADD_RELEVANT_AFTERMATH
    }
}

object ChapterConsistencyOutputContractV1 : StructuredOutputContract {
    override val schemaId = "chapter-consistency-report.v1"
    override val currentSchemaVersion = 1
    override val providerSchema = ProviderJsonSchema.from(CONSISTENCY_SCHEMA)
    override val limits = StructuredOutputLimits(
        maximumBytes = 512 * 1_024,
        maximumRepairSourceBytes = 256 * 1_024,
        maximumDepth = 10,
        maximumNodes = 12_288,
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
        root.hash("checkSourceSnapshotHash")
        root.hash("sceneContractHash")
        root.objects("criterionResults", 1..32).forEachIndexed { index, result ->
            val reader = MemoryContractReader(result, "$.criterionResults[$index]", this)
            reader.exactKeys(CRITERION_KEYS)
            reader.enumString("criterion", ConsistencyCriterionV1.entries.mapTo(mutableSetOf()) { it.name })
            reader.enumString("status", ConsistencyCriterionStatusV1.entries.mapTo(mutableSetOf()) { it.name })
            reader.strings("issueIds", 0..128, 1..128)
        }
        root.objects("requiredProcessResults", 0..64).forEachIndexed { index, result ->
            val reader = MemoryContractReader(result, "$.requiredProcessResults[$index]", this)
            reader.exactKeys(PROCESS_RESULT_KEYS)
            reader.identifier("requiredProcessNodeId")
            reader.enumString("status", RequiredProcessStatusV1.entries.mapTo(mutableSetOf()) { it.name })
            reader.nullableIdentifier("issueId")
        }
        root.objects("issues", 0..128).forEachIndexed { index, finding ->
            val reader = MemoryContractReader(finding, "$.issues[$index]", this)
            reader.exactKeys(ISSUE_KEYS)
            reader.identifier("issueId")
            reader.enumString("code", MODEL_ISSUE_CODES.mapTo(mutableSetOf()) { it.name })
            reader.enumString("severity", ConsistencyIssueSeverity.entries.mapTo(mutableSetOf()) { it.name })
            reader.int("startCodePointInclusive", 0..4_194_303)
            reader.int("endCodePointExclusive", 1..4_194_304)
            reader.strings("relatedEntityIds", 0..32, 1..128)
            reader.strings("relatedForeshadowItemIds", 0..32, 1..128)
            reader.strings("relatedRequiredProcessNodeIds", 0..64, 1..128)
            reader.enumString("repairAction", ConsistencyRepairActionV1.entries.mapTo(mutableSetOf()) { it.name })
        }
    }

    private val MODEL_ISSUE_CODES = ConsistencyIssueCode.entries.filterNot {
        it in setOf(
            ConsistencyIssueCode.SOURCE_CONTENT_MISMATCH,
            ConsistencyIssueCode.BODY_EMPTY,
            ConsistencyIssueCode.BODY_BELOW_PLAN_MINIMUM,
            ConsistencyIssueCode.BODY_SIZE_LIMIT_EXCEEDED,
            ConsistencyIssueCode.CODE_FENCE_WRAPPER,
            ConsistencyIssueCode.UNEXPECTED_CHAPTER_HEADING,
            ConsistencyIssueCode.EXACT_DUPLICATE_PARAGRAPH,
        )
    }
}

private fun JsonObject.stringValue(key: String): String = (getValue(key) as JsonPrimitive).content
private fun JsonObject.optionalStringValue(key: String): String? =
    (getValue(key) as JsonPrimitive).takeUnless { it is kotlinx.serialization.json.JsonNull }?.content
private fun JsonObject.intValue(key: String): Int = (getValue(key) as JsonPrimitive).intOrNull!!
private fun JsonObject.objectValues(key: String): List<JsonObject> = (getValue(key) as JsonArray).map { it as JsonObject }
private fun JsonObject.stringValues(key: String): List<String> =
    (getValue(key) as JsonArray).map { (it as JsonPrimitive).content }

private fun sha256(value: String): String {
    val bytes = value.toByteArray(Charsets.UTF_8)
    return try {
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
    } finally {
        bytes.fill(0)
    }
}

private val ROOT_KEYS = setOf(
    "schemaVersion", "sourceChapterVersionId", "sourceChapterContentHash", "chapterId", "chapterIndex",
    "checkSourceSnapshotHash", "sceneContractHash", "criterionResults", "issues",
    "requiredProcessResults",
)
private val CRITERION_KEYS = setOf("criterion", "status", "issueIds")
private val PROCESS_RESULT_KEYS = setOf("requiredProcessNodeId", "status", "issueId")
private val ISSUE_KEYS = setOf(
    "issueId", "code", "severity", "startCodePointInclusive", "endCodePointExclusive",
    "relatedEntityIds", "relatedForeshadowItemIds", "relatedRequiredProcessNodeIds", "repairAction",
)
private val IDENTIFIER = Regex("[A-Za-z0-9._:-]{1,128}")
private val HASH = Regex("[0-9a-f]{64}")

private val CONSISTENCY_SCHEMA = """
{"type":"object","additionalProperties":false,"required":["schemaVersion","sourceChapterVersionId","sourceChapterContentHash","chapterId","chapterIndex","checkSourceSnapshotHash","sceneContractHash","criterionResults","requiredProcessResults","issues"],"properties":{"schemaVersion":{"const":1},"sourceChapterVersionId":{"type":"string","minLength":1,"maxLength":128,"pattern":"^[A-Za-z0-9._:-]+$"},"sourceChapterContentHash":{"type":"string","pattern":"^[0-9a-f]{64}$"},"chapterId":{"type":"string","minLength":1,"maxLength":128,"pattern":"^[A-Za-z0-9._:-]+$"},"chapterIndex":{"type":"integer","minimum":1,"maximum":10000},"checkSourceSnapshotHash":{"type":"string","pattern":"^[0-9a-f]{64}$"},"sceneContractHash":{"type":"string","pattern":"^[0-9a-f]{64}$"},"criterionResults":{"type":"array","minItems":1,"maxItems":32,"items":{"type":"object","additionalProperties":false,"required":["criterion","status","issueIds"],"properties":{"criterion":{"type":"string","enum":["SOURCE_INTEGRITY","BASIC_READABILITY","ADULT_AND_IDENTITY_FACTS","HARD_FACTS","ENTITY_REFERENCES","POV_KNOWLEDGE","TIMELINE_ORDER","LOCATION_AND_SPATIAL_CONTINUITY","ITEM_OWNERSHIP","CHARACTER_AVAILABILITY","REQUIRED_EVENT_COVERAGE","MOTIVATION_CAUSALITY","RELATIONSHIP_CONTINUITY","VOICE_CONTINUITY","FORESHADOW_CONTINUITY","ACTION_REACTION","BODY_STATE_CONTINUITY","PRESENTATION_PROPORTIONALITY","REQUIRED_PROCESS_COVERAGE","NO_FADE_SUBSTITUTION","SENSORY_CONTINUITY","RELEVANT_AFTERMATH","NON_MECHANICAL_DETAIL"]},"status":{"type":"string","enum":["PASS","ISSUE"]},"issueIds":{"type":"array","maxItems":128,"uniqueItems":true,"items":{"type":"string","minLength":1,"maxLength":128,"pattern":"^[A-Za-z0-9._:-]+$"}}}}},"requiredProcessResults":{"type":"array","maxItems":64,"items":{"type":"object","additionalProperties":false,"required":["requiredProcessNodeId","status","issueId"],"properties":{"requiredProcessNodeId":{"type":"string","minLength":1,"maxLength":128,"pattern":"^[A-Za-z0-9._:-]+$"},"status":{"type":"string","enum":["COVERED","MISSING"]},"issueId":{"anyOf":[{"type":"null"},{"type":"string","minLength":1,"maxLength":128,"pattern":"^[A-Za-z0-9._:-]+$"}]}}}},"issues":{"type":"array","maxItems":128,"items":{"type":"object","additionalProperties":false,"required":["issueId","code","severity","startCodePointInclusive","endCodePointExclusive","relatedEntityIds","relatedForeshadowItemIds","relatedRequiredProcessNodeIds","repairAction"],"properties":{"issueId":{"type":"string","minLength":1,"maxLength":128,"pattern":"^[A-Za-z0-9._:-]+$"},"code":{"type":"string","enum":["ADULT_FACT_CONFLICT","HARD_FACT_CONFLICT","UNKNOWN_ENTITY_REFERENCE","POV_KNOWLEDGE_VIOLATION","TIMELINE_ORDER_CONFLICT","LOCATION_TRAVEL_CONFLICT","ITEM_OWNERSHIP_CONFLICT","DEAD_OR_EXITED_CHARACTER_RETURN","REQUIRED_EVENT_MISSING","MOTIVATION_CAUSALITY_BREAK","RELATIONSHIP_CONTINUITY_BREAK","VOICE_CONTINUITY_BREAK","FORESHADOW_CONTINUITY_BREAK","ACTION_REACTION_GAP","SPATIAL_CONTINUITY_BREAK","BODY_STATE_CONTINUITY_BREAK","SENSORY_CONTINUITY_BREAK","REQUIRED_PROCESS_MISSING","FADE_SUBSTITUTION","RELEVANT_AFTERMATH_MISSING","MECHANICAL_DETAIL_LIST","PRESENTATION_PROFILE_DRIFT"]},"severity":{"type":"string","enum":["BLOCKER","MAJOR","MINOR"]},"startCodePointInclusive":{"type":"integer","minimum":0,"maximum":4194303},"endCodePointExclusive":{"type":"integer","minimum":1,"maximum":4194304},"relatedEntityIds":{"type":"array","maxItems":32,"uniqueItems":true,"items":{"type":"string","minLength":1,"maxLength":128,"pattern":"^[A-Za-z0-9._:-]+$"}},"relatedForeshadowItemIds":{"type":"array","maxItems":32,"uniqueItems":true,"items":{"type":"string","minLength":1,"maxLength":128,"pattern":"^[A-Za-z0-9._:-]+$"}},"relatedRequiredProcessNodeIds":{"type":"array","maxItems":64,"uniqueItems":true,"items":{"type":"string","minLength":1,"maxLength":128,"pattern":"^[A-Za-z0-9._:-]+$"}},"repairAction":{"type":"string","enum":["REWRITE_RANGE","RESTORE_FACT","RESTORE_CONTINUITY","EXPAND_REQUIRED_PROCESS","ADD_RELEVANT_AFTERMATH","REMOVE_DUPLICATION","REVIEW_MANUALLY"]}}}}}}
""".trimIndent()
